;; cnf_causality_test.clj — thread 019f100f-eefe: causality/as-of + first-class
;; retraction + (the death of) the internal lease. The acceptance gate for thread H.
;;
;; Sections map to the thread's ACCEPTANCE criteria:
;;   (d) the causal STAMP (:observed) survives a log replay round-trip + is clamped
;;       to the pre-commit head (a writer cannot claim to have observed the future).
;;   (a) causal ELECTION: two agents assert rival multi-valued claims, BOTH land (no
;;       reject, no block), and select-causal-1 elects an IDENTICAL winner across all
;;       readers purely from recorded observed/cid — and it picks by DECISION order
;;       (observed), which can DIFFER from commit order (cid).
;;   (c) first-class RETRACTION: a withdrawn multi-valued member drops under remove-wins
;;       and RESURRECTS under an add-wins view — the policy is view-relative, not kernel.
;;   (b) :as-of {:seq S} reconstructs the pre-collision view AND re-sees a later-
;;       withdrawn claim (retraction-as-append is what makes as-of EXACT).
;;
;;   bb -cp out tests/cnf_causality_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord.clj")

(def checks (atom []))
(defn chk [nm ok] (swap! checks conj [nm (boolean ok)]))
(defn live-of [co te-name pred]
  (vec (live-cids-lp co (s/resolve-name (store co) te-name) (c/value-id (store co) pred))))

;; ============================================================================
;; (d) the causal stamp survives replay + is clamped to the head
;; ============================================================================
(let [log "/tmp/cnf-causality-d.log"
      co  (new-coord log)
      _   (commit! co "w" "T0" "note"  :assert "x" nil)   ; advance the global seq
      _   (commit! co "w" "T0" "note2" :assert "y" nil)
      ;; agentA reports it had observed an EARLY seq (1) when it decided
      _    (commit! co "agentA" "T1" "zzz" :assert "alice" 1)
      cidA (first (live-of co "T1" "zzz"))
      ;; a writer that claims to have observed the FUTURE is clamped to the head AT ITS COMMIT
      headB (current-seq co)
      _    (commit! co "agentB" "T2" "zzz" :assert "bob" 999999)
      cidB (first (live-of co "T2" "zzz"))
      ;; round-trip through the v2 log
      st2   (replay log)
      obs   (fn [st cid] (get-in @st [:txs (get (:tx-of @st) cid) :observed]))]
  (chk "(d) observed recorded = the base the writer reported (1)" (= 1 (observed-of co cidA)))
  (chk "(d) observed CLAMPED to the pre-commit head (no future stamp)"
       (= headB (observed-of co cidB)))
  (chk "(d) observed survives a cold log REPLAY round-trip" (= 1 (obs st2 cidA)))
  (chk "(d) clamped observed survives replay too" (= headB (obs st2 cidB))))

;; ============================================================================
;; (a) causal election — rivals coexist; the EARLIEST-DECIDER wins (observed),
;;     which can differ from earliest-COMMITTER (cid).
;; ============================================================================
(let [log "/tmp/cnf-causality-a.log"
      co  (new-coord log)
      ;; advance the global head so the two rivals can carry distinct observed stamps
      _   (dotimes [i 50] (commit! co "w" "Tseq" "n" :assert (str i) nil))
      ;; "drv" is undeclared -> MULTI -> rivals coexist (no supersede, no reject).
      ;; agentA COMMITS FIRST (smaller cid) but DECIDED LATE (observed 40).
      rA  (commit! co "agentA" "TH" "drv" :assert "A" 40)
      ;; agentB COMMITS SECOND (larger cid) but DECIDED EARLY (observed 5).
      rB  (commit! co "agentB" "TH" "drv" :assert "B" 5)
      live   (live-of co "TH" "drv")
      [cA cB] (sort live)                       ; cA = agentA's (earlier cid), cB = agentB's
      cid-win    (elect co live)                ; [cid, agent]  -> earliest COMMIT
      causal-win (elect-causal co live)]        ; [observed, cid, agent] -> earliest DECISION
  (chk "(a) both rival multi writes COMMITTED — no block, no reject" (and (:ok rA) (:ok rB)))
  (chk "(a) BOTH coexist live (no supersede)" (= 2 (count live)))
  (chk "(a) cid-election picks the earliest COMMITTER (agentA)" (= cid-win cA))
  (chk "(a) causal-election picks the earliest DECIDER (agentB, observed 5)" (= causal-win cB))
  (chk "(a) causal election DIFFERS from cid election here (the whole point)" (not= cid-win causal-win))
  (chk "(a) causal election is input-order-INDEPENDENT" (= causal-win (elect-causal co (vec (reverse live)))))
  (chk "(a) causal election is STABLE across reads" (= causal-win (elect-causal co live) (elect-causal co live)))
  (chk "(a) the elected member's value is the earliest decider's (B)"
       (= "B" (c/literal (store co) (:r (c/claim-of (store co) causal-win))))))

