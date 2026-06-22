#!/usr/bin/env bb
;; selftest_rung1_gate.bb — proves the Rung-1 M2 arm routes through the RECOMPILE-GATED
;; path (render-from-dump -> beagle-build-all --build-edn + runtime grade, i.e. the
;; route-edit/flip-graph-edit! equivalent), NOT the ungated :edit-min demo path.
;;
;; THE ASSERTION: a known-BAD rename (rename the def `f`->`f2` but MISS the call sites)
;; MUST be REJECTED by the gate. (FREEZE D3: :edit-min lands such an edit UNCHECKED;
;; the recompile gate refuses it.) We also assert a GOOD rename PASSES, so the gate is
;; not just refusing everything. No model call — pure adapter+gate exercise.
;;
;; NB finding (logged): beagle's `--build-edn` is PERMISSIVE on unbound symbols — a
;; missed call site still emits "0 error" at build, and only fails at RUNTIME grade
;; ("Unable to resolve symbol: f"). So reference integrity is enforced by the GRADE
;; stage of the gate, and the Rung-1 classifier maps that to REFERENCE_ERROR.
(ns selftest-rung1-gate
  (:require [babashka.process :as p] [clojure.string :as str]))

(def HERE (System/getProperty "user.dir"))
(load-file (str HERE "/run-rung1.bb"))   ; reuses the REAL apply/gate/grade/classify path

(def W "/home/tom/code/fram/experiments/authoring-bakeoff/scratch/rung1-selftest")
(p/sh "rm" "-rf" W) (p/sh "mkdir" "-p" W)

(def spec (run-rung1/load-rung1))
(def seed (:seed-text spec))
(def task1A (first (filter #(= "1A" (:id %)) (:tasks spec))))

;; build the M2 current-state (engine dump) EXACTLY as the runner does
(def current (run-rung1/initial-current :m2 task1A seed W))

(defn drive [tag changeset-edn]
  ;; run the REAL runner pipeline: apply-m2-arm -> build-module -> grade -> classify-rung1
  (let [applied (run-rung1/apply-m2-arm current changeset-edn task1A)]
    (if (:adapter-error applied)
      (let [cls (run-rung1/classify-rung1 {:adapter-error (:adapter-error applied)})]
        {:tag tag :bucket (name (:bucket cls)) :pass? (:pass? cls)})
      (let [gate (run-rung1/build-module applied W tag)
            b? (run-rung1/built? gate)
            g (when b? (run-rung1/grade (:outdir gate) task1A))
            cls (run-rung1/classify-rung1 {:built? b? :gate-out (:out gate) :grade g})]
        {:tag tag :built? b? :grade g :bucket (name (:bucket cls)) :pass? (:pass? cls)}))))

;; the def-leaf + use-leaves come straight from the task spec
(def def-leaf (get-in task1A [:rename :def-leaf]))
(def use-leaves (get-in task1A [:rename :use-leaves]))

;; (1) BAD: rename ONLY the def name leaf, miss every call site -> MUST REJECT
(def bad-rename (format "[RETRACT %d :v \"f\"] [%d :v \"f2\"]" def-leaf def-leaf))
;; (2) GOOD-surgical: re-spell the def + bound_to each use (the prompt-taught method)
(def good-surgical
  (str (str/join " " (for [u use-leaves] (format "[%d :bound_to %d]" u def-leaf)))
       "\n" (format "[RETRACT %d :v \"f\"] [%d :v \"f2\"]" def-leaf def-leaf)))
;; (3) GOOD-explicit: re-spell every leaf
(def good-explicit
  (str/join "\n" (for [id (cons def-leaf use-leaves)]
                   (format "[RETRACT %d :v \"f\"] [%d :v \"f2\"]" id id))))

(let [bad (drive "bad" bad-rename)
      gs  (drive "good-surgical" good-surgical)
      ge  (drive "good-explicit" good-explicit)]
  (println "=== Rung-1 M2 gated-path self-test ===")
  (doseq [r [bad gs ge]]
    (println (format "  %-15s -> bucket=%-16s pass?=%s %s"
                     (:tag r) (:bucket r) (:pass? r)
                     (if (:grade r) (str "grade=" (pr-str (:grade r))) ""))))
  (let [ok (and (not (:pass? bad))                       ; bad MUST be rejected
                (= "REFERENCE_ERROR" (:bucket bad))      ; ... as a reference error
                (:pass? gs) (= "PASS" (:bucket gs))       ; surgical good MUST pass
                (:pass? ge) (= "PASS" (:bucket ge)))]     ; explicit good MUST pass
    (println (if ok "\nSELFTEST PASS — gated path rejects the bad rename, accepts good renames."
                     "\nSELFTEST FAIL — gate did not behave as required."))
    (System/exit (if ok 0 1))))
