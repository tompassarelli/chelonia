;; ============================================================================
;; cnf_coord_fram_r1.clj — #11 R1, FRAM arm (REAL socket :edit-min, the #14 path).
;; Batched all-pass disjoint: ONE warm daemon, fire K disjoint set-body edits to K distinct
;; schema functions CONCURRENTLY through the socket. Each :edit-min is its OWN integration
;; unit (a commit) — Fram does NOT batch. Disjoint (te,p) ⇒ no OCC conflict; acyclicity gate
;; inert (AST preds only, no depends_on/part_of) ⇒ OCC-retry is the sole Fram coordination
;; event, and here it is 0. Counterpart to git R1 (cnf_coord_experiment.clj, committed c2dae60).
;; REAL: mechanics + counts. MODELED+LABELED: validation DURATION only (not in wall-ms).
;;   bb -cp out cnf_coord_fram_r1.clj
;; ============================================================================
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))
(require '[clojure.java.io :as io])
(def code-log (str (System/getProperty "user.dir") "/.fram/code.log"))
(when-not (.exists (io/file code-log)) (println "SKIP — no .fram/code.log") (System/exit 0))
(def flat (str (System/getProperty "java.io.tmpdir") "/fram-r1-" (System/nanoTime) ".code.log"))
(io/copy (io/file code-log) (io/file flat))
(defn- pf [p] (try (with-open [s (java.net.Socket.)] (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int p)) 300) false) (catch Exception _ true)))
(boot-flat! flat)
(def port (or (some #(when (pf %) %) [8260 8261 8262 8263]) 8260))
(def srv (future (serve port)))
(Thread/sleep 500)
;; 11 distinct schema functions (disjoint targets).
(def fns ["cardinality" "lookup" "lookup-all" "find-by" "def-predicate!" "name-of"
          "resolve-name" "name!" "assert!" "link!" "replace!"])
(def K (count fns))
(def t0 (System/nanoTime))
;; fire ALL K concurrently (all futures first), THEN collect — true concurrent commute.
(def futs (mapv (fn [f] (future (client port {:op :edit-min :spec {:op "set-body" :module "schema" :name f :datum 42}}))) fns))
(def res (mapv #(deref % 60000 :TIMEOUT) futs))
(def ms (Math/round (/ (- (System/nanoTime) t0) 1e6)))
(try (future-cancel srv) (catch Throwable _ nil))
(io/delete-file (io/file flat) true)
(def landed (count (filter #(and (map? %) (:ok %)) res)))
(def occ (count (filter #(and (map? %) (= :conflict (:reject %))) res)))
(def hung (count (filter #(= :TIMEOUT %) res)))

(println "\n=== #11 R1 — FRAM arm (batched all-pass, K disjoint concurrent socket set-body) ===")
(println (format "  K=%d  landed=%d  hung=%d  occ-retries=%d  integration-units=%d  validation-runs=%d  wall=%dms"
                 K landed hung occ landed landed ms))
(println "  (integration-units = validation-runs = K: Fram does NOT batch — each :edit-min is its own commit.")
(println "   wall-ms = REAL socket mechanics; validation DURATION modeled, not included.)")
(def ok (and (= landed K) (zero? hung) (zero? occ)))
(println (if ok
           "\nFRAM R1 OK — K concurrent disjoint edits ALL committed through the socket, 0 false-conflict, no hang.\n  Contrast vs git R1 (c2dae60): git = 1 speculative batch (validation-runs=1, O(1)); Fram = K commits\n  (validation-runs=K, O(K)). GIT WINS R1 on validation amortization — expected; proves the harness honest."
           "\nFRAM R1 ANOMALY — see counts above."))
(System/exit (if ok 0 1))
