;; zero concurrent merge-queue (CLEAN): K writers add a distinct fn to a SHARED zero.graph
;; via real OCC (expect graphHash -> GPH002 reject -> retry) + CAS install. Honest analog of
;; git push-to-shared-ref. Measures TOTAL WALL + failure rate (GPH002 rejects, CAS-misses,
;; lost writes). Hash tracked in an atom; NO zero process call inside the lock.
;;   bb /tmp/zbench/zero_conc2.clj   [ZBASE_N=0] [ZKS=1,2,4,8,16]
(require '[babashka.process :as proc] '[clojure.string :as str] '[clojure.java.io :as io])
(def ZB "/tmp/zbench/bin/zero")
(defn nowns [] (System/nanoTime))
(defn ms [a b] (/ (double (- b a)) 1e6))
(def KS (mapv #(Integer/parseInt %) (str/split (or (System/getenv "ZKS") "1,2,4,8,16") #",")))
(def BASE-N (Integer/parseInt (or (System/getenv "ZBASE_N") "0")))
(defn ghash [dir]   ; project graphHash; bare-file `zero status` returns empty, so always run in the project dir
  (second (re-find #"(graph:[0-9a-f]+)" (str (:out (proc/sh {:dir dir} ZB "status" "--json" "."))))))

(defn seed! [proj n]
  (proc/sh "rm" "-rf" proj)
  (.mkdirs (io/file proj))
  (proc/sh {:dir (str proj "/..")} ZB "init" "--template" "lib" (.getName (io/file proj)))
  (dotimes [i n] (proc/sh {:dir proj} ZB "patch" "--op" (str "addFunction name=\"seed" i "\" ret=\"Void\"")))
  proj)

(def DBG (= "1" (System/getenv "ZDBG")))
;; new hash is printed by `zero patch` itself ("graphHash: graph:..."); parse it from the result.
(defn out-hash [r] (second (re-find #"graphHash:\s*(graph:[0-9a-f]+)" (str (:out r) (:err r)))))
(defn try-add! [proj gpath shared-hash lock rejects cas-miss nm]
  (loop [att 0]
    (let [H   @shared-hash
          tmp (str proj "/.cand-" nm "-" (nowns) ".graph")
          r   (proc/sh {:dir proj} ZB "patch" "zero.graph"
                       "--op" (str "expect graphHash \"" H "\"")
                       "--op" (str "addFunction name=\"" nm "\" ret=\"Void\"")
                       "--out" tmp)]
      (when DBG (binding [*out* *err*] (println (format "  [%s] att=%d exit=%d H=%s newH=%s" nm att (:exit r) H (out-hash r)))))
      (if (zero? (:exit r))
        (let [newH (out-hash r)
              ok (locking lock
                   (when (= @shared-hash H)
                     (io/copy (io/file tmp) (io/file gpath))
                     (reset! shared-hash newH)
                     true))]
          (io/delete-file (io/file tmp) true)
          (if ok :ok (do (swap! cas-miss inc) (recur (inc att)))))
        (do (io/delete-file (io/file tmp) true) (swap! rejects inc) (recur (inc att)))))))

(defn run-K [K]
  (let [proj        (str "/tmp/zc2-" (nowns))
        _           (seed! proj BASE-N)
        gpath       (str proj "/zero.graph")
        shared-hash (atom (ghash proj))
        lock        (Object.)
        rejects     (atom 0)
        cas-miss    (atom 0)
        t0          (nowns)
        futs        (mapv (fn [i] (future (try-add! proj gpath shared-hash lock rejects cas-miss (str "w" i)))) (range K))
        _           (mapv deref futs)
        t1          (nowns)
        outline     (str (:out (proc/sh {:dir proj} ZB "view" "--outline" ".")))
        present     (count (filter (fn [i] (re-find (re-pattern (str "\\bw" i "\\b")) outline)) (range K)))]
    (proc/sh "rm" "-rf" proj)
    {:K K :wall (ms t0 t1) :present present :rejects @rejects :cas-miss @cas-miss}))

(println (format "=== zerolang concurrent merge-queue [CLEAN] (BASE_N=%d) ===" BASE-N))
(println "K    wall(ms)   in-graph/K  GPH002-rejects  CAS-miss  wasted-patches")
(doseq [K KS]
  (let [r (run-K K)]
    (println (format "%-4d %-10.1f %-11s %-15d %-9d %d"
                     (:K r) (:wall r) (str (:present r) "/" K) (:rejects r) (:cas-miss r)
                     (+ (:rejects r) (:cas-miss r))))))
(println "\nin-graph=K => no lost writes. wasted-patches = rejected+CAS-missed attempts (each a full O(N) patch thrown away) = the merge-queue tax.")
