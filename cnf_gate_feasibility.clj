;; ============================================================================
;; cnf_gate_feasibility.clj — RED-vs-YELLOW: decompose the scoped-resolve floor
;;   bb -cp out cnf_gate_feasibility.clj
;;
;; The propagation receipt found scoped re-resolve flat ~3s == whole-corpus. The
;; cheaper-gate fork hinges on WHERE that ~3s lives inside corpus-from-store!:
;;   - if the `by-p NAME` whole-corpus scan (always runs, unscopable) ~= 3s -> RED
;;     (need claim-store restructuring; the *corpus-scope* fix won't help)
;;   - if the per-module FRAME builds (scopable via *corpus-scope*) ~= 3s   -> YELLOW
;;     (bind *corpus-scope* in resolve-modules! and the gate gets cheap)
;;
;; Measures: (1) the by-p NAME scan + grouping alone (the floor candidate);
;;           (2) corpus-from-store! WHOLE (*corpus-scope* nil);
;;           (3) corpus-from-store! SCOPED to one module (*corpus-scope* set).
;;
;; SAFETY: isolated daemon on a /tmp COPY of .fram/code.log. Never port 7977, never
;; the canonical log, never the original corpus. Read-only over the store (corpus-
;; from-store! only set!s in-memory tables; *resolve-walk?* false skips all writes).
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")   ; loads resolve (ns resolve) + cnf_coord; tail dispatch no-ops

(defn ms [t0 t1] (/ (double (- t1 t0)) 1e6))
(defmacro timed [& body] `(let [t0# (System/nanoTime) r# (do ~@body) t1# (System/nanoTime)] [(ms t0# t1#) r#]))
(defn parse-mod [nm] (when (string? nm) (second (re-matches #"@([^#]+)#\d+" nm))))

(def tmp (str "/tmp/cnf-gate-" (System/nanoTime) ".log"))
(io/copy (io/file ".fram/code.log") (io/file tmp))
(println "=== RED-vs-YELLOW — decompose the scoped corpus-from-store! floor ===")
(println "corpus: COPY of .fram/code.log ->" tmp)
(let [[bt _] (timed (boot-flat! tmp))]
  (println (format "booted: %d live claims in %.0f ms" (count (c/current-claims (:store @co))) bt)))
(def st (:store @co))
(def NAME (c/value-id st "name"))
(def name-cids (vec (c/by-p st NAME)))
(def mods (vec (into (sorted-set) (keep #(parse-mod (c/literal st (:r (c/claim-of st %)))) name-cids))))
(println (format "name-claims=%d  modules(K)=%d  e.g. %s\n" (count name-cids) (count mods) (vec (take 6 mods))))

;; (1) the always-run floor candidate: by-p NAME scan + grouping
(let [reps 4
      ts (vec (for [_ (range reps)]
                (first (timed (reduce (fn [acc cid]
                                        (let [cl (c/claim-of st cid)
                                              m (parse-mod (c/literal st (:r cl)))]
                                          (if m (update acc m (fnil conj []) (:l cl)) acc)))
                                      {} name-cids)))))
      med (nth (sort ts) (quot (count ts) 2))]
  (println (format "(1) by-p NAME scan + grouping ALONE (always runs, unscopable): median %.0f ms" med))
  (def t-scan med))

;; (2) corpus-from-store! WHOLE — *corpus-scope* nil (all frames + export tables)
;; (3) corpus-from-store! SCOPED — *corpus-scope* matches ONE module (1 module's frames, no exports)
;; resolve-warm-store! sets up the dynamic bindings; *resolve-walk?* false => corpus-from-store! only.
(let [one (first mods)
      reps 4
      whole (vec (for [_ (range reps)]
                   (first (timed (binding [resolve/*resolve-walk?* false]
                                   (resolve/resolve-warm-store! st (fn [])))))))
      scoped (vec (for [_ (range reps)]
                    (first (timed (binding [resolve/*resolve-walk?* false
                                            resolve/*corpus-scope* (fn [sx] (str/includes? (str sx) (str one)))]
                                    (resolve/resolve-warm-store! st (fn [])))))))
      mw (nth (sort whole) (quot (count whole) 2))
      msx (nth (sort scoped) (quot (count scoped) 2))]
  (println (format "(2) corpus-from-store! WHOLE  (all %d modules' frames + exports): median %.0f ms" (count mods) mw))
  (println (format "(3) corpus-from-store! SCOPED (1 module '%s', no export tables): median %.0f ms" one msx))
  (println)
  (println "=== VERDICT ===")
  (println (format "  by-p NAME scan floor:        %.0f ms" t-scan))
  (println (format "  WHOLE corpus-from-store!:    %.0f ms" mw))
  (println (format "  SCOPED corpus-from-store!:   %.0f ms   (scoped/whole = %.2f)" msx (/ msx (max 0.001 mw))))
  (let [scan-frac (/ t-scan (max 0.001 mw))
        scoped-ratio (/ msx (max 0.001 mw))]
    (cond
      (> scoped-ratio 0.75)
      (println "\n  >>> RED: scoping frames did NOT help (scoped ~= whole). The unscopable by-p NAME\n      scan is the floor. A cheaper gate needs claim-store restructuring, not *corpus-scope*.")
      (< scoped-ratio 0.5)
      (println (format "\n  >>> YELLOW: scoping frames CUT the cost %.0f%% (scoped %.2f x whole). The frame\n      builds were the bottleneck; the by-p scan is only %.0f%% of whole. Binding\n      *corpus-scope* in resolve-modules! (one change) makes the gate cheap. Build it."
                       (* 100.0 (- 1.0 scoped-ratio)) scoped-ratio (* 100.0 scan-frac)))
      :else
      (println (format "\n  >>> MIXED: scoped %.2f x whole; scan is %.0f%% of whole. Partial win; the scan\n      sets a floor the frame-scoping can approach but not break."
                       scoped-ratio (* 100.0 scan-frac))))))
