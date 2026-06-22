(ns callgraph
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [fram.cnf :as c]
            [fram.datalog :as d]))

(defrecord Block [file triples])

(defn block-file [r] (:file r))

(defn block-triples [r] (:triples r))

(defrecord DefnEntry [key name file module])

(defn defnentry-key [r] (:key r))

(defn defnentry-name [r] (:name r))

(defn defnentry-file [r] (:file r))

(defn defnentry-module [r] (:module r))

(defrecord CallGraph [defns by-name edges])

(defn callgraph-defns [r] (:defns r))

(defn callgraph-by-name [r] (:by-name r))

(defn callgraph-edges [r] (:edges r))

(defrecord BlastResult [blast reaches])

(defn blastresult-blast [r] (:blast r))

(defn blastresult-reaches [r] (:reaches r))

(defn parse-corpus [^String path]
  (let [skips (atom 0)
   lines (fram.str/split-lines (fram.rt/slurp path))
   blocks (loop [ls lines
   cur nil
   out []]
  (if (empty? ls) (if (some? cur) (conj out (let [b cur]
  b)) out) (let [l (first ls)]
  (cond
  (str/starts-with? l "@file ") (let [new-cur (->Block (subs l 6) [])]
  (recur (vec (rest ls)) new-cur (if (some? cur) (conj out (let [b cur]
  b)) out)))
  (str/starts-with? l "[") (let [t (try
  (edn/read-string l)
  (catch Exception _
    (do
  (swap! skips (fn [x] (+ x 1)))
  nil)))]
  (recur (vec (rest ls)) (if (some? cur) (if (some? t) (let [b cur]
  (->Block (:file b) (conj (:triples b) t))) cur) cur) out))
  :else (recur (vec (rest ls)) cur out)))))]
  (if (pos? (deref skips)) (do
  (fram.rt/println-err! (str "  (skipped " (deref skips) " EDN-unparseable leaf literals)"))))
  blocks))

(defn index-by [^String pred triples]
  (reduce (fn [m t] (let [tv (let [x t]
  x)
   s (nth tv 0)
   p (nth tv 1)
   o (nth tv 2)]
  (if (= p pred) (assoc m s o) m))) {} triples))

(defn ^String module-of [^String file]
  (-> file (str/split #"/") last (str/replace #"\.[^.]+$" "")))

(defn derive-block [^Block block]
  (let [ts (:triples block)
   file (:file block)
   fk (index-by "form-kind" ts)
   names (index-by "name" ts)
   calls (index-by "calls" ts)
   kids (reduce (fn [m t] (let [tv (let [x t]
  x)
   s (nth tv 0)
   p (nth tv 1)
   o (nth tv 2)]
  (if (= p "child") (assoc m s (conj (get m s []) o)) m))) {} ts)
   childset (reduce (fn [s t] (let [tv (let [x t]
  x)
   p (nth tv 1)
   o (nth tv 2)]
  (if (= p "child") (conj s o) s))) #{} ts)
   roots (vec (remove (fn [k] (contains? childset k)) (keys fk)))
   defns (vec (for [[s k] fk
   :when (= k "defn")]
  (->DefnEntry [file s] (let [nm (get names s)]
  (if (string? nm) nm "")) file (module-of file))))
   mentions (atom [])]
  (loop [stack (mapv (fn [r] [r nil]) roots)]
  (if (seq stack) (do
  (let [top (peek stack)
   st (pop stack)
   node (nth top 0)
   cd (nth top 1)
   cd2 (if (= (get fk node) "defn") node cd)]
  (if (and (= (get fk node) "call") (some? cd2) (some? (get calls node))) (do
  (swap! mentions (fn [v] (conj v [[file cd2] (get calls node)])))))
  (recur (into st (mapv (fn [k] [k cd2]) (get kids node []))))))))
  {:file file :defns defns :mentions (deref mentions)}))

(defn ^CallGraph build-graph [blocks]
  (let [derived (mapv derive-block blocks)
   defns (vec (mapcat (fn [d] (let [v (:defns d)]
  v)) derived))
   by-name (group-by (fn [e] (:name e)) defns)
   mentions (vec (mapcat (fn [d] (let [v (:mentions d)]
  v)) derived))
   resolve-call (fn [caller-key callname] (let [cands (get by-name callname [])
   cfile (first caller-key)
   same (filterv (fn [d] (= (first (:key d)) cfile)) cands)]
  (cond
  (seq same) (:key (first same))
  (= 1 (count cands)) (:key (first (vec cands)))
  :else nil)))
   edges (distinct (vec (keep (fn [m] (let [mv (let [x m]
  x)
   ck (let [x (nth mv 0)]
  x)
   nm (let [x (nth mv 1)]
  x)
   callee (resolve-call ck nm)]
  (if (and (some? callee) (not= ck callee)) (do
  [ck callee])))) mentions)))]
  (->CallGraph defns by-name edges)))

(defn ^BlastResult blast-radius [edges]
  (let [ctx (c/new-store)
   tx (c/begin-tx! ctx "code")
   EDGE (c/value! ctx "calls-defn")
   k->id (atom {})
   ent (fn [k] (let [m (deref k->id)
   existing (get m k)]
  (if (some? existing) (let [e existing]
  e) (let [e (c/entity! ctx)]
  (swap! k->id (fn [m2] (assoc m2 k e)))
  e))))
   _ (doseq [edge edges]
  (let [ev (let [x edge]
  x)
   a (nth ev 0)
   b (nth ev 1)]
  (c/claim! ctx (ent a) EDGE (ent b) tx)))
   id->k (into {} (map (fn [kv] (let [pair (let [x kv]
  x)]
  [(nth pair 1) (nth pair 0)])) (deref k->id)))
   db (d/run-rules ctx [(d/rule "reaches" [(d/v :x) (d/v :y)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)])]) (d/rule "reaches" [(d/v :x) (d/v :z)] [(d/lit "triple" [(d/v :x) EDGE (d/v :y)]) (d/lit "reaches" [(d/v :y) (d/v :z)])])])
   reaches (set (d/facts db "reaches"))
   blast (reduce (fn [m pair] (let [pv (let [x pair]
  x)
   xid (nth pv 0)
   yid (nth pv 1)
   ykey (get id->k yid)
   xkey (get id->k xid)]
  (assoc m ykey (conj (get m ykey #{}) xkey)))) {} reaches)]
  (->BlastResult blast reaches)))
