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

;; split a triple line "<predicate><ws><object...>" into [pred obj]; obj may
;; contain spaces (it's the rest of the line). Blank/garbage -> [line ""].
(defn split-kv [line]
  (let [t (str/trim line)
        m (re-find #"^(\S+)\s+(.*)$" t)]
    (if m [(nth m 1) (nth m 2)] [t ""])))

;; --- claim-native triple-file value (de)serialization -----------------------
;; A claim object in a triple file is either a ref (@id, handled by the caller)
;; or a literal. Literals are quoted/unquoted via EDN — bulletproof escaping
;; (the same pr-str/read-string pair the log uses), so no hand-rolled quoter can
;; ever emit something a real parser rejects.
(defn edn-quote [s] (pr-str s))
(defn edn-unquote [s] (edn/read-string s))

;; --- thread id: human-grouped, fixed-width, opaque key ----------------------
;; 2026-06-15-150040 (yyyy-MM-dd-HHmmss). Dashes for glance-readability; fixed
;; width so id<->slug splits by position; sorts chronologically as a plain string.
(defn- fmt-id [n]
  (let [s (str n)]
    (str (subs s 0 4) "-" (subs s 4 6) "-" (subs s 6 8) "-" (subs s 8 14))))

(defn now-id []
  (fmt-id (.format (java.time.LocalDateTime/now)
                   (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss"))))

;; The thread id, not the filename, is the entity key — two captures in the same
;; second produce distinct filenames (slugs differ) but would COLLIDE on id.
(defn- id-taken? [dir id]
  (let [f (io/file dir)]
    (boolean
     (when (.isDirectory f)
       (some (fn [n] (or (str/starts-with? n (str id "-")) (= n (str id ".md"))))
             (map #(.getName ^java.io.File %) (.listFiles f)))))))

;; Atomically reserve a free id ACROSS concurrent capture processes: bump past
;; any id already claimed by a file (id-taken?) AND any in-flight reservation —
;; the latter via an exclusive CREATE_NEW of a per-id lock dotfile, which two
;; racers in the same second cannot both win. Caller writes <id>-<slug>.md then
;; release-id. (A scan-then-write alone has a TOCTOU window two distinct-slug
;; captures slip through, silently folding into one entity on import.)
(defn- lock-path [dir id] (str dir "/." id ".lock"))
(defn reserve-id [dir]
  (loop [n (Long/parseLong (.format (java.time.LocalDateTime/now)
                                    (java.time.format.DateTimeFormatter/ofPattern "yyyyMMddHHmmss")))]
    (let [id (fmt-id n)
          ;; try returns the id on a clean exclusive create, nil if the id is
          ;; taken or a racer won the lock — recur OUTSIDE the try (recur cannot
          ;; cross a try/catch boundary).
          got (when-not (id-taken? dir id)
                (try (java.nio.file.Files/createFile
                      (.toPath (io/file (lock-path dir id)))
                      (make-array java.nio.file.attribute.FileAttribute 0))
                     id
                     (catch java.nio.file.FileAlreadyExistsException _ nil)))]
      (if got got (recur (inc n))))))
(defn release-id [dir id] (.delete (io/file (lock-path dir id))) nil)

(defn slugify [title]
  (let [base (-> (str/lower-case (str title))
                 (str/replace #"[^a-z0-9]+" "_")
                 (str/replace #"^_+" "")
                 (str/replace #"_+$" ""))
        capped (if (> (count base) 60) (subs base 0 60) base)
        clean (str/replace capped #"_+$" "")]
    (if (str/blank? clean) "untitled" clean)))

;; --- portable defaults ------------------------------------------------------

(defn threads-dir []
  (or (System/getenv "CHELONIA_THREADS")
      (str (System/getProperty "user.dir") "/threads")))
(defn log-path []
  (or (System/getenv "CHELONIA_LOG")
      (str (System/getProperty "user.dir") "/claims.log")))
(defn time-dir []
  (or (System/getenv "CHELONIA_TIME_DIR")
      (str (System/getProperty "user.dir") "/time")))

;; capture provenance: generic fallbacks here; a consumer (e.g. the life-os
;; wrapper) exports its own conventions via these env vars.
(defn getenv-or [k fallback] (or (System/getenv k) fallback))

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

;; --- time module runtime (ported from los.rt for `chelonia time`) -----------

(defn error-exit [msg]
  (binding [*out* *err*] (println (str "error: " msg)))
  (System/exit 1))

(defn now-iso []
  (.format (java.time.LocalDateTime/now)
           (java.time.format.DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss")))

(defn iso-to-seconds [s]
  (let [normalized (if (= 16 (count s)) (str s ":00") s)]
    (.toEpochSecond (.atZone (java.time.LocalDateTime/parse normalized)
                             (java.time.ZoneId/systemDefault)))))

;; tolerant int parse for claim literals (estimate_hours etc.); 0 on garbage.
(defn parse-int [s]
  (try (Integer/parseInt (str/trim s)) (catch Exception _ 0)))

(defn this-week-dates []
  (let [today (java.time.LocalDate/now)
        dow (.getValue (.getDayOfWeek today))]
    (mapv (fn [i] (.toString (.plusDays today (- i (dec dow))))) (vec (range 0 7)))))

(defn file-exists [p] (.exists (io/file p)))
(defn create-dirs [p] (.mkdirs (io/file p)) nil)
(defn delete-file [p] (when (.exists (io/file p)) (.delete (io/file p))) nil)
(defn spit-append [p content] (spit p content :append true) nil)
(defn getenv [nm] (System/getenv nm))
(defn filter-digits [s] (str/replace s #"[^0-9]" ""))
(defn is-iso-datetime-19 [s]
  (boolean (and (= 19 (count s)) (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}" s))))
(defn is-iso-datetime-16 [s]
  (boolean (and (= 16 (count s)) (re-matches #"\d{4}-\d{2}-\d{2}T\d{2}:\d{2}" s))))
(defn repeat-str [s n] (apply str (repeat (max 0 (long n)) s)))

;; Clockify HTTP — lazy-resolve babashka.http-client so the AOT/native path
;; never references it at compile time (network/out-of-scope there).
(defn http-get [url api-key]
  (or (:body ((requiring-resolve 'babashka.http-client/get)
              url {:headers {"X-Api-Key" api-key}})) ""))
(defn http-post [url api-key body]
  (or (:body ((requiring-resolve 'babashka.http-client/post)
              url {:headers {"X-Api-Key" api-key "Content-Type" "application/json"}
                   :body body})) ""))
