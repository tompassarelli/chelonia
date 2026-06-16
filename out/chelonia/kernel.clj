(ns chelonia.kernel
  (:require [clojure.string :as str]))

(def single-valued ["title" "owner" "lead" "driver" "source" "part_of" "do_on" "valid_until" "estimate_hours" "created_at" "updated_at" "name" "body" "created_by" "committed" "outcome" "abandoned" "superseded_by" "merged_into" "session_of" "start_time" "end_time" "clockify_id"])

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
  (or (some? (one claims te "outcome")) (some? (one claims te "abandoned"))))

(defn- uniq [xs]
  (reduce (fn [acc x] (if (vec-contains? acc x) acc (conj acc x))) [] xs))

(defn thread-ids [claims]
  (filterv (fn [s] (some? (one claims s "title"))) (uniq (mapv (fn [c] (:l c)) claims))))

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
  (let [ids (thread-ids claims)
   v2 (reduce (fn [acc d] (let [a (if (not (vec-contains? ids d)) (conj acc (str "depends_on references missing entity " d)) acc)]
  (if (and (not (terminal? claims te)) (some? (one claims d "abandoned"))) (conj a (str "depends_on points at abandoned " d)) a))) [] (many claims te "depends_on"))
   pa (one claims te "part_of")
   v3 (if (and (some? pa) (not (vec-contains? ids pa))) (conj v2 (str "part_of references missing entity " pa)) v2)
   v5 (if (cycle? claims "depends_on" te) (conj v3 "depends_on cycle") v3)
   v6 (if (cycle? claims "part_of" te) (conj v5 "part_of cycle") v5)
   v7 (reduce (fn [acc p] (reduce (fn [a rt] (if (not (vec-contains? ids rt)) (conj a (str p " references missing entity " rt)) a)) acc (many claims te p))) v6 ["relates_to" "clarifies" "amends"])]
  v7))

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
  (filterv (fn [s] (some? (one-i idx s "title"))) (:subjects idx)))

(defn ^Boolean terminal-i? [^Index idx ^String te]
  (or (some? (one-i idx te "outcome")) (some? (one-i idx te "abandoned"))))

(defn dependents-i [^Index idx ^String te]
  (get (:revdep idx) te []))

(defn ^Boolean cycle-i? [^Index idx ^String pred ^String te]
  (let [succ (fn [x] (if (= pred "part_of") (let [pp (one-i idx x "part_of")]
  (if (some? pp) [pp] [])) (many-i idx x "depends_on")))]
  (loop [front (succ te)
   seen []]
  (cond
  (empty? front) false
  (= (first front) te) true
  (vec-contains? seen (first front)) (recur (vec (rest front)) seen)
  :else (recur (vec (concat (rest front) (succ (first front)))) (conj seen (first front)))))))

(defn violations-i [^Index idx ^String te]
  (let [term? (terminal-i? idx te)
   v2 (reduce (fn [acc d] (let [a (if (nil? (one-i idx d "title")) (conj acc (str "depends_on references missing entity " d)) acc)]
  (if (and (not term?) (some? (one-i idx d "abandoned"))) (conj a (str "depends_on points at abandoned " d)) a))) [] (many-i idx te "depends_on"))
   pa (one-i idx te "part_of")
   v3 (if (and (some? pa) (nil? (one-i idx pa "title"))) (conj v2 (str "part_of references missing entity " pa)) v2)
   v5 (if (cycle-i? idx "depends_on" te) (conj v3 "depends_on cycle") v3)
   v6 (if (cycle-i? idx "part_of" te) (conj v5 "part_of cycle") v5)
   v7 (reduce (fn [acc p] (reduce (fn [a rt] (if (nil? (one-i idx rt "title")) (conj a (str p " references missing entity " rt)) a)) acc (many-i idx te p))) v6 ["relates_to" "clarifies" "amends"])]
  v7))
