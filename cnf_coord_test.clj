;; cnf_coord_test.clj — Stage 6 gate: adversarial concurrency + durability proof
;; for the reified coordinator (mirrors coord.clj's run-test).
;;   bb -cp out cnf_coord_test.clj
(require '[chelonia.cnf :as c] '[chelonia.schema :as s])
(load-file "cnf_coord.clj")   ; side-effect-free library: new-coord/commit!/replay/...

(let [log "/tmp/cnf-coord-test.log"
      co (new-coord log)
      _ (register-pred! co "status" "single" "literal")
      _ (register-pred! co "tag" "multi" "ref")
      _ (register-pred! co "part_of" "single" "ref")
      checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm ok]))]

  ;; ---- (A) base_version: N clients race the SAME single-valued (T,status) ----
  (let [seed (commit! co "seed" "T" "status" :assert "init" 0)
        base (:ok seed)
        n 24
        fs (mapv (fn [i] (future (commit! co (str "w" i) "T" "status" :assert (str "v" i) base)))
                 (range n))
        rs (mapv deref fs)
        wins (filter :ok rs)
        conflicts (filter #(= :conflict (:reject %)) rs)
        live (live-cids-lp co (s/resolve-name (store co) "T") (c/value-id (store co) "status"))]
    (chk "base_version: exactly one racer wins" (= 1 (count wins)))
    (chk "base_version: the rest are :conflict" (= (dec n) (count conflicts)))
    (chk "single-valued: exactly one live (T,status) claim" (= 1 (count live))))

  ;; ---- (B) multi-valued idempotency: identical link! twice = one edge --------
  (let [_ (commit! co "a" "T" "tag" :link "X" 0)
        r2 (commit! co "a" "T" "tag" :link "X" 0)
        live (live-cids-lp co (s/resolve-name (store co) "T") (c/value-id (store co) "tag"))]
    (chk "idempotency: second identical link! is a no-op" (:idempotent r2))
    (chk "idempotency: exactly one live (T,tag,X) edge" (= 1 (count live)))
    (chk "idempotency: distinct multi values both kept"
         (do (commit! co "a" "T" "tag" :link "Y" 0)
             (= 2 (count (live-cids-lp co (s/resolve-name (store co) "T")
                                       (c/value-id (store co) "tag")))))))

  ;; ---- (C) obligation: part_of acyclicity rejected, no state leaked ----------
  (commit! co "a" "A" "part_of" :link "B" 0)
  (commit! co "a" "B" "part_of" :link "C" 0)
  (let [before (live-triples (store co))
        self (commit! co "a" "E" "part_of" :link "E" 0)       ; fresh subject isolates the self-loop check from base_version
        cyc  (commit! co "a" "C" "part_of" :link "A" 0)       ; C->A closes A->B->C->A
        after (live-triples (store co))]
    (chk "obligation: self part_of rejected" (vector? (:reject self)))
    (chk "obligation: cycle part_of rejected" (vector? (:reject cyc)))
    (chk "obligation: rejected writes leak ZERO state" (= before after))
    (chk "obligation: a valid part_of still commits"
         (:ok (commit! co "a" "D" "part_of" :link "A" 0))))

  ;; ---- (D) durability: replay reconstructs the exact live view --------------
  (let [rp (replay log)]
    (chk "replay: live view set-equal to the coordinator store"
         (= (live-triples (store co)) (live-triples rp))))

  ;; ---- (E) atomicity: a torn (un-committed) trailing tx is dropped ----------
  (let [before (live-triples (store co))]
    (with-open [os (java.io.FileOutputStream. log true)]   ; simulate a crash mid-append
      (.write os (.getBytes (str (pr-str {:k :value :id 99999 :v "torn"}) "\n"
                                 (pr-str {:k :claim :cid 99998 :l 0 :p 0 :r 99999 :tx 99997}) "\n"
                                 "{:k :claim :cid 99996 :l 0 :p ")        ; torn mid-line
                            "UTF-8")))
    (let [rp (replay log)]
      (chk "atomicity: torn trailing tx dropped on replay" (= before (live-triples rp)))
      (chk "atomicity: torn value not present after replay"
           (not (contains? (set (vals (:values @rp))) "torn")))))

  (let [cs @checks fails (remove second cs)]
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (println "\nStage 6: reified coordinator —" (count cs) "/" (count cs) "PASS")
      (do (println "\nStage 6:" (count fails) "FAILED") (System/exit 1)))))
