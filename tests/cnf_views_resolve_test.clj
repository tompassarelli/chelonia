;; cnf_views_resolve_test.clj — thread E, read-layer half: select-main-1 is now
;; VIEW-RELATIVE. The resolver's single read-time selection point (the descendant of
;; the bare `(first …)` take-firsts, VIEWS_AND_BRANCHES §6/§8) elects the *view*'s
;; member of a live (l,p) group via (view selects @cid) overlay claims.
;;
;; Companion to cnf_honesty_pass_test (which proves the *view*=nil default ≡ first):
;; this proves a BOUND `*view*` isolates a branch's line, and silence inherits main.
;;   bb -cp out tests/cnf_views_resolve_test.clj
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))   ; loads ns `resolve`
(require '[fram.cnf :as c])

(def fails (atom 0))
(defn chk [name ok] (if ok (println (str "  [PASS] " name))
                        (do (swap! fails inc) (println (str "  [FAIL] " name)))))

;; one store, one (l,p) group with three rivals; two branch overlays select one rival each.
(def st  (c/new-store))
(def tx  (c/begin-tx! st "w"))
(def P   (c/value! st "color"))
(def T   (c/entity! st))
(def c0  (c/claim! st T P (c/value! st "base")  tx))   ; bare (no overlay) — main's
(def c1  (c/claim! st T P (c/value! st "b1val") tx))   ; branch b1's rival
(def c2  (c/claim! st T P (c/value! st "b2val") tx))   ; branch b2's rival
(def SEL (c/value! st "selects"))
(def Vb1 (c/value! st "@view:b1"))
(def Vb2 (c/value! st "@view:b2"))
(c/claim! st Vb1 SEL c1 tx)                            ; (b1 selects @c1)
(c/claim! st Vb2 SEL c2 tx)                            ; (b2 selects @c2)
(def grp [c0 c1 c2])
(defn val-of [cid] (c/literal st (:r (c/claim-of st cid))))

(binding [resolve/ctx st]
  ;; default-main view (*view*=nil): elect the whole group — earliest cid = the bare base.
  (chk "main (*view* nil): elects earliest-cid bare claim"
       (= "base" (val-of (resolve/select-main-1 grp))))
  ;; per-branch isolation: each bound view elects ONLY its own selected rival.
  (binding [resolve/*view* "@view:b1"]
    (chk "branch b1: select-main-1 elects b1's selected rival" (= "b1val" (val-of (resolve/select-main-1 grp))))
    (chk "branch b1: input-order-independent" (= (resolve/select-main-1 grp) (resolve/select-main-1 (vec (reverse grp))))))
  (binding [resolve/*view* "@view:b2"]
    (chk "branch b2: select-main-1 elects b2's selected rival" (= "b2val" (val-of (resolve/select-main-1 grp)))))
  ;; isolation: the three views resolve to three DISTINCT claims.
  (chk "isolation: b1, b2, main elect distinct claims"
       (apply distinct? [(binding [resolve/*view* "@view:b1"] (resolve/select-main-1 grp))
                         (binding [resolve/*view* "@view:b2"] (resolve/select-main-1 grp))
                         (resolve/select-main-1 grp)]))
  ;; inherit-the-base: a view SILENT on this group, and an UNKNOWN view, both fall back to main.
  (binding [resolve/*view* "@view:silent"]
    (chk "inherit: a view that selects none of the group falls back to main"
         (= "base" (val-of (resolve/select-main-1 grp)))))
  (binding [resolve/*view* "@view:never-interned"]
    (chk "inherit: an unknown (un-interned) view selects nothing -> main"
         (= "base" (val-of (resolve/select-main-1 grp)))))
  ;; view-cids is the overlay reader: b1's overlay over the group is exactly [c1].
  (binding [resolve/*view* "@view:b1"]
    (chk "view-cids: b1's overlay over the group is exactly its one rival"
         (= [c1] (vec (resolve/view-cids "@view:b1" grp))))))

;; the honesty-pass invariant still holds: no store bound (ctx nil) => byte-identical to first.
(chk "ctx nil: select-main-1 still ≡ first (honesty-pass preserved)"
     (= (mapv resolve/select-main-1 [[] [1] [9 8 7] (range 12)]) (mapv first [[] [1] [9 8 7] (range 12)])))

(println (str "\n---- views-as-claims (resolve layer): " (if (zero? @fails) "ALL PASS" (str @fails " FAIL")) " ----"))
(System/exit (if (zero? @fails) 0 1))
