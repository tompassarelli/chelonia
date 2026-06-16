;; cnf_dropin_test.clj — Stage 7 drop-in gate: the reified daemon as a reversible
;; DROP-IN over the EXISTING flat log (no format change). Proven on a COPY:
;;   - boots by migrating the flat log -> reified store (live view == flat fold);
;;   - socket writes commit through the reified substrate AND append the flat line;
;;   - EXTERNAL flat edits (capture/import/set, out-of-band) are absorbed on the
;;     next op via reload — so every existing write path keeps working;
;;   - base_version contention holds.
;; Cardinality comes from chelonia.kernel/single? (no hardcoded vocab); touches no
;; live file.
;;   CHELONIA_LOG=/path bb -cp out cnf_dropin_test.clj
(require '[chelonia.cnf :as c] '[chelonia.schema :as s]
         '[chelonia.fold :as fold] '[chelonia.rt]
         '[clojure.set :as set] '[clojure.java.io :as io] '[clojure.string :as str])
(load-file "cnf_coord_daemon.clj")

(def live (System/getenv "CHELONIA_LOG"))
(when (or (nil? live) (not (.exists (io/file live))))
  (println "cnf_dropin_test: skipped — set CHELONIA_LOG") (System/exit 0))

(def flat "/tmp/dropin-flat.log")
(io/copy (io/file live) (io/file flat))

(defn domain-triples [st]
  (set (keep (fn [cid]
               (let [cl (c/claim-of st cid) pstr (c/literal st (:p cl))]
                 (when-not (schema-preds pstr)
                   [(s/name-of st (:l cl)) pstr
                    (if (c/value-object? st (:r cl)) (c/literal st (:r cl)) (s/name-of st (:r cl)))])))
             (c/current-claims st))))
(defn flat-set [f] (set (map (fn [cl] [(:l cl) (:p cl) (:r cl)])
                             (:claims (fold/fold (vec (filter #(and (:l %) (:p %) (:r %)) (chelonia.rt/read-log f))))))))

;; --- boot the drop-in daemon over the flat copy -----------------------------
(boot-flat! flat)
(def port 7996)
(def server (future (serve port)))
(Thread/sleep 500)

(def boot-equal (= (domain-triples (:store @co)) (flat-set flat)))

;; --- a socket write commits through the reified substrate + projects to flat -
(def v0 (:version (client port {:op :version})))
(def w1 (client port {:op :assert :te "@dropin-subj" :p "title" :r "Drop-in test" :base v0}))
;; a DOMAIN write to a reserved engine predicate must be rejected (would collide
;; with the reified schema layer)
(def reserved-rej (client port {:op :assert :te "@dropin-subj" :p "name" :r "collide" :base v0}))
(def after-write-has (contains? (domain-triples (:store @co)) ["@dropin-subj" "title" "Drop-in test"]))
(def flat-has-write (contains? (flat-set flat) ["@dropin-subj" "title" "Drop-in test"]))

;; --- EXTERNAL edit: append a flat line out-of-band (simulating import/capture) -
(with-open [os (java.io.FileOutputStream. flat true)]
  (.write os (.getBytes (str (pr-str {:tx 999999 :op "assert" :l "@ext-subj" :p "title" :r "External import" :ts "t" :by "import"}) "\n") "UTF-8")))
;; next op triggers maybe-reload! -> absorbs the external edit
(def status (client port {:op :status}))
(def absorbed (contains? (domain-triples (:store @co)) ["@ext-subj" "title" "External import"]))
(def daemon-write-survived (contains? (domain-triples (:store @co)) ["@dropin-subj" "title" "Drop-in test"]))

;; --- base_version contention over the socket --------------------------------
(def vc (:version (client port {:op :version})))
(def race (mapv deref (mapv (fn [i] (future (client port {:op :assert :te "@dropin-subj" :p "title" :r (str "r" i) :base vc}))) (range 6))))
(future-cancel server)

;; --- ANTI-REGRESSION: the corrupting/irreversible bug the adversaries found ---
;; the drop-in must NEVER write reified v2 :k-records into the canonical flat log,
;; the REAL cold-read path (unfiltered fold, what the CLI/MCP run) must not crash
;; and must equal the reified view, and :version must match the flat fold (doctor
;; FRESH). Also: coord.clj must still be able to fold the post-write log (rollback).
(def flat-lines (remove str/blank? (str/split-lines (slurp flat))))
(def no-v2-pollution (not-any? #(str/starts-with? (str/triml %) "{:k") flat-lines))
(def cold-fold-ok
  (try (= (set (map (fn [cl] [(:l cl) (:p cl) (:r cl)]) (:claims (fold/fold (chelonia.rt/read-log flat)))))
          (domain-triples (:store @co)))
       (catch Throwable _ false)))
;; doctor computes log-v = (:version (fold (read-log flat))) over ALL parsed lines
;; (UNFILTERED), so daemon-v (current-seq) must match THAT, not the filtered fold.
(def version-fresh (= (current-seq @co) (:version (fold/fold (chelonia.rt/read-log flat)))))
;; ...and it must stay FRESH even with a torn tail (the live log is append-no-fsync,
;; so an EDN-valid-but-incomplete tail line is a realistic standing condition).
(with-open [os (java.io.FileOutputStream. flat true)]
  (.write os (.getBytes (str (pr-str {:tx 7777777 :op "assert" :l "@torn-tail" :p "title"}) "\n") "UTF-8")))
(client port {:op :status})    ; trigger maybe-reload! over the torn tail
(def fresh-with-torn-tail (= (current-seq @co) (:version (fold/fold (chelonia.rt/read-log flat)))))

(def checks
  [["boot: reified live view == flat fold" boot-equal]
   ["socket write committed" (:ok w1)]
   ["write present in reified store" after-write-has]
   ["write projected to the flat log" flat-has-write]
   ["external flat edit absorbed on reload" absorbed]
   ["daemon write survived the reload (re-migrate is lossless)" daemon-write-survived]
   ["base_version: exactly one racer wins" (= 1 (count (filter :ok race)))]
   ["base_version: the rest conflict" (= 5 (count (filter #(= :conflict (:reject %)) race)))]
   ["NO v2 :k-records pollute the canonical flat log" no-v2-pollution]
   ["REAL cold fold (unfiltered) succeeds AND == reified view" cold-fold-ok]
   [":version == flat fold version (doctor reports FRESH)" version-fresh]
   [":version stays FRESH with a torn tail (no false STALE)" fresh-with-torn-tail]
   ["write to a reserved engine predicate is rejected" (some? (:reject reserved-rej))]
   ["reserved-pred reject did NOT pollute the flat log"
    (not (some #(str/includes? % "\"name\"") (remove str/blank? (str/split-lines (slurp flat)))))]])

(println "boot live:" (count (flat-set flat)))
(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nStage 7 (drop-in): reified engine over the flat log, edits absorbed PASS")
    (do (println "\nStage 7 (drop-in): FAIL") (System/exit 1))))
(System/exit 0)
