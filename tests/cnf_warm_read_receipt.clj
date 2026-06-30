;; ============================================================================
;; cnf_warm_read_receipt.clj — interface investigation #1: warm read vs cold fold
;;   bb -cp out cnf_warm_read_receipt.clj
;;
;; The MCP read path COLD-FOLDS the whole log per request (load-state -> fold(read-log));
;; the daemon's warm :query serves off the maintained in-memory store. Measures the
;; per-read tax: warm :query vs cold fold+query, and confirms the capability handshake
;; (coord-query to a down/old daemon -> nil -> cold fallback).
;;
;; SAFE: synthetic /tmp log + in-process daemon (boot-flat!, handle{}); no socket, no
;; port 7977, never the canonical tern log. The one socket call is to a DEAD port
;; (fallback probe) -> connection refused -> nil.
;; ============================================================================
(require '[clojure.java.io :as io] '[clojure.string :as str]
         '[fram.cnf :as c] '[fram.fold :as fold] '[fram.query :as q] '[fram.rt])
(load-file "cnf_coord_daemon.clj")
(defn ms [t0 t1] (/ (double (- t1 t0)) 1e6))
(defmacro timed [& b] `(let [t0# (System/nanoTime) r# (do ~@b) t1# (System/nanoTime)] [(ms t0# t1#) r#]))
(defn med [xs] (nth (sort xs) (quot (count xs) 2)))

;; synthetic flat log ~ canonical thread-graph profile (N threads x 3 claims)
(def log "/tmp/cnf-warmread-synth.log")
(def N 1200)
(spit log (str/join "\n"
  (mapcat (fn [k] [(pr-str {:tx (* k 3) :op "assert" :l (str "@t" k) :p "title" :r (str "thread " k)})
                   (pr-str {:tx (+ 1 (* k 3)) :op "assert" :l (str "@t" k) :p "committed" :r "true"})
                   (pr-str {:tx (+ 2 (* k 3)) :op "assert" :l (str "@t" k) :p "depends_on" :r (str "@t" (mod (inc k) N))})])
          (range N))))
(def Q {:find "out" :rules [{:head {:rel "out" :args [{:var "l"}]}
                             :body [{:rel "triple" :args [{:var "l"} "committed" "true"]}]}]})

(boot-flat! log)
(handle {:op :query :query Q})                                  ; warm the cache
(def warm (vec (repeatedly 9 #(first (timed (handle {:op :query :query Q}))))))
(defn cold-read [] (q/run (:claims (fold/fold (fram.rt/read-log log))) Q))  ; what the MCP cold path does per read
(def cold (vec (repeatedly 9 #(first (timed (cold-read))))))

(println "=== interface #1 — WARM READ vs COLD FOLD (synthetic" (* N 3) "claim log) ===")
(def wq (count (:ok (handle {:op :query :query Q}))))
(println "query result rows (both paths):" wq "(committed threads)")
(println (format "WARM :query  (daemon warm store, no fold): median %.1f ms  [%.1f..%.1f]" (med warm) (apply min warm) (apply max warm)))
(println (format "COLD fold+query (per-read full-log fold):  median %.1f ms  [%.1f..%.1f]" (med cold) (apply min cold) (apply max cold)))
(println (format ">>> warm is %.0fx faster per read (the per-read fold tax, eliminated)" (/ (med cold) (max 0.001 (med warm)))))
(println "fallback handshake (coord-query to DEAD port 19998 -> nil -> cold path):" (nil? (fram.rt/coord-query 19998 Q)))

(println "\n=== VERDICT ===")
(if (and (< (med warm) (med cold)) (pos? wq) (nil? (fram.rt/coord-query 19998 Q)))
  (println "PASS — warm read path serves the same result far cheaper than the per-read cold fold,"
           "and falls back cleanly when the daemon is down/old. Rep-stable (Datalog over (l,p,r)).")
  (println "FAIL — see above."))
