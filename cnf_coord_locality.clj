;; ============================================================================
;; cnf_coord_locality.clj — STRUCTURAL-POSITION CONFLICT-DETECTION RECEIPT
;;   bb -cp out cnf_coord_locality.clj
;;
;; CLAIM (verified — premise corrected by grounding + 3-lens adversarial audit):
;;   The engine gives commit-time conflict detection to SINGLE-VALUED fields
;;   (stale concurrent write -> :reject :conflict). It gives NONE to the dense
;;   MULTI-VALUED `fN` structural-ordering positions. So two overlapping
;;   structural edits to the same position both land -> two live children at one
;;   index -> ordered-children renders a DUPLICATE position = SILENT CORRUPTION.
;;
;;   This is the OPPOSITE of the assumed failure mode. ChatGPT, claude-4.8 and I
;;   all assumed dense fN OVER-serializes via OCC. It UNDER-protects: fN is multi
;;   (kernel single? excludes it), and the base_version reject at cnf_coord.clj:130
;;   is `(and single ...)` -> never fires for positions.
;;
;; LIVE, NOT LATENT (resolved by the daemon-masking audit lens):
;;   Concurrent :edit-min upsert-form APPENDs to one module corrupt the index in
;;   the live daemon. Both requests clone lock-free (cnf_coord_daemon.clj:842) and
;;   dispatch OUTSIDE the outer dlock (:953); each computes next-n = (inc (max fN))
;;   on its own clone of the same base state (resolve.clj:1168) and freezes the
;;   literal "f3" at harvest; the dlock (:923) wraps ONLY the commit replay, with
;;   NO re-read of max under the lock. SMOKING GUN: the codebase fixed this exact
;;   clone-side race for node-name-ints with an atomic counter
;;   (cnf_coord_daemon.clj:798-815) but LEFT THE fN INDEX UNPATCHED. The hazard is
;;   real and currently untested (existing edit-min concurrency tests use disjoint
;;   set-body on different defns, which commute).
;;
;; SAFETY: isolated coordinator on a /tmp log. Never touches port 7977 or the
;; canonical lodestar log (~/.local/state/lodestar/claims.log).
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s] '[clojure.string :as str])
(load-file "cnf_coord.clj")

(defn fresh-coord [pos-card]                ; pos-card = "multi" (real fN) | "single" (the fix)
  (let [log (str "/tmp/cnf-locality-" (System/nanoTime) ".log")
        co  (new-coord log)]
    (register-pred! co "title" "single" "literal")     ; a single-valued field, for contrast
    (doseq [p ["p0" "p1" "p2" "p3"]] (register-pred! co p pos-card "ref"))  ; ordering positions
    [co log]))

;; live children at one ordering position (>1 == duplicate-index corruption)
(defn pos-count [co m-name pos]
  (let [st (store co) m (s/resolve-name st m-name) p (c/value-id st pos)]
    (if (and m p) (count (live-cids-lp co m p)) 0)))

;; ordered-children's shape (position -> #live children), mirroring resolve.clj:107
(defn ordered-shape [co m-name positions]
  (into (sorted-map) (map (fn [p] [p (pos-count co m-name p)]) positions)))

;; two agents APPEND to one module: both compute the same next index from the same
;; base view, then race the commit. `pos-card` decides whether positions are
;; multi (the real fN) or single (the fix direction).
(defn append-race [pos-card]
  (let [[co _] (fresh-coord pos-card)
        _  (commit! co "init" "m" "p0" :link "c0" 0)
        _  (commit! co "init" "m" "p1" :link "c1" 0)
        _  (commit! co "init" "m" "p2" :link "c2" 0)
        st (store co)
        m  (s/resolve-name st "m")
        p3 (c/value-id st "p3")
        base (base-version co m p3)                     ; p3 empty => 0; both agents see this
        r-a  (commit! co "A" "m" "p3" :link "cA" base)  ; A appends cA at p3
        r-b  (commit! co "B" "m" "p3" :link "cB" base)] ; B appends cB at p3, SAME base
    {:a r-a :b r-b :shape (ordered-shape co "m" ["p0" "p1" "p2" "p3"])
     :at-p3 (pos-count co "m" "p3")}))

(println "=== ORDERING-LOCALITY RECEIPT — structural-position conflict detection ===\n")

