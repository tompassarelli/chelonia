;; Fram MEDIUM-APP build: author N defs round-robin across M modules via the warm daemon.
;; Per-op is O(edited-module) (the corpus cache killed the O(total) reduce), so as the APP grows
;; the per-op stays ~flat (module size = N/M), unlike zerolang's O(total-graph) per patch.
;;   bb -cp out /tmp/fram_multi_build.clj   [FRAM_SEED=/tmp/fram-multi.log] [FRAM_M=50] [FRAM_N=500]
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(defn nowns [] (System/nanoTime))
(defn ms [a b] (/ (double (- b a)) 1e6))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def seed (or (System/getenv "FRAM_SEED") "/tmp/fram-multi.log"))
(def M (Integer/parseInt (or (System/getenv "FRAM_M") "50")))
(def N (Integer/parseInt (or (System/getenv "FRAM_N") "500")))
(def flat (str "/tmp/fram-multi-run-" (nowns) ".log"))
(io/copy (io/file seed) (io/file flat))
(boot-flat! flat)
(def port 8242)
(def server (future (serve port)))
(Thread/sleep 700)
(println (format "=== Fram medium-app build: %d defs across %d modules ===" N M))
(println "ops  per-op(ms)  module-size~  cum-wall(s)")
(def t-start (nowns))
(def times (atom []))
(doseq [i (range N)]
  (let [m  (format "mod%02d" (mod i M))
        nm (str "mb" i)
        t0 (nowns)
        r  (client port {:op :edit-min :spec {:op "upsert-form" :module m :datum (list 'def (symbol nm) i)}})
        t1 (nowns)]
    (swap! times conj (ms t0 t1))
    (when-not (:ok r) (println "  FAIL" nm "->" (pr-str r)))
    (when (zero? (mod (inc i) 50))
      (println (format "%-4d %-11.1f %-12d %.2f" (inc i) (ms t0 t1) (+ 2 (quot (inc i) M)) (/ (ms t-start (nowns)) 1000.0))))))
(let [tot (ms t-start (nowns)) ts @times]
  (println (format "\nTOTAL build wall: %.2fs for %d defs (%.1f ms/def avg)" (/ tot 1000.0) N (/ tot N)))
  (println (format "per-op: first-50 mean=%.1fms  last-50 mean=%.1fms  growth=%.2fx"
                   (/ (reduce + (take 50 ts)) 50.0) (/ (reduce + (take-last 50 ts)) 50.0)
                   (/ (/ (reduce + (take-last 50 ts)) 50.0) (max 1e-6 (/ (reduce + (take 50 ts)) 50.0))))))
(future-cancel server)
(System/exit 0)
