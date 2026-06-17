(ns fram.datalog
  (:require [fram.cnf :as c]))

(defn v [^String name]
  {:var name})

(defn lit [^String rel args]
  {:rel rel :args args :neg false})

(defn nlit [^String rel args]
  {:rel rel :args args :neg true})

(defn rule [^String hrel hargs body]
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

(defn- positive-positions [body]
  (loop [i 0
   ls body
   acc []]
  (if (empty? ls) acc (recur (+ i 1) (rest ls) (if (:neg (first ls)) acc (conj acc i))))))

(defn- eval-body-pinned [db delta body pin]
  (loop [i 0
   ls body
   substs [{}]]
  (if (empty? ls) substs (let [litt (first ls)
   src (if (:neg litt) db (if (= i pin) delta db))
   substs2 (reduce (fn [acc s] (vec (concat acc (match-lit src litt s)))) [] substs)]
  (recur (+ i 1) (rest ls) substs2)))))

(defn- derive-rule-delta [db delta r]
  (let [head (:head r)
   body (:body r)]
  (reduce (fn [acc pin] (reduce (fn [a s] (conj a (ground (:args head) s))) acc (eval-body-pinned db delta body pin))) #{} (positive-positions body))))

(defn- rule-head-rels [rules]
  (vec (reduce (fn [acc r] (conj acc (:rel (:head r)))) #{} rules)))

(defn- new-only [cand db rels]
  (reduce (fn [acc rel] (let [n (reduce (fn [s t] (if (contains? (get db rel #{}) t) s (conj s t))) #{} (get cand rel #{}))]
  (if (empty? n) acc (assoc acc rel n)))) {} rels))

(defn- db-merge [db delta rels]
  (reduce (fn [acc rel] (let [d (get delta rel #{})]
  (if (empty? d) acc (update acc rel (fn [s] (reduce (fn [a t] (conj a t)) (or s #{}) d)))))) db rels))

(defn- ^Boolean delta-empty? [delta rels]
  (loop [rs rels]
  (if (empty? rs) true (if (empty? (get delta (first rs) #{})) (recur (rest rs)) false))))

(defn- snr-round [db delta rules rels]
  (let [cand (reduce (fn [acc r] (let [rel (:rel (:head r))
   hs (derive-rule-delta db delta r)]
  (update acc rel (fn [s] (reduce (fn [a h] (conj a h)) (or s #{}) hs))))) {} rules)]
  (new-only cand db rels)))

(defn fixpoint [db0 rules]
  (let [rels (rule-head-rels rules)
   seeded (reduce (fn [d r] (let [rel (:rel (:head r))
   heads (derive-rule db0 r)]
  (update d rel (fn [s] (reduce (fn [a h] (conj a h)) (or s #{}) heads))))) db0 rules)
   delta0 (new-only seeded db0 rels)]
  (loop [db seeded
   delta delta0]
  (if (delta-empty? delta rels) db (let [nw (snr-round db delta rules rels)]
  (recur (db-merge db nw rels) nw))))))

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
   bad-fwd (filterv (fn [nl] (not (or (= "triple" (:rel nl)) (or (= "claim" (:rel nl)) (contains? lower (:rel nl)))))) (neg-lits stratum))
   bad-self (filterv (fn [nl] (contains? this-rels (:rel nl))) (neg-lits stratum))
   probs2 (vec (concat probs (concat (mapv (fn [nl] (str "stratum " i ": negated '" (:rel nl) "' is not EDB or a lower stratum")) bad-fwd) (mapv (fn [nl] (str "stratum " i ": negated '" (:rel nl) "' is also derived in the SAME stratum (recursion through negation — not stratifiable)")) bad-self))))]
  (recur (+ i 1) (reduce (fn [acc rel] (conj acc rel)) lower this-rels) probs2)))))

(defn facts [db ^String rel]
  (vec (get db rel #{})))