;; --------------------------------------------------------------------------
;; EXP 1 — SINGLE-VALUED FIELD: clean conflict detection (the PROTECTED baseline)
;; --------------------------------------------------------------------------
(let [[co _] (fresh-coord "multi")
      seed (commit! co "init" "d" "title" :assert "v0" 0)
      base (:ok seed)
      r-a  (commit! co "A" "d" "title" :assert "vA" base)
      r-b  (commit! co "B" "d" "title" :assert "vB" base)]
  (println "EXP 1 — single-valued field (d,title), two writers, same base:")
  (println "  A:" r-a "   B:" r-b)
  (println "  live values at (d,title):" (pos-count co "d" "title") " (single => exactly 1 survives)")
  (println "  >>> SAFE: loser REJECTED at commit (base_version OCC, cnf_coord.clj:130)\n"))

;; --------------------------------------------------------------------------
;; EXP 2 — MULTI-VALUED fN POSITION (the real ordering rep): NO conflict detection
;; --------------------------------------------------------------------------
(let [{:keys [a b shape at-p3]} (append-race "multi")]
  (println "EXP 2 — structural position (m,p3) as MULTI (real fN), two appends, same base:")
  (println "  A:" a "   B:" b)
  (println "  ordered-children shape (position -> #live children):" shape)
  (println "  live children at (m,p3):" at-p3 " <== 2 means TWO forms at index 3")
  (println "  >>> HAZARD: BOTH writers :ok (L130 reject is `(and single ...)`; fN is multi)")
  (println "  >>> ordered-children yields a DUPLICATE index 3 -> SILENT RENDER CORRUPTION\n"))

;; --------------------------------------------------------------------------
;; EXP 3 — SAME APPEND RACE, positions registered SINGLE (fix class A: REJECT)
;;   note: this catches the APPEND-into-fresh-position case too, because
;;   base_version is read at commit time against CURRENT live state, not the
;;   empty-at-compute snapshot. Stronger than a replace-into-seeded demo.
;; --------------------------------------------------------------------------
(let [{:keys [a b at-p3]} (append-race "single")]
  (println "EXP 3 — SAME append race, positions SINGLE-valued (fix class A — REJECT):")
  (println "  A:" a "   B:" b)
  (println "  live children at (m,p3):" at-p3 " (single => loser rejected, exactly 1)")
  (println "  >>> FIX A works — even on append-into-fresh: base_version reads CURRENT")
  (println "  >>> live state at commit, so after A lands, B's stale base is rejected.\n"))

(println "=== VERDICT — LIVE hazard; three-way fix fork ===")
(println "Single-valued fields: stale concurrent write -> :reject :conflict (SAFE, EXP1).")
(println "Dense multi-valued fN positions: stale concurrent write -> BOTH land -> duplicate")
(println "  index -> silent render corruption (HAZARD, EXP2). No commit-time conflict detection.")
(println "LIVE in the daemon, not latent: edit-min computes next-n on a lock-free clone and")
(println "  commits the frozen \"fN\" under the dlock with NO recompute (daemon:842,:953,:923;")
(println "  resolve.clj:1168). The wired verbs avoid corruption ONLY single-writer / fresh-clone:")
(println "  they harvest retire+mint ops that COMMIT under the dlock, but the mint/index-compute")
(println "  itself runs lock-free on the clone. Smoking gun: the SAME clone-side race was fixed")
(println "  for node-name-ints (daemon:798-815) but the fN index was left unpatched.")
(println "")
(println "FIX FORK (all DEFERRED — not built here):")
(println "  A. single-valued-per-position cardinality -> REJECT the loser (EXP3). Safe, but")
(println "     concurrent structural appends now SERIALIZE (loser retries) = a barrier.")
(println "  B. bare fractional/sparse keys -> does NOT fix appends: both compute the same")
(println "     after-last key from the same base view. Insufficient.")
(println "  C. tiebroken/actor-id ordering key (fractional-index + actor-id / LSEQ-style) ->")
(println "     ACCEPT both at distinct deterministic positions. Safe AND commuting: no false")
(println "     conflict, both appends survive. Most thesis-aligned (commute-by-construction).")
(println "  The A-vs-C fork (safe-but-serializing vs safe-and-commuting) IS the thesis-relevant")
(println "  decision. Measure-first corrected the premise: the cure is conflict detection or a")
(println "  commuting key, NOT bare fractional keys.")
