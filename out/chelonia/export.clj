(ns chelonia.export
  (:require [chelonia.kernel :as k]
            [clojure.string :as str]))

(defn- ^Boolean indicator-first? [^String v]
  (let [c (subs v 0 1)]
  (k/vec-contains? ["-" "?" ":" "[" "]" "{" "}" "," "#" "&" "*" "!" "|" ">" "'" "\"" "%" "@" "`"] c)))

(defn- ^Boolean hazard? [^String v]
  (if (str/blank? v) true (or (str/includes? v ":") (str/includes? v "#") (str/includes? v "'") (str/includes? v "\"") (not (= v (str/trim v))) (indicator-first? v))))

(defn- ^String yaml-scalar [^String v]
  (if (hazard? v) (str "'" (str/replace v "'" "''") "'") v))

(defn- ^String strip-pfx [^String pfx ^String v]
  (if (str/starts-with? v pfx) (subs v (count pfx)) v))

(defn- scalar-line [idx ^String te ^String kk ^String pred ^String pfx]
  (let [v (k/one-i idx te pred)]
  (if (some? v) (str kk ": " (yaml-scalar (strip-pfx pfx v))) nil)))

(defn- multi-block [idx ^String te ^String kk ^String pred ^String pfx]
  (let [vs (k/many-i idx te pred)]
  (if (empty? vs) nil (str kk ":\n" (str/join "\n" (mapv (fn [v] (str "  - " (yaml-scalar (strip-pfx pfx v)))) vs))))))

(defn- add-line [acc s]
  (if (some? s) (conj acc s) acc))

(defn- ^String frontmatter [idx ^String te ^String id]
  (let [a0 [(str "id: " id)]
   a1 (add-line a0 (scalar-line idx te "title" "title" ""))
   a2 (add-line a1 (scalar-line idx te "state" "state" ""))
   a3 (add-line a2 (scalar-line idx te "owner" "owner" "owner:"))
   a4 (add-line a3 (scalar-line idx te "lead" "lead" "person:"))
   a5 (add-line a4 (scalar-line idx te "driver" "driver" "person:"))
   a6 (add-line a5 (scalar-line idx te "source" "source" ""))
   a7 (add-line a6 (multi-block idx te "proposed_by" "proposed_by" "person:"))
   a8 (add-line a7 (scalar-line idx te "created_by" "created_by" "person:"))
   a9 (add-line a8 (scalar-line idx te "created_at" "created_at" ""))
   a10 (add-line a9 (scalar-line idx te "updated_at" "updated_at" ""))
   a11 (add-line a10 (scalar-line idx te "do_on" "do_on" ""))
   a12 (add-line a11 (scalar-line idx te "valid_until" "valid_until" ""))
   a13 (add-line a12 (scalar-line idx te "estimate_hours" "estimate_hours" ""))
   a14 (add-line a13 (multi-block idx te "repo" "repo" "repo:"))
   a15 (add-line a14 (scalar-line idx te "part_of" "part_of" "thread:"))
   a16 (add-line a15 (multi-block idx te "depends_on" "depends_on" "thread:"))
   a17 (add-line a16 (scalar-line idx te "superseded_by" "superseded_by" "thread:"))
   a18 (add-line a17 (multi-block idx te "clarifies" "clarifies" "thread:"))
   a19 (add-line a18 (multi-block idx te "amends" "amends" "thread:"))
   a20 (add-line a19 (scalar-line idx te "canceled_reason" "canceled_reason" ""))
   a21 (add-line a20 (multi-block idx te "tags" "tag" "tag:"))]
  (str/join "\n" a21)))

(defn ^String thread-filename [idx ^String te]
  (let [id (subs te 7)
   s (k/one-i idx te "slug")]
  (if (some? s) (str id "-" s ".md") (str id ".md"))))

(defn ^String thread-md [idx ^String te]
  (let [id (subs te 7)
   fm (frontmatter idx te id)
   b (k/one-i idx te "body")
   body (if (some? b) b "")]
  (str "---\n" fm "\n---\n" body)))
