;; cnf_shadow_test.clj — Stage 7 read-equivalence gate: the reified read path
;; (read-bridge -> existing projections) produces output IDENTICAL to today's
;; flat system, across the WHOLE read surface (ready / blocked / leverage /
;; validate), on the LIVE corpus. This is the shadow-verification that makes the
;; read side of the cutover provably safe; it is read-only (touches no file).
;;   CHELONIA_LOG=/path bb -cp out cnf_shadow_test.clj
(require '[chelonia.cnf :as c] '[chelonia.schema :as s]
         '[chelonia.kernel :as ck] '[chelonia.projections :as proj]
         '[chelonia.fold :as fold] '[chelonia.rt]
         '[clojure.string :as str] '[clojure.set :as set] '[clojure.java.io :as io])
(load-file "cnf_coord_daemon.clj")   ; reuse reified->claims / lev-top / all-violations

(def log (System/getenv "CHELONIA_LOG"))
(when (or (nil? log) (not (.exists (io/file log))))
  (println "cnf_shadow_test: skipped — set CHELONIA_LOG") (System/exit 0))

;; --- flat (today's) read surface = the golden reference ---------------------
(def flat-claims (:claims (fold/fold (chelonia.rt/read-log log))))
(def flat-idx (ck/build-index flat-claims))

;; --- reified: load flat -> store, then read through the bridge --------------
(def single-preds #{"title" "owner" "lead" "driver" "assignee" "source" "part_of"
                    "do_on" "valid_until" "estimate_hours" "created_at" "updated_at"
                    "body" "created_by" "committed" "outcome" "abandoned"
                    "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"})
(def ref-preds #{"depends_on" "part_of" "relates_to" "clarifies" "amends" "created_by"
                 "lead" "driver" "assignee" "proposed_by" "session_of" "superseded_by" "merged_into"})
(def st (c/new-store))
(def tx (c/begin-tx! st "shadow"))
(s/setup! st tx)
(doseq [p (distinct (map :p flat-claims))]
  (s/def-predicate! st p (if (single-preds p) "single" "multi") (if (ref-preds p) "ref" "literal") tx))
(def memo (atom {}))
(defn ent-for! [sid] (or (get @memo sid) (let [id (c/entity! st)] (swap! memo assoc sid id) (s/name! st id sid tx) id)))
(defn ref? [x] (str/starts-with? x "@"))
(doseq [cl flat-claims]
  (let [subj (ent-for! (:l cl)) p (:p cl) r (:r cl)]
    (if (ref? r) (s/link! st subj p (ent-for! r) tx) (s/assert! st subj p r tx))))
(def reif-idx (ck/build-index (reified->claims {:store st})))

;; --- compare the whole read surface (as sets — content equivalence) ---------
(def checks
  [["ready    == flat" (= (set (proj/ready flat-idx))    (set (proj/ready reif-idx)))]
   ["blocked  == flat" (= (set (proj/blocked flat-idx))  (set (proj/blocked reif-idx)))]
   ["leverage == flat" (= (set (lev-top flat-idx))       (set (lev-top reif-idx)))]
   ["validate == flat" (= (set (all-violations flat-idx)) (set (all-violations reif-idx)))]
   ["ready non-trivial" (pos? (count (proj/ready reif-idx)))]])

(println "ready:" (count (proj/ready flat-idx)) "blocked:" (count (proj/blocked flat-idx))
         "leverage:" (count (lev-top flat-idx)) "violations:" (count (all-violations flat-idx)))
(let [fails (remove second checks)]
  (doseq [[nm ok] checks] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nStage 7 (read-equivalence): reified read surface == flat, on the live corpus PASS")
    (do (println "\nStage 7 (read-equivalence): FAIL") (System/exit 1))))
