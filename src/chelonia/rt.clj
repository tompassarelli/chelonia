(ns chelonia.rt
  "Host-interop runtime for Chelonia's Beagle modules — the irreducible Clojure
  layer (file IO, log read/write, string ops) the .bclj `declare-extern`s bind
  to. Beagle owns the typed logic; this owns the host calls.

  Paths default to the current working directory (./threads, ./claims.log) and
  are overridable via CHELONIA_THREADS / CHELONIA_LOG."
  (:require [clojure.string :as str]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [chelonia.fold :as fold]))

;; --- file IO ----------------------------------------------------------------

(defn slurp [path] (clojure.core/slurp path))

(defn list-md
  "Absolute paths of *.md directly under dir, sorted, excluding CLAUDE.md."
  [dir]
  (->> (.listFiles (io/file dir))
       (map #(.getPath ^java.io.File %))
       (filter #(str/ends-with? % ".md"))
       (remove #(str/ends-with? % "CLAUDE.md"))
       sort
       vec))

;; --- string ops the parser needs -------------------------------------------

(defn split-on [s sep]
  (vec (str/split s (re-pattern (java.util.regex.Pattern/quote sep)) -1)))
(defn str-index-of [s sub] (str/index-of s sub))
(defn split-comma [s]
  (->> (str/split s #",") (map str/trim) (remove str/blank?) vec))
(defn today-iso [] (str (java.time.LocalDate/now)))
(defn str-lt? [a b] (neg? (compare a b)))

;; --- portable defaults ------------------------------------------------------

(defn threads-dir []
  (or (System/getenv "CHELONIA_THREADS")
      (str (System/getProperty "user.dir") "/threads")))
(defn log-path []
  (or (System/getenv "CHELONIA_LOG")
      (str (System/getProperty "user.dir") "/claims.log")))

;; --- the assertion log ------------------------------------------------------
;; one EDN map per line: {:tx Int :op "assert"|"retract" :l :p :r}.

(defn read-log [path]
  (if (.exists (io/file path))
    (->> (str/split-lines (clojure.core/slurp path))
         (remove str/blank?)
         (keep (fn [line]
                 (try (let [m (edn/read-string line)]
                        (fold/->Assertion (:tx m) (:op m) (:l m) (:p m) (:r m)))
                      (catch Exception _ nil))))
         vec)
    []))

(defn write-log [path assertions]
  (let [lines (map (fn [a]
                     (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a)}))
                   assertions)]
    (spit path (str (str/join "\n" lines) "\n"))))

(defn append-assertion [path a]
  (spit path (str (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a)}) "\n")
        :append true))
