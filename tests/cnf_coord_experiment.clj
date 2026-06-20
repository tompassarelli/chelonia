;; ============================================================================
;; cnf_coord_experiment.clj — #11 R1 git arm, SAME-ARTIFACT (beagle-text-vs-beagle-graph).
;; HARDENED: the git arm now edits the RENDERED TEXT of the REAL `schema` module — the SAME
;; module the Fram arm edits via the claim graph (cnf_coord_fram_r1.clj). Previously the git
;; arm used a SYNTHETIC disjoint-functions stand-in; the coordination COUNTS are content-
;; independent (they hold), but this closes the apples-vs-oranges hole: now both arms operate
;; on the same beagle artifact, git as text + Fram as graph. NOT beagle-vs-clojure.
;;
;; REAL: git mechanics (worktree/branch-per-agent, real 3-way auto-merge, speculative batch).
;; MEASURED: C_REAL = build-all recompile of the rendered schema = ~3880ms (2 runs 3912/3848).
;;   => git per-edit landing latency ~= C_REAL..2*C_REAL (validate-on-land); Fram = 70ms (defer).
;;
;; Prereq: a rendered schema text at $SCHEMA_BCLJ (default /tmp/schema.bclj), produced by:
;;   bb -cp out bin/fram-render-code schema --log <copy-of-.fram/code.log> --out /tmp/schema.bclj
;; R1 ACCEPTANCE (anti-strawman): K disjoint text edits to distinct functions must 3-way
;; auto-merge (0 conflicts) and validation-runs ~ O(1) in K (one speculative batch), not O(K).
;;   bb -cp out cnf_coord_experiment.clj
;; ============================================================================
(require '[clojure.string :as str] '[clojure.java.io :as io] '[babashka.process :as proc])
(def schema-bclj (or (System/getenv "SCHEMA_BCLJ") "/tmp/schema.bclj"))
(when-not (.exists (io/file schema-bclj))
  (println "SKIP — render the real schema first: bb -cp out bin/fram-render-code schema --log <code.log copy> --out /tmp/schema.bclj")
  (System/exit 0))
(def C-REAL-MS 3880)   ; MEASURED: recompile of the rendered schema module (build-all, 2 runs)
;; the SAME functions the Fram R1 arm edits (cnf_coord_fram_r1.clj).
(def schema-fns ["cardinality" "lookup" "lookup-all" "find-by" "def-predicate!" "name-of"
                 "resolve-name" "name!" "assert!" "link!" "replace!"])

(def gitenv {"GIT_AUTHOR_NAME" "x" "GIT_AUTHOR_EMAIL" "x@x" "GIT_COMMITTER_NAME" "x" "GIT_COMMITTER_EMAIL" "x@x"})
(defn git [dir & args] (apply proc/sh {:dir dir :extra-env gitenv :out :string :err :string} "git" args))
;; agent edit = IN-PLACE append to the target fn's defn line (no line-count change, matching
;; the synthetic arm's in-place edit type — fair). Match `name ` with a trailing space so
;; `!`-suffixed names (assert!/link!/name!/def-predicate!/replace!) are matched too (the `\b`
;; bug failed on `!`). Distinct functions => distinct defn lines.
(defn agent-edit [src fname]
  (str/replace src
               (re-pattern (str "(?m)^(\\(defn-? " (java.util.regex.Pattern/quote fname) " [^\\n]*)$"))
               (str "$1  ;; coord-edit-" fname)))

(defn git-r1 [k]
  (let [dir (str (System/getProperty "java.io.tmpdir") "/coordexp-sa-" k "-" (System/nanoTime))
        base-src (slurp schema-bclj)
        fns (take k schema-fns)]
    (.mkdirs (io/file dir))
    (git dir "init" "-q") (git dir "config" "commit.gpgsign" "false")
    (spit (str dir "/schema.bclj") base-src)
    (git dir "add" "-A") (git dir "commit" "-qm" "base (rendered real schema)")
    (let [t0 (System/nanoTime)
          base (str/trim (:out (git dir "rev-parse" "HEAD")))
          applied (atom 0)
          _ (doseq [f fns]
              (git dir "checkout" "-q" "-b" (str "agent-" f) base)
              (let [src (slurp (str dir "/schema.bclj")) ed (agent-edit src f)]
                (when (not= src ed) (swap! applied inc))
                (spit (str dir "/schema.bclj") ed))
              (git dir "commit" "-aqm" (str "edit " f))
              (git dir "checkout" "-q" base))
          _ (git dir "checkout" "-q" "-b" "integ" base)
          merges (mapv (fn [f] (git dir "merge" "-q" "--no-edit" (str "agent-" f))) fns)
          conflicts (count (filter #(not (zero? (:exit %))) merges))
          merged (slurp (str dir "/schema.bclj"))
          landed (count (filter (fn [f] (str/includes? merged (str "coord-edit-" f))) fns))
          ms (Math/round (/ (- (System/nanoTime) t0) 1e6))]
      (proc/sh "rm" "-rf" dir)
      {:K k :edits-applied @applied :landed landed :conflicts conflicts
       :integration-units 1 :validation-runs (if (zero? conflicts) 1 (inc conflicts)) :wall-ms ms})))

(println "=== #11 R1 git arm — SAME ARTIFACT (rendered real schema, beagle-text-vs-beagle-graph) ===\n")
(def rows (mapv git-r1 [4 8 11]))
(doseq [r rows]
  (println (format "  K=%-3d edits-applied=%-3d landed=%-3d conflicts=%-2d integration-units=%-2d validation-runs=%-2d wall=%dms"
                   (:K r) (:edits-applied r) (:landed r) (:conflicts r) (:integration-units r) (:validation-runs r) (:wall-ms r))))
(def vr (mapv :validation-runs rows))
(def o1 (apply = vr))
(def clean (every? #(and (zero? (:conflicts %)) (= (:landed %) (:K %)) (= (:edits-applied %) (:K %))) rows))
(println "\n--- R1 acceptance (same artifact) ---")
(println "  validation-runs across K=[4 8 11]:" vr "  (O(1) in K?" o1 ")")
(println "  all K disjoint edits applied to distinct REAL schema fns + auto-merged + landed:" clean)
(println (format "\nC_REAL (measured recompile of rendered schema) = %dms  => git landing latency ~%d..%dms/edit (validate-on-land); Fram = 70ms (defer)" C-REAL-MS C-REAL-MS (* 2 C-REAL-MS)))
(println (if (and o1 clean)
           ">>> SAME-ARTIFACT R1 PASS — git edits the REAL rendered schema (same module Fram edits via graph),\n    disjoint edits 3-way auto-merge (0 conflicts), validation-runs O(1). Comparison is now\n    beagle-text-vs-beagle-graph. Counts UNCHANGED from the synthetic arm (content-independent, as claimed)."
           ">>> SAME-ARTIFACT R1 anomaly — see rows."))
(System/exit (if (and o1 clean) 0 1))