;; ============================================================================
;; (b) as-of — reconstruct a historical view; a later-superseded claim is RE-SEEN.
;; ============================================================================
(let [log "/tmp/cnf-causality-b.log"
      co  (new-coord log)
      _   (register-pred! co "status" "single" "literal")   ; single -> overwrite supersedes
      pid (c/value-id (store co) "status")
      tid (fn [] (s/resolve-name (store co) "TB"))
      r1  (commit! co "w" "TB" "status" :assert "open" 0)   ; born at seq S1
      s1  (:ok r1)
      mid (current-seq co)                                  ; a seq AFTER open, BEFORE closed
      r2  (commit! co "w" "TB" "status" :assert "closed" s1); supersedes "open" at seq S2 > S1
      s2  (:ok r2)
      val-of (fn [cids] (mapv #(c/literal (store co) (:r (c/claim-of (store co) %))) cids))]
  (chk "(b) NOW: only the latest value is live (open superseded)"
       (= ["closed"] (val-of (live-cids-lp co (tid) pid))))
  (chk "(b) as-of a seq BEFORE the overwrite RE-SEES the superseded 'open'"
       (= ["open"] (val-of (live-as-of-lp co mid (tid) pid))))
  (chk "(b) as-of the CURRENT seq == the live view ('closed')"
       (= ["closed"] (val-of (live-as-of-lp co s2 (tid) pid))))
  (chk "(b) as-of seq 0 (pre-history) sees nothing on this group"
       (empty? (live-as-of-lp co 0 (tid) pid)))
  (chk "(b) as-of is bounded: live-as-of folds the in-store tail, returns a set"
       (set? (live-as-of co s2))))

;; ============================================================================
;; (c) first-class retraction — a withdrawn MULTI member drops under remove-wins
;;     and RESURRECTS under an add-wins view; the tombstone is attributable.
;; ============================================================================
(let [log "/tmp/cnf-causality-c.log"
      co  (new-coord log)
      pid (fn [] (c/value-id (store co) "done_worker"))
      tid (fn [] (s/resolve-name (store co) "@barrier"))
      ;; "done_worker" is undeclared -> MULTI: a K-of-N quorum family (the design's proof
      ;; that retraction works on the completion/quorum dual, not just election).
      _   (commit! co "w" "@barrier" "done_worker" :assert "wA" nil)
      _   (commit! co "w" "@barrier" "done_worker" :assert "wB" nil)
      _   (commit! co "w" "@barrier" "done_worker" :assert "wC" nil)
      before (count (live-members co (tid) (pid)))
      ;; agentX WITHDRAWS wB with attribution (a "done-undo")
      rw  (retract! co "agentX" "@barrier" "done_worker" "wB" nil "mis-reported")
      ;; the withdrawn victim cid (it is now superseded but carries a tombstone)
      victim (first (filter #(withdrawn? co %)
                            (get (:idx-by-lp @(store co)) [(tid) (pid)])))
      remove-live (mapv #(c/literal (store co) (:r (c/claim-of (store co) %)))
                        (live-members co (tid) (pid) :remove-wins))
      add-live    (sort (mapv #(c/literal (store co) (:r (c/claim-of (store co) %)))
                              (live-members co (tid) (pid) :add-wins)))
      wd  (withdrawal-of co victim)]
  (chk "(c) 3 members live before the withdrawal" (= 3 before))
  (chk "(c) the retract committed" (:ok rw))
  (chk "(c) remove-wins (DEFAULT): the withdrawn member wB DROPS"
       (= ["wA" "wC"] (sort remove-live)))
  (chk "(c) remove-wins count is now K-1 (the barrier dual feels the un-vote)"
       (= 2 (count remove-live)))
  (chk "(c) add-wins VIEW: wB RESURRECTS (same log, policy is view-relative)"
       (= ["wA" "wB" "wC"] add-live))
  (chk "(c) the withdrawal is ATTRIBUTABLE — who" (= "agentX" (:by wd)))
  (chk "(c) the withdrawal is ATTRIBUTABLE — why" (= "mis-reported" (:reason wd)))
  (chk "(c) the withdrawal records WHEN (a seq stamp)" (some? (:at wd)))
  ;; a GENUINE overwrite (single-valued LWW) must NOT resurrect under add-wins —
  ;; only withdrawals carry the tombstone that add-wins keys on.
  (let [_  (register-pred! co "phase" "single" "literal")
        r1 (commit! co "w" "@barrier" "phase" :assert "one" 0)
        _  (commit! co "w" "@barrier" "phase" :assert "two" (:ok r1))   ; overwrites "one" (no withdrawal)
        ppid (c/value-id (store co) "phase")
        addp (mapv #(c/literal (store co) (:r (c/claim-of (store co) %)))
                   (live-members co (tid) ppid :add-wins))]
    (chk "(c) add-wins does NOT resurrect a genuine overwrite (no tombstone)"
         (= ["two"] addp))))

;; ---- summary ---------------------------------------------------------------
(let [cs @checks fails (remove second cs)]
  (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
  (if (empty? fails)
    (println "\ncnf-causality:" (count cs) "/" (count cs) "PASS")
    (do (println "\ncnf-causality:" (count fails) "FAILED of" (count cs)) (System/exit 1))))
