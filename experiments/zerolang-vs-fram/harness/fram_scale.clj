;; Fram per-op (upsert-form def) vs project size N — warm daemon, driven via the SOCKET
;; (the daemon op, NOT the bb-CLI which pays ~280ms startup). Authors N defs into a small
;; seed project (greet), timing each add. Tests whether Fram's per-op is O(1) (flat) as the
;; project grows, vs zero's measured O(N) (whole-graph reload+revalidate+rewrite per patch).
;;   bb -cp out /tmp/fram_scale.clj    [FRAM_SEED=/tmp/fram-small.log] [FRAM_N=240]
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(def root (System/getProperty "user.dir"))
(defn nowns [] (System/nanoTime))
(defn ms [a b] (/ (double (- b a)) 1e6))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def seed (or (System/getenv "FRAM_SEED") "/tmp/fram-small.log"))
(def flat (str "/tmp/fram-scale-" (nowns) ".log"))
(io/copy (io/file seed) (io/file flat))
(boot-flat! flat)
(def port 8231)
(def server (future (serve port)))
(Thread/sleep 700)
(def N (Integer/parseInt (or (System/getenv "FRAM_N") "240")))
(println "=== Fram per-op (upsert-form def) vs N, warm daemon via socket ===")
(println "N    per-add(ms)  log-lines")
(def times (atom []))
(doseq [i (range 1 (inc N))]
  (let [nm (str "fw" i)
        t0 (nowns)
        r (client port {:op :edit-min :spec {:op "upsert-form" :module "greet"
                                             :datum (list 'def (symbol nm) i)}})
        t1 (nowns)]
    (swap! times conj (ms t0 t1))
    (when-not (:ok r) (println "  add" nm "FAILED:" (pr-str r)))
    (when (zero? (mod i 20))
      (println (format "%-4d %-11.1f %d" i (ms t0 t1) (count (str/split-lines (slurp flat))))))))
(let [ts @times n (count ts)
      first20 (/ (reduce + (take 20 ts)) 20.0)
      last20  (/ (reduce + (take-last 20 ts)) 20.0)]
  (println (format "\nfirst-20 mean=%.1fms  last-20 mean=%.1fms  growth=%.2fx (flat => O(1) per op)"
                   first20 last20 (/ last20 (max 1e-6 first20)))))
(future-cancel server)
(System/exit 0)
