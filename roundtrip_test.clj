;; roundtrip_test.clj — claims<->files idempotence guard.
;;
;; Proves the cutover keystone stays true: import -> export -> import yields the
;; SAME claim set. If this ever fails, the export projection has lost (or gained)
;; information and files can no longer be trusted as a view of the claim graph.
;;
;;   bb -cp out roundtrip_test.clj      (run from the repo root; uses threads/)
(require '[chelonia.kernel :as k]
         '[chelonia.fold :as fold]
         '[chelonia.import :as imp]
         '[chelonia.export :as exp]
         '[chelonia.rt]
         '[clojure.java.io :as io])

(defn claim-set [assertions]
  (set (map (juxt :l :p :r) (:claims (fold/fold assertions)))))

(let [src "threads"
      a-asserts (imp/load-corpus src)
      a (claim-set a-asserts)
      idx (k/build-index (:claims (fold/fold a-asserts)))
      out (str (System/getProperty "java.io.tmpdir") "/cheln-rt-"
               (System/currentTimeMillis))]
  (.mkdirs (io/file out))
  (doseq [te (k/thread-ids-i idx)]
    (spit (str out "/" (exp/thread-filename idx te)) (exp/thread-md idx te)))
  (let [b (claim-set (imp/load-corpus out))
        only-a (clojure.set/difference a b)
        only-b (clojure.set/difference b a)]
    (println "round-trip:" (count a) "claims in," (count b) "claims back ("
             (count (k/thread-ids-i idx)) "threads )")
    (when (seq only-a) (println "  LOST (in source, not round-trip):")
          (doseq [x (take 10 only-a)] (println "   " (pr-str x))))
    (when (seq only-b) (println "  GAINED (in round-trip, not source):")
          (doseq [x (take 10 only-b)] (println "   " (pr-str x))))
    (if (and (empty? only-a) (empty? only-b))
      (println "  [PASS] import->export->import is claim-identical")
      (do (println "  [FAIL] round-trip is lossy") (System/exit 1)))))
