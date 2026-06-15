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

(defn spit-file [path content] (spit path content) nil)
(defn ensure-dir [dir] (.mkdirs (io/file dir)) nil)
(defn file-slug
  "Slug portion of a thread filename: '<id>-<slug>.md' -> '<slug>'."
  [path]
  (let [base (str/replace (.getName (io/file path)) #"\.md$" "")
        dash (str/index-of base "-")]
    (if dash (subs base (inc dash)) base)))

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
                        (fold/->Assertion (:tx m) (:op m) (:l m) (:p m) (:r m) (or (:frame m) (:by m) "legacy")))
                      (catch Exception _ nil))))
         vec)
    []))

(defn write-log [path assertions]
  (let [lines (map (fn [a]
                     (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a)}))
                   assertions)]
    (spit path (str (str/join "\n" lines) "\n"))))

(defn append-assertion [path a]
  (spit path (str (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a)}) "\n")
        :append true))

;; --- coordinator client: write THROUGH the daemon (safe concurrent path) -----
;; One request/response over the local socket. The daemon serializes writes
;; (optimistic base_version + obligation rules), so this is the safe multi-agent
;; write path — unlike append-assertion, which writes the log directly.

(defn- coord-rt [port req]
  (with-open [s (java.net.Socket.)]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (let [w (io/writer (.getOutputStream s))
          r (io/reader (.getInputStream s))]
      (.write w (str (pr-str req) "\n"))
      (.flush w)
      (edn/read-string (.readLine r)))))

(defn coord-version [port]
  (try (let [resp (coord-rt port {:op :version})] (or (:version resp) -1))
       (catch Exception _ -1)))

(defn- coord-write [op port te pred value base]
  (try (let [resp (coord-rt port {:op op :te te :p pred :r value :base base :frame "agent"})]
         (cond (:ok resp)                (str "ok:" (:ok resp))
               (= (:reject resp) :conflict) "conflict"
               (:reject resp)            (str "reject:" (str/join "; " (:reject resp)))
               :else                     (str "error:" (pr-str resp))))
       (catch Exception _ "error:nodaemon")))

(defn coord-assert  [port te pred value base] (coord-write :assert  port te pred value base))
(defn coord-retract [port te pred value base] (coord-write :retract port te pred value base))

(defn coord-port [] (if-let [p (System/getenv "CHELONIA_PORT")] (Integer/parseInt p) 7977))

(defn coord-status [port]
  (try (let [r (coord-rt port {:op :status})]
         (str "up|" (:version r) "|" (:claims r) "|" (:log r)))
       (catch Exception _ "down")))

;; subscribe + stream commit events (one EDN line each) until disconnect
(defn coord-watch [port]
  (with-open [s (java.net.Socket.)]
    (.connect s (java.net.InetSocketAddress. "127.0.0.1" (int port)) 2000)
    (let [w (io/writer (.getOutputStream s))
          r (io/reader (.getInputStream s))]
      (.write w "{:op :subscribe}\n") (.flush w)
      (loop []
        (when-let [line (.readLine r)]
          (println line)
          (recur)))))
  nil)
