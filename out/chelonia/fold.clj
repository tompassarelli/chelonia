(ns chelonia.fold
  (:require [chelonia.kernel :as k]))

(defrecord Assertion [tx op l p r])

(defn assertion-tx [r] (:tx r))

(defn assertion-op [r] (:op r))

(defn assertion-l [r] (:l r))

(defn assertion-p [r] (:p r))

(defn assertion-r [r] (:r r))

(defrecord Fold [claims version])

(defn fold-claims [r] (:claims r))

(defn fold-version [r] (:version r))

(defn- max-tx [as]
  (reduce (fn [m a] (if (> (:tx a) m) (:tx a) m)) 0 as))

(defrecord Latest [tx op l p r])

(defn latest-tx [r] (:tx r))

(defn latest-op [r] (:op r))

(defn latest-l [r] (:l r))

(defn latest-p [r] (:p r))

(defn latest-r [r] (:r r))

(defn- ^String key-of [^Assertion a]
  (if (k/single? (:p a)) (str (:l a) "\u0001" (:p a)) (str (:l a) "\u0001" (:p a) "\u0001" (:r a))))

(defn ^Fold fold [asserts]
  (let [keyed (reduce (fn [m a] (let [k (key-of a)
   prev (get m k)]
  (if (and (some? prev) (> (:tx prev) (:tx a))) m (assoc m k (->Latest (:tx a) (:op a) (:l a) (:p a) (:r a)))))) {} asserts)
   claims (reduce (fn [acc v] (if (= (:op v) "assert") (conj acc (k/->Claim (:l v) (:p v) (:r v))) acc)) [] (vec (vals keyed)))]
  (->Fold claims (max-tx asserts))))
