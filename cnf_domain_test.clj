;; cnf_domain_test.clj — Stage 2 gate: load the LIVE corpus through the schema
;; layer (identity as name-claims, predicate vocab + cardinality/value-kind as
;; claims, refs linked, dangling persons minted), and prove the reified DOMAIN
;; claims (excluding the schema/identity claims) SET-EQUAL the flat fold.
;;   FRAM_LOG=/path bb -cp out cnf_domain_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[fram.fold :as fold] '[fram.rt]
         '[clojure.string :as str] '[clojure.set :as set] '[clojure.java.io :as io])

(def log (System/getenv "FRAM_LOG"))
(when (or (nil? log) (not (.exists (io/file log))))
  (println "cnf_domain_test: skipped — set FRAM_LOG to a claims.log to run")
  (System/exit 0))
(def flat-claims (:claims (fold/fold (fram.rt/read-log log))))
(def flat-set (set (map (fn [cl] [(:l cl) (:p cl) (:r cl)]) flat-claims)))

;; --- resolved vocabulary table (Stage 0 cardinality decision) ---------------
(def single-preds #{"title" "owner" "lead" "driver" "assignee" "source" "part_of"
                    "do_on" "valid_until" "estimate_hours" "created_at" "updated_at"
                    "body" "created_by" "committed" "outcome" "abandoned"
                    "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"})
(def ref-preds #{"depends_on" "part_of" "relates_to" "clarifies" "amends" "created_by"
                 "lead" "driver" "assignee" "proposed_by" "session_of" "superseded_by" "merged_into"})

(def ctx (c/new-store))
(def tx (c/begin-tx! ctx "domain-load"))
(s/setup! ctx tx)
(doseq [p (distinct (map :p flat-claims))]
  (s/def-predicate! ctx p (if (single-preds p) "single" "multi") (if (ref-preds p) "ref" "literal") tx))

;; every @id (subject OR ref-target) -> a named entity (mints the dangling persons too)
(def memo (atom {}))
(defn ent-for! [sid]
  (or (get @memo sid)
      (let [id (c/entity! ctx)] (swap! memo assoc sid id) (s/name! ctx id sid tx) id)))
(defn ref? [x] (str/starts-with? x "@"))
(doseq [cl flat-claims]
  (let [subj (ent-for! (:l cl)) p (:p cl) r (:r cl)]
    (if (ref? r) (s/link! ctx subj p (ent-for! r) tx) (s/assert! ctx subj p r tx))))

;; --- reconstruct the reified DOMAIN view (exclude schema/identity claims) ----
(def schema-preds #{"name" "cardinality" "value_kind" "cnf-supersedes"})
(def reified-domain
  (set (keep (fn [cid]
               (let [cl (c/claim-of ctx cid) pstr (c/literal ctx (:p cl))]
                 (when-not (schema-preds pstr)
                   [(s/name-of ctx (:l cl)) pstr
                    (if (c/value-object? ctx (:r cl)) (c/literal ctx (:r cl)) (s/name-of ctx (:r cl)))])))
             (c/current-claims ctx))))

(def only-flat (set/difference flat-set reified-domain))
(def only-reif (set/difference reified-domain flat-set))
(def gate-ok (and (empty? only-flat) (empty? only-reif)))
;; generic: every ref-target (incl. person handles) resolves to a named entity —
;; no hardcoded handles, no dangling refs.
(def ref-targets (distinct (filter ref? (map :r flat-claims))))
(def refs-ok (every? (fn [h] (some? (s/resolve-name ctx h))) ref-targets))
;; cardinality is now READ FROM THE GRAPH (no hardcoded list)
(def card-ok (and (= "single" (s/cardinality ctx "owner")) (= "multi" (s/cardinality ctx "relates_to"))))

(println "flat-fold:" (count flat-set) " reified-domain:" (count reified-domain) " entities:" (count @memo))
(when (seq only-flat) (println "  LOST:") (doseq [x (take 8 only-flat)] (println "   " (pr-str x))))
(when (seq only-reif) (println "  GAINED:") (doseq [x (take 8 only-reif)] (println "   " (pr-str x))))
(println (if gate-ok    "  [PASS]" "  [FAIL]") "domain claims == flat fold (identity/vocab as claims, excluded)")
(println (if refs-ok    "  [PASS]" "  [FAIL]") "all ref-targets resolve to a named entity (no dangling refs)")
(println (if card-ok    "  [PASS]" "  [FAIL]") "cardinality read from the graph (no hardcoded list)")
(if (and gate-ok refs-ok card-ok)
  (println "\nStage 2: schema/identity/vocab load PASS")
  (do (println "\nStage 2: FAIL") (System/exit 1)))
