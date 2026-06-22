#!/usr/bin/env bb
;; selftest_rung1_m15.bb — the UNIFIED Rung-1 PRE-FLIGHT discriminator gate.
;; ============================================================================
;; This is the run-before-the-headline gate. It exercises ALL THREE arms' apply
;; paths (m1 whole-module / m15 def-level / m2 claim-changeset) on FIXED inputs
;; with ZERO model calls, through the REAL runner pipeline
;; (apply-arm -> build-module -> grade -> classify-rung1). Each arm is fed:
;;   - a known-GOOD edit  -> MUST grade PASS
;;   - a known-BAD  edit  -> MUST grade the correct error bucket (REFERENCE_ERROR)
;; If a correct edit false-fails OR a broken edit false-passes, the arm cannot
;; tell a real failure from a success and the headline is garbage. Runs in seconds;
;; prints GREEN/RED per arm. Non-negotiable: apply-path bugs are caught HERE
;; deterministically, not discovered at n=3 in a live run.
;;
;; HISTORY (two apply-path bugs this gate now pins, both false REFERENCE_ERRORs):
;;   M1.5 (fixed d78f8a6): def-level upsert appended the renamed-to def LAST (forward
;;     ref at load) and could not delete the renamed-away def. Fix: structural rename
;;     detection (drop the orphan) + topological def ordering.
;;   M2 (this fix): the adapter minted EVERY "@id" FRESH, so the model's "@m#8" (its
;;     taught spelling for EXISTING node 8 — the M2 prompt spells every node "@m#N")
;;     never reached node 8; the binding-leaf respell silently no-op'd and the pristine
;;     seed rebuilt -> f2 never created -> false REFERENCE_ERROR. CRUCIAL: the prior M2
;;     gate (selftest_rung1_gate.bb) fed BARE INTEGER ids, which the adapter handled, so
;;     it never exercised the "@m#N" spelling the model actually emits — the bug shipped.
;;     This gate feeds M2 the "@m#N" spelling to close exactly that hole.
(ns selftest-rung1-preflight
  (:require [babashka.process :as p] [clojure.string :as str]))

(def HERE (System/getProperty "user.dir"))
(load-file (str HERE "/run-rung1.bb"))   ; reuses the REAL apply/gate/grade/classify path

(def W "/home/tom/code/fram/experiments/authoring-bakeoff/scratch/rung1-preflight")
(p/sh "rm" "-rf" W) (p/sh "mkdir" "-p" W)

