;; roundtrip_test.clj — claims<->files idempotence guard.
;;
;; Proves the cutover keystone stays true: import -> export -> import yields the
;; SAME claim set. If this ever fails, the export projection has lost (or gained)
;; information and files can no longer be trusted as a view of the claim graph.
;;
;;   bb -cp out roundtrip_test.clj      (run from the repo root; uses threads/)
(require '[fram.kernel :as k]
         '[fram.fold :as fold]
         '[fram.import :as imp]
         '[fram.export :as exp]
         '[fram.rt]
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
  (let [claims (:claims (fold/fold a-asserts))]
    (doseq [te (k/thread-ids-i idx)]
      (let [title (k/one-i idx te "title")
            fname (str (subs te 1) "-" (fram.rt/slugify (if title title "untitled")) ".md")]
        (spit (str out "/" fname) (exp/thread-md claims te)))))
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
