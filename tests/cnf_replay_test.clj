;; cnf_replay_test.clj — Stage 1 gate: replay the live FLAT corpus into the
;; reified CNF kernel, then prove the reified current-view SET-EQUALS the flat
;; fold (no claim lost or gained). The flat->reified bridge is domain-level
;; (knows the @id ref convention); the kernel stays domain-agnostic.
;;   FRAM_LOG=/path bb -cp out cnf_replay_test.clj
(require '[fram.cnf :as c]
         '[fram.fold :as fold]
         '[fram.rt]
         '[clojure.string :as str]
         '[clojure.set :as set]
         '[clojure.java.io :as io])

(def log (System/getenv "FRAM_LOG"))
(when (or (nil? log) (not (.exists (io/file log))))
  (println "cnf_replay_test: skipped — set FRAM_LOG to a claims.log to run")
  (System/exit 0))

;; today's flat fold: the current (l p r) string triples.
(def flat-claims (:claims (fold/fold (fram.rt/read-log log))))
(def flat-set (set (map (fn [cl] [(:l cl) (:p cl) (:r cl)]) flat-claims)))

;; --- replay into the reified kernel -----------------------------------------
(def ctx (c/new-store))
(def tx (c/begin-tx! ctx "flat-replay"))
(def ent (atom {}))                       ; @id-string -> entity-id (domain memo)
(defn ent-for! [s] (or (get @ent s) (let [id (c/entity! ctx)] (swap! ent assoc s id) id)))
(defn ref? [s] (str/starts-with? s "@"))
(defn obj-for! [s] (if (ref? s) (ent-for! s) (c/value! ctx s)))  ; r: ref->entity else value
(doseq [cl flat-claims]
  (c/claim! ctx (ent-for! (:l cl)) (c/value! ctx (:p cl)) (obj-for! (:r cl)) tx))

;; --- reconstruct the reified current-view as string triples -----------------
(def rev-ent (into {} (map (fn [[k v]] [v k]) @ent)))   ; entity-id -> @id-string
(defn obj-str [id] (if (c/value-object? ctx id) (c/literal ctx id) (get rev-ent id)))
(def reified-set
  (set (map (fn [cid]
              (let [cl (c/claim-of ctx cid)]
                [(get rev-ent (:l cl)) (c/literal ctx (:p cl)) (obj-str (:r cl))]))
            (c/current-claims ctx))))

;; --- dump -> EDN -> load round-trip: durable serialization fidelity ---------
(def data (read-string (pr-str (c/dump-store ctx))))
(def ctx2 (c/new-store))
(c/load-store! ctx2 data)
(def rt-ok (and (= (set (c/current-claims ctx)) (set (c/current-claims ctx2)))
                (every? (fn [cid] (= (c/claim-of ctx cid) (c/claim-of ctx2 cid)))
                        (c/current-claims ctx))))

(def only-flat (set/difference flat-set reified-set))
(def only-reif (set/difference reified-set flat-set))
(def gate-ok (and (empty? only-flat) (empty? only-reif)))

(println "flat-fold:" (count flat-set) " reified-current:" (count reified-set) " entities:" (count @ent))
(when (seq only-flat) (println "  LOST:") (doseq [x (take 8 only-flat)] (println "   " (pr-str x))))
(when (seq only-reif) (println "  GAINED:") (doseq [x (take 8 only-reif)] (println "   " (pr-str x))))
(println (if gate-ok "  [PASS]" "  [FAIL]") "Stage-1 gate: reified current-view == flat fold")
(println (if rt-ok   "  [PASS]" "  [FAIL]") "dump/load round-trip: store fidelity preserved")
(if (and gate-ok rt-ok)
  (println "\nStage 1: replay + serialization PASS")
  (do (println "\nStage 1: FAIL") (System/exit 1)))
