(ns fram.import
  (:require [fram.kernel :as k]
            [fram.fold :as fold]
            [clojure.string :as str]
            [fram.rt :as rt]))

(defrecord Doc [head body])

(defn doc-head [r] (:head r))

(defn doc-body [r] (:body r))

(defn- ^Doc split-doc [^String content]
  (let [lines (fram.rt/split-on content "\n")
   n (count lines)]
  (loop [i 0]
  (cond
  (>= i n) (->Doc content "")
  (= "---" (str/trim (nth lines i))) (->Doc (str/join "\n" (subvec (vec lines) 0 i)) (str/join "\n" (subvec (vec lines) (+ i 1) n)))
  :else (recur (+ i 1))))))

(defn- ^String parse-obj [^String tok]
  (cond
  (str/starts-with? tok "@") tok
  (str/starts-with? tok "\"") (fram.rt/edn-unquote tok)
  :else tok))

(defn- warn [^String msg]
  (binding [*out* *err*] (println (str "WARN import: " msg))))

(defn- file->claims [^String path ^String content]
  (let [doc (split-doc content)
   lines (fram.rt/split-on (:head doc) "\n")
   n (count lines)
   si (loop [i 0]
  (cond
  (>= i n) (- 0 1)
  (str/starts-with? (str/trim (nth lines i)) "@") i
  :else (recur (+ i 1))))]
  (if (< si 0) (do
  (if (str/blank? (:head doc)) nil (warn (str path " — no @subject line found in head; dropping " n " head line(s) (a corrupted/hand-edited first line, or a stray BOM/whitespace before @?)")))
  []) (let [subj (str/trim (nth lines si))
   claims (loop [i (+ si 1)
   acc []]
  (if (>= i n) acc (let [t (str/trim (nth lines i))]
  (if (str/blank? t) (recur (+ i 1) acc) (let [kv (fram.rt/split-kv t)]
  (recur (+ i 1) (conj acc (k/->Claim subj (nth kv 0) (parse-obj (nth kv 1))))))))))
   body (:body doc)]
  (if (str/blank? body) claims (conj claims (k/->Claim subj "body" body)))))))

(defn- number-assertions [claims]
  (loop [cs claims
   i 1
   acc []]
  (if (empty? cs) acc (let [c (first cs)]
  (recur (rest cs) (+ i 1) (conj acc (fold/->Assertion i "assert" (:l c) (:p c) (:r c) "import")))))))

(defn- safe-file->claims [^String path]
  (try
  (file->claims path (fram.rt/slurp path))
  (catch Exception e
    (warn (str path " — skipped (could not parse): " (.getMessage e)))
    [])))

(defn load-corpus [^String threads-dir]
  (let [files (fram.rt/list-md threads-dir)
   claims (reduce (fn [acc path] (vec (concat acc (safe-file->claims path)))) [] files)]
  (number-assertions claims)))
