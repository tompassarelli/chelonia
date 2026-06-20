;; ============================================================================
;; cnf_honesty_pass_test.clj — proves the read-side honesty pass is a
;; NO-SEMANTIC-CHANGE cleanup: the selection point is now NAMED (select-main-1)
;; but its behavior is byte-for-byte the old implicit (first …).
;;
;; This is the UNIT half (selection point == first, by construction). The
;; END-TO-END half (same RESOLVED outputs) is the existing suite: cnf_code_flip
;; KEYSTONE-A (render(log) == render(text) BYTE-IDENTICAL — exercises
;; pred-val/kind-of/sym-val/refers-target) + cnf_edit_min_scoped_correct
;; (refers_to symdiff 0). Run all three; green = behavior preserved.
;;   bb -cp out cnf_honesty_pass_test.clj
;; ============================================================================
(binding [*command-line-args* []] (load-file "cnf_coord_daemon.clj"))  ; loads ns `resolve`

(def fails (atom 0))
(defn chk [name got want]
  (if (= got want)
    (println (str "  [PASS] " name))
    (do (swap! fails inc) (println (str "  [FAIL] " name " — got " (pr-str got) " want " (pr-str want))))))

;; select-main-1 IS first — the rename introduced zero behavior change.
(chk "select-main-1 picks the default-main (first) member" (resolve/select-main-1 [10 20 30]) 10)
(chk "select-main-1 of a singleton"                        (resolve/select-main-1 [42]) 42)
(chk "select-main-1 of empty is nil (was: (when (seq cs)))" (resolve/select-main-1 []) nil)
(chk "select-main-1 of nil is nil"                          (resolve/select-main-1 nil) nil)
;; equivalence to the former bare (first …) over arbitrary inputs
(let [samples [[] [1] [1 2] [9 8 7 6] (range 50)]]
  (chk "select-main-1 ≡ first over all samples"
       (mapv resolve/select-main-1 samples) (mapv first samples)))

(println (str "\n---- honesty-pass unit: " (if (zero? @fails) "ALL PASS" (str @fails " FAIL")) " ----"))
(System/exit (if (zero? @fails) 0 1))
