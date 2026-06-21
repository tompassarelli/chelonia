;; cnf_coord.clj — Stage 6: the coordinator on the REIFIED kernel.
;; ============================================================================
;; Sole writer, serialized (one lock). The flat coord.clj's proven skeleton
;; (locking write-lock, optimistic base_version, rule-check, append+notify),
;; rebuilt over the reified store (fram.cnf + fram.schema). Full Clojure
;; — direct, mutable access to the store map, no Beagle typing friction.
;;
;; The six concurrency/durability holes the analysis flagged, closed here:
;;   1. base_version  = max tx-seq over the LIVE claims on (l,p); a stale
;;      single-valued write is rejected (lost-update protection).
;;   2. validate-without-mutating — obligations (acyclicity) are a PURE pre-check
;;      over resolved ids, run BEFORE any tx/entity is minted, so a rejected
;;      write leaves ZERO unlogged state (else the live store would diverge from
;;      a replay of the log).
;;   3. single-subject obligations (depends_on/part_of acyclicity) for v1.
;;   4. multi-valued idempotency — reified claim! mints a fresh cid every call,
;;      so without this two identical link!s make duplicate live edges; we no-op
;;      when the live (l,p,r) already exists.
;;   5. atomic v2 log — each committed tx appends its records + a :commit marker,
;;      fsync'd; a torn tx (records without :commit, always trailing under a
;;      single appender) is DROPPED on replay. Durability the bb coord lacked.
;;   6. file-import optimistic concurrency — every write goes through the one
;;      lock; the base_version contract (C5) decides who wins.
;;
;;   bb -cp out cnf_coord.clj test
;; ============================================================================
(require '[fram.cnf :as c] '[fram.schema :as s] '[fram.kernel :as ck]
         '[clojure.edn :as edn] '[clojure.java.io :as io] '[clojure.string :as str])

(defn- store [co] (:store co))

;; --- atomic v2 log: a tx's records + :commit, fsync'd -----------------------
(defn- append-tx! [co records]
  (when (:log co)                               ; nil :log = drop-in mode: the flat
    (with-open [os (java.io.FileOutputStream. (str (:log co)) true)]   ; log is canonical
      (doseq [r records] (.write os (.getBytes (str (pr-str r) "\n") "UTF-8")))  ; and is written
      (.flush os)                               ; ONLY via the daemon's append-flat!,
      (.force (.getChannel os) true))))         ; never with v2 :k-records. fsync (design A).

;; the records minted in `store` since id `since` (new values/entities/claims),
;; plus this tx's provenance and the terminating :commit marker.
(defn- delta-records [co since txid]
  (let [m @(store co)]
    (concat
     (for [[id v] (:values m) :when (>= id since)] {:k :value :id id :v v})
     (for [id (keys (:objects m))
           :when (and (>= id since)
                      (not (contains? (:values m) id))
                      (not (contains? (:claims m) id)))]
       {:k :entity :id id})
     (for [[cid mm] (:claims m) :when (>= cid since)]
       {:k :claim :cid cid :l (:l mm) :p (:p mm) :r (:r mm) :tx (get (:tx-of m) cid)})
     [{:k :tx :tx txid :seq (get-in m [:txs txid :seq]) :agent (get-in m [:txs txid :agent])}
      {:k :commit :tx txid}])))

;; --- reads over the reified store -------------------------------------------
(defn- live-cids-lp [co te pid]
  (let [m @(store co)]
    (remove #(contains? (:superseded m) %) (get (:idx-by-lp m) [te pid]))))
(defn- seq-of [co cid]
  (let [m @(store co)] (get-in m [:txs (get (:tx-of m) cid) :seq] 0)))
(defn base-version [co te pid]
  (reduce max 0 (map #(seq-of co %) (live-cids-lp co te pid))))
(defn current-seq [co]
  ;; O(1): :next-seq is the monotonic seq counter — begin-tx! does (update :next-seq inc) and
  ;; assigns the result as the tx's :seq, so the LAST-assigned seq == the MAX seq == :next-seq.
  ;; The old (reduce max ... (:txs m)) was O(total-commits) and, called ~37x per authoring op,
  ;; was the residual O(total) authoring cost (per-op grew 14->61ms over a 500-def build).
  (or (:next-seq @(store co)) 0))

(defn- ent! [co tx nm]
  (or (s/resolve-name (store co) nm)
      (let [e (c/entity! (store co))] (s/name! (store co) e nm tx) e)))

;; finding #12: a brand-new predicate has NO cardinality claim, so s/cardinality
;; defaults to "multi" — and s/link!/s/assert! only supersede a prior value when
;; the STORE records cardinality=="single" (they don't consult ck/single?). So a
;; kernel-single predicate that was never def-predicate!'d (e.g. one not present
;; in the flat log at migration) would have its first write NOT supersede,
;; accumulating duplicate live values. Before such a write, pin the store's
;; cardinality to "single" (in the SAME tx) so the schema layer supersedes
;; correctly. Idempotent: only fires when the kernel says single but the store
;; doesn't already record it, so an already-defined-single predicate is untouched.
(defn- ensure-single-cardinality! [co tx pred kind]
  (when (and (ck/single? pred) (not= "single" (s/cardinality (store co) pred)))
    (s/def-predicate! (store co) pred "single"
                      (if (= kind :link) "ref" "literal") tx)))

;; --- obligation: depends_on/part_of acyclicity (pure, over resolved ids) ----
(defn- succ [co pid x]
  (let [m @(store co)]
    (map #(:r (get (:claims m) %))
         (remove #(contains? (:superseded m) %) (get (:idx-by-lp m) [x pid])))))
;; reachability over the reified store — routed through the kernel's ONE verified
;; traversal (ck/reachable-from?) instead of a second hand-rolled DFS. The store
;; supplies `succ`; the algorithm (and its correctness) lives once, in Beagle.
(defn- reaches? [co pid from to]
  (ck/reachable-from? (fn [x] (succ co pid x)) [from] to))

;; --- bootstrap a coordinator (multi-store: one engine, many coordinators) ---
(defn new-coord [log-path]
  (spit log-path "")
  (let [st (c/new-store)
        tx0 (c/begin-tx! st "bootstrap")
        co {:store st :log log-path :lock (Object.)}]
    (s/setup! st tx0)
    (append-tx! co (delta-records co 0 tx0))     ; the bootstrap is the first committed tx
    co))

;; register a domain predicate's metadata (its own committed tx)
(defn register-pred! [co pname card kind]
  (locking (:lock co)
    (let [since (:next-id @(store co))
          tx (c/begin-tx! (store co) "schema")]
      (s/def-predicate! (store co) pname card kind tx)
      (append-tx! co (delta-records co since tx))
      pname)))

;; --- the sole writer --------------------------------------------------------
;; kind = :assert (literal value) | :link (ref to an entity by name).  Retract is
;; a separate entry point (retract!) since it removes rather than supersedes-by-add.
(defn commit! [co agent te-name pred kind r-spec base]
  (locking (:lock co)
    (let [pid    (c/value-id (store co) pred)
          te0    (s/resolve-name (store co) te-name)
          tgt0   (when (= kind :link) (s/resolve-name (store co) r-spec))
          vid    (when (= kind :assert) (c/value-id (store co) r-spec))
          single (or (= "single" (s/cardinality (store co) pred)) (ck/single? pred))
          bv     (if (and te0 pid) (base-version co te0 pid) 0)
          live   (if (and te0 pid) (live-cids-lp co te0 pid) [])
          claims (:claims @(store co))]
      (cond
        ;; (1)(6) base_version: reject a stale single-valued write
        (and single (> bv base))
        {:reject :conflict :version (current-seq co)}

        ;; (2)(3) obligation: acyclicity — pure pre-check, before any mutation
        (and (= kind :link) (contains? #{"depends_on" "part_of"} pred)
             (or (= te-name r-spec) (and te0 tgt0 (reaches? co pid tgt0 te0))))
        {:reject [(str pred " cycle")] :version (current-seq co)}

        ;; (4) multi-valued idempotency: no-op if the live (l,p,r) already exists
        (and (not single) (= kind :link) tgt0 (some #(= tgt0 (:r (get claims %))) live))
        {:ok (current-seq co) :idempotent true}
        (and (not single) (= kind :assert) vid (some #(= vid (:r (get claims %))) live))
        {:ok (current-seq co) :idempotent true}

        :else
        (let [since (:next-id @(store co))
              tx (c/begin-tx! (store co) agent)
              te (ent! co tx te-name)]
          (ensure-single-cardinality! co tx pred kind)   ; (#12) supersede on first single write
          (case kind
            :link   (s/link! (store co) te pred (ent! co tx r-spec) tx)
            :assert (s/assert! (store co) te pred r-spec tx))
          (append-tx! co (delta-records co since tx))   ; (5) atomic + fsync
          {:ok (get-in @(store co) [:txs tx :seq])})))))

;; retract: single-valued clears (te,pred); multi-valued removes the (te,pred,r)
;; edge. Same lock + base_version contract as commit! — clearing a driver out
;; from under an active thread races safely. Supersession is append-only (a
;; cnf-supersedes claim marks each victim), so retract is itself reversible.
(defn retract! [co agent te-name pred r-spec base]
  (locking (:lock co)
    (let [pid    (c/value-id (store co) pred)
          te0    (s/resolve-name (store co) te-name)
          single (or (= "single" (s/cardinality (store co) pred)) (ck/single? pred))]
      (if (or (nil? te0) (nil? pid))
        {:ok (current-seq co)}                              ; nothing to retract
        (let [bv (base-version co te0 pid)]
          (if (and single (> bv base))
            {:reject :conflict :version (current-seq co)}
            (let [tgt (if (and r-spec (str/starts-with? (str r-spec) "@"))
                        (s/resolve-name (store co) r-spec)
                        (c/value-id (store co) r-spec))
                  claims (:claims @(store co))
                  victims (if single
                            (live-cids-lp co te0 pid)
                            (filter #(= tgt (:r (get claims %))) (live-cids-lp co te0 pid)))]
              (if (empty? victims)
                {:ok (current-seq co)}
                (let [since (:next-id @(store co))
                      tx (c/begin-tx! (store co) agent)
                      sup (c/value! (store co) "cnf-supersedes")]
                  (doseq [old victims] (c/claim! (store co) old sup old tx))
                  (append-tx! co (delta-records co since tx))
                  {:ok (get-in @(store co) [:txs tx :seq])})))))))))

;; --- replay: rebuild the store from the v2 log (drops torn/uncommitted txs) --
(defn- read-records [path]
  (with-open [r (io/reader path)]
    (doall (keep (fn [ln] (try (edn/read-string ln) (catch Exception _ nil)))   ; tolerate a torn last line
                 (line-seq r)))))

(defn- committed-records [recs]
  ;; group into per-tx buffers; a buffer terminated by :commit is kept, a
  ;; trailing buffer with no :commit (a torn tx) is dropped.
  (loop [rs recs buf [] out []]
    (if (empty? rs)
      out
      (let [r (first rs)]
        (if (= (:k r) :commit)
          (recur (rest rs) [] (into out buf))
          (recur (rest rs) (conj buf r) out))))))

(defn- assemble-dump [recs]
  (let [vals   (vec (for [r recs :when (= (:k r) :value)]  [(:id r) (:v r)]))
        ents   (vec (for [r recs :when (= (:k r) :entity)] (:id r)))
        claims (vec (for [r recs :when (= (:k r) :claim)]  [(:cid r) {:l (:l r) :p (:p r) :r (:r r)}]))
        tx-of  (vec (for [r recs :when (= (:k r) :claim)]  [(:cid r) (:tx r)]))
        txs    (vec (for [r recs :when (= (:k r) :tx)]     [(:tx r) {:seq (:seq r) :agent (:agent r)}]))
        sup    (some (fn [[id v]] (when (= v "cnf-supersedes") id)) vals)
        superd (vec (for [[_ m] claims :when (= (:p m) sup)] (:r m)))
        all-id (concat (map first vals) ents (map first claims) (map first txs))
        all-sq (map (fn [[_ m]] (:seq m)) txs)]
    ;; the kernel's counters hold the LAST-used id/seq (fresh-id!/begin-tx! return
    ;; the post-increment value), so recover them as max — NOT max+1 — else the
    ;; next mint would skip an id/seq (a gap) instead of continuing contiguously.
    {:next-id (reduce max 0 all-id) :next-seq (reduce max 0 all-sq)
     :supersedes-pred sup
     :objects (vec (concat (map first vals) ents (map first claims)))
     :values vals :claims claims :tx-of tx-of :txs txs :superseded superd}))

(defn replay [path]
  (let [st (c/new-store)]
    (c/load-store! st (assemble-dump (committed-records (read-records path))))
    st))

;; write a whole reified store as a v2 log (all records + one trailing :commit)
;; that `replay` consumes — the migration target. After migration the live
;; coordinator boots via (replay path); next-id/next-seq are recovered from the
;; logged ids, so its appends continue cleanly from where migration left off.
(defn dump-log! [st path]
  (spit path "")
  (let [m @st]
    (with-open [os (java.io.FileOutputStream. (str path) true)]
      (let [emit (fn [r] (.write os (.getBytes (str (pr-str r) "\n") "UTF-8")))]
        (doseq [[id v] (:values m)] (emit {:k :value :id id :v v}))
        (doseq [id (keys (:objects m))
                :when (and (not (contains? (:values m) id)) (not (contains? (:claims m) id)))]
          (emit {:k :entity :id id}))
        (doseq [[cid cl] (:claims m)]
          (emit {:k :claim :cid cid :l (:l cl) :p (:p cl) :r (:r cl) :tx (get (:tx-of m) cid)}))
        (doseq [[tx t] (:txs m)] (emit {:k :tx :tx tx :seq (:seq t) :agent (:agent t)}))
        (emit {:k :commit :tx :migration}))
      (.force (.getChannel os) true))))

;; live (l,p,r) id-triples of a reified store (substrate identity for tests/diff)
(defn live-triples [st]
  (let [m @st]
    (set (for [cid (keys (:claims m)) :when (not (contains? (:superseded m) cid))]
           (let [cl (get (:claims m) cid)] [(:l cl) (:p cl) (:r cl)])))))
