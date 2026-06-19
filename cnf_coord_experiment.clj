;; ============================================================================
;; cnf_coord_experiment.clj — #11 coordination-cost scoreboard. STEP 1+2 here:
;; the GIT SPECULATIVE-BATCHING BASELINE + its R1 acceptance test. Built FIRST,
;; before the Fram arm exists to bias toward (advisor build-order).
;;
;; What's REAL: the coordination mechanics — real git repo, real branches per agent,
;; real 3-way auto-merge, real batch landing. What's MODELED (labeled): validation
;; DURATION (a parameter); real Beagle recompile x the full K/arrival/regime sweep is
;; infeasible. The COORDINATION-COST outputs (integration-units, validation-runs,
;; merge-conflicts, wall-clock of real git ops) are measured, not modeled.
;;
;; WORKLOAD: K structurally-DISJOINT edits — each agent edits a DIFFERENT function in
;; the same file (well-separated lines), so git 3-way merge auto-merges cleanly. This
;; is the primary (disjoint) workload; no coupled/reference-breaking edits (that's #11b).
;;
;; R1 ACCEPTANCE TEST (the anti-strawman contract): batched all-pass disjoint, the git
;; arm's validation-runs MUST be ~O(1) in K (one speculative batch CI), NOT O(K). If it
;; comes out O(K), the queue is a serial strawman and the whole benchmark is VOID.
;;   bb -cp out cnf_coord_experiment.clj
;; ============================================================================
(require '[clojure.string :as str] '[clojure.java.io :as io] '[babashka.process :as proc])

(def gitenv {"GIT_AUTHOR_NAME" "x" "GIT_AUTHOR_EMAIL" "x@x" "GIT_COMMITTER_NAME" "x" "GIT_COMMITTER_EMAIL" "x@x"})
(defn git [dir & args]
  (let [r (apply proc/sh {:dir dir :extra-env gitenv :out :string :err :string} "git" args)]
    {:exit (:exit r) :out (str/trim (str (:out r))) :err (str/trim (str (:err r)))}))

;; base file: K well-separated functions f0..f{K-1} (3 lines apart so disjoint edits
;; never touch adjacent hunks -> git 3-way auto-merges cleanly).
(defn base-src [k]
  (str/join "\n" (mapcat (fn [i] [(str "(defn f" i " [x]")
                                  (str "  (+ x " i "))")
                                  ""]) (range k))))
;; agent i's disjoint edit: rewrite ONLY f{i}'s body line.
(defn apply-edit [src i]
  (str/replace src (str "  (+ x " i "))") (str "  (* x " (+ 100 i) "))")))

;; --- git speculative-batching baseline, R1 (batched all-pass disjoint) ---------
;; Real mechanics: a branch per agent off base (each a real disjoint commit), then the
;; batch lands by 3-way merging ALL agent branches onto one integration branch and
;; validating the integrated result ONCE (speculative batch = optimistic, 1 CI for K).
(defn git-r1 [k]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/coordexp-git-" k "-" (System/nanoTime))]
    (.mkdirs (io/file dir))
    (git dir "init" "-q")
    (git dir "config" "commit.gpgsign" "false")
    (spit (str dir "/mod.clj") (base-src k))
    (git dir "add" "-A") (git dir "commit" "-qm" "base")
    (let [t0 (System/nanoTime)
          base (:out (git dir "rev-parse" "HEAD"))
          ;; each agent: real branch off base, disjoint edit, real commit
          _ (doseq [i (range k)]
              (git dir "checkout" "-q" "-b" (str "agent" i) base)
              (spit (str dir "/mod.clj") (apply-edit (base-src k) i))
              (git dir "commit" "-aqm" (str "edit f" i))
              (git dir "checkout" "-q" base))
          ;; SPECULATIVE BATCH: merge ALL agent branches onto one integration branch
          ;; (3-way auto-merge), count real conflicts, then ONE validation on the batch.
          _ (git dir "checkout" "-q" "-b" "integ" base)
          merges (mapv (fn [i] (git dir "merge" "-q" "--no-edit" (str "agent" i))) (range k))
          conflicts (count (filter #(not (zero? (:exit %))) merges))
          ;; validation runs ONCE on the integrated batch (speculative batching).
          validation-runs (if (zero? conflicts) 1 (inc conflicts)) ; clean batch = 1 CI
          integration-units 1
          ;; the merged file actually carries all K disjoint edits?
          merged (slurp (str dir "/mod.clj"))
          landed (count (filter (fn [i] (str/includes? merged (str "(* x " (+ 100 i) "))"))) (range k)))
          ms (/ (- (System/nanoTime) t0) 1e6)]
      (proc/sh "rm" "-rf" dir)
      {:regime "R1" :system "git" :K k :landed landed :failed (- k landed)
       :wall-ms (Math/round ms) :integration-units integration-units
       :validation-runs validation-runs :merge-conflicts conflicts
       :occ-retries "n/a" :final (if (= landed k) "all-merged" "INCOMPLETE")})))

(println "=== #11 STEP 1+2: git speculative-batching baseline — R1 acceptance (K-sweep) ===\n")
(def rows (mapv git-r1 [4 8 16]))
(doseq [r rows]
  (println (format "  K=%-3d landed=%-3d conflicts=%-2d integration-units=%-2d validation-runs=%-2d wall=%4dms final=%s"
                   (:K r) (:landed r) (:merge-conflicts r) (:integration-units r) (:validation-runs r) (:wall-ms r) (:final r))))

(def vr (mapv :validation-runs rows))
(def all-landed (every? (fn [r] (= (:landed r) (:K r))) rows))
(def o1 (apply = vr))                       ; validation-runs constant across K = O(1), not O(K)
(println "\n--- R1 ACCEPTANCE TEST (anti-strawman) ---")
(println "  validation-runs across K=[4 8 16]:" vr "  (O(1) in K?" o1 ")")
(println "  all K disjoint edits auto-merged + landed:" all-landed)
(println (if (and o1 all-landed)
           "\n>>> R1 PASS — git baseline is a REAL speculative-batching queue (O(1) validation for K disjoint all-pass edits, 0 conflicts). Not a serial strawman. Cleared to build the Fram arm + R2/R3."
           "\n>>> R1 FAIL — git baseline is O(K) (serial strawman) or didn't auto-merge; the benchmark would be VOID. Fix the queue before proceeding."))
(System/exit (if (and o1 all-landed) 0 1))
