(ns fram.kernel
  (:require [clojure.string :as str]))

(def terminal-preds (let [env (System/getenv "FRAM_TERMINAL_PREDS")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["outcome" "abandoned"])))

(def withdrawn-preds (let [env (System/getenv "FRAM_WITHDRAWN_PREDS")]
  (if (and (some? env) (not (= env ""))) (vec (str/split env #"\s+")) ["abandoned"])))

(defn ^Boolean vec-contains? [xs ^String s]
  (loop [r xs]
  (if (empty? r) false (if (= (first r) s) true (recur (rest r))))))

(defn- ^String sorted-join [xs]
  (str/join "," (vec (sort xs))))

(defn ^String vocab-fingerprint []
  (str "terminal=" (sorted-join terminal-preds) " |withdrawn=" (sorted-join withdrawn-preds)))

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

(defn ^String cardinality-of [claims ^String p]
  (let [c (one claims p "cardinality")]
  (if (some? c) c "multi")))

(defn ^Boolean single-from-claims? [claims ^String p]
  (= "single" (cardinality-of claims p)))

(defn- ^Boolean any-of? [claims ^String te preds]
  (loop [ps preds]
  (if (empty? ps) false (if (some? (one claims te (first ps))) true (recur (rest ps))))))

(defn ^Boolean terminal? [claims ^String te]
  (any-of? claims te terminal-preds))

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
  (if (single-from-claims? claims (:p c)) (conj (drop-lp claims (:l c) (:p c)) c) (if (has-claim? claims c) claims (conj claims c))))

(defn apply-retract [claims ^Claim c]
  (if (single-from-claims? claims (:p c)) (drop-lp claims (:l c) (:p c)) (filterv (fn [x] (not (claim-eq? x c))) claims)))

(defn ^Boolean reachable-from? [succ frontier ^String target]
  (loop [front frontier
   seen #{}]
  (cond
  (empty? front) false
  (= (first front) target) true
  (contains? seen (first front)) (recur (vec (rest front)) seen)
  :else (recur (vec (concat (rest front) (succ (first front)))) (conj seen (first front))))))

(defn ^Boolean cycle? [claims ^String pred ^String te]
  (let [succ (fn [x] (if (= pred "part_of") (let [pp (one claims x "part_of")]
  (if (some? pp) [pp] [])) (many claims x "depends_on")))]
  (reachable-from? succ (succ te) te)))

(defn violations [claims ^String te]
  (let [ids (thread-ids claims)
   v2 (reduce (fn [acc d] (let [a (if (not (vec-contains? ids d)) (conj acc (str "depends_on references missing entity " d)) acc)]
  (if (and (not (terminal? claims te)) (any-of? claims d withdrawn-preds)) (conj a (str "depends_on points at abandoned " d)) a))) [] (many claims te "depends_on"))
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

(defn ^Boolean anchor-i? [^Index idx ^String te]
  (= (one-i idx te "source") "migrated"))

(defn work-thread-ids-i [^Index idx]
  (filterv (fn [s] (not (anchor-i? idx s))) (thread-ids-i idx)))

(defn- ^Boolean any-of-i? [^Index idx ^String te preds]
  (loop [ps preds]
  (if (empty? ps) false (if (some? (one-i idx te (first ps))) true (recur (rest ps))))))

(defn ^Boolean terminal-i? [^Index idx ^String te]
  (any-of-i? idx te terminal-preds))

(defn dependents-i [^Index idx ^String te]
  (get (:revdep idx) te []))

(defn ^Boolean cycle-i? [^Index idx ^String pred ^String te]
  (let [succ (fn [x] (if (= pred "part_of") (let [pp (one-i idx x "part_of")]
  (if (some? pp) [pp] [])) (many-i idx x "depends_on")))]
  (reachable-from? succ (succ te) te)))

(defn violations-i [^Index idx ^String te]
  (let [term? (terminal-i? idx te)
   v2 (reduce (fn [acc d] (let [a (if (nil? (one-i idx d "title")) (conj acc (str "depends_on references missing entity " d)) acc)]
  (if (and (not term?) (any-of-i? idx d withdrawn-preds)) (conj a (str "depends_on points at abandoned " d)) a))) [] (many-i idx te "depends_on"))
   pa (one-i idx te "part_of")
   v3 (if (and (some? pa) (nil? (one-i idx pa "title"))) (conj v2 (str "part_of references missing entity " pa)) v2)
   v5 (if (cycle-i? idx "depends_on" te) (conj v3 "depends_on cycle") v3)
   v6 (if (cycle-i? idx "part_of" te) (conj v5 "part_of cycle") v5)
   v7 (reduce (fn [acc p] (reduce (fn [a rt] (if (nil? (one-i idx rt "title")) (conj a (str p " references missing entity " rt)) a)) acc (many-i idx te p))) v6 ["relates_to" "clarifies" "amends"])]
  v7))
