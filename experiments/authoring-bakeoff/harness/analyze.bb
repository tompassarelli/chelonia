#!/usr/bin/env bb
;; analyze.bb — the bake-off analysis pipeline (MASTER_SPEC Part IV "Metrics"/"Stats").
;; ============================================================================
;; Reads ANY results.jsonl (Rung-0 or Rung-1; partial or complete) and produces:
;;   (1) per-cell (arm×density×task) correctness@1 / correctness@k, collateral-damage,
;;       reference-error, mean retry-rounds, mean cost + out-tokens, n — all with
;;       Wilson 95% CIs on every proportion;
;;   (2) the HEADLINE contrasts M2-vs-M1 and M2-vs-M1.5 (Δ + CI) on correctness@k +
;;       collateral-damage (two-proportion, Wald CI on the difference);
;;   (3) the H2 learnability slope — correctness@1 vs density (3/6/10) per arm;
;;   (4) a cost/savings column — tokens-per-successful-program per arm;
;;   (5) machine-readable output (analyze-out.tsv + analyze-out.json),
;;   (6) a clean markdown summary table to stdout, AND
;;   (7) if a charting tool is present (gnuplot | python3+matplotlib) a summary PNG
;;       (correctness + CIs per arm×task); else it is skipped and SAID so.
;;
;; Field schema (one trial per JSON line): arm density task bucket pass?
;;   first-pass-attempt attempts in/out/cache-tokens cost rounds[].
;; Works on partial streams — a cell with n=0 is simply absent.
;; ============================================================================
(ns analyze
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [babashka.process :as p]))

;; --- Wilson 95% CI for a single proportion ----------------------------------
(defn wilson [k n]
  (if (zero? n) [0.0 0.0 0.0]
    (let [z 1.96 p (/ (double k) n)
          den (+ 1 (/ (* z z) n))
          centre (/ (+ p (/ (* z z) (* 2 n))) den)
          half (/ (* z (Math/sqrt (+ (/ (* p (- 1 p)) n) (/ (* z z) (* 4 n n))))) den)]
      [p (max 0.0 (- centre half)) (min 1.0 (+ centre half))])))

;; --- two-proportion difference (Wald) — for the M2-vs-M1 / M2-vs-M1.5 deltas -
;; Δ = pA-pB ; 95% CI = Δ ± 1.96·sqrt(pA(1-pA)/nA + pB(1-pB)/nB).
;; "real" per spec = |Δ|≥0.10 AND the CI excludes 0.
(defn two-prop-delta [kA nA kB nB]
  (if (or (zero? nA) (zero? nB))
    {:delta 0.0 :lo 0.0 :hi 0.0 :real? false :n? false}
    (let [pA (/ (double kA) nA) pB (/ (double kB) nB)
          d (- pA pB)
          se (Math/sqrt (+ (/ (* pA (- 1 pA)) nA) (/ (* pB (- 1 pB)) nB)))
          lo (- d (* 1.96 se)) hi (+ d (* 1.96 se))]
      {:delta d :lo lo :hi hi
       :real? (and (>= (Math/abs d) 0.10) (or (> lo 0.0) (< hi 0.0)))
       :n? true})))

(defn pct [x] (format "%.0f%%" (* 100.0 x)))
(defn spct [x] (format "%+.0fpp" (* 100.0 x)))            ; signed percentage-points
(defn ci-str [k n] (let [[p lo hi] (wilson k n)]
                     (format "%s [%s,%s]" (pct p) (pct lo) (pct hi))))

;; --- bucket / correctness predicates over a trial row -----------------------
(defn correct1? [r] (and (:pass? r) (= 0 (:first-pass-attempt r))))
(defn collateral? [r] (= "COLLATERAL_DAMAGE" (:bucket r)))
(defn refer-err? [r] (= "REFERENCE_ERROR" (:bucket r)))

