(ns chelonia.datalog
  (:require [chelonia.cnf :as c]))

(defn v [name]
  {:var name})

(defn lit [rel args]
  {:rel rel :args args :neg false})

(defn nlit [rel args]
  {:rel rel :args args :neg true})

(defn rule [hrel hargs body]
  {:head {:rel hrel :args hargs} :body body})

(defn- ^Boolean var? [t]
  (and (map? t) (contains? t :var)))

(defn edb [ctx]
  (reduce (fn [db cid] (let [cl (c/claim-of ctx cid)
   l (:l cl)
   p (:p cl)
   r (:r cl)
   db1 (update db "triple" (fn [s] (conj (or s #{}) [l p r])))]
  (update db1 "claim" (fn [s] (conj (or s #{}) [cid l p r]))))) {} (c/current-claims ctx)))

(defn- unify [term val subst]
  (if (var? term) (let [n (:var term)]
  (if (contains? subst n) (if (= (get subst n) val) subst nil) (assoc subst n val))) (if (= term val) subst nil)))

(defn- unify-args [args tuple subst]
  (if (not (= (count args) (count tuple))) nil (loop [a args
   t tuple
   s subst]
  (cond
  (nil? s) nil
  (empty? a) s
  :else (recur (rest a) (rest t) (unify (first a) (first t) s))))))

(defn- ground [args subst]
  (mapv (fn [t] (if (var? t) (get subst (:var t)) t)) args))

(defn- match-lit [db litt subst]
  (if (:neg litt) (let [g (ground (:args litt) subst)]
  (if (contains? (get db (:rel litt) #{}) g) [] [subst])) (let [tuples (vec (get db (:rel litt) #{}))
   args (:args litt)]
  (filterv (fn [s] (some? s)) (mapv (fn [tup] (unify-args args tup subst)) tuples)))))

(defn- eval-body [db body subst]
  (reduce (fn [substs litt] (reduce (fn [acc s] (vec (concat acc (match-lit db litt s)))) [] substs)) [subst] body))

(defn- derive-rule [db r]
  (let [head (:head r)]
  (reduce (fn [acc s] (conj acc (ground (:args head) s))) #{} (eval-body db (:body r) {}))))

(defn fixpoint [db0 rules]
  (loop [db db0]
  (let [db2 (reduce (fn [d r] (let [rel (:rel (:head r))
   heads (derive-rule db r)]
  (update d rel (fn [s] (reduce (fn [acc h] (conj acc h)) (or s #{}) heads))))) db rules)]
  (if (= db2 db) db (recur db2)))))

(defn run-rules [ctx rules]
  (fixpoint (edb ctx) rules))

(defn run-strata [ctx strata]
  (reduce (fn [db stratum] (fixpoint db stratum)) (edb ctx) strata))

(defn- neg-lits [stratum]
  (reduce (fn [acc r] (vec (concat acc (filterv (fn [l] (:neg l)) (:body r))))) [] stratum))

(defn strata-violations [strata]
  (loop [i 0
   lower #{}
   probs []]
  (if (>= i (count strata)) probs (let [stratum (nth strata i)
   this-rels (reduce (fn [acc r] (conj acc (:rel (:head r)))) #{} stratum)
   bad (filterv (fn [nl] (not (or (= "triple" (:rel nl)) (or (= "claim" (:rel nl)) (contains? lower (:rel nl)))))) (neg-lits stratum))
   probs2 (vec (concat probs (mapv (fn [nl] (str "stratum " i ": negated '" (:rel nl) "' is not EDB or a lower stratum")) bad)))]
  (recur (+ i 1) (reduce (fn [acc rel] (conj acc rel)) lower this-rels) probs2)))))

(defn facts [db rel]
  (vec (get db rel #{})))
