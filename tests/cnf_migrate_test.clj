;; cnf_migrate_test.clj — Stage 7 migration gate: the LIVE flat log migrates,
;; LOSSLESS, into the reified substrate and out to a v2 reified log that the
;; coordinator can boot from. Round-trip proven:
;;
;;   flat log --(schema loader)--> reified store --(dump-log!)--> v2 log
;;            --(replay)--> reified' store
;;
;; Gate: reified' domain view (names) SET-EQUAL to the flat fold, AND the v2-log
;; round-trip is the identity on the store's live id-triples. This is the data
;; half of the cutover; it touches NO live file (writes /tmp only) and is fully
;; reversible (the flat log + pre-cnf tag are untouched).
;;   FRAM_LOG=/path bb -cp out cnf_migrate_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[fram.fold :as fold] '[fram.rt]
         '[clojure.string :as str] '[clojure.set :as set] '[clojure.java.io :as io])

(def log (System/getenv "FRAM_LOG"))
(when (or (nil? log) (not (.exists (io/file log))))
  (println "cnf_migrate_test: skipped — set FRAM_LOG to a claims.log to run")
  (System/exit 0))

;; cnf_coord.clj is a script (dump-log!/replay live in `user`); load it for them.
;; run-test is guarded by command-line-args, so loading it has no side effects.
(load-file "cnf_coord.clj")

(def flat-claims (:claims (fold/fold (vec (filter #(and (:l %) (:p %) (:r %)) (fram.rt/read-log log))))))
(def flat-set (set (map (fn [cl] [(:l cl) (:p cl) (:r cl)]) flat-claims)))

;; --- load flat -> reified (the proven Stage 2/4 loader) ---------------------
(def single-preds #{"title" "owner" "lead" "driver" "assignee" "source" "part_of"
                    "do_on" "valid_until" "estimate_hours" "created_at" "updated_at"
                    "body" "created_by" "committed" "outcome" "abandoned"
                    "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"})
(def ref-preds #{"depends_on" "part_of" "relates_to" "clarifies" "amends" "created_by"
                 "lead" "driver" "assignee" "proposed_by" "session_of" "superseded_by" "merged_into"})
(def ctx (c/new-store))
(def tx (c/begin-tx! ctx "migrate"))
(s/setup! ctx tx)
(doseq [p (distinct (map :p flat-claims))]
  (s/def-predicate! ctx p (if (single-preds p) "single" "multi") (if (ref-preds p) "ref" "literal") tx))
(def memo (atom {}))
(defn ent-for! [sid] (or (get @memo sid) (let [id (c/entity! ctx)] (swap! memo assoc sid id) (s/name! ctx id sid tx) id)))
(defn ref? [x] (str/starts-with? x "@"))
(doseq [cl flat-claims]
  (let [subj (ent-for! (:l cl)) p (:p cl) r (:r cl)]
    (if (ref? r) (s/link! ctx subj p (ent-for! r) tx) (s/assert! ctx subj p r tx))))

;; --- the migration round-trip: dump to a v2 log, boot a fresh store from it --
(def mlog "/tmp/cnf-migrate.log")
(dump-log! ctx mlog)
(def ctx2 (replay mlog))

;; live id-triples (substrate-level identity of the round-trip)
(defn live-id-triples [st]
  (let [m @st]
    (set (for [cid (keys (:claims m)) :when (not (contains? (:superseded m) cid))]
           (let [cl (get (:claims m) cid)] [(:l cl) (:p cl) (:r cl)])))))

;; reconstruct the DOMAIN view (names) from the REPLAYED store
(def schema-preds #{"name" "cardinality" "value_kind" "cnf-supersedes"})
(defn domain-view [st]
  (set (keep (fn [cid]
               (let [cl (c/claim-of st cid) pstr (c/literal st (:p cl))]
                 (when-not (schema-preds pstr)
                   [(s/name-of st (:l cl)) pstr
                    (if (c/value-object? st (:r cl)) (c/literal st (:r cl)) (s/name-of st (:r cl)))])))
             (c/current-claims st))))

(def reified2-domain (domain-view ctx2))
(def only-flat (set/difference flat-set reified2-domain))
(def only-reif (set/difference reified2-domain flat-set))

(def checks
  [["v2-log round-trip is identity on live id-triples" (= (live-id-triples ctx) (live-id-triples ctx2))]
   ["replayed domain view == flat fold (lossless migration)" (and (empty? only-flat) (empty? only-reif))]
   ["replayed next-id recovered (coordinator can append)" (= (:next-id @ctx) (:next-id @ctx2))]
   ["replayed next-seq recovered" (= (:next-seq @ctx) (:next-seq @ctx2))]
   ["supersedes-pred recovered (supersession intact)" (some? (:supersedes-pred @ctx2))]])

(println "flat-fold:" (count flat-set) " replayed-domain:" (count reified2-domain)
         " entities:" (count @memo) " claims:" (count (:claims @ctx2)))
(when (seq only-flat) (println "  LOST:") (doseq [x (take 8 only-flat)] (println "   " (pr-str x))))
(when (seq only-reif) (println "  GAINED:") (doseq [x (take 8 only-reif)] (println "   " (pr-str x))))
(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nStage 7 (migration): flat log -> reified -> v2 log -> replay is LOSSLESS PASS")
    (do (println "\nStage 7 (migration): FAIL") (System/exit 1))))
