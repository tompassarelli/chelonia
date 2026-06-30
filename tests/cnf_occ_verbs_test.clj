;; cnf_occ_verbs_test.clj — the move-C gate: per-(s,p) cardinality-typed write verbs
;; over a base-OPTIONAL commit!. The client global-version CAS ritual is gone; the
;; engine now splits writes by cardinality, exercised here straight against commit!
;; (the wire verbs append!/put!/swap! in tern.coord are thin shells over these
;; same commit! calls — append!/put! pass NO base, swap! passes one):
;;
;;   (a) append! — MULTI pred, NO base: never rejected; two concurrent DISJOINT
;;       writes both LAND and coexist.
;;   (b) put!    — SINGLE pred, NO base: last-writer-wins supersede (one live value).
;;   (c) swap!   — SINGLE pred WITH base: a stale base is rejected (:conflict).
;;   (d) base-OPTIONAL — a NO-base write to a declared-single pred whose value MOVED
;;       is ACCEPTED (the intended LWW: callers who want conflict-detection use swap!).
;;   bb -cp out tests/cnf_occ_verbs_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord.clj")   ; new-coord/commit!/live-cids-lp/register-pred!/store/elect

(let [log "/tmp/cnf-occ-verbs-test.log"
      co (new-coord log)
      _ (register-pred! co "status" "single" "literal")   ; declared-single via a cardinality CLAIM
      _ (register-pred! co "tag" "multi" "ref")            ; declared multi
      checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm ok]))
      live-of (fn [te-name pred]
                (vec (live-cids-lp co (s/resolve-name (store co) te-name)
                                   (c/value-id (store co) pred))))
      val-of  (fn [cid] (c/literal (store co) (:r (c/claim-of (store co) cid))))]

  ;; ---- (a) append! — MULTI pred, NO base, concurrent + disjoint, both land ----
  (let [fs   (mapv (fn [v] (future (commit! co (str "w" v) "M1" "tag" :link v nil)))   ; nil base
                   ["X" "Y"])
        rs   (mapv deref fs)
        live (live-of "M1" "tag")]
    (chk "append: a no-base MULTI write is never rejected (#1)" (:ok (rs 0)))
    (chk "append: a no-base MULTI write is never rejected (#2)" (:ok (rs 1)))
    (chk "append: both concurrent DISJOINT writes LAND and coexist" (= 2 (count live)))
    (chk "append: NO :conflict on any no-base multi write"
         (not-any? #(= :conflict (:reject %)) rs)))

  ;; ---- (b) put! — SINGLE pred, NO base, last-writer-wins supersede -------------
  (let [r1   (commit! co "w1" "P1" "status" :assert "open" nil)   ; nil base
        r2   (commit! co "w2" "P1" "status" :assert "done" nil)   ; nil base — supersedes
        live (live-of "P1" "status")]
    (chk "put: both no-base SINGLE writes commit (no staleness reject)" (and (:ok r1) (:ok r2)))
    (chk "put: exactly ONE live value (supersede, not coexist)" (= 1 (count live)))
    (chk "put: the live value is the LAST writer's (LWW)" (= "done" (val-of (first live)))))

  ;; ---- (c) swap! — SINGLE pred WITH base, stale base rejected ------------------
  (let [seed  (commit! co "w0" "P2" "status" :assert "a" 0)
        base  (:ok seed)
        good  (commit! co "w1" "P2" "status" :assert "b" base)   ; up-to-date base wins, bumps base
        stale (commit! co "w2" "P2" "status" :assert "c" base)   ; SAME (now-stale) base -> reject
        live  (live-of "P2" "status")]
    (chk "swap: an up-to-date base commits" (:ok good))
    (chk "swap: a STALE base is REJECTED (:conflict)" (= :conflict (:reject stale)))
    (chk "swap: exactly one live value (the winner's)"
         (and (= 1 (count live)) (= "b" (val-of (first live))))))

  ;; ---- (d) base-OPTIONAL — no-base write to a MOVED single pred is ACCEPTED ----
  ;; This is the LWW contract: put! (no base) does NOT see the staleness reject that
  ;; swap! (base) would. A single pred whose value moved still accepts a no-base write
  ;; and that write WINS. (Conflict-detection is opt-IN via swap!, never imposed.)
  (let [seed  (commit! co "w0" "P3" "status" :assert "a" 0)
        _     (commit! co "w1" "P3" "status" :assert "b" (:ok seed))   ; value MOVES; base advances
        nb    (commit! co "w2" "P3" "status" :assert "c" nil)          ; NO base, against a moved single pred
        live  (live-of "P3" "status")]
    (chk "base-optional: a NO-base write to a MOVED single pred is ACCEPTED" (:ok nb))
    (chk "base-optional: it superseded (LWW) — exactly one live = the last value"
         (and (= 1 (count live)) (= "c" (val-of (first live)))))
    ;; the swap! contract still holds on the SAME pred: a STALE base is still rejected.
    (chk "base-optional: swap! (base) still rejects a stale base on the same pred"
         (= :conflict (:reject (commit! co "w3" "P3" "status" :assert "d" (:ok seed))))))

  (let [cs @checks fails (remove second cs)]
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (println "\nmove-C (OCC write verbs / base-optional):" (count cs) "/" (count cs) "PASS")
      (do (println "\nmove-C (OCC write verbs):" (count fails) "FAILED") (System/exit 1)))))
