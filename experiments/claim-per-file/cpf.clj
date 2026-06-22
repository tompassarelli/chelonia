#!/usr/bin/env bb
;; cpf — claim-per-file store prototype (architecture spike, #foundational-store-direction).
;;
;; Thesis (Tom's direction): replace the append-only log with content-addressed /
;; UUIDv7-named PER-FILE claims. There is NO log and NO central tx counter.
;;   - ORDER comes from time-sortable ids (UUIDv7 = 48-bit ms prefix, so the hex
;;     id sorts chronologically => the filename IS the position).
;;   - CAUSALITY is edges inside the claim (:supersedes / :retracts / :depends_on),
;;     not a position in a log.
;;   - ATOMIC multi-claim transactions via a COMMIT-CLAIM that references its
;;     members; members go live ONLY when the commit-claim file lands (git model:
;;     an object is dangling until a commit points at it).
;;
;; A store is just a directory of *.edn files. To read the graph you list the dir,
;; gate by commit-visibility, sort by id, and fold. Merge two stores = union the
;; files (no coordinator) — the whole federation story falls out of that.
(ns cpf
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import [java.security MessageDigest]
           [java.nio.file Files Paths StandardCopyOption]
           [java.nio.file.attribute FileAttribute]))

;; ---------------------------------------------------------------------------
;; UUIDv7 — 48-bit big-endian unix-ms timestamp | ver(7) | rand_a | variant | rand_b.
;; The ms prefix makes the canonical hex string monotonic-ish & lexicographically
;; time-sortable. A per-process monotonic counter in rand_a breaks same-ms ties so
;; ids minted in a tight loop still sort by mint order (no lost writes from collision).
;; ---------------------------------------------------------------------------
(def ^:private seq-ctr (atom 0))
(defn uuidv7
  ([] (uuidv7 (System/currentTimeMillis)))
  ([ms]
   (let [ctr   (bit-and (swap! seq-ctr inc) 0xFFF)         ; 12-bit intra-ms counter
         rnd   (long (* (rand) 0x3FFFFFFFFFFFFFF))         ; 62-bit randomness
         hi    (bit-or (bit-shift-left (bit-and ms 0xFFFFFFFFFFFF) 16)
                       (bit-shift-left 0x7 12)             ; version 7
                       ctr)
         lo    (bit-or (bit-shift-left 0x2 62)             ; RFC variant
                       (bit-and rnd 0x3FFFFFFFFFFFFFFF))
         u     (java.util.UUID. hi lo)]
     (str u))))

;; content address: sha256 over the canonical (sorted) claim body, sans :id.
;; Naming a claim by its hash gives free dedup + tamper-evidence, but LOSES the
;; time-ordering the filename otherwise carries (see WRITEUP §addressing).
(defn content-hash [m]
  (let [canon (pr-str (into (sorted-map) (dissoc m :id)))
        dig   (.digest (MessageDigest/getInstance "SHA-256") (.getBytes canon "UTF-8"))]
    (apply str (map #(format "%02x" %) dig))))

;; ---------------------------------------------------------------------------
;; write path — one claim, one file. Atomic via write-temp + rename (POSIX rename
;; is atomic within a dir), so a torn read is impossible (cf. the log's fsync-less
;; append, which CAN tear a line: kernel.bclj:453).
;; ---------------------------------------------------------------------------
(defn ensure-store [dir] (.mkdirs (io/file dir)) dir)

(defn- atomic-spit [dir fname content]
  (let [tmp (io/file dir (str "." fname ".tmp"))
        dst (io/file dir fname)]
    (spit tmp content)
    (Files/move (.toPath tmp) (.toPath dst)
                (into-array java.nio.file.CopyOption
                            [StandardCopyOption/ATOMIC_MOVE]))))

(defn put-claim
  "Write one claim. Returns its id. opts may carry :supersedes/:retracts/:depends_on
   (vectors of ids), :commit (id of the commit-claim this is a member of), :by."
  [dir {:keys [l p r] :as claim}]
  (let [id  (uuidv7)
        m   (merge {:op :assert} claim {:id id})]
    (atomic-spit dir (str id ".edn") (pr-str m))
    id))

(defn put-commit
  "Write a COMMIT-CLAIM referencing member ids. Members become visible only once
   THIS file exists. Returns the commit id."
  [dir {:keys [members by]}]
  (let [id (uuidv7)
        m  {:id id :op :commit :members (vec members) :by by}]
    (atomic-spit dir (str id ".edn") (pr-str m))
    id))

(defn stage-member
  "Write a PENDING member claim (git model: a dangling object). Its id is minted now
   (carrying its real birth time), but it stays invisible until SOME commit-claim
   lists it. Returns the id, which the caller collects to build the commit."
  [dir {:keys [l p r] :as claim}]
  (put-claim dir (assoc claim :pending true)))

;; ---------------------------------------------------------------------------
;; load path — list, parse, gate by commit, sort by id, fold.
;; ---------------------------------------------------------------------------
(defn- read-all [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName %) ".edn"))
       (remove #(str/starts-with? (.getName %) "."))      ; skip *.tmp in flight
       (keep (fn [f]
               (try (edn/read-string (slurp f))
                    (catch Exception _ nil))))             ; torn/partial => skip (can't happen w/ atomic rename, but be safe on merge)
       (vec)))

(defn load-store
  "Returns {:claims <live, id-sorted> :state <{[l p] -> r|#{r}}> :commits <set>
            :dropped <ids gated out as uncommitted>}.
   Folding rules:
     - a claim with :commit C is live iff commit C exists AND lists it in :members
     - explicit :retracts / :supersedes edges kill their targets
     - remaining claims sorted by :id (UUIDv7 => chronological); single-valued (l p)
       collapses last-write-wins, multi accumulates. Cardinality is graph-sourced:
       (P :cardinality \"single\") claims make P single (mirrors kernel/single-in?)."
  [dir]
  (let [all       (read-all dir)
        commits   (filter #(= :commit (:op %)) all)
        claims    (filter #(not= :commit (:op %)) all)
        landed    (into #{} (mapcat :members commits))    ; ids made live by some commit
        ;; commit-gate: a :pending member is invisible until a commit lists it
        {visible true dropped false}
        (group-by (fn [c] (if (:pending c)
                            (contains? landed (:id c))
                            true))
                  claims)
        sorted    (sort-by :id (or visible []))
        ;; pass 1: collect kill-set from causal edges (order-independent)
        killed    (into #{} (mapcat (fn [c] (concat (:retracts c) (:supersedes c))) sorted))
        alive     (remove #(contains? killed (:id %)) sorted)
        ;; cardinality registry from the graph
        single?   (into #{} (comp (filter #(= "cardinality" (:p %)))
                                  (filter #(= "single" (:r %)))
                                  (map :l))
                        alive)
        ;; pass 2: fold to state
        state     (reduce
                   (fn [st c]
                     (let [k [(:l c) (:p c)]]
                       (cond
                         (= :retract (:op c)) (update st k
                                                      (fn [v] (if (set? v) (disj v (:r c)) nil)))
                         (single? (:p c))     (assoc st k (:r c))
                         :else                (update st k (fnil conj #{}) (:r c)))))
                   {} alive)]
    {:claims (vec alive)
     :state  (into {} (remove (comp nil? val) state))
     :commits (into #{} (map :id commits))
     :dropped (mapv :id (or dropped []))}))

;; merge = union of files. Demonstrated by load-store over a dir that is the union;
;; here we expose a helper that copies B's files into A (federation pull).
(defn merge-into! [dst-dir src-dir]
  (ensure-store dst-dir)
  (doseq [f (.listFiles (io/file src-dir))
          :when (str/ends-with? (.getName f) ".edn")]
    (let [d (io/file dst-dir (.getName f))]
      (when-not (.exists d) (io/copy f d))))
  dst-dir)

;; ---- tiny CLI for the demo/bench scripts ----------------------------------
(defn -main [& args] (println "cpf: library — see demo.clj / bench.clj"))
