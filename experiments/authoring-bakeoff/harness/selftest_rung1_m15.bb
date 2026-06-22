#!/usr/bin/env bb
;; selftest_rung1_m15.bb — proves the Rung-1 M1.5 (def-level apply) arm models a rename
;; FAITHFULLY: a CORRECT rename emission grades PASS, a BAD one (missed call site) grades
;; REFERENCE_ERROR. This is the discriminator the headline (M1.5-vs-M2) rests on.
;;
;; WHY THIS EXISTS: the first Rung-1 run false-failed EVERY correct M1.5 rename as
;; REFERENCE_ERROR. Root cause was in apply-m15 (def-level upsert), NOT the model:
;;   (1) the renamed-to def f2 was APPENDED LAST, after callers g/h/k that reference it,
;;       so f2 was an unresolved FORWARD reference at runtime load (bb/SCI) -> false
;;       "Unable to resolve symbol: f2";
;;   (2) the renamed-away def f could not be DELETED (upsert-by-name can't remove), leaving
;;       an ORPHAN. The fix: structural rename detection (drop the renamed-away def) +
;;       topological def ordering. (M1 whole-module replace was already clean -> it passed;
;;       M2 routes through the recompile-gated adapter -> unaffected. The fix is m15-only.)
;;
;; We drive the REAL runner pipeline (apply-m15 -> build-module -> grade -> classify-rung1),
;; no model call — pure apply+gate+grade exercise, exactly like selftest_rung1_gate.bb (M2).
(ns selftest-rung1-m15
  (:require [babashka.process :as p] [clojure.string :as str]))

(def HERE (System/getProperty "user.dir"))
(load-file (str HERE "/run-rung1.bb"))   ; reuses the REAL apply/gate/grade/classify path

(def W "/home/tom/code/fram/experiments/authoring-bakeoff/scratch/rung1-m15-selftest")
(p/sh "rm" "-rf" W) (p/sh "mkdir" "-p" W)

(def spec (run-rung1/load-rung1))
(def seed (:seed-text spec))
(def task1A (first (filter #(= "1A" (:id %)) (:tasks spec))))
(def task1B (first (filter #(= "1B" (:id %)) (:tasks spec))))

(defn drive [task tag model-text]
  ;; run the REAL runner pipeline for the M1.5 arm
  (let [current {:text seed}
        applied (run-rung1/apply-m15 current model-text task)
        gate (run-rung1/build-module applied W tag)
        b? (run-rung1/built? gate)
        g (when b? (run-rung1/grade (:outdir gate) task))
        cls (run-rung1/classify-rung1 {:built? b? :gate-out (:out gate) :grade g})]
    {:tag tag :built? b? :grade g :bucket (name (:bucket cls)) :pass? (:pass? cls)}))

;; ---- 1A discriminator ------------------------------------------------------
;; CORRECT rename: f->f2 at the def AND every call site (the model's verified-good emission)
(def good-1A
  (str "(defn f2 [x :- Int] :- Int (+ x 1))\n"
       "(defn g [a :- Int] :- Int (+ (f2 a) (f2 a)))\n"
       "(defn h [b :- Int] :- Int (* (f2 b) 2))\n"
       "(defn k [c :- Int] :- Int (+ (f2 c) (f2 (f2 c))))"))
;; BAD rename: def renamed to f2, g/k re-spelled, but h MISSES a call site (still `f`)
(def bad-1A
  (str "(defn f2 [x :- Int] :- Int (+ x 1))\n"
       "(defn g [a :- Int] :- Int (+ (f2 a) (f2 a)))\n"
       "(defn h [b :- Int] :- Int (* (f b) 2))\n"
       "(defn k [c :- Int] :- Int (+ (f2 c) (f2 (f2 c))))"))

;; ---- 1B sanity (body re-mint must NOT false-fail) --------------------------
;; CORRECT re-mint: f becomes (* x 2); callers untouched. (Minimal changed-def emit = just f.)
(def good-1B "(defn f [x :- Int] :- Int (* x 2))")
;; BAD re-mint: a caller is collaterally damaged (h rewired to call a non-existent fn)
(def bad-1B
  (str "(defn f [x :- Int] :- Int (* x 2))\n"
       "(defn h [b :- Int] :- Int (* (nope b) 2))"))

(let [g1a (drive task1A "1A-good" good-1A)
      b1a (drive task1A "1A-bad"  bad-1A)
      g1b (drive task1B "1B-good" good-1B)
      b1b (drive task1B "1B-bad"  bad-1B)]
  (println "=== Rung-1 M1.5 def-level-apply discriminator self-test ===")
  (doseq [r [g1a b1a g1b b1b]]
    (println (format "  %-9s -> bucket=%-16s pass?=%s" (:tag r) (:bucket r) (:pass? r))))
  (let [ok (and (:pass? g1a) (= "PASS" (:bucket g1a))                ; correct 1A rename -> PASS
                (not (:pass? b1a)) (= "REFERENCE_ERROR" (:bucket b1a)); bad 1A rename -> REFERENCE_ERROR
                (:pass? g1b) (= "PASS" (:bucket g1b))                ; correct 1B re-mint -> PASS
                (not (:pass? b1b)))]                                  ; bad 1B -> not PASS (detected)
    (println (if ok
               (str "\nSELFTEST PASS — M1.5 models rename faithfully:\n"
                    "  correct rename -> PASS (no false REFERENCE_ERROR), bad rename -> REFERENCE_ERROR;\n"
                    "  1B body re-mint clean both ways.")
               "\nSELFTEST FAIL — M1.5 apply did not behave as required."))
    (System/exit (if ok 0 1))))
