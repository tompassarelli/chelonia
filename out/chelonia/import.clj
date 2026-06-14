(ns chelonia.import
  (:require [chelonia.kernel :as k]
            [chelonia.fold :as fold]
            [clojure.string :as str]
            [chelonia.rt :as rt]))

(defn- ^String unquote-scalar [^String v]
  (let [n (count v)]
  (cond
  (and (>= n 2) (str/starts-with? v "\"") (str/ends-with? v "\"")) (str/replace (subs v 1 (- n 1)) "\\\"" "\"")
  (and (>= n 2) (str/starts-with? v "'") (str/ends-with? v "'")) (str/replace (subs v 1 (- n 1)) "''" "'")
  :else v)))

(defn- ^String strip-comment [^String s]
  (let [i (chelonia.rt/str-index-of s " #")]
  (if (some? i) (str/trim (subs s 0 i)) s)))

(defn parse-flat-fm [^String fm]
  (let [lines (chelonia.rt/split-on fm "\n")]
  (loop [i 0
   acc {}]
  (if (>= i (count lines)) acc (let [line (nth lines i)]
  (cond
  (= (str/trim line) "") (recur (+ i 1) acc)
  (str/starts-with? line "  - ") (recur (+ i 1) acc)
  :else (let [colon (chelonia.rt/str-index-of line ":")]
  (if (nil? colon) (recur (+ i 1) acc) (let [kk (str/trim (subs line 0 colon))
   v (str/trim (subs line (+ colon 1)))]
  (cond
  (= v "") (let [items (loop [j (+ i 1)
   out []]
  (if (and (< j (count lines)) (str/starts-with? (nth lines j) "  - ")) (recur (+ j 1) (conj out (unquote-scalar (strip-comment (str/trim (subs (nth lines j) 4)))))) out))]
  (recur (+ i 1) (assoc acc kk items)))
  (and (str/starts-with? v "[") (str/ends-with? v "]")) (recur (+ i 1) (assoc acc kk (mapv (fn [s] (unquote-scalar s)) (chelonia.rt/split-comma (subs v 1 (- (count v) 1))))))
  :else (recur (+ i 1) (assoc acc kk (unquote-scalar v)))))))))))))

(defrecord Doc [fm body])

(defn doc-fm [r] (:fm r))

(defn doc-body [r] (:body r))

(defn split-doc [^String content]
  (let [lines (chelonia.rt/split-on content "\n")]
  (if (or (empty? lines) (not (= "---" (str/trim (first lines))))) nil (loop [j 1]
  (cond
  (>= j (count lines)) nil
  (= "---" (str/trim (nth lines j))) (->Doc (str/join "\n" (subvec (vec lines) 1 j)) (str/join "\n" (subvec (vec lines) (+ j 1) (count lines))))
  :else (recur (+ j 1)))))))

(defn- fm-opt [m ^String kk]
  (let [v (get m kk)]
  (if (string? v) v nil)))

(defn- fm-list [m ^String kk]
  (let [v (get m kk)]
  (cond
  (nil? v) []
  (string? v) [v]
  :else (vec v))))

(defn- prefixed [^String pfx v]
  (if (some? v) (str pfx v) nil))

(defn- add-scalar [acc ^String te ^String p r]
  (if (and (some? r) (not (str/blank? r))) (conj acc (k/->Claim te p r)) acc))

(defn- add-multi [acc ^String te ^String p ^String pfx xs]
  (reduce (fn [a x] (conj a (k/->Claim te p (str pfx x)))) acc xs))

(defn thread->claims [m ^String body ^String slug]
  (let [id (fm-opt m "id")]
  (if (nil? id) [] (let [te (str "thread:" id)
   c1 (add-scalar [] te "title" (fm-opt m "title"))
   c2 (add-scalar c1 te "state" (fm-opt m "state"))
   c3 (add-scalar c2 te "owner" (prefixed "owner:" (fm-opt m "owner")))
   c4 (add-scalar c3 te "lead" (prefixed "person:" (fm-opt m "lead")))
   c5 (add-scalar c4 te "driver" (prefixed "person:" (fm-opt m "driver")))
   c6 (add-scalar c5 te "source" (fm-opt m "source"))
   c7 (add-scalar c6 te "created_at" (fm-opt m "created_at"))
   c8 (add-scalar c7 te "updated_at" (fm-opt m "updated_at"))
   c9 (add-scalar c8 te "do_on" (fm-opt m "do_on"))
   c10 (add-scalar c9 te "valid_until" (fm-opt m "valid_until"))
   c11 (add-scalar c10 te "estimate_hours" (fm-opt m "estimate_hours"))
   c12 (add-scalar c11 te "part_of" (prefixed "thread:" (fm-opt m "part_of")))
   c13 (add-scalar c12 te "body" body)
   cby (add-scalar c13 te "created_by" (prefixed "person:" (fm-opt m "created_by")))
   csl (add-scalar cby te "slug" slug)
   c14 (add-multi csl te "depends_on" "thread:" (fm-list m "depends_on"))
   c15 (add-multi c14 te "tag" "tag:" (fm-list m "tags"))
   c16 (add-multi c15 te "repo" "repo:" (fm-list m "repo"))
   c17 (add-multi c16 te "proposed_by" "person:" (fm-list m "proposed_by"))
   c18 (add-scalar c17 te "superseded_by" (prefixed "thread:" (fm-opt m "superseded_by")))
   c19 (add-multi c18 te "clarifies" "thread:" (fm-list m "clarifies"))
   c20 (add-multi c19 te "amends" "thread:" (fm-list m "amends"))
   c21 (add-scalar c20 te "canceled_reason" (fm-opt m "canceled_reason"))]
  c21))))

(defn- person-name-claims [claims]
  (let [persons (loop [cs claims
   out []]
  (if (empty? cs) out (let [r (:r (first cs))]
  (if (and (str/starts-with? r "person:") (not (k/vec-contains? out r))) (recur (rest cs) (conj out r)) (recur (rest cs) out)))))]
  (mapv (fn [pe] (k/->Claim pe "name" (str/replace (subs pe 7) "_" " "))) persons)))

(defn- number-assertions [claims]
  (loop [cs claims
   i 1
   acc []]
  (if (empty? cs) acc (let [c (first cs)]
  (recur (rest cs) (+ i 1) (conj acc (fold/->Assertion i "assert" (:l c) (:p c) (:r c) "import")))))))

(defn load-corpus [^String threads-dir]
  (let [files (chelonia.rt/list-md threads-dir)
   thread-claims (reduce (fn [acc path] (let [doc (split-doc (chelonia.rt/slurp path))]
  (if (some? doc) (vec (concat acc (thread->claims (parse-flat-fm (:fm doc)) (:body doc) (chelonia.rt/file-slug path)))) acc))) [] files)
   all (vec (concat thread-claims (person-name-claims thread-claims)))]
  (number-assertions all)))
