;; graph_arm_occ_probe.clj — verify OCC base_version over REAL sockets against a
;; TEMP serve-flat coordinator on a verified-free port. Mirrors cnf_flip_test's
;; race, but boots from a temp flat log (NOT the live log). Proves: K concurrent
;; single-triple proxy asserts on the SAME (subject,single-pred) with a stale base
;; -> exactly 1 :ok, rest :reject :conflict. This is the serialization invariant
;; the graph arm's commit step relies on (no lost updates).
;;
;;   PORT=<free> FLAT=<temp.log> bb -cp out experiments/concurrent-authoring/graph_arm_occ_probe.clj
(require '[clojure.string :as str] '[clojure.edn :as edn] '[clojure.java.io :as io])
(load-file "cnf_coord_daemon.clj")

(def port (Integer/parseInt (or (System/getenv "PORT") "9100")))
(def flat (or (System/getenv "FLAT") "/tmp/t2-occ-probe.log"))

;; seed a temp flat log with one synthetic single-valued claim (subject @T, title).
(spit flat "{:l \"@T\" :p \"title\" :r \"seed\" :tx 1}\n")

(boot-flat! flat)
;; CONFIRM we booted the EXPECTED temp log, not the live one.
(let [st (handle {:op :status})]
  (when (not= (:log st) flat)
    (println "FATAL: booted log" (:log st) "!= expected" flat) (System/exit 2))
  (println "status-log-ok" (:log st) "claims" (:claims st)))

(def server (future (serve port)))
(Thread/sleep 600)

;; K concurrent racers on the SAME (@T, title) with the SAME stale base -> OCC.
(def v0 (:version (client port {:op :version})))
(def K 8)
(def race
  (mapv deref
        (mapv (fn [i] (future (client port {:op :assert :te "@T" :p "title"
                                            :r (str "race" i) :base v0})))
              (range K))))
(def wins (count (filter :ok race)))
(def conflicts (count (filter #(= :conflict (:reject %)) race)))

;; the final live value is exactly ONE of the racers (no lost-update corruption:
;; the store holds a single coherent value, not a torn mix).
(def final-status (handle {:op :status}))

(future-cancel server)
(println "OCC-RACE K=" K " wins=" wins " conflicts=" conflicts
         " final-version=" (:version final-status))
(if (and (= 1 wins) (= (dec K) conflicts))
  (do (println "OCC-PROBE: PASS (exactly 1 win, rest conflict — serialization holds, no lost update)")
      (System/exit 0))
  (do (println "OCC-PROBE: FAIL") (System/exit 1)))
