(ns fram.fold
  (:require [fram.kernel :as k]))

(defrecord Assertion [tx op l p r frame])

(defn assertion-tx [r] (:tx r))

(defn assertion-op [r] (:op r))

(defn assertion-l [r] (:l r))

(defn assertion-p [r] (:p r))

(defn assertion-r [r] (:r r))

(defn assertion-frame [r] (:frame r))

(defrecord Fold [claims version])

(defn fold-claims [r] (:claims r))

(defn fold-version [r] (:version r))

(defn- tx-of [^Assertion a]
  (let [t (:tx a)]
  (if (int? t) t 0)))

(defn- max-tx [as]
  (reduce (fn [m a] (let [t (tx-of a)]
  (if (> t m) t m))) 0 as))

(defrecord Latest [tx op l p r frame])

(defn latest-tx [r] (:tx r))

(defn latest-op [r] (:op r))

(defn latest-l [r] (:l r))

(defn latest-p [r] (:p r))

(defn latest-r [r] (:r r))

(defn latest-frame [r] (:frame r))

(defn- ^String key-of [^Assertion a]
  (if (k/single? (:p a)) (str (:l a) "\u0001" (:p a)) (str (:l a) "\u0001" (:p a) "\u0001" (:r a))))

(defn- keyed-latest [asserts]
  (reduce (fn [m a] (let [k (key-of a)
   prev (get m k)
   atx (tx-of a)]
  (if (and (some? prev) (> (:tx prev) atx)) m (assoc m k (->Latest atx (:op a) (:l a) (:p a) (:r a) (:frame a)))))) {} asserts))

(defn ^Fold fold [asserts]
  (let [valid (filterv (fn [a] (and (some? (:l a)) (and (some? (:p a)) (some? (:r a))))) asserts)
   keyed (keyed-latest valid)
   claims (reduce (fn [acc v] (if (= (:op v) "assert") (conj acc (k/->Claim (:l v) (:p v) (:r v))) acc)) [] (vec (vals keyed)))]
  (->Fold claims (max-tx asserts))))

(defn fold-latest [asserts]
  (filterv (fn [v] (= (:op v) "assert")) (vec (vals (keyed-latest asserts)))))
