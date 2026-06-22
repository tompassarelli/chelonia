#!/usr/bin/env bb
;; bench — honest perf: append-only log vs claim-per-file, WRITE + LOAD, decomposed.
;;
;; Baseline mirrors the real engine's write path: one EDN op per line, append to a
;; single fd, FLUSH but no per-line fsync (kernel.bclj:453 — the live log is appended
;; without fsync). The per-file path is measured two ways to attribute cost:
;;   - atomic   : temp-write + atomic rename (torn-read-proof; what cpf.clj does)
;;   - direct   : plain spit per file (rename cost removed; pure inode-per-claim cost)
;; LOAD: log = sequential read + fold; per-file = listdir + N opens + sort + fold.
(require '[clojure.java.io :as io]
         '[clojure.edn :as edn]
         '[clojure.string :as str]
         '[babashka.fs :as fs])
(import '[java.nio.file Files StandardCopyOption])

(defn now [] (System/nanoTime))
(defn ms [t0 t1] (/ (- t1 t0) 1e6))
(defn rm [d] (fs/delete-tree d) (.mkdirs (io/file d)) d)

(defn gen-claims [n]
  (mapv (fn [i] {:op :assert :l (str "@e" (mod i 500)) :p (rand-nth ["title" "note" "kind" "owner" "ref"])
                 :r (str "v" i)}) (range n)))

;; --- WRITE: append-only log -------------------------------------------------
(defn write-log [path claims]
  (with-open [w (io/writer path :append true)]
    (let [t0 (now)]
      (doseq [[i c] (map-indexed vector claims)]
        (.write w (pr-str (assoc c :tx (inc i)))) (.write w "\n"))
      (.flush w)
      (ms t0 (now)))))

;; --- WRITE: claim-per-file (atomic rename) ----------------------------------
(defn uuid7 [ctr] ; cheap monotone id for the bench (real cpf uuidv7 is equivalent cost)
  (format "%013x-%07x" (System/currentTimeMillis) ctr))
(defn write-files-atomic [dir claims]
  (let [t0 (now)]
    (doseq [[i c] (map-indexed vector claims)]
      (let [id (uuid7 i) tmp (io/file dir (str "." id ".tmp")) dst (io/file dir (str id ".edn"))]
        (spit tmp (pr-str (assoc c :id id)))
        (Files/move (.toPath tmp) (.toPath dst)
                    (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE]))))
    (ms t0 (now))))

;; --- WRITE: claim-per-file (direct spit, no rename) -------------------------
(defn write-files-direct [dir claims]
  (let [t0 (now)]
    (doseq [[i c] (map-indexed vector claims)]
      (let [id (uuid7 i)] (spit (io/file dir (str id ".edn")) (pr-str (assoc c :id id)))))
    (ms t0 (now))))

;; --- LOAD: log = read + fold ------------------------------------------------
(defn load-log [path]
  (let [t0 (now)
        lines (str/split-lines (slurp path))
        claims (keep #(try (edn/read-string %) (catch Exception _ nil)) lines)
        state (reduce (fn [m c] (assoc m [(:l c) (:p c)] (:r c))) {} claims)]
    [(ms t0 (now)) (count state)]))

;; --- LOAD: per-file = listdir + opens + sort + fold -------------------------
(defn load-files [dir]
  (let [t0 (now)
        fs* (->> (.listFiles (io/file dir))
                 (filter #(str/ends-with? (.getName %) ".edn")))
        claims (->> fs* (keep #(try (edn/read-string (slurp %)) (catch Exception _ nil)))
                    (sort-by :id))
        state (reduce (fn [m c] (assoc m [(:l c) (:p c)] (:r c))) {} claims)]
    [(ms t0 (now)) (count state)]))

(defn bench-n [n]
  (let [claims (gen-claims n)
        base   "/tmp/cpf-bench"
        logp   (str base "/log.edn")
        atomd  (rm (str base "/files-atomic"))
        dird   (rm (str base "/files-direct"))]
    (io/delete-file logp true)
    (let [wl  (write-log logp claims)
          wfa (write-files-atomic atomd claims)
          wfd (write-files-direct dird claims)
          [ll cl] (load-log logp)
          [lfa _] (load-files atomd)
          [lfd cd] (load-files dird)]
      (printf "N=%-6d  WRITE  log=%7.1fms  file-atomic=%8.1fms (%4.1fx)  file-direct=%8.1fms (%4.1fx)\n"
              n wl wfa (/ wfa wl) wfd (/ wfd wl))
      (printf "%14s  LOAD   log=%7.1fms  file=%8.1fms (%4.1fx)   [state log=%d file=%d]\n\n"
              "" ll lfd (/ lfd ll) cl cd))))

(println "claim-per-file vs append-log — write+load, no per-line fsync either side\n")
(doseq [n [1000 5000 10000]] (bench-n n))
(println "(file-atomic = temp+rename per claim; file-direct = plain spit per claim; log = 1 fd append)")
