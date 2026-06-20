;; query_test.clj — the structured/validated Datalog-shaped query surface.
;; Proves: (1) positive join + transitive closure return correct tuples over a
;; flat claim fold; (2) the boundary REJECTS malformed/unsafe queries (unknown
;; relation, bad term, unstratified negation) instead of running them — the
;; "can't emit broken" property; (3) stratified negation runs when well-formed.
;;   bb -cp out query_test.clj
(require '[fram.kernel :as k] '[fram.query :as q] '[fram.datalog :as d])

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

;; ============================================================================
;; (8) AUDIT REGRESSIONS — soundness holes in the validate boundary. Each query
;; below validated CLEAN before the fix (silently wrong / unbounded) and is now
;; REJECTED (or bounded) with a clear, specific signal.
;; ============================================================================

;; #7 (HIGH) recursion-through-negation: p is in stratum 0 (so it lands in `lower`)
;; AND re-derived in stratum 1, which also negates p. The negative cycle
;; p -(neg)-> q -> p was accepted as stratifiable (strata-violations returned []);
;; NOT p was evaluated against a still-growing p (eval-order-dependent, inconsistent
;; — q(@b) asserted because ¬p(@b) held, yet p(@b) then derived). Now REJECTED.
(let [seed-claims [(k/->Claim "@a" "seed" "yes")
                   (k/->Claim "@a" "node" "yes")
                   (k/->Claim "@b" "node" "yes")]
      r (q/run seed-claims
          {:find "p"
           :strata [[{:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "seed" "yes"]}]}]
                    [{:head {:rel "q" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "node" "yes"]}
                             {:rel "p" :args [{:var "x"}] :neg true}]}
                     {:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "q" :args [{:var "x"}]}]}]]})]
  (chk "#7 recursion-through-negation REJECTED (negated rel re-derived in same stratum)"
       (and (contains? r :error) (not (contains? r :ok))))
  (chk "#7 error names the recursion-through-negation cause"
       (some #(re-find #"recursion through negation" %) (:error r))))

;; #7 (companion at the engine level): strata-violations itself now flags the cycle.
(chk "#7 d/strata-violations flags the re-derived negated rel (was [])"
     (not (empty? (d/strata-violations
                   [[{:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "seed" "yes"]}]}]
                    [{:head {:rel "q" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "node" "yes"]}
                             {:rel "p" :args [{:var "x"}] :neg true}]}
                     {:head {:rel "p" :args [{:var "x"}]}
                      :body [{:rel "q" :args [{:var "x"}]}]}]]))))

