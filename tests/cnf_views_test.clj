;; cnf_views_test.clj — thread E (views-as-claims) gate: per-branch isolation +
;; read-time, view-relative election. A VIEW is a first-class subject; (view selects
;; @cid) claims are its overlay; elect(view, cids) restricts the election to a branch's
;; selected rivals, inheriting main where the branch is silent. Builds on move-B
;; (coexist-elect): rival writes already coexist; this proves a NAMED view picks its
;; OWN winner without disturbing main or sibling branches — conflict dissolved into
;; coexistence, "merge" demoted to a per-view read-time choice (VIEWS_AND_BRANCHES §8).
;;   bb -cp out tests/cnf_views_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord.clj")   ; new-coord/commit!/commit-on-view!/select!/view-selects/elect/live-cids-lp/store

(let [log "/tmp/cnf-views-test.log"
      co (new-coord log)
      checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm ok]))
      live-of (fn [te p] (vec (live-cids-lp co (s/resolve-name (store co) te)
                                            (c/value-id (store co) p))))
      val-of  (fn [cid] (c/literal (store co) (:r (c/claim-of (store co) cid))))]

  ;; ---- (1) coexist base: three rivals to one UNDECLARED (multi) (s,p) ----
  ;; main's bare write + two branch writes — all coexist (move-B), none rejected.
  (let [r0 (commit! co "main" "T" "color" :assert "base" nil)
        b1 (commit-on-view! co "@view:b1" "b1" "T" "color" :assert "b1val")
        b2 (commit-on-view! co "@view:b2" "b2" "T" "color" :assert "b2val")
        live (live-of "T" "color")]
    (chk "coexist: all three rivals committed — no writer blocked/rejected"
         (and (:ok r0) (:ok b1) (:ok b2)))
    (chk "coexist: THREE live claims on (T,color) (append-only, no supersede)"
         (= 3 (count live)))

    ;; ---- (2) per-branch isolation: each view elects its OWN rival ----
    (chk "main (default view) elects the EARLIEST-cid bare base claim"
         (= "base" (val-of (elect co live))))
    (chk "main: 2-arity == 3-arity nil view (byte-identical default election)"
         (= (elect co live) (elect co nil live)))
    (chk "branch b1 elects b1's OWN selected rival" (= "b1val" (val-of (elect co "@view:b1" live))))
    (chk "branch b2 elects b2's OWN selected rival" (= "b2val" (val-of (elect co "@view:b2" live))))
    (chk "isolation: b1, b2, main each elect a DISTINCT claim"
         (apply distinct? (map #(elect co % live) ["@view:b1" "@view:b2" nil])))
    (chk "isolation: a branch write NEVER changes main's election"
         (= "base" (val-of (elect co nil live))))

    ;; ---- (3) view-selects reads the overlay; select! is idempotent ----
    (chk "view-selects: b1 selects EXACTLY its one rival"
         (= #{(:cid b1)} (view-selects co "@view:b1")))
    (chk "select!: re-selecting a cid is idempotent (no duplicate overlay edge)"
         (and (:idempotent (select! co "@view:b1" (:cid b1)))
              (= 1 (count (view-selects co "@view:b1"))))))

  ;; ---- (4) inherit-the-base: a view SILENT on a (s,p) falls back to main ----
  (commit! co "main" "T" "size" :assert "M" nil)            ; only main writes (T,size)
  (let [live (live-of "T" "size")]
    (chk "inherit: a branch silent on (T,size) inherits main's election"
         (= "M" (val-of (elect co "@view:b1" live))))
    (chk "inherit: an UNKNOWN view selects nothing -> inherits main"
         (= "M" (val-of (elect co "@view:never" live)))))

  ;; ---- (5) within-view election is deterministic (the elect-everywhere-identical guarantee) ----
  (let [live (live-of "T" "color")]
    (chk "view election is input-order-INDEPENDENT (reversed input, same winner)"
         (= (elect co "@view:b1" live) (elect co "@view:b1" (vec (reverse live)))))
    (chk "view election is STABLE across repeated reads"
         (= (elect co "@view:b2" live) (elect co "@view:b2" live))))

  ;; ---- (6) durability: per-branch selection survives a replay of the log ----
  (let [st2  (replay log)
        co2  {:store st2 :log nil :lock (Object.)}
        live (vec (live-cids-lp co2 (s/resolve-name st2 "T") (c/value-id st2 "color")))]
    (chk "replay: branch b1 still elects b1val after a cold log replay"
         (= "b1val" (c/literal st2 (:r (c/claim-of st2 (elect co2 "@view:b1" live))))))
    (chk "replay: main still elects base after a cold log replay"
         (= "base" (c/literal st2 (:r (c/claim-of st2 (elect co2 live)))))))

  (let [cs @checks fails (remove second cs)]
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (println "\nViews-as-claims (thread E):" (count cs) "/" (count cs) "PASS")
      (do (println "\nViews-as-claims (thread E):" (count fails) "FAILED") (System/exit 1)))))
