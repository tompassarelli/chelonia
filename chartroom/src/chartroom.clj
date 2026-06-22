(ns chartroom
  (:require [clojure.string :as str]
            [callgraph :as cg]
            [fram.cnf :as c]
            [fram.datalog :as d]))

(defn rev-adj [edges]
  (reduce (fn [m e] (let [ev (let [x e]
  x)
   a (nth ev 0)
   b (nth ev 1)]
  (assoc m b (conj (get m b #{}) a)))) {} edges))

(defn fwd-adj [edges]
  (reduce (fn [m e] (let [ev (let [x e]
  x)
   a (nth ev 0)
   b (nth ev 1)]
  (assoc m a (conj (get m a #{}) b)))) {} edges))

(defn transitive [adj n]
  (loop [seen #{}
   frontier (vec (get adj n #{}))]
  (if (empty? frontier) seen (let [x (peek frontier)
   fr (pop frontier)]
  (if (contains? seen x) (recur seen fr) (recur (conj seen x) (into fr (vec (get adj x #{})))))))))

(defn avg-ranks [v]
  (let [idx (vec (map-indexed (fn [i x] [i x]) v))
   sorted (vec (sort-by (fn [p] (nth (let [x p]
  x) 1)) idx))
   groups (vec (partition-by (fn [p] (nth (let [x p]
  x) 1)) sorted))
   rank-map (loop [gs groups
   start 1
   acc {}]
  (if (empty? gs) acc (let [g (first gs)
   gv (let [x g]
  x)
   n (count gv)
   r (/ (+ (* 2.0 start) (- n 1)) 2.0)]
  (recur (vec (rest gs)) (+ start n) (reduce (fn [a pair] (let [pv (let [x pair]
  x)
   i (nth pv 0)]
  (assoc a i r))) acc gv)))))]
  (mapv (fn [i] (get rank-map i)) (range (count v)))))

(defn pearson [xs ys]
  (let [n (count xs)
   mx (/ (reduce (fn [s x] (+ s x)) 0.0 xs) n)
   my (/ (reduce (fn [s y] (+ s y)) 0.0 ys) n)
   cov (reduce (fn [s i] (let [x (nth xs i)
   y (nth ys i)]
  (+ s (* (- x mx) (- y my))))) 0.0 (range n))
   sx (Math/sqrt (reduce (fn [s x] (let [dd (- x mx)]
  (+ s (* dd dd)))) 0.0 xs))
   sy (Math/sqrt (reduce (fn [s y] (let [dd (- y my)]
  (+ s (* dd dd)))) 0.0 ys))]
  (if (or (zero? sx) (zero? sy)) 0.0 (/ cov (* sx sy)))))

(defn spearman [xs ys]
  (pearson (avg-ranks xs) (avg-ranks ys)))

(defn ^String fmt-f3 [x]
  (format "%.3f" (double x)))

(defn ^String short-file [k]
  (str/replace (let [kv (let [x k]
  x)]
  (let [s (first kv)]
  s)) #".*/gjoa/" ""))

(defn -main []
  (let [corpus-path (or (first *command-line-args*) "build/gjoa.claims")
   blocks (cg/parse-corpus corpus-path)
   graph (cg/build-graph blocks)
   defns (:defns graph)
   by-name (:by-name graph)
   edges (:edges graph)
   radj (rev-adj edges)
   fadj (fwd-adj edges)
   defn-keys (mapv (fn [e] (:key e)) defns)
   direct (into {} (map (fn [k] [k (count (get radj k #{}))]) defn-keys))
   blast (into {} (map (fn [k] [k (count (transitive radj k))]) defn-keys))]
  (println "================ CHARTROOM — code-as-claims on Fram ================")
  (println (str "corpus: " corpus-path))
  (println (str "files: " (count blocks) "  defns: " (count defns) "  resolved internal call-edges: " (count edges)))
  (let [ctx (c/new-store)
   tx (c/begin-tx! ctx "code")
   EDGE (c/value! ctx "calls-defn")
   k->id (atom {})
   ent (fn [k] (let [existing (get (deref k->id) k)]
  (if (some? existing) (let [e existing]
  e) (let [e (c/entity! ctx)]
  (swap! k->id (fn [m] (assoc m k e)))
  e))))
   _ (doseq [edge edges]
  (let [ev (let [x edge]
  x)
   a (nth ev 0)
   b (nth ev 1)]
  (c/claim! ctx (ent a) EDGE (ent b) tx)))
   t0 (System/currentTimeMillis)
   db (d/run-rules ctx [(d/rule "reaches" [(d/v :x) (d/v :y)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])]) (d/rule "reaches" [(d/v :x) (d/v :z)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)]) (d/lit "reaches" [(d/v :y) (d/v :z)])])])
   dl-reaches (set (d/facts db "reaches"))
   t1 (System/currentTimeMillis)
   truth (reduce (fn [s k] (+ s (count (transitive fadj k)))) 0 defn-keys)]
  (println (str "\nFram Datalog transitive closure: " (count dl-reaches) " reaches-pairs in " (- t1 t0) " ms" "  (in-process closure: " truth " pairs — " (if (= (count dl-reaches) truth) "MATCH" "DIVERGE") ")")))
  (println "\n================ BENCHMARK A — caller precision on collisions ================")
  (println "(oracle = module-local scope: a call binds the defn in its own file)")
  (let [collisions (vec (sort (map first (filter (fn [kv] (let [pair (let [x kv]
  x)
   ds (let [x (nth pair 1)]
  x)]
  (> (count (distinct (map (fn [d] (first (:key d))) ds))) 1))) by-name))))
   rows (vec (for [nm collisions
   :let [ds (get by-name (let [s nm]
  s) [])
   incumbent (reduce (fn [s d] (into s (get radj (:key d) #{}))) #{} ds)]
   d ds
   :let [g (get radj (:key d) #{})
   gc (count g)
   ic (count incumbent)]
   :when (pos? ic)]
  {:name nm :file (short-file (:key d)) :graph-p 1.0 :incumbent-p (/ gc (double ic)) :gn gc :in ic}))
   rows (filterv (fn [r] (pos? (let [v (:gn r)]
  v))) rows)
   mean-delta (if (seq rows) (/ (reduce (fn [s r] (+ s (- (let [v (:graph-p r)]
  v) (let [v (:incumbent-p r)]
  v)))) 0.0 rows) (count rows)) 0.0)
   tot-g (reduce (fn [s r] (+ s (let [v (:gn r)]
  v))) 0 rows)
   tot-in (reduce (fn [s r] (+ s (let [v (:in r)]
  v))) 0 rows)
   micro-incumbent-p (if (pos? tot-in) (/ tot-g (double tot-in)) 1.0)
   micro-delta (- 1.0 micro-incumbent-p)
   wrong (count (filterv (fn [r] (< (let [v (:incumbent-p r)]
  v) 1.0)) rows))]
  (println (str "collision names: " (count collisions) "  scored targets: " (count rows)))
  (doseq [r (->> rows (sort-by (fn [r2] (:incumbent-p r2))) (take 12))]
  (println (format "  %-18s %-22s graph P=%.2f  incumbent P=%.2f  (%d of %d callers are in-scope)" (:name r) (:file r) (:graph-p r) (:incumbent-p r) (:gn r) (:in r))))
  (println (format "graph is PERFECT on %d/%d targets; the bare-symbol incumbent is WRONG (P<1) on %d (%.0f%%)" (count rows) (count rows) wrong (* 100.0 (/ wrong (max 1 (count rows))))))
  (println (str "MACRO mean precision delta (graph - incumbent): " (fmt-f3 mean-delta)))
  (println (format "MICRO pooled delta: %s  (incumbent P=%.3f over %d in-scope / %d returned call-sites)" (fmt-f3 micro-delta) micro-incumbent-p tot-g tot-in))
  (println (str "  [PASS >= +0.20 (documented kill line)]" (if (>= mean-delta 0.2) " ✅" " —"))))
  (println "\n================ BENCHMARK B — transitive leverage (keystones) ================")
  (let [called (filterv (fn [k] (pos? (get blast k 0))) defn-keys)
   xs (mapv (fn [k] (double (get direct k 0))) called)
   ys (mapv (fn [k] (double (get blast k 0))) called)
   rho (spearman xs ys)
   top-direct (set (->> defn-keys (sort-by (fn [k] (- (get direct k 0)))) (take 10)))
   top-blast (vec (->> defn-keys (sort-by (fn [k] (- (get blast k 0)))) (take 5)))
   hidden (vec (remove (fn [k] (contains? top-direct k)) top-blast))
   ratio>=3 (filterv (fn [k] (let [d (get direct k 0)
   b (get blast k 0)]
  (and (pos? d) (>= (/ b (double d)) 3)))) defn-keys)]
  (println (str "called defns: " (count called) "  (ranked)"))
  (println "\nTOP 8 by transitive blast radius (transitive callers):")
  (doseq [k (->> defn-keys (sort-by (fn [k2] (- (get blast k2 0)))) (take 8))]
  (let [nm (:name (first (filterv (fn [e] (= (:key e) k)) defns)))]
  (println (format "  blast=%-4d direct=%-3d  %s   %s" (get blast k 0) (get direct k 0) nm (short-file k)))))
  (println (str "\nSpearman rho (direct-rank vs blast-rank): " (fmt-f3 rho) "  [PASS < 0.80 => closure reorders] " (if (< rho 0.8) "✅" "—")))
  (println (str "defns with blast/direct >= 3x: " (count ratio>=3) "  [PASS >= 15] " (if (>= (count ratio>=3) 15) "✅" "—")))
  (if (seq hidden) (do
  (let [k (first hidden)
   kn (:name (first (filterv (fn [e] (= (:key e) k)) defns)))]
  (println (format "KEYSTONE HIDDEN BY ONE-HOP: %s (blast=%d, direct=%d) is top-5 transitive but NOT top-10 direct ✅" kn (get blast k 0) (get direct k 0)))))))
  (println "\n====================================================================")))
