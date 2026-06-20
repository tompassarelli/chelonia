;; cnf_flip_test.clj — Stage 7 cutover-mechanism gate: the WHOLE flip, proven on a
;; COPY of the live log (touches no live file).
;;
;;   live flat log --(copy)--> flip-flat   (the projection seed, full history)
;;                --(migrate)--> flip-v2    (the reified canonical log)
;;   reified daemon boots from flip-v2, projects new commits BACK to flip-flat.
;;
;; Gate: after live socket writes, folding the flat projection yields a live view
;; SET-EQUAL to the reified store — i.e. the flat log stays a faithful mirror, so
;; the cold CLI (which folds it) keeps working unchanged across the cutover. Plus
;; base_version contention holds end-to-end over the socket.
;;   FRAM_LOG=/path bb -cp out cnf_flip_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[fram.fold :as fold] '[fram.rt] '[fram.kernel :as ck]
         '[clojure.string :as str] '[clojure.set :as set] '[clojure.java.io :as io])
(load-file "cnf_coord_daemon.clj")   ; daemon: boot!/serve/client/do-* + reified->claims

(def live (System/getenv "FRAM_LOG"))
(when (or (nil? live) (not (.exists (io/file live))))
  (println "cnf_flip_test: skipped — set FRAM_LOG") (System/exit 0))

(def flip-flat "/tmp/flip-flat.log")
(def flip-v2   "/tmp/flip-v2.log")
(io/copy (io/file live) (io/file flip-flat))         ; the flat projection seed (full history)

;; --- migrate the copy -> reified store -> v2 log ----------------------------
(def flat-claims (:claims (fold/fold (vec (filter #(and (:l %) (:p %) (:r %)) (fram.rt/read-log flip-flat))))))
(def single-preds #{"title" "owner" "lead" "driver" "assignee" "source" "part_of"
                    "do_on" "valid_until" "estimate_hours" "created_at" "updated_at"
                    "body" "created_by" "committed" "outcome" "abandoned"
                    "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"})
(def ref-preds #{"depends_on" "part_of" "relates_to" "clarifies" "amends" "created_by"
                 "lead" "driver" "assignee" "proposed_by" "session_of" "superseded_by" "merged_into"})
(def mst (c/new-store))
(def mtx (c/begin-tx! mst "migrate"))
(s/setup! mst mtx)
(doseq [p (distinct (map :p flat-claims))]
  (s/def-predicate! mst p (if (single-preds p) "single" "multi") (if (ref-preds p) "ref" "literal") mtx))
(def memo (atom {}))
(defn ent-for! [sid] (or (get @memo sid) (let [id (c/entity! mst)] (swap! memo assoc sid id) (s/name! mst id sid mtx) id)))
(doseq [cl flat-claims]
  (let [su (ent-for! (:l cl)) p (:p cl) r (:r cl)]
    (if (str/starts-with? r "@") (s/link! mst su p (ent-for! r) mtx) (s/assert! mst su p r mtx))))
(dump-log! mst flip-v2)

;; --- boot the reified daemon on the v2 log, projecting to the flat log -------
(boot! flip-v2 flip-flat)
(def port 7995)
(def server (future (serve port)))
(Thread/sleep 500)

;; helpers
(defn domain-triples [st]
  (set (keep (fn [cid]
               (let [cl (c/claim-of st cid) pstr (c/literal st (:p cl))]
                 (when-not (schema-preds pstr)
                   [(s/name-of st (:l cl)) pstr
                    (if (c/value-object? st (:r cl)) (c/literal st (:r cl)) (s/name-of st (:r cl)))])))
             (c/current-claims st))))
(defn flat-triples [f] (set (map (fn [cl] [(:l cl) (:p cl) (:r cl)]) (:claims (fold/fold (vec (filter #(and (:l %) (:p %) (:r %)) (fram.rt/read-log f))))))))

(def before-reif (domain-triples (:store @co)))
(def before-flat (flat-triples flip-flat))

;; --- live writes over the socket (to a fresh synthetic subject) -------------
(def S "@flip-test-subject")
(def v0 (:version (client port {:op :version})))
(def w1 (client port {:op :assert :te S :p "title"      :r "Flip mechanism test" :base v0}))
(def w2 (client port {:op :assert :te S :p "relates_to" :r "@flip-test-other"    :base v0}))
(def w3 (client port {:op :assert :te S :p "committed"  :r "true"                :base v0}))
;; base_version contention: two racers on the same single-valued field, stale base
(def vc (:version (client port {:op :version})))
(def race (mapv deref (mapv (fn [i] (future (client port {:op :assert :te S :p "title" :r (str "race" i) :base vc})))
                            (range 8))))
(def race-wins (count (filter :ok race)))
(def race-conf (count (filter #(= :conflict (:reject %)) race)))

(def after-reif (domain-triples (:store @co)))
(def after-flat (flat-triples flip-flat))
(future-cancel server)

(def new-reif (set/difference after-reif before-reif))
(def new-flat (set/difference after-flat before-flat))

(def checks
  [["write committed over the socket"                 (and (:ok w1) (:ok w2) (:ok w3))]
   ["flat projection == reified store (faithful mirror, full corpus)" (= after-flat after-reif)]
   ["new writes landed in BOTH (reified == flat delta)" (= new-reif new-flat)]
   ["the live title is one race winner (not race0-specific)"
    (= 1 (count (filter (fn [[l p r]] (and (= l S) (= p "title") (str/starts-with? (str r) "race"))) after-reif)))]
   ["base_version: exactly one racer wins"             (= 1 race-wins)]
   ["base_version: the rest conflict"                  (= 7 race-conf)]
   ["single-valued title -> one live value after race" (= 1 (count (filter (fn [[l p _]] (and (= l S) (= p "title"))) after-reif)))]])

(println "corpus flat:" (count before-flat) " reified:" (count before-reif)
         " | after flat:" (count after-flat) " reified:" (count after-reif)
         " | race wins/conflicts:" race-wins "/" race-conf)
(when (not= after-flat after-reif)
  (println "  flat-only:" (take 4 (set/difference after-flat after-reif)))
  (println "  reif-only:" (take 4 (set/difference after-reif after-flat))))
(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nStage 7 (flip mechanism): cutover proven on a copy — flat projection stays a faithful mirror PASS")
    (do (println "\nStage 7 (flip mechanism): FAIL") (System/exit 1))))
(System/exit 0)