;; #18 (MEDIUM) positive cross-stratum FORWARD reference: stratum 0 positively
;; references `b`, which is defined only in stratum 1. run evaluates strata in
;; order, so `b` is still empty when stratum 0 runs -> `a` is silently empty.
;; validate's `known` (all heads everywhere) let it pass. Now REJECTED.
(let [r (q/run [(k/->Claim "@x" "title" "T")]
          {:find "a"
           :strata [[{:head {:rel "a" :args [{:var "x"}]}
                      :body [{:rel "b" :args [{:var "x"}]}]}]
                    [{:head {:rel "b" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "title" {:var "t"}]}]}]]})]
  (chk "#18 positive forward-reference REJECTED (was silently empty :ok)"
       (and (contains? r :error) (not (contains? r :ok))))
  (chk "#18 error names the forward-referenced rel + later-stratum cause"
       (some #(re-find #"defined only in a LATER stratum" %) (:error r))))

;; #18 the CORRECTLY-ordered program (b first, a after) must still RUN non-empty —
;; the fix rejects only the bad order, it does not break legitimate layering.
(let [r (q/run [(k/->Claim "@x" "title" "T")]
          {:find "a"
           :strata [[{:head {:rel "b" :args [{:var "x"}]}
                      :body [{:rel "triple" :args [{:var "x"} "title" {:var "t"}]}]}]
                    [{:head {:rel "a" :args [{:var "x"}]}
                      :body [{:rel "b" :args [{:var "x"}]}]}]]})]
  (chk "#18 correctly-ordered strata still run (not over-rejected)"
       (and (contains? r :ok) (= (set (:ok r)) #{["@x"]}))))

;; #22 (LOW) a head relation defined at inconsistent arities -> ragged tuples.
;; `r` derived at arity 1 AND 2 previously validated clean and emitted mixed-shape
;; rows. Now REJECTED.
(let [r (q/run [(k/->Claim "@a" "p" "@b") (k/->Claim "@c" "p" "@d")]
          {:find "r"
           :rules [{:head {:rel "r" :args [{:var "x"}]}
                    :body [{:rel "triple" :args [{:var "x"} "p" {:var "z"}]}]}
                   {:head {:rel "r" :args [{:var "x"} {:var "y"}]}
                    :body [{:rel "triple" :args [{:var "x"} "p" {:var "y"}]}]}]})]
  (chk "#22 inconsistent head arity REJECTED (was ragged :ok output)"
       (and (contains? r :error) (not (contains? r :ok))))
  (chk "#22 error names inconsistent arities"
       (some #(re-find #"inconsistent arit" %) (:error r))))

;; #22 a head derived by MULTIPLE rules at the SAME arity must still run.
(chk "#22 same-arity multi-rule head still runs (not over-rejected)"
     (contains? (q/run claims
                  {:find "edge"
                   :rules [{:head {:rel "edge" :args [{:var "x"} {:var "y"}]}
                            :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}
                           {:head {:rel "edge" :args [{:var "x"} {:var "y"}]}
                            :body [{:rel "triple" :args [{:var "y"} "depends_on" {:var "x"}]}]}]})
                :ok))

;; #11 (HIGH) result-set bound on the AI-facing path. A cross-join (two
;; unconstrained `triple` literals sharing no variables) over M subjects yields M^2
;; pairs. With no cap this is materialized as a vector and JSON-serialized whole.
;; The cap is q/max-results (env FRAM_MAX_RESULTS, here whatever is configured):
;; build a corpus whose cross-join strictly exceeds it, and assert the over-limit
;; signal is returned INSTEAD of the (huge) result. Self-calibrates to the cap.
(let [cap q/max-results
      m (inc (long (Math/ceil (Math/sqrt (double cap)))))   ; m^2 > cap
      big-claims (mapv (fn [i] (k/->Claim (str "@s" i) "p" (str "@o" i))) (range m))
      r (q/run big-claims
          {:find "pair"
           :rules [{:head {:rel "pair" :args [{:var "a"} {:var "b"}]}
                    :body [{:rel "triple" :args [{:var "a"} {:var "pp"} {:var "qq"}]}
                           {:rel "triple" :args [{:var "b"} {:var "ss"} {:var "tt"}]}]}]})]
  (chk "#11 oversized result set is BOUNDED (over-limit signal, not :ok)"
       (and (not (contains? r :ok)) (contains? r :error)))
  (chk "#11 over-limit signal reports the count and the cap"
       (and (= (:max r) cap) (> (:over-limit r) cap)))
  (chk "#11 over-limit error is a clear, actionable message"
       (some #(re-find #"result set too large" %) (:error r))))

;; #11 a result set WITHIN the cap still returns :ok (the bound doesn't over-trigger).
(chk "#11 result within the cap still returns :ok"
     (contains? (q/run claims {:find "r" :rules [{:head {:rel "r" :args [{:var "x"} {:var "y"}]}
                                                  :body [{:rel "triple" :args [{:var "x"} "depends_on" {:var "y"}]}]}]}) :ok))

;; --- report ---
(let [cs @checks fails (filter (fn [[_ ok]] (not ok)) cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\nfram.query:" (count cs) "/" (count cs) "PASS")
    (do (println "\nfram.query:" (count fails) "FAILED") (System/exit 1))))
