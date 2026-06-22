#!/usr/bin/env bb
;; run-rung1.bb — the Rung-1 bake-off loop (the punish-text headline).
;; ============================================================================
;; SAME matched control-flow as run.bb (C1): ONE loop, arm-specific ONLY at
;; parse/apply, then the IDENTICAL recompile gate (beagle-build-all) + runtime grade.
;; This is a SEPARATE runner so it cannot perturb the frozen Rung-0 run.bb:
;;   - reads tasks-rung1.edn (1A multi-callsite rename, 1B body re-mint),
;;   - M2 uses m2_adapter_rung1.bb (existing-node addressing + bound_to resolution),
;;   - results stream to scratch/run/full-rung1/ (NOT full-rung0/),
;;   - default scratch label "full-rung1".
;;
;; Rung-1 differs from Rung-0 in WHAT the state is: the seed program already exists,
;; so all three arms get the seed as state (M1/M1.5 the text; M2 the seed text with the
;; engine leaf-ids legend so a rename can address the binding+use leaves). The edit is:
;;   M1   re-emit the whole edited module (must hunt+rewrite EVERY call site)
;;   M1.5 emit only changed defs; harness upserts at def granularity
;;   M2   emit a claim changeset over the existing graph (rename = re-spell the binding
;;        leaf + bound_to the uses; the adapter follows identity); recompile-gated.
;;
;; CLASSIFY (Rung-1 taxonomy, the H1 headline): a build that compiles but whose grade
;; raises "Unable to resolve symbol" = REFERENCE_ERROR (a missed call site); a preserved
;; assertion that turns FALSE = COLLATERAL_DAMAGE; target false but runs = SEMANTIC_WRONG.
;; (beagle's build is permissive on unbound symbols — they surface at runtime grade,
;; verified — so reference integrity is a GRADE-stage judgment, not a build-stage one.)
;; ============================================================================
(ns run-rung1
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [babashka.process :as p]
            [cheshire.core :as json]
            [clojure.java.io :as io]))

(def HERE (System/getProperty "user.dir"))
(load-file (str HERE "/m2_adapter_rung1.bb"))

;; --- config (held IDENTICAL to run.bb) ---------------------------------------
(def MODEL (or (System/getenv "BAKEOFF_MODEL") "claude-opus-4-5-20251101"))
(def R (parse-long (or (System/getenv "BAKEOFF_R") "4")))
(def TOKEN-CEIL (parse-long (or (System/getenv "BAKEOFF_TOKEN_CEIL") "8000")))
(def BEAGLE "/home/tom/code/beagle/bin/beagle-build-all")
(def ROUNDTRIP "/home/tom/code/beagle/beagle-lib/private/claims-roundtrip.rkt")
(def SCRATCH (or (System/getenv "BAKEOFF_SCRATCH")
                 "/home/tom/code/fram/experiments/authoring-bakeoff/scratch/run"))
(def PROMPTS-DIR "/home/tom/code/fram/experiments/authoring-bakeoff/prompts")
(def SYS-PROMPT
  "You are a precise code-emission engine. Follow the output contract exactly. Output ONLY the requested artifact — no prose, no explanation, no markdown fences.")

;; --- model call (pinned, recorded — IDENTICAL to run.bb) ---------------------
(defn call-model [user-prompt]
  (let [r (p/sh {:in user-prompt}
                "claude" "-p" "--model" MODEL "--output-format" "json"
                "--disable-slash-commands" "--allowedTools" "" "--system-prompt" SYS-PROMPT)]
    (if (not (zero? (:exit r)))
      {:error (str "claude CLI exit " (:exit r) ": " (str/trim (:err r)))}
      (let [j (try (json/parse-string (:out r) true) (catch Exception _ nil))]
        (if (or (nil? j) (:is_error j))
          {:error (str "model error: " (or (:result j) (str/trim (:err r))))}
          {:text (:result j)
           :in-tokens (get-in j [:usage :input_tokens] 0)
           :out-tokens (get-in j [:usage :output_tokens] 0)
           :cache-tokens (+ (get-in j [:usage :cache_creation_input_tokens] 0)
                            (get-in j [:usage :cache_read_input_tokens] 0))
           :cost (get j :total_cost_usd 0.0)})))))

