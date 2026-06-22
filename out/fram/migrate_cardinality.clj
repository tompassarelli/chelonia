(ns fram.migrate-cardinality
  (:require [fram.fold :as fold]
            [fram.kernel :as k]
            [clojure.string :as str]
            [fram.rt :as rt]))

(def single-vocab ["name" "cardinality" "value_kind" "cnf-supersedes" "title" "owner" "lead" "driver" "source" "part_of" "do_on" "valid_until" "estimate_hours" "created_at" "updated_at" "body" "created_by" "committed" "outcome" "abandoned" "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"])

(defn- ^Boolean vec-has? [xs ^String s]
  (loop [r xs]
  (if (empty? r) false (if (= (first r) s) true (recur (rest r))))))

(defn ^Boolean single-pred? [^String p]
  (or (vec-has? single-vocab p) (str/starts-with? p "emoji_")))

(defn- distinct-preds [asserts]
  (reduce (fn [acc a] (let [p (:p a)]
  (if (vec-has? acc p) acc (conj acc p)))) [] asserts))

(defn cardinality-assertions [asserts]
  (let [singles (filterv single-pred? (distinct-preds asserts))]
  (mapv (fn [p] (fold/->Assertion 0 "assert" p "cardinality" "single" "migrate")) singles)))

(defn cardinality-of-claims [claims]
  (reduce (fn [m c] (if (= (:p c) "cardinality") (assoc m (:l c) (:r c)) m)) {} claims))

(defn migrate-log! [^String log]
  (let [as (fram.rt/read-log log)
   cards (cardinality-assertions as)
   maxtx (fold/max-tx as)]
  (reduce (fn [i c] (fram.rt/append-assertion log (fold/->Assertion (+ maxtx (+ i 1)) "assert" (:l c) (:p c) (:r c) "migrate"))
  (+ i 1)) 0 cards)))
