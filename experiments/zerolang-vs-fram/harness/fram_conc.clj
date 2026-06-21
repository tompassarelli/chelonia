;; Fram concurrent authoring [commute]: K writers each upsert a distinct def into a SHARED
;; warm daemon (greet module) via the socket, then content-assert visibility (:seen). Measures
;; TOTAL WALL (all K land + visible) + failures. No OCC, no rejects (writes commute). Pairs with
;; zero_conc2.clj (the merge-queue) for the head-to-head.
;;   bb -cp out /tmp/fram_conc.clj   [FRAM_SEED=/tmp/fram-small.log] [FRAM_BASE_N=0] [FRAM_KS=1,2,4,8,16]
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(def root (System/getProperty "user.dir"))
(defn nowns [] (System/nanoTime))
(defn ms [a b] (/ (double (- b a)) 1e6))
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(def seed (or (System/getenv "FRAM_SEED") "/tmp/fram-small.log"))
(def BASE-N (Integer/parseInt (or (System/getenv "FRAM_BASE_N") "0")))
(def KS (mapv #(Integer/parseInt %) (str/split (or (System/getenv "FRAM_KS") "1,2,4,8,16") #",")))
(def flat (str "/tmp/fram-conc-" (nowns) ".log"))
(io/copy (io/file seed) (io/file flat))
(boot-flat! flat)
(def port 8233)
(def server (future (serve port)))
(Thread/sleep 700)
(dotimes [i BASE-N]
  (client port {:op :edit-min :spec {:op "upsert-form" :module "greet"
                                     :datum (list 'def (symbol (str "seed" i)) i)}}))
(println (format "=== Fram concurrent authoring [commute] (BASE_N=%d) ===" BASE-N))
(println "K    wall(ms)  landed/K  visible/K  failures")
(defn writer [base i]
  (let [nm (str base "w" i)
        r (client port {:op :edit-min :spec {:op "upsert-form" :module "greet"
                                             :datum (list 'def (symbol nm) i)}})
        seen (loop [n 0] (cond (:seen (client port {:op :seen :v nm})) true
                               (> n 200000) false
                               :else (recur (inc n))))]
    {:landed (boolean (:ok r)) :seen seen}))
(doseq [K KS]
  (let [base (str "k" K "_")
        t0 (nowns)
        futs (mapv (fn [i] (future (writer base i))) (range K))
        rs (mapv deref futs)
        t1 (nowns)]
    (println (format "%-4d %-9.1f %-9s %-9s %d"
                     K (ms t0 t1)
                     (str (count (filter :landed rs)) "/" K)
                     (str (count (filter :seen rs)) "/" K)
                     (count (remove :landed rs))))))
(future-cancel server)
(System/exit 0)
