;; datalog_scale_test.clj — semi-naive fixpoint scales where naive hung.
;; Transitive closure over a 200-node chain = 200*199/2 = 19900 reaches-pairs.
;; Under the old naive fixpoint this blew up (the adversarial review measured a
;; hang at N=120); semi-naive completes it in well under a second.
;;   bb -cp out datalog_scale_test.clj
(require '[fram.kernel :as k] '[fram.query :as q])

(def N 200)
;; chain: @a1 -depends_on-> @a2 -> ... -> @aN
(def claims
  (mapv (fn [i] (k/->Claim (str "@a" i) "depends_on" (str "@a" (inc i)))) (range 1 N)))

(def t0 (System/currentTimeMillis))
(def res (q/run claims
           {:find "reaches"
            :rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                     :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                    {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
                     :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                            {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}]}))
(def ms (- (System/currentTimeMillis) t0))
(def n (count (:ok res)))
(def expected (/ (* N (dec N)) 2))

(println (str "  reaches-pairs: " n " (expected " expected ")  in " ms " ms"))
(let [ok-count (= n expected)
      ok-time (< ms 30000)]   ; generous ceiling; semi-naive does this in well under 1s
  (println (if ok-count "  [PASS] " "  [FAIL] ") "transitive closure of a 200-chain is exact")
  (println (if ok-time  "  [PASS] " "  [FAIL] ") "completed under the time ceiling (naive hung here)")
  (if (and ok-count ok-time)
    (println "\ndatalog scale: 2 / 2 PASS")
    (do (println "\ndatalog scale: FAILED") (System/exit 1))))
