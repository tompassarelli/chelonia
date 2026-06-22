;; prototype.clj — snapshot writer + boot path over candidate B (per-writer logs).
;;
;; Proves: a program's state = fold(claims <= T) is a pure fn of the log, so an IMAGE is
;; that fold memoized at HLC T, and BOOT = mmap(image) + fold(tail after T). We reuse the
;; bake-off's B layout (per-writer .log files) and its fold semantics (common.clj), both
;; read-only. The snapshot is a flat mmap-friendly binary arena (see DESIGN.md §2), NOT
;; re-parsed EDN — that's where the speed comes from.
;;
;; usage: bb prototype.clj            -> self-test (correctness + equivalence)
(require '[clojure.java.io :as io]
         '[clojure.string :as str]
         '[clojure.edn :as edn])
(import '[java.io FileOutputStream RandomAccessFile ByteArrayOutputStream DataOutputStream]
        '[java.nio ByteBuffer ByteOrder]
        '[java.nio.channels FileChannel FileChannel$MapMode]
        '[java.nio.file Files Paths StandardOpenOption])
(load-file (str (System/getProperty "user.dir") "/experiments/store-bakeoff/common.clj"))
(alias 'c 'common)

;; ===========================================================================
;; STORE BUILD (candidate B: NWRITERS per-writer append logs) — reused layout
;; ===========================================================================
(defn build-store!
  "Write `claims` into dir as candidate-B per-writer logs (one .log per :by)."
  [dir claims]
  (c/fresh-dir! dir)
  (doseq [[w cs] (group-by :by claims)]
    (with-open [out (FileOutputStream. (str dir "/" w ".log") true)]
      (doseq [cl cs] (.write out (.getBytes (str (pr-str cl) "\n") "UTF-8")))
      (.flush out)))
  dir)

(defn read-all-claims
  "All claims across every per-writer log (the G-Set, as EDN records)."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName %) ".log"))
       (mapcat (fn [f] (->> (slurp f) str/split-lines (remove str/blank?) (map c/de))))
       (vec)))

(defn- line-id
  "Extract the :id literal from a raw record line {:id \"<hlc>\" ...} without EDN-parsing."
  [line]
  (let [i (.indexOf line "\"") j (.indexOf line "\"" (inc i))]
    (when (and (pos? i) (> j i)) (subs line (inc i) j))))

(defn- tail-of-log
  "Read ONLY the suffix of one per-writer log with id > frontier. Each writer's ids are
   monotone within its own log (append order), so the tail is a contiguous SUFFIX — we read
   the file backward in chunks and stop once we cross the frontier. Cost ~ O(tail bytes),
   NOT O(file). This is what keeps boot flat in total N."
  [f frontier]
  (with-open [raf (RandomAccessFile. f "r")]
    (let [len (.length raf)
          chunk 65536]
      (loop [end len acc-bytes (java.io.ByteArrayOutputStream.)]
        (let [start (max 0 (- end chunk))
              n (- end start)
              ba (byte-array n)
              _ (.seek raf start) _ (.readFully raf ba)
              merged (java.io.ByteArrayOutputStream.)
              _ (.write merged ba) _ (.write merged (.toByteArray acc-bytes))
              s (String. (.toByteArray merged) "UTF-8")
              ;; when start>0 the FIRST line is a (possibly partial) record cut by the chunk
              ;; boundary — drop it; an earlier iteration will re-emit it whole. When start=0
              ;; the first line is a true record boundary — keep it.
              lines (cond->> (str/split-lines s) (pos? start) (drop 1))
              lines (remove str/blank? lines)
              ;; the earliest COMPLETE line we now hold: if its id is already <= frontier, we
              ;; have fully covered the tail boundary and can stop. (Strict filter below.)
              earliest-id (some-> (first lines) line-id)
              covered? (or (= start 0)
                           (and earliest-id (<= (compare earliest-id frontier) 0)))]
          (if covered?
            (->> lines
                 (filter (fn [line] (when-let [id (line-id line)] (pos? (compare id frontier)))))
                 (mapv c/de))
            (recur start merged)))))))

