;; ============================================================================
;; cnf_ksweep.clj — System 3: gate cost vs corpus size K, decomposed
;;   bb -cp out cnf_ksweep.clj <corpus-log-path>
;;
;; Measures, for one corpus, the gate's cost components — keeping MATERIALIZATION
;; (graph->resolver-tables, corpus-from-store!) SEPARATE from COORDINATION (the
;; coherence scan / gate logic), so the K-trend of each is visible and neither is
;; folded into the other (the materialization-vs-coordination confound flag).
;;   scan-floor   : by-p NAME group scan — O(K), UNSCOPABLE (always runs)
;;   whole-frame  : corpus-from-store! all K modules' frames — MATERIALIZATION, O(K)
;;   scoped-frame : corpus-from-store! ONE module's frames — MATERIALIZATION, O(affected)+floor
;;   coh-scan     : forward-ref coherence check over all edges — COORDINATION/gate, O(edges)
;; The gate does NOT render graph->Clojure (that materialization is paid only to RUN
;; code, never to gate) — so coordination here is pure-graph, no Clojure materialization.
;;
;; SAFETY: boots an isolated /tmp corpus (arg). Never port 7977 / canonical log.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")
(def logpath (first *command-line-args*))
(defn ms [t0 t1] (/ (double (- t1 t0)) 1e6))
(defmacro timed [& body] `(let [t0# (System/nanoTime) r# (do ~@body) t1# (System/nanoTime)] [(ms t0# t1#) r#]))

(boot-flat! logpath)
(def st (:store @co))
(def NAME (c/value-id st "name"))
(def name-cids (vec (c/by-p st NAME)))
(def mods (vec (into (sorted-set) (keep #(let [nm (c/literal st (:r (c/claim-of st %)))]
                                           (when (string? nm) (second (re-matches #"@([^#]+)#\d+" nm)))) name-cids))))
(def K (count mods))
(def one (first mods))
(def nclaims (count (c/current-claims st)))

(def scan-floor (first (timed (doall (map #(c/claim-of st %) name-cids)))))
(def whole-frame (nth (sort (repeatedly 3 #(first (timed (binding [resolve/*resolve-walk?* false]
                                                           (resolve/resolve-warm-store! st (fn []))))))) 1))
(def scoped-frame (nth (sort (repeatedly 3 #(first (timed (binding [resolve/*resolve-walk?* false
                                                                    resolve/*corpus-scope* (fn [sx] (str/includes? (str sx) (str one)))]
                                                            (resolve/resolve-warm-store! st (fn []))))))) 1))
(handle {:op :refers-ensure})   ; materialize refers_to so the coherence scan has edges

(defn fref-count []             ; forward-ref coherence check (coordination/gate logic), whole
  (with-resolve-read st
    (let [psi (parent-slot-index st)
          top (fn [n] (let [p (node-path psi n)] (when-let [s (first p)] (when (re-matches #"f\d+" s) (parse-long (subs s 1))))))]
      (->> (c/by-p resolve/ctx resolve/REFERS) (map #(c/claim-of resolve/ctx %))
           (filter (fn [cl] (let [L (:l cl) D (resolve/ultimate (:r cl)) lp (top L) dp (top D)
                                  lm (resolve/name->module (s/name-of resolve/ctx L))
                                  dm (resolve/name->module (s/name-of resolve/ctx D))]
                              (and lm dm (= lm dm) lp dp (< lp dp))))) count))))
(def coh (timed (fref-count)))

(println (format "KSWEEP K=%d claims=%d scan_floor=%.0f whole_frame=%.0f scoped_frame=%.0f coh_scan=%.0f fwd_refs=%d"
                 K nclaims scan-floor whole-frame scoped-frame (first coh) (second coh)))