(defn cell-stats [rs]
  (let [n (count rs)
        c1 (count (filter correct1? rs))
        ck (count (filter :pass? rs))
        cd (count (filter collateral? rs))
        re (count (filter refer-err? rs))
        kch (count (filter #(= "KCH" (:bucket %)) rs))
        sw (count (filter #(= "SEMANTIC_WRONG" (:bucket %)) rs))
        si (count (filter #(= "STRUCTURAL_INVALID" (:bucket %)) rs))
        rounds (map #(or (:attempts %) 1) rs)
        out (map #(or (:out-tokens %) 0) rs)
        cost (map #(or (:cost %) 0.0) rs)]
    {:n n :c1 c1 :ck ck :cd cd :re re :kch kch :sw sw :si si
     :mean-rounds (if (zero? n) 0.0 (/ (double (reduce + rounds)) n))
     :mean-out (if (zero? n) 0.0 (/ (double (reduce + out)) n))
     :mean-cost (if (zero? n) 0.0 (/ (reduce + cost) (double n)))}))

;; --- arm rollup (pooled over density+task) for cost + deltas ----------------
(defn arm-rollup [rs]
  (let [n (count rs)
        succ (filter :pass? rs)
        ns (count succ)
        tot-out (reduce + (map #(or (:out-tokens %) 0) rs))
        tot-cost (reduce + (map #(or (:cost %) 0.0) rs))
        out-succ (reduce + (map #(or (:out-tokens %) 0) succ))
        cost-succ (reduce + (map #(or (:cost %) 0.0) succ))]
    {:n n :succ ns
     :tok-per-attempt (if (zero? n) 0.0 (/ (double tot-out) n))
     ;; tokens-per-SUCCESSFUL-program: total out-tokens spent across ALL attempts
     ;; (incl. retries on the way to a pass) divided by # successes — the spec's
     ;; "tokens/SUCCESS" cost axis. (Pooled total-out / successes captures retry tax.)
     :tok-per-success (if (zero? ns) nil (/ (double tot-out) ns))
     :cost-per-success (if (zero? ns) nil (/ tot-cost (double ns)))
     :tok-per-success-narrow (if (zero? ns) nil (/ (double out-succ) ns))}))

;; ============================================================================
(defn load-rows [file]
  (->> (line-seq (io/reader file))
       (remove str/blank?)
       (map #(json/parse-string % true))
       (remove #(= "HARNESS_ERROR" (:bucket %)))
       vec))

;; --- charting tool detection ------------------------------------------------
(defn chart-tool []
  (cond
    (zero? (:exit (p/sh "bash" "-c" "command -v gnuplot")))   :gnuplot
    (zero? (:exit (p/sh "bash" "-c" "python3 -c 'import matplotlib' 2>/dev/null"))) :matplotlib
    :else nil))

(defn render-chart-gnuplot [png cells-seq]
  ;; cells-seq: [{:label "m1 0A" :p .. :lo .. :hi ..} ...]
  (let [tmp (str png ".dat")
        rows (map-indexed (fn [i {:keys [label p lo hi]}]
                            (format "%d\t%s\t%.4f\t%.4f\t%.4f" i label p lo hi))
                          cells-seq)
        labels (str/join ", " (map-indexed (fn [i c] (format "\"%s\" %d" (:label c) i)) cells-seq))
        script (format (str "set terminal pngcairo size 1100,520\n"
                            "set output '%s'\n"
                            "set title 'correctness@k by arm x task (Wilson 95%%)'\n"
                            "set yrange [0:1.05]\nset ylabel 'correctness@k'\n"
                            "set xtics rotate by -40 (%s)\nset grid ytics\nset boxwidth 0.5\n"
                            "set style fill solid 0.4\nunset key\n"
                            "plot '%s' using 1:3 with boxes lc rgb '#4477aa', "
                            "'' using 1:3:4:5 with yerrorbars lc rgb '#222222' pt 7 ps 0.6\n")
                       png labels tmp)
        sf (str png ".gp")]
    (spit tmp (str (str/join "\n" rows) "\n"))
    (spit sf script)
    (let [r (p/sh "gnuplot" sf)]
      (if (zero? (:exit r)) {:ok png} {:err (str (:out r) (:err r))}))))

(defn render-chart-matplotlib [png cells-seq]
  (let [pyjson (json/generate-string cells-seq)
        py (format (str "import json,sys\n"
                        "import matplotlib\nmatplotlib.use('Agg')\n"
                        "import matplotlib.pyplot as plt\n"
                        "cells=json.loads('''%s''')\n"
                        "labels=[c['label'] for c in cells]\n"
                        "p=[c['p'] for c in cells]\n"
                        "lo=[c['p']-c['lo'] for c in cells]\n"
                        "hi=[c['hi']-c['p'] for c in cells]\n"
                        "x=range(len(cells))\n"
                        "fig,ax=plt.subplots(figsize=(11,5.2))\n"
                        "ax.bar(x,p,color='#4477aa',alpha=0.5)\n"
                        "ax.errorbar(x,p,yerr=[lo,hi],fmt='o',color='#222',ms=4,capsize=3)\n"
                        "ax.set_xticks(list(x)); ax.set_xticklabels(labels,rotation=40,ha='right')\n"
                        "ax.set_ylim(0,1.05); ax.set_ylabel('correctness@k')\n"
                        "ax.set_title('correctness@k by arm x task (Wilson 95%%)'); ax.grid(axis='y',alpha=0.3)\n"
                        "plt.tight_layout(); plt.savefig('%s',dpi=120)\n")
                   pyjson png)
        sf (str png ".py")]
    (spit sf py)
    (let [r (p/sh "python3" sf)]
      (if (zero? (:exit r)) {:ok png} {:err (str (:out r) (:err r))}))))

;; ============================================================================
(defn -main [& args]
  (let [file (or (first args) "results.jsonl")
        out-dir (or (second args)
                    "/home/tom/code/fram/experiments/authoring-bakeoff/scratch/analysis")
        png (str out-dir "/rung0.png")
        rows (load-rows file)
        arms-present (vec (sort (distinct (map :arm rows))))
        tasks-present (vec (sort (distinct (map :task rows))))
        densities ["d3" "d6" "d10"]
        by-cell (group-by (juxt :arm :density :task) rows)
        by-arm (group-by :arm rows)
        by-arm-task (group-by (juxt :arm :task) rows)]
    (when (empty? rows)
      (println "no usable rows in" file) (System/exit 1))

    ;; ---------- (a) machine-readable: per-cell TSV + an aggregate JSON --------
    (.mkdirs (io/file out-dir))
    (let [tsv-hdr (str/join "\t" ["arm" "density" "task" "n"
                                  "c1" "c1_lo" "c1_hi" "ck" "ck_lo" "ck_hi"
                                  "collat" "collat_lo" "collat_hi"
                                  "refer_err" "re_lo" "re_hi"
                                  "mean_rounds" "mean_out_tok" "mean_cost"])
          tsv-rows (for [[[arm den tsk] rs] (sort-by key by-cell)
                         :let [s (cell-stats rs)
                               [c1p c1lo c1hi] (wilson (:c1 s) (:n s))
                               [ckp cklo ckhi] (wilson (:ck s) (:n s))
                               [cdp cdlo cdhi] (wilson (:cd s) (:n s))
                               [rep relo rehi] (wilson (:re s) (:n s))]]
                     (str/join "\t" [arm den tsk (:n s)
                                     (format "%.4f" c1p) (format "%.4f" c1lo) (format "%.4f" c1hi)
                                     (format "%.4f" ckp) (format "%.4f" cklo) (format "%.4f" ckhi)
                                     (format "%.4f" cdp) (format "%.4f" cdlo) (format "%.4f" cdhi)
                                     (format "%.4f" rep) (format "%.4f" relo) (format "%.4f" rehi)
                                     (format "%.2f" (:mean-rounds s))
                                     (format "%.1f" (:mean-out s))
                                     (format "%.5f" (:mean-cost s))]))
          tsv-path (str out-dir "/analyze-out.tsv")]
      (spit tsv-path (str tsv-hdr "\n" (str/join "\n" tsv-rows) "\n"))

      ;; aggregate JSON: cells + arm rollups + deltas + slopes
      (let [cells-json (for [[[arm den tsk] rs] (sort-by key by-cell)
                             :let [s (cell-stats rs)]]
                         (let [[c1p c1lo c1hi] (wilson (:c1 s) (:n s))
                               [ckp cklo ckhi] (wilson (:ck s) (:n s))
                               [cdp cdlo cdhi] (wilson (:cd s) (:n s))
                               [rep relo rehi] (wilson (:re s) (:n s))]
                           {:arm arm :density den :task tsk :n (:n s)
                            :correct1 {:p c1p :lo c1lo :hi c1hi}
                            :correctk {:p ckp :lo cklo :hi ckhi}
                            :collateral {:p cdp :lo cdlo :hi cdhi}
                            :reference-error {:p rep :lo relo :hi rehi}
                            :mean-rounds (:mean-rounds s) :mean-out (:mean-out s) :mean-cost (:mean-cost s)
                            :buckets {:KCH (:kch s) :SEMANTIC_WRONG (:sw s) :STRUCTURAL_INVALID (:si s)}}))
            arm-cost (into {} (for [[arm rs] by-arm] [arm (arm-rollup rs)]))
            ;; headline deltas, pooled over density+task, per task and overall
            delta-block (fn [metric-fn]
                          (let [pool (fn [arm] (filter #(= arm (:arm %)) rows))
                                cnt (fn [arm] (let [r (pool arm)] [(count (filter metric-fn r)) (count r)]))]
                            (when (and (some #{"m2"} arms-present))
                              (into {}
                                (for [base ["m1" "m15"] :when (some #{base} arms-present)
                                      :let [[kB nB] (cnt base) [kA nA] (cnt "m2")]]
                                  [(keyword (str "m2-vs-" base)) (two-prop-delta kA nA kB nB)])))))
            slopes (into {}
                     (for [arm arms-present]
                       [arm (into {}
                              (for [d densities
                                    :let [drs (filter #(and (= arm (:arm %)) (= d (:density %))) rows)
                                          n (count drs) c1 (count (filter correct1? drs))]
                                    :when (pos? n)]
                                [d {:p (/ (double c1) n) :n n}]))]))
            agg {:source file :n-rows (count rows)
                 :arms arms-present :tasks tasks-present
                 :cells cells-json
                 :arm-cost arm-cost
                 :headline {:correctk (delta-block :pass?)
                            :collateral (delta-block collateral?)}
                 :h2-slope slopes}]
        (spit (str out-dir "/analyze-out.json") (json/generate-string agg {:pretty true})))

      ;; ---------- (b) markdown summary to stdout -----------------------------
      (println (format "# Authoring bake-off — analysis (%s)\n" file))
      (println (format "rows=%d | arms=%s | tasks=%s | (partial-safe)\n"
                       (count rows) (str/join "," arms-present) (str/join "," tasks-present)))

      (println "## Per cell — arm × density × task (Wilson 95% CI)\n")
      (println "| arm | den | task | n | correct@1 | correct@k | collateral | ref-err | rounds | out-tok | $cost |")
      (println "|-----|-----|------|---|-----------|-----------|------------|---------|--------|---------|-------|")
      (doseq [[[arm den tsk] rs] (sort-by key by-cell)
              :let [s (cell-stats rs)]]
        (println (format "| %s | %s | %s | %d | %s | %s | %s | %s | %.2f | %.0f | %.4f |"
                         arm den tsk (:n s)
                         (ci-str (:c1 s) (:n s)) (ci-str (:ck s) (:n s))
                         (ci-str (:cd s) (:n s)) (ci-str (:re s) (:n s))
                         (:mean-rounds s) (:mean-out s) (:mean-cost s))))

      (println "\n## Per arm × task (pooled over density)\n")
      (println "| arm | task | n | correct@1 | correct@k | collateral | ref-err |")
      (println "|-----|------|---|-----------|-----------|------------|---------|")
      (doseq [[[arm tsk] rs] (sort-by key by-arm-task)
              :let [s (cell-stats rs)]]
        (println (format "| %s | %s | %d | %s | %s | %s | %s |"
                         arm tsk (:n s)
                         (ci-str (:c1 s) (:n s)) (ci-str (:ck s) (:n s))
                         (ci-str (:cd s) (:n s)) (ci-str (:re s) (:n s)))))

      ;; ---------- headline contrasts (Δ + CI) --------------------------------
      (println "\n## HEADLINE — M2 vs M1 / M1.5 (two-proportion Δ, 95% CI)\n")
      (println "Δ = M2 − baseline. \"real\" = |Δ|≥10pp AND CI excludes 0 (spec §IV Stats).\n")
      (println "| contrast | metric | M2 | baseline | Δ (95% CI) | real? |")
      (println "|----------|--------|----|----------|------------|-------|")
      (when (some #{"m2"} arms-present)
        (let [pool (fn [arm] (filter #(= arm (:arm %)) rows))
              prop (fn [arm pred] (let [r (pool arm)] [(count (filter pred r)) (count r)]))]
          (doseq [[base blabel] [["m1" "M1"] ["m15" "M1.5"]]
                  :when (some #{base} arms-present)
                  [metric pred] [["correct@k" :pass?] ["collateral" collateral?]]]
            (let [[kA nA] (prop "m2" pred) [kB nB] (prop base pred)
                  d (two-prop-delta kA nA kB nB)]
              (println (format "| M2 vs %s | %s | %s | %s | %s [%s,%s] | %s |"
                               blabel metric
                               (pct (if (zero? nA) 0.0 (/ (double kA) nA)))
                               (pct (if (zero? nB) 0.0 (/ (double kB) nB)))
                               (spct (:delta d)) (spct (:lo d)) (spct (:hi d))
                               (if (:real? d) "**YES**" "no")))))))

      ;; ---------- H2 learnability slope --------------------------------------
      (println "\n## H2 learnability slope — correct@1 vs density (per arm)\n")
      (println "| arm | d3 | d6 | d10 | slope(d10−d3) |")
      (println "|-----|----|----|-----|---------------|")
      (doseq [arm arms-present]
        (let [pt (fn [d] (let [drs (filter #(and (= arm (:arm %)) (= d (:density %))) rows)
                               n (count drs) c1 (count (filter correct1? drs))]
                           (if (zero? n) nil (/ (double c1) n))))
              d3 (pt "d3") d6 (pt "d6") d10 (pt "d10")
              fmt (fn [x] (if x (pct x) "-"))
              slope (if (and d3 d10) (spct (- d10 d3)) "-")]
          (println (format "| %s | %s | %s | %s | %s |" arm (fmt d3) (fmt d6) (fmt d10) slope))))

      ;; ---------- cost / savings ---------------------------------------------
      (println "\n## Cost / savings — tokens-per-successful-program (per arm)\n")
      (println "| arm | n | successes | out-tok/attempt | **out-tok/success** | $cost/success |")
      (println "|-----|---|-----------|-----------------|---------------------|---------------|")
      (doseq [[arm rs] (sort-by key by-arm)
              :let [a (arm-rollup rs)]]
        (println (format "| %s | %d | %d | %.0f | **%s** | %s |"
                         arm (:n a) (:succ a) (:tok-per-attempt a)
                         (if (:tok-per-success a) (format "%.0f" (:tok-per-success a)) "n/a")
                         (if (:cost-per-success a) (format "%.4f" (:cost-per-success a)) "n/a"))))

      ;; ---------- (c) chart ---------------------------------------------------
      (let [tool (chart-tool)
            cells-seq (for [[[arm tsk] rs] (sort-by key by-arm-task)
                            :let [s (cell-stats rs)
                                  [p lo hi] (wilson (:ck s) (:n s))]]
                        {:label (str arm " " tsk) :p p :lo lo :hi hi})]
        (println "\n## Chart")
        (if (nil? tool)
          (println (str "- no charting tool installed (checked gnuplot, python3+matplotlib) "
                        "— PNG skipped; the markdown table above is the fallback. "
                        "(`nix-shell -p gnuplot` or matplotlib enables it.)"))
          (let [res (case tool
                      :gnuplot (render-chart-gnuplot png cells-seq)
                      :matplotlib (render-chart-matplotlib png cells-seq))]
            (if (:ok res)
              (println (format "- chart-tool=%s → PNG written: %s" (name tool) png))
              (println (format "- chart-tool=%s FAILED: %s" (name tool) (:err res)))))))

      (println (format "\n_machine output: %s , %s_" tsv-path (str out-dir "/analyze-out.json"))))))

(apply -main *command-line-args*)
