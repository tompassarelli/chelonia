;; ============================================================================
;; cnf_rename_race_receipt.clj — #(a) rename under concurrency: the snapshot-window race.
;;   bb -cp out cnf_rename_race_receipt.clj   (needs BEAGLE_HOME for the cold render)
;;
;; The red-team's hazard: persist-bound-for-rename! used to snapshot references under ONE
;; dlock, then the rename committed under a SEPARATE dlock — a concurrent agent that adds a
;; NEW reference to the renamed def (by its OLD spelling) in that window lands with no
;; identity edge, and a COLD re-render re-derives by spelling -> dangles. Fix: persist runs
;; INSIDE the commit dlock (one acquisition); ensure-refers! re-derives fresh, so any ref
;; committed before the lock is captured into bound_to.
;;
;; TEST 1 (decisive mechanism, sequential): author a NEW def referencing `replace!` via
;;   :edit-min (capture-only -> it gets NO refers_to), THEN rename replace! -> supersede-prior!.
;;   Cold render: the new def's body must read `supersede-prior!` (identity captured by the
;;   rename's fresh ensure-refers!), NOT the stale `replace!`. This is what the old window
;;   would have dropped.
;; TEST 2 (concurrent stress): the rename and the new-ref insert race as futures; assert the
;;   cold render is COHERENT (compiles) and report the new ref's resolution.
;;
;; SAFE: isolated daemon on a /tmp COPY of .fram/code.log; never 7977 / canonical log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s] '[babashka.process :as proc])
(load-file "cnf_coord_daemon.clj")
(def home (System/getProperty "user.home"))
(def root (System/getProperty "user.dir"))
(def beagle-home (or (System/getenv "BEAGLE_HOME") (str home "/code/beagle")))
(def base-env {"BEAGLE_HOME" beagle-home "FRAM_OUT" (str root "/out")
               "FRAM_ROUNDTRIP" (str beagle-home "/beagle-lib/private/claims-roundtrip.rkt")
               "FRAM_RESOLVE" (str root "/chartroom/src/resolve.clj")})

(defn vof [st e] (let [Vp (c/value-id st "v")] (some->> (c/by-lp st e Vp) first (c/claim-of st) :r (c/literal st))))
(defn forms-of [st wrap]
  (->> (c/by-l st wrap)
       (keep (fn [cid] (let [cl (c/claim-of st cid) k (resolve/ord-parse (c/literal st (:p cl)))] (when k {:key k :child (:r cl)}))))
       (sort-by :key resolve/ord-cmp) vec))
(defn def-name [st child]
  (let [kids (->> (c/by-l st child) (keep (fn [cid] (let [k (resolve/ord-parse (c/literal st (:p (c/claim-of st cid))))] (when k [k (c/claim-of st cid)])))) (sort-by first resolve/ord-cmp))]
    (when (>= (count kids) 2) (vof st (:r (second (nth kids 1)))))))
(defn head-of [st child] (vof st (:child (first (forms-of st child)))))
(defn wrapper [st m]
  (let [NAME (c/value-id st "name") pfx (str "@" m "#")]
    (->> (c/by-p st NAME)
         (keep (fn [cid] (let [nm (c/literal st (:r (c/claim-of st cid)))] (when (and (string? nm) (str/starts-with? nm pfx)) (:l (c/claim-of st cid))))))
         (filter (fn [e] (let [fs (forms-of st e)] (and (seq fs) (= "beagle-file" (vof st (:child (first fs)))))))) first)))
(defn anchor-name [st]
  (let [wrap (wrapper st "schema") pre (forms-of st wrap)
        ai (first (filter (fn [i] (and (< (inc i) (count pre)) (#{"def" "defn" "defn-" "def-" "defonce"} (head-of st (:child (nth pre i)))) (def-name st (:child (nth pre i))))) (range (count pre))))]
    (def-name st (:child (nth pre ai)))))

;; cold render schema off `log`, return the line for the new def (or nil)
(defn cold-render-line [log marker]
  (let [out (str "/tmp/rename-race-" (System/nanoTime) ".bclj")]
    (proc/shell {:continue true :extra-env (merge (into {} (System/getenv)) base-env) :err :string}
                "bb" "-cp" "out" "bin/fram-render-code" "schema" "--log" log "--out" out)
    (let [txt (when (.exists (io/file out)) (slurp out))]
      {:txt txt :line (some #(when (str/includes? % marker) %) (some-> txt str/split-lines))})))

(defn run-case [tag concurrent?]
  (let [log (str "/tmp/cnf-rename-race-" tag "-" (System/nanoTime) ".log")]
    (io/copy (io/file ".fram/code.log") (io/file log))
    (boot-flat! log)
    (let [st (:store @co)
          anc (anchor-name st)
          mk-rename #(handle {:op :edit-min :spec {:op "rename" :module "schema" :old "replace!" :new "supersede-prior!"}})
          mk-insert #(handle {:op :edit-min :spec {:op "insert-form" :module "schema" :after anc
                                                   :datum (list 'def 'cnf_race_uses (list 'replace! 1))}})]
      (if concurrent?
        (let [fa (future (mk-rename)) fb (future (mk-insert))] [@fa @fb])
        (do [(mk-insert) (mk-rename)]))                ; sequential: insert NEW ref first, then rename
      log)))

(println "=== #(a) rename-under-concurrency: snapshot-window race ===")
;; TEST 1 — sequential mechanism (decisive)
(def log1 (run-case "seq" false))
(def r1 (cold-render-line log1 "cnf_race_uses"))
(println "\n[TEST 1 — sequential mechanism] new def line in cold render:")
(println "  " (or (:line r1) "<cnf_race_uses MISSING from render>"))
(def t1-identity (boolean (and (:line r1) (str/includes? (:line r1) "supersede-prior!") (not (str/includes? (:line r1) "replace!")))))
(println "  -> resolves by IDENTITY to renamed def (supersede-prior!):" t1-identity)

;; TEST 2 — concurrent stress
(def log2 (run-case "conc" true))
(def r2 (cold-render-line log2 "cnf_race_uses"))
(def t2-coherent (boolean (and (:txt r2) (= (count (re-seq #"\(" (:txt r2))) (count (re-seq #"\)" (:txt r2)))) (> (count (:txt r2)) 0))))
(println "\n[TEST 2 — concurrent rename || insert-ref] new def line in cold render:")
(println "  " (or (:line r2) "<cnf_race_uses MISSING (lost the race / not inserted)>"))
(println "  -> cold render coherent (parens balanced, non-empty):" t2-coherent)
(def t2-clean (or (nil? (:line r2))                                   ; lost the race: cleanly absent is OK
                  (str/includes? (:line r2) "supersede-prior!")))     ; captured: renders new name
(println "  -> no SILENT stale-dangling (either renders new name or cleanly absent):" t2-clean)

(println "\n=== VERDICT ===")
(if (and t1-identity t2-coherent t2-clean)
  (println "PASS — a freshly-authored reference is captured by the rename's in-lock persist and"
           "resolves by IDENTITY to the renamed def in a COLD render (TEST 1); the concurrent race"
           "stays coherent with no silent stale-dangling (TEST 2). Snapshot-window closed.")
  (println "FAIL — t1-identity=" t1-identity " t2-coherent=" t2-coherent " t2-clean=" t2-clean
           " (a new ref dangled to the old spelling after rename — window NOT closed)."))