(defn unfence [t]
  (let [t (str/trim t)]
    (if (str/starts-with? t "```")
      (-> t (str/replace #"(?s)^```[a-zA-Z]*\n?" "") (str/replace #"```\s*$" "") str/trim)
      t)))

(defn normalize-diag [out]
  (-> out
      (str/replace #"(?m)^\s*\S+?(?::\d+)?: beagle:" "  module: beagle:")
      (str/replace #"(?m)^\s*\S+?: type errors" "  module: type errors")
      (str/replace #"/[^\s:]+\.bclj" "module.bclj")
      str/trim))

;; ============================================================================
;; ENGINE GATE — shared, fail-closed (IDENTICAL to run.bb).
;; ============================================================================
(defn build-module [rep work tag]
  (let [outdir (str work "/out-" tag)]
    (cond
      (:text rep)
      (let [f (str work "/" tag ".bclj")]
        (spit f (:text rep))
        (let [r (p/sh BEAGLE f "--out" outdir)]
          {:exit (:exit r) :out (str (:out r) (:err r)) :outdir outdir}))
      (:dump-lines rep)
      (let [f (str work "/" tag ".edn")]
        (spit f (str (str/join "\n" (:dump-lines rep)) "\n"))
        (let [r (p/sh BEAGLE "--build-edn" f "--out" outdir)]
          {:exit (:exit r) :out (str (:out r) (:err r)) :outdir outdir})))))

(defn built? [gate] (str/includes? (:out gate) "0 error"))

;; grade returns the RAW signals so the Rung-1 classifier can split reference vs
;; collateral vs semantic. (Same eval-after-load technique as run.bb.)
(defn grade [outdir task]
  (let [clj (str outdir "/beagle/user.clj")]
    (if-not (.exists (io/file clj))
      {:grade-err (str "no emitted clj at " clj)}
      (let [n-pres (count (:preserved task))
            target-prints (format "(println (str \"TARGET=\" (eval (read-string %s))))"
                                  (pr-str (:target-test task)))
            pres-prints (str/join "\n  "
                          (map-indexed (fn [i pv]
                                         (format "(println (str \"PRES%d=\" (eval (read-string %s))))"
                                                 i (pr-str (:test pv))))
                                       (:preserved task)))
            grader (format
                    "(try (load-file \"%s\")\n  %s\n  %s\n  (catch Throwable e (println (str \"GRADEERR=\" (.getMessage e)))))"
                    clj target-prints pres-prints)
            gf (str outdir "/grade.clj")]
        (spit gf grader)
        (let [r (p/sh "bb" gf)
              out (str (:out r) (:err r))]
          (cond
            (str/includes? out "GRADEERR=")
            {:grade-err (-> out (str/split #"GRADEERR=") second str/trim)}
            :else
            {:target-pass? (str/includes? out "TARGET=true")
             :collateral? (boolean (some (fn [i] (str/includes? out (format "PRES%d=false" i)))
                                         (range n-pres)))}))))))

;; --- Rung-1 classifier: reference integrity is the headline -----------------
(defn ref-err-msg? [s]
  (boolean (and s (re-find #"(?i)unable to resolve|unbound|not.*resolve|no such var" s))))

(defn classify-rung1 [{:keys [adapter-error built? gate-out grade]}]
  (cond
    adapter-error {:bucket :STRUCTURAL_INVALID :pass? false :detail adapter-error}
    (not built?)
    ;; build-reject: reuse the diag shape (KCH / reference / structural). Rare here
    ;; (beagle build is permissive); a hard build fail = STRUCTURAL_INVALID.
    (let [o (str/lower-case (or gate-out ""))]
      (cond
        (ref-err-msg? o) {:bucket :REFERENCE_ERROR :pass? false}
        (re-find #"unknown|unsupported|invalid type|no such builtin" o) {:bucket :KCH :pass? false}
        :else {:bucket :STRUCTURAL_INVALID :pass? false}))
    ;; built, but grade raised — a dangling reference (missed call site) is the H1 signal
    (:grade-err grade)
    (if (ref-err-msg? (:grade-err grade))
      {:bucket :REFERENCE_ERROR :pass? false :detail (:grade-err grade)}
      {:bucket :SEMANTIC_WRONG :pass? false :detail (:grade-err grade)})
    ;; built + ran: collateral OUTRANKS target correctness
    (:collateral? grade) {:bucket :COLLATERAL_DAMAGE :pass? false}
    (:target-pass? grade) {:bucket :PASS :pass? true}
    :else {:bucket :SEMANTIC_WRONG :pass? false}))

;; ============================================================================
;; ARM-SPECIFIC APPLY (mirrors run.bb; M2 uses the Rung-1 adapter).
;; ============================================================================
(defn def-name [form]
  (when (and (seq? form) (#{'defn 'defn- 'defrecord 'def 'defonce} (first form)))
    (second form)))

(defn read-forms [text]
  (let [body (str/replace text #"(?m)^#lang[^\n]*\n?" "")
        rdr (java.io.PushbackReader. (java.io.StringReader. body))]
    (loop [acc []]
      (let [f (try (edn/read {:eof ::eof} rdr) (catch Exception _ ::eof))]
        (if (= f ::eof) acc (recur (conj acc f)))))))

(defn apply-m1 [_current model-text _task]
  (let [t (unfence model-text)
        t (if (str/includes? t "#lang") t (str "#lang beagle/clj\n" t))]
    {:text t}))

(defn apply-m15 [current model-text _task]
  (let [cur-forms (read-forms (:text current))
        new-forms (read-forms (unfence model-text))
        new-by-name (into {} (keep (fn [f] (when-let [n (def-name f)] [n f])) new-forms))
        replaced (map (fn [f] (if-let [n (def-name f)] (get new-by-name n f) f)) cur-forms)
        existing-names (set (keep def-name cur-forms))
        added (for [f new-forms :let [n (def-name f)] :when (and n (not (existing-names n)))] f)
        all (concat replaced added)
        text (str "#lang beagle/clj\n" (str/join "\n" (map pr-str all)) "\n")]
    {:text text}))

(defn apply-m2-arm [current model-text task]
  (let [res (m2-adapter-rung1/apply-m2 current (unfence model-text) (str (:module task) ".bclj"))]
    (if (:error res) {:adapter-error (:error res)} {:dump-lines (:lines res)})))

;; M2 current state = the seed engine dump (real integer ids).
(defn initial-current [arm task seed-text work]
  (case arm
    (:m1 :m15) {:text seed-text}
    :m2 (let [f (str work "/seed.bclj")]
          (spit f seed-text)
          {:lines (str/split-lines (:out (p/sh "racket" ROUNDTRIP "--emit-edn" f)))})))

;; ============================================================================
;; PROMPT ASSEMBLY — reuse the FROZEN d10 prompt (full bank incl. the edit examples
;; E6 rename / E8 body-swap), append the live edit task + state. d10 only: Rung-1 is
;; the highest-density edit setting (the slope point lives in Rung-0).
;; ============================================================================
(defn arm-prompt-file [arm]
  (str PROMPTS-DIR "/" (case arm :m1 "M1" :m15 "M1.5" :m2 "M2") "-d10.md"))

;; M2 state shows the seed text + an engine leaf-id legend so a rename/edit can address
;; existing nodes (mirrors M2-d10 E6's "binding leaf @mN, uses @mP @mQ" annotation).
(defn m2-state-legend [task]
  (case (:id task)
    "1A" (let [{:keys [from def-leaf use-leaves]} (:rename task)]
           (str "; existing engine node-ids: the binding leaf `" from "` is node " def-leaf
                "; its call-site leaves are nodes " (str/join " " use-leaves) ".\n"
                "; (rename = re-spell node " def-leaf " and bound_to each use leaf to it,\n"
                ";  OR re-spell every one of those leaves; address nodes by their integer id.)"))
    "1B" (let [{:keys [body-list op-leaf lit-leaf]} (:remint task)]
           (str "; existing engine node-ids: the body of `f` is list node " body-list
                "; its operator leaf is node " op-leaf " and its literal leaf is node " lit-leaf ".\n"
                "; (supersede those leaves in place: address nodes by their integer id.)"))
    ""))

(defn state-text [arm task seed-text current]
  (case arm
    (:m1 :m15) (let [t (str/trim (str/replace (:text current) #"(?m)^#lang[^\n]*\n?" ""))]
                 (if (str/blank? t) "(empty program)" t))
    :m2 (str (str/trim (str/replace seed-text #"(?m)^#lang[^\n]*\n?" ""))
             "\n" (m2-state-legend task))))

(defn build-prompt [arm task seed-text current error-feedback]
  (let [base (slurp (arm-prompt-file arm))]
    (str base
         "\n\n## YOUR TASK (scored)\n"
         "TASK: " (:task task) "\n"
         "STATE:\n```\n" (state-text arm task seed-text current) "\n```\n"
         (when error-feedback
           (str "\n## PREVIOUS ATTEMPT FAILED — revise\n"
                "Your prior output produced this engine error. Fix it and re-emit per your output contract:\n```\n"
                error-feedback "\n```\n"))
         "\nEmit your output now (only the artifact, no prose):")))

;; ============================================================================
;; ONE ATTEMPT + TRIAL (mirrors run.bb, Rung-1 classifier).
;; ============================================================================
(defn apply-arm [arm current model-text task]
  (case arm
    :m1  (apply-m1 current model-text task)
    :m15 (apply-m15 current model-text task)
    :m2  (apply-m2-arm current model-text task)))

(defn run-attempt [arm task seed-text current work attempt-idx error-feedback]
  (let [prompt (build-prompt arm task seed-text current error-feedback)
        m (call-model prompt)]
    (if (:error m)
      {:fatal (:error m) :tokens 0}
      (let [over-ceiling? (> (:out-tokens m) TOKEN-CEIL)
            applied (apply-arm arm current (:text m) task)
            tag (format "%s-%s-a%d" (name arm) (:id task) attempt-idx)]
        (if (:adapter-error applied)
          {:model m :applied applied
           :class (classify-rung1 {:adapter-error (:adapter-error applied)})
           :gate-out (:adapter-error applied) :over-ceiling? over-ceiling?}
          (let [gate (build-module applied work tag)
                b? (built? gate)
                g (when b? (grade (:outdir gate) task))
                cls (classify-rung1 {:built? b? :gate-out (:out gate) :grade g})]
            {:model m :applied applied :gate gate :grade g :class cls
             :gate-out (:out gate) :over-ceiling? over-ceiling?}))))))

(defn run-trial [arm task seed-text work trial-idx]
  (let [current (initial-current arm task seed-text work)]
    (loop [attempt 0 feedback nil rounds [] tot-in 0 tot-out 0 tot-cache 0 tot-cost 0.0]
      (let [r (run-attempt arm task seed-text current work attempt feedback)]
        (if (:fatal r)
          {:arm (name arm) :density "d10" :task (:id task) :trial trial-idx
           :bucket "HARNESS_ERROR" :pass? false :fatal (:fatal r)
           :attempts (inc attempt) :rounds rounds
           :in-tokens tot-in :out-tokens tot-out :cache-tokens tot-cache :cost tot-cost}
          (let [m (:model r)
                ti (+ tot-in (:in-tokens m)) to (+ tot-out (:out-tokens m))
                tc (+ tot-cache (:cache-tokens m)) cost (+ tot-cost (:cost m))
                cls (:class r)
                round {:attempt attempt :bucket (name (:bucket cls))
                       :out-tokens (:out-tokens m)
                       :diag (when-not (:pass? cls) (normalize-diag (or (:gate-out r) "")))
                       :raw (:text m)}
                rounds' (conj rounds round)]
            (cond
              (:pass? cls)
              {:arm (name arm) :density "d10" :task (:id task) :trial trial-idx
               :bucket "PASS" :pass? true :attempts (inc attempt) :first-pass-attempt attempt
               :rounds rounds' :in-tokens ti :out-tokens to :cache-tokens tc :cost cost}
              (or (>= attempt R) (:over-ceiling? r))
              {:arm (name arm) :density "d10" :task (:id task) :trial trial-idx
               :bucket (name (:bucket cls)) :pass? false :attempts (inc attempt)
               :over-ceiling? (:over-ceiling? r)
               :rounds rounds' :in-tokens ti :out-tokens to :cache-tokens tc :cost cost}
              :else
              (recur (inc attempt) (normalize-diag (or (:gate-out r) "")) rounds' ti to tc cost))))))))

;; ============================================================================
;; DRIVER
;; ============================================================================
(defn load-rung1 []
  (edn/read-string (slurp (str HERE "/tasks-rung1.edn"))))

(defn -main [& args]
  (let [opts (apply hash-map args)
        arms (if-let [a (get opts "--arms")] (map keyword (str/split a #",")) [:m1 :m15 :m2])
        task-ids (when-let [t (get opts "--tasks")] (set (str/split t #",")))
        n (parse-long (or (get opts "--n") "30"))
        label (or (get opts "--label") "full-rung1")
        spec (load-rung1)
        seed-text (:seed-text spec)
        all-tasks (:tasks spec)
        tasks (if task-ids (filter #(task-ids (:id %)) all-tasks) all-tasks)
        work (str SCRATCH "/" label)
        _ (do (p/sh "rm" "-rf" work) (.mkdirs (io/file work)))
        results-file (str work "/results.jsonl")]
    (println (format "BAKEOFF-RUNG1 %s | model=%s R=%d ceil=%d | arms=%s tasks=%s n=%d"
                     label MODEL R TOKEN-CEIL (str/join "," (map name arms))
                     (str/join "," (map :id tasks)) n))
    (println (str "results -> " results-file))
    (with-open [w (io/writer results-file)]
      (doseq [arm arms task tasks trial (range n)]
        (let [res (run-trial arm task seed-text work trial)]
          (.write w (str (json/generate-string res) "\n"))
          (.flush w)
          (println (format "  %-4s %-2s t%-2d -> %-18s attempts=%d out=%d"
                           (name arm) (:id task) trial
                           (:bucket res) (:attempts res) (:out-tokens res))))))
    (println "DONE.")))

;; auto-run ONLY when invoked directly (not when load-file'd by the self-test, which
;; reuses the apply/gate/grade/classify fns without launching a run).
(when (str/ends-with? (or (System/getProperty "babashka.file") "") "run-rung1.bb")
  (apply -main *command-line-args*))
