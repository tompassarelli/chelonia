(ns fram.export
  (:require [fram.kernel :as k]
            [clojure.string :as str]))

(def order ["title" "owner" "lead" "driver" "source" "proposed_by" "created_by" "created_at" "updated_at" "committed" "do_on" "valid_until" "estimate_hours" "repo" "part_of" "depends_on" "relates_to" "clarifies" "amends" "outcome" "abandoned"])

(def ref-preds ["depends_on" "part_of" "relates_to" "clarifies" "amends" "created_by" "lead" "driver" "proposed_by"])

(defn- distinct-s [xs]
  (reduce (fn [acc x] (if (k/vec-contains? acc x) acc (conj acc x))) [] xs))

(defn- ^String render-obj [^String p ^String v]
  (if (k/vec-contains? ref-preds p) v (if (or (str/blank? v) (str/includes? v " ") (str/includes? v "\t") (str/includes? v "\n") (str/includes? v "\r") (str/starts-with? v "@") (str/starts-with? v "\"")) (pr-str v) v)))

(defn ^String thread-md [claims ^String te]
  (let [present (distinct-s (mapv (fn [c] (:p c)) (k/q-by-l claims te)))
   ordered (filterv (fn [p] (k/vec-contains? present p)) order)
   extra (vec (sort (filterv (fn [p] (and (not (k/vec-contains? order p)) (not (= p "body")))) present)))
   preds (vec (concat ordered extra))
   lines (reduce (fn [acc p] (vec (concat acc (mapv (fn [v] (str p "  " (render-obj p v))) (k/many claims te p))))) [] preds)
   b (k/one claims te "body")
   body (if (some? b) b "")]
  (str te "\n" (str/join "\n" lines) "\n---\n" body)))
