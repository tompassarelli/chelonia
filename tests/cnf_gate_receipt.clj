;; ============================================================================
;; cnf_gate_receipt.clj — System 1 part 2 (coupling guard) + System 2 gate-core
;;   bb -cp out cnf_gate_receipt.clj
;;
;; The coherence check: a reference whose resolved refers_to target sits at a HIGHER
;; top-level fN in the SAME module is a FORWARD-REF (use-before-def). This is:
;;   - System 1's no-silent-misorder guard: a coupled concurrent append that lands the
;;     referencer below the referent is DETECTED (not silently committed).
;;   - System 2's gate-core: scoped reference-validity over a staged change.
;; Built on RESOLVED refers_to (resolve/ultimate), not symbol spelling — so it has no
;; false-negative (the rejected C/hybrid detector's flaw) and no builtin false-positive
;; (builtins carry no refers_to edge, so they're skipped).
;;
;; R3 good order (referent-first): append B then A(refs B) -> A above B -> NO forward-ref.
;; R4 bad  order (referent-last):  append A(refs B) then B -> A below B -> forward-ref DETECTED.
;;
;; SAFETY: isolated daemon on a /tmp COPY of .fram/code.log. Never port 7977 / canonical log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")

(def tmp (str "/tmp/cnf-gate-rcpt-" (System/nanoTime) ".log"))
(io/copy (io/file ".fram/code.log") (io/file tmp))
(boot-flat! tmp)
(def st (:store @co))
(defn ms [t0 t1] (/ (double (- t1 t0)) 1e6))
(defmacro timed [& body] `(let [t0# (System/nanoTime) r# (do ~@body) t1# (System/nanoTime)] [(ms t0# t1#) r#]))

;; FORWARD-REF coherence check: {:ref :def :ref-pos :def-pos} for each same-module
;; refers_to edge whose referencer's top-level fN < the def's top-level fN.
(defn forward-refs [store]
  (with-resolve-read store
    (let [psi (parent-slot-index store)
          top-fN (fn [node] (let [p (node-path psi node)]
                              (when-let [s (first p)]
                                (when (re-matches #"f\d+" s) (parse-long (subs s 1))))))]
      (->> (c/by-p resolve/ctx resolve/REFERS)
           (map #(c/claim-of resolve/ctx %))
           (keep (fn [cl]
                   (let [L (:l cl) D (resolve/ultimate (:r cl))
                         lm (resolve/name->module (s/name-of resolve/ctx L))
                         dm (resolve/name->module (s/name-of resolve/ctx D))
                         lp (top-fN L) dp (top-fN D)]
                     (when (and lm dm (= lm dm) lp dp (< lp dp))
                       {:module lm
                        :ref (resolve/binding-name (resolve/refers-target L))
                        :def (resolve/binding-name D) :ref-pos lp :def-pos dp}))))
           set))))

(defn append! [m datum] (handle {:op :edit-min :spec {:op "upsert-form" :module m :datum datum}}))

(println "=== System 1 part 2 (coupling guard) + System 2 gate-core ===")
(def M "kernel")
(handle {:op :refers-ensure})                 ; cold materialize (ground truth)
(def pre0 (timed (forward-refs st)))
(def t-pre (first pre0)) (def pre (second pre0))
(println (format "baseline forward-refs in %s: %d  (gate scan %.0f ms)" M (count pre) t-pre))

;; R4 — bad order: referencer A appended BEFORE referent B  => A below B => forward-ref
(def r4a (append! M '(def fram_fr_lo_A (identity fram_fr_hi_B))))
(def r4b (append! M '(def fram_fr_hi_B 1)))
;; R3 — good order: referent B appended BEFORE referencer A  => A above B => OK
(def r3b (append! M '(def fram_fr_ok_B 2)))
(def r3a (append! M '(def fram_fr_ok_A (identity fram_fr_ok_B))))
(println "appends:" (mapv #(boolean (:ok %)) [r4a r4b r3b r3a]))

(handle {:op :refers-ensure})                 ; refresh kernel's refers_to (dirty)
(def post0 (timed (forward-refs st)))
(def t-post (first post0)) (def post (second post0))
(def newfr (clojure.set/difference post pre))
(println (format "post forward-refs: %d  new: %d  (gate scan %.0f ms)" (count post) (count newfr) t-post))
(doseq [fr newfr] (println "  NEW forward-ref:" fr))

;; the forward-ref entry's :def is the TARGET binding's name; check by :def + positions.
(def r4-detected (some #(= "fram_fr_hi_B" (:def %)) newfr))   ; lo_A(pos41) -> hi_B(pos42): load-time forward-ref
(def r3-violated (some #(= "fram_fr_ok_B" (:def %)) newfr))   ; ok_A(pos44) -> ok_B(pos43): NOT forward (good order)

(println "\n=== FINDING (measure-first) ===")
(println (format "%s has %d LEGITIMATE pre-existing forward-refs — Beagle functions resolve at CALL" M (count pre)))
(println "time, not load time, so a forward-ref is NOT a blanket coherence error. The correct gate is")
(println "therefore DELTA-based: flag only forward-refs INTRODUCED by an edit, excluding the baseline.")

(println "\n=== VERDICT ===")
(println "R4 (coupled bad order, load-time value ref) NEW forward-ref DETECTED:" (boolean r4-detected) "(must be true)")
(println "R3 (coupled good order)                     NO new forward-ref:    " (not (boolean r3-violated)) "(must be true)")
(if (and r4-detected (not r3-violated) (every? :ok [r4a r4b r3b r3a]))
  (do
    (println "PASS — the DELTA-based resolved-refers_to check surfaces the coupled load-time forward-ref (R4)")
    (println "and does NOT false-flag the correctly-ordered pair (R3). No-silent-misorder bar MET: the misorder")
    (println "hazard is DETECTABLE, not silent. Gate-core works; delta-check excludes the legitimate baseline.")
    (println "CAVEAT (documented for morning): the check is conservative — it would also flag a *function*")
    (println "forward-ref (legitimate via deferred eval) if an edit introduced one; refining to load-time-value")
    (println "positions only is future work. So: D commutes (R1); coupling-misorder is narrow + detectable (here)."))
  (println "FAIL — see above."))
