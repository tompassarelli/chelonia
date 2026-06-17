(ns fram.query
  (:require [fram.kernel :as k]
            [fram.datalog :as d]
            [clojure.string :as str]))

(defn claims->edb [claims]
  (loop [cs claims
   i 0
   triple #{}
   claim #{}]
  (if (empty? cs) {"triple" triple "claim" claim} (let [c (first cs)]
  (recur (rest cs) (+ i 1) (conj triple [(:l c) (:p c) (:r c)]) (conj claim [(str "c" i) (:l c) (:p c) (:r c)]))))))

(defn- ^Boolean term-ok? [t]
  (if (map? t) (and (contains? t :var) (string? (:var t))) (or (string? t) (number? t))))

(defn- vars-of [args]
  (reduce (fn [acc t] (if (and (map? t) (contains? t :var)) (conj acc (:var t)) acc)) #{} args))

(defn- positive-body-vars [body]
  (reduce (fn [acc litt] (if (and (map? litt) (not (:neg litt)) (vector? (:args litt))) (reduce (fn [a v] (conj a v)) acc (vec (vars-of (:args litt)))) acc)) #{} body))

(defn- ^Boolean all-vectors? [xs]
  (loop [ys xs]
  (if (empty? ys) true (if (vector? (first ys)) (recur (rest ys)) false))))

(defn- all-rules [q]
  (if (contains? q :strata) (reduce (fn [acc s] (vec (concat acc s))) [] (:strata q)) (let [rs (:rules q)]
  (if (some? rs) rs []))))

(defn- strata-of [q]
  (if (contains? q :strata) (:strata q) (let [rs (:rules q)]
  [(if (some? rs) rs [])])))

(defn- head-rels [rules]
  (reduce (fn [acc r] (if (and (map? r) (map? (:head r))) (conj acc (:rel (:head r))) acc)) #{} rules))

(defn- lit-errors [litt known bound]
  (if (not (map? litt)) ["body literal must be a map {:rel r :args [...] :neg? bool}"] (let [rel (:rel litt)
   args (:args litt)
   e1 (if (string? rel) [] [(str "literal :rel must be a string, got " (str rel))])
   e2 (if (vector? args) [] ["literal :args must be a vector"])
   e3 (if (and (string? rel) (not (contains? known rel))) [(str "unknown relation '" rel "' — use triple, claim, or a :head rel you define")] [])
   e4 (if (and (= rel "triple") (vector? args) (not (= (count args) 3))) ["relation triple takes 3 args (l p r)"] [])
   e5 (if (and (= rel "claim") (vector? args) (not (= (count args) 4))) ["relation claim takes 4 args (cid l p r)"] [])
   en (if (and (contains? litt :neg) (not (= (:neg litt) true)) (not (= (:neg litt) false))) ["literal :neg must be true or false"] [])
   e6 (if (vector? args) (reduce (fn [acc t] (if (term-ok? t) acc (conj acc (str "bad term " (str t) " — use {:var \"n\"} or a constant")))) [] args) [])
   e7 (if (and (= (:neg litt) true) (vector? args)) (reduce (fn [acc v] (if (contains? bound v) acc (conj acc (str "negated var '" (str v) "' must be bound by an earlier positive literal")))) [] (vec (vars-of args))) [])]
  (vec (concat e1 (concat e2 (concat e3 (concat e4 (concat e5 (concat en (concat e6 e7)))))))))))

(defn- body-errors [body known]
  (loop [ls body
   bound #{}
   errs []]
  (if (empty? ls) errs (let [litt (first ls)
   le (lit-errors litt known bound)
   bound2 (if (and (map? litt) (not (:neg litt)) (vector? (:args litt))) (reduce (fn [acc v] (conj acc v)) bound (vec (vars-of (:args litt)))) bound)]
  (recur (rest ls) bound2 (vec (concat errs le)))))))

(defn- rule-errors [r known]
  (if (not (map? r)) ["rule must be a map {:head {...} :body [...]}"] (let [head (:head r)
   body (:body r)
   head-ok (and (map? head) (string? (:rel head)) (vector? (:args head)))
   eh (if head-ok [] ["rule :head must be {:rel <string> :args [terms]}"])
   ehrel (if (and head-ok (or (= (:rel head) "triple") (= (:rel head) "claim"))) ["rule :head :rel cannot be a base relation (triple/claim)"] [])
   ehargs (if head-ok (reduce (fn [acc t] (if (term-ok? t) acc (conj acc (str "bad head term " (str t) " — use {:var \"n\"} or a constant")))) [] (:args head)) [])
   eb (if (vector? body) (body-errors body known) ["rule :body must be a vector of literals"])
   ehsafe (if (and head-ok (vector? body)) (let [bound (positive-body-vars body)]
  (reduce (fn [acc v] (if (contains? bound v) acc (conj acc (str "head var '" (str v) "' is not bound by a positive body literal")))) [] (vec (vars-of (:args head))))) [])]
  (vec (concat eh (concat ehrel (concat ehargs (concat eb ehsafe))))))))

(defn validate [q]
  (if (not (map? q)) ["query must be a map: {:find <rel> :rules [<rule>...]} (or :strata [[...]...])"] (if (and (contains? q :rules) (not (vector? (:rules q)))) [":rules must be a vector of rules"] (if (and (contains? q :strata) (not (and (vector? (:strata q)) (all-vectors? (:strata q))))) [":strata must be a vector of strata, each a vector of rules"] (let [rules (all-rules q)
   strata (strata-of q)
   derived (head-rels rules)
   known (conj (conj derived "triple") "claim")
   find (:find q)
   ef (if (string? find) (if (contains? known find) [] [(str "unknown :find relation '" find "' — name a :head rel you define")]) [":find must be a relation name (string)"])
   er (if (empty? rules) ["provide at least one rule in :rules or :strata"] [])
   erules (reduce (fn [acc r] (vec (concat acc (rule-errors r known)))) [] rules)
   esv (d/strata-violations strata)]
  (vec (concat ef (concat er (concat erules esv)))))))))

(defn run [claims q]
  (let [errs (validate q)]
  (if (not (empty? errs)) {:error errs} (let [edb (claims->edb claims)
   strata (strata-of q)
   db (reduce (fn [acc stratum] (d/fixpoint acc stratum)) edb strata)]
  {:ok (d/facts db (:find q))}))))
