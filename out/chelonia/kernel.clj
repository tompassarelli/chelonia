(ns chelonia.kernel
  (:require [clojure.string :as str]))

(def single-valued ["title" "state" "owner" "lead" "driver" "source" "part_of" "do_on" "valid_until" "estimate_hours" "created_at" "updated_at" "name" "body" "created_by" "slug"])

(def valid-states ["draft" "ready" "active" "done" "canceled"])

(def terminal-states ["done" "canceled"])

(defn ^Boolean vec-contains? [xs ^String s]
  (loop [r xs]
  (if (empty? r) false (if (= (first r) s) true (recur (rest r))))))

(defn ^Boolean single? [^String p]
  (vec-contains? single-valued p))

(defrecord Claim [l p r])

(defn claim-l [r] (:l r))

(defn claim-p [r] (:p r))

(defn claim-r [r] (:r r))

(defn ^Boolean claim-eq? [^Claim a ^Claim b]
  (and (= (:l a) (:l b)) (= (:p a) (:p b)) (= (:r a) (:r b))))

(defn q-lp [claims ^String l ^String p]
  (filterv (fn [c] (and (= (:l c) l) (= (:p c) p))) claims))

(defn q-by-l [claims ^String l]
  (filterv (fn [c] (= (:l c) l)) claims))

(defn one [claims ^String l ^String p]
  (let [hits (q-lp claims l p)]
  (if (empty? hits) nil (:r (first hits)))))

(defn many [claims ^String l ^String p]
  (mapv (fn [c] (:r c)) (q-lp claims l p)))

(defn ^Boolean terminal? [claims ^String te]
  (let [st (one claims te "state")]
  (if (some? st) (vec-contains? terminal-states st) false)))

(defn- uniq [xs]
  (reduce (fn [acc x] (if (vec-contains? acc x) acc (conj acc x))) [] xs))

(defn thread-ids [claims]
  (uniq (filterv (fn [s] (str/starts-with? s "thread:")) (mapv (fn [c] (:l c)) claims))))

(defn- drop-lp [claims ^String l ^String p]
  (filterv (fn [x] (not (and (= (:l x) l) (= (:p x) p)))) claims))

(defn- ^Boolean has-claim? [claims ^Claim c]
  (loop [r claims]
  (if (empty? r) false (if (claim-eq? (first r) c) true (recur (rest r))))))

(defn apply-assert [claims ^Claim c]
  (if (single? (:p c)) (conj (drop-lp claims (:l c) (:p c)) c) (if (has-claim? claims c) claims (conj claims c))))

(defn apply-retract [claims ^Claim c]
  (if (single? (:p c)) (drop-lp claims (:l c) (:p c)) (filterv (fn [x] (not (claim-eq? x c))) claims)))

(defn ^Boolean cycle? [claims ^String pred ^String te]
  (let [succ (fn [x] (if (= pred "part_of") (let [pp (one claims x "part_of")]
  (if (some? pp) [pp] [])) (many claims x "depends_on")))]
  (loop [front (succ te)
   seen []]
  (cond
  (empty? front) false
  (= (first front) te) true
  (vec-contains? seen (first front)) (recur (vec (rest front)) seen)
  :else (recur (vec (concat (rest front) (succ (first front)))) (conj seen (first front)))))))

(defn violations [claims ^String te]
  (let [st (one claims te "state")
   ids (thread-ids claims)
   v1 (if (and (some? st) (not (vec-contains? valid-states st))) [(str "invalid state '" st "'")] [])
   v2 (reduce (fn [acc d] (let [a (if (not (vec-contains? ids d)) (conj acc (str "depends_on references missing entity " d)) acc)]
  (if (and (not (terminal? claims te)) (= "canceled" (one claims d "state"))) (conj a (str "depends_on points at canceled " d)) a))) v1 (many claims te "depends_on"))
   pa (one claims te "part_of")
   v3 (if (and (some? pa) (not (vec-contains? ids pa))) (conj v2 (str "part_of references missing entity " pa)) v2)
   v4 (if (and (= st "active") (nil? (one claims te "driver"))) (conj v3 "active thread has no driver") v3)
   v5 (if (cycle? claims "depends_on" te) (conj v4 "depends_on cycle") v4)
   v6 (if (cycle? claims "part_of" te) (conj v5 "part_of cycle") v5)]
  v6))

(defrecord Index [single bypred subjects revdep])

(defn index-single [r] (:single r))

(defn index-bypred [r] (:bypred r))

(defn index-subjects [r] (:subjects r))

(defn index-revdep [r] (:revdep r))

(defn ^Index build-index [claims]
  (let [single (reduce (fn [m c] (assoc m (str (:l c) "\u0001" (:p c)) (:r c))) {} claims)
   bypred (reduce (fn [m c] (let [kk (str (:l c) "\u0001" (:p c))]
  (assoc m kk (conj (get m kk []) (:r c))))) {} claims)
   subjects (uniq (mapv (fn [c] (:l c)) claims))
   revdep (reduce (fn [m c] (if (= (:p c) "depends_on") (assoc m (:r c) (conj (get m (:r c) []) (:l c))) m)) {} claims)]
  (->Index single bypred subjects revdep)))

(defn one-i [^Index idx ^String l ^String p]
  (get (:single idx) (str l "\u0001" p)))

(defn many-i [^Index idx ^String l ^String p]
  (get (:bypred idx) (str l "\u0001" p) []))

(defn thread-ids-i [^Index idx]
  (filterv (fn [s] (str/starts-with? s "thread:")) (:subjects idx)))

(defn ^Boolean terminal-i? [^Index idx ^String te]
  (let [st (one-i idx te "state")]
  (if (some? st) (vec-contains? terminal-states st) false)))

(defn dependents-i [^Index idx ^String te]
  (get (:revdep idx) te []))
