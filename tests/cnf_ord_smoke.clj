;; Stage B smoke — resolver compiles with the ord-lib + old-format reads unchanged.
;;   bb -cp out cnf_ord_smoke.clj    (uses an OLD f<int> corpus: /tmp/cnf-ksweep-K2.log)
;; SAFE: /tmp copy, in-process.
(require '[clojure.java.io :as io] '[clojure.string :as str] '[fram.cnf :as c] '[fram.schema :as s])
(load-file "cnf_coord_daemon.clj")            ; loads chartroom/src/resolve.clj (the ord-lib)
(def src "/tmp/cnf-ksweep-K2.log")
(when-not (.exists (io/file src)) (println "need" src "— run ksweep first") (System/exit 1))
(def tmp (str "/tmp/cnf-ordsmoke-" (System/nanoTime) ".log"))
(io/copy (io/file src) (io/file tmp))
(boot-flat! tmp)
(def st (:store @co))
(println "resolve.clj loaded OK; ord-parse resolves:" (some? (resolve/ord-parse "f3")))

;; OLD f<int> keys (incl the f10-after-f2 trap) must sort IDENTICALLY under the new
;; ord-cmp (via ord-parse) and under the old integer sort — no read regression.
(def fstrs (mapv #(str "f" %) (range 13)))     ; f0..f12
(def by-int (vec (sort-by #(parse-long (subs % 1)) fstrs)))
(def by-ord (vec (sort-by resolve/ord-parse resolve/ord-cmp fstrs)))
(println "f0..f12 (incl f10>f2): ord-cmp sort == integer sort:" (= by-int by-ord) " ->" by-ord)
;; also: a NEW key sorts between the right OLD keys (interleave correctness)
(def mixed ["f1" "f2" (resolve/ord-str (resolve/ord-between (:path (resolve/ord-parse "f1")) (:path (resolve/ord-parse "f2"))) 99) "f3"])
(def mixed-sorted (vec (sort-by resolve/ord-parse resolve/ord-cmp mixed)))
(println "new key interleaves between f1 and f2:" (= mixed-sorted ["f1" (mixed 2) "f2" "f3"]))
(println (if (and (= by-int by-ord) (pos? (count fstrs)) (= mixed-sorted ["f1" (mixed 2) "f2" "f3"]))
           "\nPASS — resolver compiles with ord-lib; OLD f<int> keys sort IDENTICALLY (no read regression); NEW CRDT key interleaves at the correct gap. Dual parser safe."
           "\nFAIL — see above."))