(def spec (run-rung1/load-rung1))
(def seed (:seed-text spec))
(def task1A (first (filter #(= "1A" (:id %)) (:tasks spec))))
(def task1B (first (filter #(= "1B" (:id %)) (:tasks spec))))

;; one driver for every arm: same real pipeline the live runner uses.
(defn drive [arm task tag model-text]
  (let [current (run-rung1/initial-current arm task seed W)
        applied (run-rung1/apply-arm arm current model-text task)]
    (if (:adapter-error applied)
      (let [cls (run-rung1/classify-rung1 {:adapter-error (:adapter-error applied)})]
        {:tag tag :bucket (name (:bucket cls)) :pass? (:pass? cls)})
      (let [gate (run-rung1/build-module applied W tag)
            b? (run-rung1/built? gate)
            g (when b? (run-rung1/grade (:outdir gate) task))
            cls (run-rung1/classify-rung1 {:built? b? :gate-out (:out gate) :grade g})]
        {:tag tag :built? b? :grade g :bucket (name (:bucket cls)) :pass? (:pass? cls)}))))

;; ============================================================================
;; FIXED INPUTS — 1A rename f->f2, per arm, good + bad.
;; ============================================================================
;; -- M1 / M1.5 : whole-module / changed-defs TEXT --------------------------------
(def good-text
  (str "(defn f2 [x :- Int] :- Int (+ x 1))\n"
       "(defn g [a :- Int] :- Int (+ (f2 a) (f2 a)))\n"
       "(defn h [b :- Int] :- Int (* (f2 b) 2))\n"
       "(defn k [c :- Int] :- Int (+ (f2 c) (f2 (f2 c))))"))
;; BAD: def + g + k re-spelled, but h MISSES a call site (still `f`) -> dangling ref
(def bad-text
  (str "(defn f2 [x :- Int] :- Int (+ x 1))\n"
       "(defn g [a :- Int] :- Int (+ (f2 a) (f2 a)))\n"
       "(defn h [b :- Int] :- Int (* (f b) 2))\n"
       "(defn k [c :- Int] :- Int (+ (f2 c) (f2 (f2 c))))"))

;; -- M2 : claim changeset in the "@m#N" spelling THE MODEL ACTUALLY EMITS ---------
;; def-leaf=8, use-leaves=[33 36 51 67 70 72]. The model addresses existing node N as @m#N.
(def def-leaf (get-in task1A [:rename :def-leaf]))
(def use-leaves (get-in task1A [:rename :use-leaves]))
(defn atid [n] (str "\"@m#" n "\""))
;; GOOD: respell the binding leaf + bound_to every use (the prompt-taught surgical rename)
(def good-m2
  (str (str/join "\n" (for [u use-leaves] (format "[%s :bound_to %s]" (atid u) (atid def-leaf))))
       "\n" (format "[RETRACT %s :v \"f\"]\n[ASSERT %s :v \"f2\"]" (atid def-leaf) (atid def-leaf))))
;; BAD-omit: never respell the binding leaf -> f2 never exists -> grade can't resolve f2
(def bad-m2-omit (format "[ASSERT %s :kind \"symbol\"]" (atid def-leaf)))
;; BAD-dangle: respell the def leaf but leave ONE call site's bound_to MISSING (skip @m#51)
(def bad-m2-dangle
  (str (str/join "\n" (for [u (remove #{51} use-leaves)] (format "[%s :bound_to %s]" (atid u) (atid def-leaf))))
       "\n" (format "[RETRACT %s :v \"f\"]\n[ASSERT %s :v \"f2\"]" (atid def-leaf) (atid def-leaf))))

;; ============================================================================
;; RUN
;; ============================================================================
(def results
  {:m1   {:good (drive :m1  task1A "m1-good"  good-text)
          :bad  (drive :m1  task1A "m1-bad"   bad-text)}
   :m15  {:good (drive :m15 task1A "m15-good" good-text)
          :bad  (drive :m15 task1A "m15-bad"  bad-text)}
   :m2   {:good (drive :m2  task1A "m2-good"  good-m2)
          ;; two distinct BAD shapes for M2 (omit the rename / dangle a call site)
          :bad  (drive :m2  task1A "m2-bad-omit"   bad-m2-omit)
          :bad2 (drive :m2  task1A "m2-bad-dangle" bad-m2-dangle)}})

(defn green? [arm]
  (let [{:keys [good bad bad2]} (get results arm)]
    (and (:pass? good) (= "PASS" (:bucket good))
         (not (:pass? bad)) (= "REFERENCE_ERROR" (:bucket bad))
         (or (nil? bad2) (and (not (:pass? bad2)) (= "REFERENCE_ERROR" (:bucket bad2)))))))

(println "=== Rung-1 PRE-FLIGHT discriminator gate (m1 / m15 / m2, no model calls) ===")
(doseq [arm [:m1 :m15 :m2]]
  (let [{:keys [good bad bad2]} (get results arm)
        g? (green? arm)]
    (println (format "  [%s] %-4s  good=%-16s  bad=%-16s%s"
                     (if g? "GREEN" " RED ") (name arm)
                     (:bucket good) (:bucket bad)
                     (if bad2 (format "  bad2=%-16s" (:bucket bad2)) "")))))
(let [ok (every? green? [:m1 :m15 :m2])]
  (println (if ok
             "\nPRE-FLIGHT GREEN — all three arms discriminate (correct->PASS, broken->REFERENCE_ERROR). Safe to fire the headline."
             "\nPRE-FLIGHT RED — an arm cannot tell a real failure from a success. DO NOT fire the headline."))
  (System/exit (if ok 0 1)))