(defn read-tail-claims
  "Only claims with :id > frontier across the per-writer logs. This is the BOOT TAIL.
   Reads each log's SUFFIX (backward) — cost is O(tail), not O(total N)."
  [dir frontier]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName %) ".log"))
       (mapcat #(tail-of-log % frontier))
       (vec)))

;; ===========================================================================
;; MATERIALIZED INDEX — fold(claims) to {[l p] -> r|#{r}}  (cpf2/common semantics)
;; ===========================================================================
;; common/fold-state gives us the converged state map. That map IS the materialized index.
;; We also fold INCREMENTALLY for the boot path: seed with snapshot state, fold only tail.

(defn fold-into
  "Seed an existing state map with prior materialized state, then fold the (sorted) tail
   claims into it. Same algebra as common/fold-state, but starting from `seed` instead of {}.
   Single preds LWW (tail id > snapshot id, so tail always wins); multi accumulates."
  [seed tail-claims]
  (let [sorted (sort-by :id tail-claims)
        killed (into #{} (mapcat (fn [c] (concat (:sup c) (:ret c))) sorted))
        alive  (remove #(contains? killed (:id %)) sorted)]
    (reduce (fn [st cl]
              (let [k [(:l cl) (:p cl)]]
                (cond
                  (= :retract (:op cl)) (update st k (fn [v] (if (set? v) (disj v (:r cl)) nil)))
                  (c/single-preds (:p cl)) (assoc st k (:r cl))
                  :else (update st k (fnil conj #{}) (:r cl)))))
            seed alive)))

;; ===========================================================================
;; SNAPSHOT FORMAT — flat mmap-friendly binary arena (DESIGN.md §2)
;; ===========================================================================
;; Layout: [magic][version][frontier][n-rows][strtab][rows]. Strings interned (offsets into
;; strtab). Rows fixed-shaped: l-off, p-off, n-vals, then n-vals val-offs. Sorted by [l p].
;; We do NOT round-trip EDN on boot — we walk the byte arena directly.

(def ^:bytes MAGIC (.getBytes "FBSNAP01" "UTF-8"))

(defn- state->rows
  "state {[l p] -> r|#{r}} -> sorted vec of [l p [vals...]]."
  [state]
  (->> state
       (map (fn [[[l p] v]] [l p (if (set? v) (vec (sort v)) [v])]))
       (sort-by (fn [[l p _]] [l p]))
       (vec)))

(defn write-snapshot!
  "Serialize materialized `state` at HLC `frontier` to a flat binary arena file `path`."
  [path state frontier]
  (let [rows (state->rows state)
        ;; intern every distinct string once
        strs (->> rows (mapcat (fn [[l p vs]] (cons l (cons p vs)))) distinct vec)
        str->idx (into {} (map-indexed (fn [i s] [s i]) strs))
        ;; build string table: u32 len + bytes, per string; record byte-offset of each
        strtab (ByteArrayOutputStream.)
        sd (DataOutputStream. strtab)
        offs (reduce (fn [acc s]
                       (let [b (.getBytes s "UTF-8")]
                         (.writeInt sd (alength b)) (.write sd b)
                         (conj acc (- (.size strtab) (+ 4 (alength b))))))
                     [] strs)
        idx->off (vec offs)
        ;; rows: l-off u32 | p-off u32 | n-vals u32 | val-off u32 *
        rowbuf (ByteArrayOutputStream.)
        rd (DataOutputStream. rowbuf)
        _ (doseq [[l p vs] rows]
            (.writeInt rd (int (idx->off (str->idx l))))
            (.writeInt rd (int (idx->off (str->idx p))))
            (.writeInt rd (int (count vs)))
            (doseq [v vs] (.writeInt rd (int (idx->off (str->idx v))))))
        fb (.getBytes frontier "UTF-8")
        strtab-bytes (.toByteArray strtab)
        rows-bytes (.toByteArray rowbuf)
        ;; header: magic(8) ver(4) flen(4) frontier(flen) nrows(8) strtab-len(8) rows-len(8)
        hdr (ByteArrayOutputStream.)
        hd (DataOutputStream. hdr)]
    (.write hd MAGIC)
    (.writeInt hd 1)
    (.writeInt hd (alength fb)) (.write hd fb)
    (.writeLong hd (count rows))
    (.writeLong hd (alength strtab-bytes))
    (.writeLong hd (alength rows-bytes))
    (with-open [out (FileOutputStream. path)]
      (.write out (.toByteArray hdr))
      (.write out strtab-bytes)
      (.write out rows-bytes))
    {:path path :rows (count rows) :strs (count strs)
     :bytes (+ (.size hdr) (alength strtab-bytes) (alength rows-bytes))}))

;; ---- boot: hydrate the arena via mmap (zero-copy page-in) ------------------
(defn- read-cstr [^ByteBuffer buf strtab-base off]
  (let [p (+ strtab-base off)
        len (.getInt buf p)
        ba (byte-array len)]
    (.get buf (int (+ p 4)) ba (int 0) (int len))         ; absolute bulk get, no reposition
    (String. ba "UTF-8")))

(defn hydrate-mmap
  "BOOT STEP 1: mmap the snapshot file and walk rows -> {[l p] -> r|#{r}} + frontier.
   No EDN. Strings paged in lazily by the OS; rows are fixed-stride. This is the I/O-bound
   load that replaces recompute."
  [path]
  (with-open [ch (FileChannel/open (Paths/get path (into-array String []))
                                   (into-array StandardOpenOption [StandardOpenOption/READ]))]
    (let [sz (.size ch)
          buf (.map ch FileChannel$MapMode/READ_ONLY 0 sz)]
      (.order buf ByteOrder/BIG_ENDIAN)
      ;; header
      (let [_magic (let [m (byte-array 8)] (.get buf m) m)
            _ver (.getInt buf)
            flen (.getInt buf)
            fb (byte-array flen) _ (.get buf fb)
            frontier (String. fb "UTF-8")
            nrows (.getLong buf)
            strtab-len (.getLong buf)
            _rows-len (.getLong buf)
            strtab-base (.position buf)
            rows-base (+ strtab-base strtab-len)]
        (loop [i 0 pos rows-base st (transient {})]
          (if (= i nrows)
            {:state (persistent! st) :frontier frontier :rows nrows}
            (let [l-off (.getInt buf pos)
                  p-off (.getInt buf (+ pos 4))
                  nv (.getInt buf (+ pos 8))
                  vbase (+ pos 12)
                  l (read-cstr buf strtab-base l-off)
                  p (read-cstr buf strtab-base p-off)
                  vals (mapv (fn [k] (read-cstr buf strtab-base (.getInt buf (+ vbase (* 4 k)))))
                             (range nv))
                  v (if (c/single-preds p) (first vals) (into #{} vals))]
              (recur (inc i) (+ vbase (* 4 nv)) (assoc! st [l p] v)))))))))

;; ---- mmap POINT READ: a single [l p] lookup without hydrating the whole index ----
;; binary-search the sorted rows; touches only the pages for that row + its strings.
(defn mmap-point-read [path l p]
  (with-open [ch (FileChannel/open (Paths/get path (into-array String []))
                                   (into-array StandardOpenOption [StandardOpenOption/READ]))]
    (let [sz (.size ch)
          buf (.map ch FileChannel$MapMode/READ_ONLY 0 sz)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (.position buf 8) (.getInt buf)                       ; skip magic+ver
      (let [flen (.getInt buf) _ (.position buf (+ (.position buf) flen))
            nrows (.getLong buf)
            strtab-len (.getLong buf)
            _ (.getLong buf)
            strtab-base (.position buf)
            rows-base (+ strtab-base strtab-len)]
        ;; rows are variable-width (n-vals), so we can't O(1)-stride to row k for bsearch;
        ;; scan sequentially (still no EDN, still mmap'd). A fixed-stride index sidecar would
        ;; restore log-n bsearch; out of scope — the point is "no full hydrate".
        (loop [i 0 pos rows-base]
          (if (= i nrows) nil
              (let [l-off (.getInt buf pos) p-off (.getInt buf (+ pos 4))
                    nv (.getInt buf (+ pos 8)) vbase (+ pos 12)
                    rl (read-cstr buf strtab-base l-off)
                    rp (read-cstr buf strtab-base p-off)]
                (if (and (= rl l) (= rp p))
                  (mapv (fn [k] (read-cstr buf strtab-base (.getInt buf (+ vbase (* 4 k))))) (range nv))
                  (recur (inc i) (+ vbase (* 4 nv)))))))))))

;; ===========================================================================
;; BOOT PATHS (the head-to-head)
;; ===========================================================================
(defn boot-full-replay
  "NAIVE: read every claim across all logs, fold from scratch. Cost ~ O(total N)."
  [dir]
  (c/fold-state (read-all-claims dir)))

(defn boot-image
  "IMAGE-BOOT: hydrate the snapshot (mmap), then fold only the tail (id > frontier).
   Cost ~ O(image-size) + O(tail). Flat in total N when snapshot cadence bounds the tail."
  [dir snap-path]
  (let [{:keys [state frontier]} (hydrate-mmap snap-path)
        tail (read-tail-claims dir frontier)]
    (fold-into state tail)))

;; ===========================================================================
;; SELF-TEST — correctness + the rebuild-from-logs guarantee (DESIGN.md §6)
;; ===========================================================================
(defn -selftest []
  (let [dir "/dev/shm/snapboot-selftest"
        snapdir (str dir "/snapshots")
        n 20000 nwriters 8 nsubj 1000
        claims (vec (mapcat #(c/gen-claims % (quot n nwriters) nsubj) (range nwriters)))]
    (build-store! dir claims)
    (.mkdirs (io/file snapdir))
    ;; choose a frontier in the MIDDLE of history (so there's a real tail)
    (let [all (read-all-claims dir)
          sorted-ids (sort (map :id all))
          frontier (nth sorted-ids (int (* 0.7 (count sorted-ids))))   ; snapshot @70%, 30% tail
          ;; materialize the index at frontier T = fold(claims <= T)
          head (filter #(<= (compare (:id %) frontier) 0) all)
          snap-state (c/fold-state head)
          snap-path (str snapdir "/snap-" frontier ".fbin")
          snap-meta (write-snapshot! snap-path snap-state frontier)
          ;; boot both ways
          full (boot-full-replay dir)
          img  (boot-image dir snap-path)
          ;; rebuild-from-logs guarantee: re-fold head, re-serialize, expect byte-identical
          snap-path2 (str snapdir "/snap-rebuild.fbin")
          _ (write-snapshot! snap-path2 (c/fold-state head) frontier)
          b1 (Files/readAllBytes (Paths/get snap-path (into-array String [])))
          b2 (Files/readAllBytes (Paths/get snap-path2 (into-array String [])))
          pt (mmap-point-read snap-path (:l (first all)) (:p (first all)))]
      (println "snapshot meta     :" snap-meta)
      (println "tail size (id > T):" (count (read-tail-claims dir frontier)) "of" (count all))
      (println "full-replay state :" (count full) "keys")
      (println "image-boot  state :" (count img) "keys")
      (println "STATE MATCH       :" (= full img))
      (println "REBUILD byte-eq   :" (java.util.Arrays/equals b1 b2) "(snapshot == reserialize(refold(logs<=T)))")
      (println "point-read sample :" (:l (first all)) (:p (first all)) "->" pt)
      (assert (= full img) "image-boot must equal full-replay")
      (assert (java.util.Arrays/equals b1 b2) "snapshot must be reconstructable byte-for-byte from logs")
      (println "\nALL SELF-TESTS PASSED"))))

;; Run the self-test only on the explicit "selftest" arg, so prototype.clj can be load-file'd
;; as a library by the bench/affordance scripts without firing it.
(when (= "selftest" (first *command-line-args*)) (-selftest))
