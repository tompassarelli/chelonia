;; query_test.clj — the structured/validated Datalog-shaped query surface.
;; Proves: (1) positive join + transitive closure return correct tuples over a
;; flat claim fold; (2) the boundary REJECTS malformed/unsafe queries (unknown
;; relation, bad term, unstratified negation) instead of running them — the
;; "can't emit broken" property; (3) stratified negation runs when well-formed.
;;   bb -cp out query_test.clj
(require '[fram.kernel :as k] '[fram.query :as q])

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm ok]))

;; a tiny graph as CLAIMS (strings, exactly the flat fold's shape):
;;   a -depends_on-> b -depends_on-> c   ; b,c are "hub"
(def claims
  [(k/->Claim "@a" "depends_on" "@b")
   (k/->Claim "@b" "depends_on" "@c")
   (k/->Claim "@b" "kind" "hub")
   (k/->Claim "@c" "kind" "hub")
   (k/->Claim "@a" "title" "Alpha")])

;; (1) positive 2-literal join: hubdep(X,H) :- triple(X,depends_on,H), triple(H,kind,hub)
(let [r (q/run claims
          {:find "hubdep"
           :rules [{:head {:rel "hubdep" :args [{:var "x"} {:var "h"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "h"}]}
                           {:rel "triple" :args [{:var "h"} "kind" "hub"]}]}]})
      got (set (:ok r))]
  (chk "positive join finds a->b (b is a hub)" (contains? got ["@a" "@b"]))
  (chk "positive join excludes a->c (a doesn't directly depend on c)" (not (contains? got ["@a" "@c"]))))

;; (2) transitive closure (recursion): reaches(X,Y) base + step
(let [r (q/run claims
          {:find "reaches"
           :rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                   {:head {:rel "reaches" :args [{:var "x"} {:var "z"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                           {:rel "reaches" :args [{:var "y"} {:var "z"}]}]}]})
      got (set (:ok r))]
  (chk "transitive closure reaches a->b" (contains? got ["@a" "@b"]))
  (chk "transitive closure reaches a->c (a->b->c)" (contains? got ["@a" "@c"]))
  (chk "transitive closure reaches b->c" (contains? got ["@b" "@c"])))

;; (3) BOUNDARY: unknown relation is rejected, not silently empty
(let [r (q/run claims
          {:find "x" :rules [{:head {:rel "x" :args [{:var "a"}]}
                              :body [{:rel "bogus" :args [{:var "a"}]}]}]})]
  (chk "unknown relation -> :error (not :ok)" (and (contains? r :error) (not (contains? r :ok))))
  (chk "unknown relation error mentions the bad rel"
       (some #(re-find #"bogus" %) (:error r))))

;; (4) BOUNDARY: a malformed term (not {:var} / not constant) is rejected
(let [r (q/run claims
          {:find "x" :rules [{:head {:rel "x" :args [{:var "a"}]}
                              :body [{:rel "triple" :args [{:not-a-var "a"} "depends_on" {:var "a"}]}]}]})]
  (chk "bad term -> :error" (contains? r :error)))

;; (5) BOUNDARY: unstratified negation (negate a derived rel in the same stratum) is rejected
(let [r (q/run claims
          {:find "safe"
           :rules [{:head {:rel "reaches" :args [{:var "x"} {:var "y"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                   {:head {:rel "safe" :args [{:var "x"}]}
                    :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                           {:rel "reaches" :args [{:var "x"} {:var "y"}] :neg true}]}]})]
  (chk "negating a same-stratum derived rel -> :error (stratification)" (contains? r :error)))

;; (6) stratified negation that IS well-formed runs: terminal via explicit strata.
;;   stratum 0: done(X) :- triple(X,kind,hub)        (treat hub as "done")
;;   stratum 1: open(X) :- triple(X,depends_on,_), NOT done(X)
(let [r (q/run claims
          {:find "open"
           :strata [[{:head {:rel "done" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "kind" "hub"]}]}]
                    [{:head {:rel "open" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}
                             {:rel "done" :args [{:var "x"}] :neg true}]}]]})
      got (set (map first (:ok r)))]
  (chk "stratified negation runs (well-formed)" (contains? r :ok))
  (chk "open includes @a (depends, not a hub)" (contains? got "@a"))
  (chk "open excludes @b (depends, but IS a hub/done)" (not (contains? got "@b"))))

;; (7) BOUNDARY FIXES from adversarial review — the head is now as rigorous as the
;; body, and validation is total (never crashes on malformed input).
(defn err? [q] (contains? (q/run claims q) :error))
(chk "bad HEAD term rejected (was silently emitted as a constant)"
     (err? {:find "r" :rules [{:head {:rel "r" :args [{:foo 1}]}
                               :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}]}]}))
(chk "unbound HEAD var rejected (was emitting nil tuples)"
     (err? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"} {:var "UNBOUND"}]}
                               :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}]}]}))
(chk "HEAD :rel shadowing 'triple' rejected (was injecting fabricated facts)"
     (err? {:find "triple" :rules [{:head {:rel "triple" :args ["@fake" "hacked" "yes"]}
                                    :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}]}]}))
(chk "malformed :strata -> :error, not a crash"  (err? {:find "r" :strata "nope"}))
(chk "malformed :rules -> :error, not a crash"   (err? {:find "r" :rules "nope"}))
(chk "non-boolean :neg rejected"
     (err? {:find "r" :rules [{:head {:rel "r" :args [{:var "x"}]}
                               :body [{:rel "triple" :args [{:var "x"} "title" {:var "y"}]}
                                      {:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}] :neg "yes"}]}]}))
(chk "valid query still runs after tightening (regression)"
     (contains? (q/run claims {:find "r" :rules [{:head {:rel "r" :args [{:var "x"} {:var "y"}]}
                                                  :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}]}) :ok))

;; --- report ---
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.query:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.query:" (count fails) "FAILED") (System/exit 1))))
