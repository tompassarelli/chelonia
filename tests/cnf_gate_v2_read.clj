;; ============================================================================
;; cnf_gate_v2_read.clj — the DISCRIMINATING read (measure, don't assert).
;;
;; Settles, once, for every future session: on the MAINLINE authored path, is a
;; cross-form reference SPELLING (v -> interned literal, refers_to derived) or
;; IDENTITY (an authored claim whose r-slot is an entity-id pointing at the def)?
;;
;; CELL 1: for a committed cross-form reference (call-site S -> definition B in a
;;   DIFFERENT module), dump EVERY authored claim with l=S, and classify each r-slot:
;;     :literal  = r is a value-object (interned string)  -> spelling
;;     :entity   = r is an entity-id (points at a node)    -> identity
;;   Decisive: is the ONLY identifying authored claim `v -> "foo"` (:literal), or is
;;   there ALSO an authored claim with an :entity r pointing at B? (rules out the
;;   spelling-AND-id conjunction). refers_to/markers are DERIVED — excluded from
;;   "authored" (they don't persist; see CELL 2).
;; CELL 2 is a separate grep of the committed flat log (.fram/code.log).
;;   bb -cp out cnf_gate_v2_read.clj
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s]
         '[clojure.string :as str] '[clojure.java.io :as io])
(def root (System/getProperty "user.dir"))
(def code-log (str root "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no .fram/code.log") (System/exit 0))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def flat (str (System/getProperty "java.io.tmpdir") "/gate-v2-read-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(boot-flat! flat)
(def st (:store @co))
(materialize-refers-whole!)                       ; derive refers_to ONLY to LOCATE cross-form refs
(def KIND (c/value-id st "kind")) (def VV (c/value-id st "v")) (def REFERS (c/value-id st "refers_to"))
(def derived-preds #{"refers_to" "keep_spelling" "qualifier" "ctor_prefix" "accessor_field" "supersedes"})
(defn nm [id] (s/name-of st id))
(defn module-of [name] (when name (second (re-matches #"@([^#]+)#.*" name))))

;; locate cross-form references: symbol S with a DERIVED refers_to -> B, S and B in different modules
(def cross
  (->> (c/by-p st REFERS)
       (keep (fn [cid] (let [cl (c/claim-of st cid) s (:l cl) b (:r cl)
                             sn (nm s) bn (nm b)]
                         (when (and sn bn (module-of sn) (module-of bn)
                                    (not= (module-of sn) (module-of bn)))
                           {:s s :b b :sn sn :bn bn}))))
       (take 3) vec))
(println "cross-form references located (via derived refers_to):" (count cross))

;; CELL 1 — for each, dump every AUTHORED claim with l=S, classify r-slot.
(defn dump [s b]
  (->> (c/by-l st s)
       (map (fn [cid]
              (let [cl (c/claim-of st cid) p (c/literal st (:p cl)) r (:r cl)
                    ent? (not (c/value-object? st r))]
                {:p p
                 :r-kind (if ent? :entity :literal)
                 :r (if ent? (or (nm r) (str "#" r)) (pr-str (c/literal st r)))
                 :points-at-def? (and ent? (= r b))
                 :derived? (boolean (derived-preds p))})))
       vec))

(doseq [{:keys [s b sn bn]} cross]
  (println (str "\n──── call-site " sn "  -->  definition " bn
                "  (module " (module-of sn) " -> " (module-of bn) ") ────"))
  (let [claims (dump s b)
        authored (remove :derived? claims)
        authored-entity-refs (filter #(and (= :entity (:r-kind %)) (not (:derived? %))) authored)
        authored-to-def (filter :points-at-def? authored)]
    (doseq [c claims]
      (println (format "   %-12s r-slot=%-8s r=%-22s %s%s"
                       (:p c) (name (:r-kind c)) (:r c)
                       (if (:derived? c) "[DERIVED]" "[authored]")
                       (if (:points-at-def? c) "  <== ENTITY-ID -> the definition!" ""))))
    (println "   ── authored entity-id refs:" (count authored-entity-refs)
             "| authored claims pointing AT the def:" (count authored-to-def))))

(println "\n================ VERDICT (cell 1) ================")
(def any-identity
  (some (fn [{:keys [s b]}]
          (some :points-at-def? (remove :derived? (dump s b)))) cross))
(println (if any-identity
           "IDENTITY PRESENT — an AUTHORED claim's r-slot is an entity-id pointing at the definition. References carry identity on the mainline TODAY → gate-v2 is a PRESENT obligation."
           "SPELLING ONLY — every authored claim's identifying r-slot is a LITERAL (v -> interned string); the only entity-id link to the def is the DERIVED refers_to. References are spelling+derived → dangling-pointer hazard does NOT exist on the authored path."))
(System/exit 0)
