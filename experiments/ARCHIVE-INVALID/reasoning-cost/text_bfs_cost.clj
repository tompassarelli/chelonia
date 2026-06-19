#!/usr/bin/env bb
;; ============================================================================
;; text_bfs_cost.clj — the CONCRETE, MODEL-FREE scripted-text-BFS op-count for
;; task (c) on THIS corpus (a single falsifiable number, computed from the
;; callgraph structure — NOT an open-ended ">=N" floor).
;; ============================================================================
;; Task (c): list every defn that transitively reaches kernel/thread-ids-i (the
;; reaches-closure). The graph answers in ONE recursive query (Datalog fixpoint).
;; Text holds NO transitive structure, so a CORRECT closure MUST be a hand-run BFS.
;;
;; THE FAIR (minimal-correct) TEXT COST — frontier-BATCHED, per METHODOLOGY guard 2
;; (single-pass alternation is ONE op; a real engineer writes `rg '(a|b|c|d)'`):
;;
;;   * ONE alternation search per BFS LEVEL (not per node): at each level the whole
;;     frontier's names go into one `rg '(n1|n2|..)'` pass that surfaces all of that
;;     level's callers at once.  searches = number of BFS levels until fixpoint.
;;   * ONE classify-read per caller-MODULE-REGION newly introduced at a level: the
;;     search hit list gives line text but NOT the enclosing defn; to map a call-site
;;     to its enclosing defn (and to disambiguate noisy substrings like `call` /
;;     `dispatch` from comments) the engineer reads that module's region once.
;;     reads = sum over levels of (# distinct new-caller modules at that level).
;;
;; This is the structure the graph PRE-COMPUTED: every refers_to edge already
;; resolved, the reverse index already built, the transitive closure a fixpoint.
;; The text agent rebuilds it level by level.
;;
;; We compute the closure + its BFS level structure from the SAME callgraph oracle
;; the ground truth uses, and report  searches + reads = total ops.
;;
;; Usage: bb text_bfs_cost.clj <emit-edn-dir>   (prints the op-count to stdout)
(require '[clojure.string :as str] '[clojure.set :as set] '[clojure.java.shell :as sh])

(let [edn-dir (first *command-line-args*)
      cg-out (:out (apply sh/sh "bb" "-cp" "out" "chartroom/src/resolve.clj" "callgraph"
                          (sort (map str (filter #(str/ends-with? (str %) ".edn")
                                                 (map str (.listFiles (java.io.File. edn-dir))))))))
      cg (try (require '[cheshire.core :as j])
              ((resolve 'cheshire.core/parse-string) cg-out true)
              (catch Throwable _ nil))]
  (if-not cg
    (binding [*out* *err*] (println "callgraph failed") (System/exit 1))
    (let [defns (:defns cg)
          edges (:edges cg)
          tkey (some (fn [d] (when (= "thread-ids-i" (:name d)) (:key d))) defns)
          mod-of (into {} (map (fn [d] [(:key d) (str/replace (or (:module d) "") #"^fram\." "")]) defns))
          callers-of (reduce (fn [m [a b]] (update m b (fnil conj #{}) a)) {} edges)
          ;; frontier-batched BFS: per level, +1 search; +1 read per NEW caller-module.
          [searches reads nlevels closure]
          (loop [frontier #{tkey} seen #{} lvl 0 searches 0 reads 0]
            (if (empty? frontier)
              [searches reads lvl seen]
              (let [nw (apply set/union (map #(get callers-of % #{}) frontier))
                    new-callers (set/difference nw (set/union seen frontier))
                    caller-mods (set (map mod-of new-callers))]
                (recur new-callers (set/union seen frontier) (inc lvl)
                       (inc searches) (+ reads (count caller-mods))))))
          ops (+ searches reads)
          closure-size (count (disj closure tkey))]
      (binding [*out* *err*]
        (println (str "  text-BFS (frontier-batched): " nlevels " BFS levels over a "
                      closure-size "-member transitive-caller closure; "
                      searches " alternation searches (1/level) + " reads
                      " classify-reads (1 per new caller-module/level) = " ops " ops")))
      ;; print just the op-count integer (the harness captures it)
      (println ops))))
