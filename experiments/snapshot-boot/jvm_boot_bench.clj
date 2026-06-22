;; jvm_boot_bench.clj — the SAME boot benchmark on the JVM (not SCI).
;;
;; Why a JVM variant: the real fram daemon runs compiled on the JVM, where per-call
;; ByteBuffer/string interop is ~50-100x cheaper than babashka/SCI's interpreted loop. The
;; SCI prototype proves CORRECTNESS + the SHAPES (tail-read O(tail), image flat in N); this
;; proves the ABSOLUTE boot-time thesis (<= a couple seconds, sub-second mmap) on the runtime
;; that ships. Same algebra, same B layout, same flat-arena format — just compiled.
;;
;; usage: clojure -M jvm_boot_bench.clj <N> <K> [root]
(ns jvm-boot-bench
  (:require [clojure.java.io :as io] [clojure.string :as str] [clojure.edn :as edn])
  (:import [java.io FileOutputStream RandomAccessFile ByteArrayOutputStream DataOutputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels FileChannel FileChannel$MapMode]
           [java.nio.file Files Paths StandardOpenOption]
           [java.util.concurrent.atomic AtomicLong]))

(set! *warn-on-reflection* true)

;; ---- claim gen + fold (lifted from store-bakeoff/common.clj; same semantics) ----
(def preds ["title" "note" "kind" "owner" "ref"])
(def single-preds #{"title" "kind" "owner"})

(defn mk-hlc-fn [w]
  (let [ctr (AtomicLong. 0)]
    (fn [] (let [t (System/currentTimeMillis) c (.getAndIncrement ctr)]
             (format "%012x-%03x-%07x" t (bit-and w 0xfff) (bit-and c 0xfffffff))))))

(defn gen-claims [w n nsubj]
  (let [hlc (mk-hlc-fn w)]
    (mapv (fn [i] {:id (hlc) :op :assert
                   :l (str "@e" (mod (+ i (* w 7)) nsubj))
                   :p (nth preds (mod i 5)) :r (str "v" w "-" i) :by (str "w" w)})
          (range n))))

(defn fold-state [claims]
  (let [sorted (sort-by :id claims)
        killed (into #{} (mapcat (fn [c] (concat (:sup c) (:ret c))) sorted))
        alive (remove #(contains? killed (:id %)) sorted)]
    (reduce (fn [st c] (let [k [(:l c) (:p c)]]
                         (cond (= :retract (:op c)) (update st k (fn [v] (if (set? v) (disj v (:r c)) nil)))
                               (single-preds (:p c)) (assoc st k (:r c))
                               :else (update st k (fnil conj #{}) (:r c)))))
            {} alive)))

(defn fold-into [seed tail]
  (let [sorted (sort-by :id tail)
        killed (into #{} (mapcat (fn [c] (concat (:sup c) (:ret c))) sorted))
        alive (remove #(contains? killed (:id %)) sorted)]
    (reduce (fn [st c] (let [k [(:l c) (:p c)]]
                         (cond (= :retract (:op c)) (update st k (fn [v] (if (set? v) (disj v (:r c)) nil)))
                               (single-preds (:p c)) (assoc st k (:r c))
                               :else (update st k (fnil conj #{}) (:r c)))))
            seed alive)))

;; ---- store build (candidate B per-writer logs) ----
(defn fresh-dir! [d]
  (let [f (io/file d)] (when (.exists f) (run! io/delete-file (reverse (file-seq f)))) (.mkdirs f) d))

(defn build-store! [dir claims]
  (fresh-dir! dir)
  (doseq [[w cs] (group-by :by claims)]
    (with-open [out (FileOutputStream. (str dir "/" w ".log") true)]
      (doseq [cl cs] (.write out (.getBytes (str (pr-str cl) "\n") "UTF-8")))
      (.flush out)))
  dir)

(defn read-all-claims [dir]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".log"))
       (mapcat (fn [f] (->> (slurp f) str/split-lines (remove str/blank?) (map edn/read-string))))
       vec))

(defn- line-id ^String [^String line]
  (let [i (.indexOf line "\"") j (.indexOf line "\"" (inc i))]
    (when (and (pos? i) (> j i)) (subs line (inc i) j))))

(defn- tail-of-log [^java.io.File f ^String frontier]
  (with-open [raf (RandomAccessFile. f "r")]
    (let [len (.length raf) chunk 65536]
      (loop [end len ^ByteArrayOutputStream acc (ByteArrayOutputStream.)]
        (let [start (max 0 (- end chunk))
              n (int (- end start))
              ba (byte-array n)
              _ (.seek raf start) _ (.readFully raf ba)
              merged (ByteArrayOutputStream.)
              _ (.write merged ba) _ (.write merged (.toByteArray acc))
              s (String. (.toByteArray merged) "UTF-8")
              lines (cond->> (str/split-lines s) (pos? start) (drop 1))
              lines (remove str/blank? lines)
              earliest-id (some-> ^String (first lines) line-id)
              covered? (or (= start 0) (and earliest-id (<= (compare earliest-id frontier) 0)))]
          (if covered?
            (->> lines (filter (fn [l] (when-let [id (line-id l)] (pos? (compare id frontier)))))
                 (mapv edn/read-string))
            (recur start merged)))))))

(defn read-tail-claims [dir ^String frontier]
  (->> (.listFiles (io/file dir))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".log"))
       (mapcat #(tail-of-log % frontier)) vec))

;; ---- snapshot format (flat arena) ----
(def MAGIC (.getBytes "FBSNAP01" "UTF-8"))

(defn- state->rows [state]
  (->> state (map (fn [[[l p] v]] [l p (if (set? v) (vec (sort v)) [v])]))
       (sort-by (fn [[l p _]] [l p])) vec))

(defn write-snapshot! [^String path state ^String frontier]
  (let [rows (state->rows state)
        strs (->> rows (mapcat (fn [[l p vs]] (cons l (cons p vs)))) distinct vec)
        str->idx (into {} (map-indexed (fn [i s] [s i]) strs))
        strtab (ByteArrayOutputStream.) sd (DataOutputStream. strtab)
        offs (reduce (fn [acc s] (let [b (.getBytes ^String s "UTF-8")]
                                   (.writeInt sd (alength b)) (.write sd b)
                                   (conj acc (- (.size strtab) (+ 4 (alength b)))))) [] strs)
        idx->off (vec offs)
        rowbuf (ByteArrayOutputStream.) rd (DataOutputStream. rowbuf)
        _ (doseq [[l p vs] rows]
            (.writeInt rd (int (idx->off (str->idx l))))
            (.writeInt rd (int (idx->off (str->idx p))))
            (.writeInt rd (int (count vs)))
            (doseq [v vs] (.writeInt rd (int (idx->off (str->idx v))))))
        fb (.getBytes frontier "UTF-8")
        strtab-bytes (.toByteArray strtab) rows-bytes (.toByteArray rowbuf)
        hdr (ByteArrayOutputStream.) hd (DataOutputStream. hdr)]
    (.write hd MAGIC) (.writeInt hd 1)
    (.writeInt hd (alength fb)) (.write hd fb)
    (.writeLong hd (count rows)) (.writeLong hd (alength strtab-bytes)) (.writeLong hd (alength rows-bytes))
    (with-open [out (FileOutputStream. path)]
      (.write out (.toByteArray hdr)) (.write out strtab-bytes) (.write out rows-bytes))
    {:path path :rows (count rows) :strs (count strs)
     :bytes (+ (.size hdr) (alength strtab-bytes) (alength rows-bytes))}))

(defn- read-cstr ^String [^ByteBuffer buf ^long strtab-base ^long off]
  (let [p (+ strtab-base off) len (.getInt buf (int p)) ba (byte-array len)]
    (.get buf (int (+ p 4)) ba (int 0) (int len)) (String. ba "UTF-8")))

(defn hydrate-mmap [^String path]
  (with-open [ch (FileChannel/open (Paths/get path (into-array String []))
                                   (into-array StandardOpenOption [StandardOpenOption/READ]))]
    (let [sz (.size ch) buf (.map ch FileChannel$MapMode/READ_ONLY 0 sz)]
      (.order buf ByteOrder/BIG_ENDIAN)
      (let [_ (.get buf (byte-array 8)) _ (.getInt buf)
            flen (.getInt buf) fb (byte-array flen) _ (.get buf fb)
            frontier (String. fb "UTF-8")
            nrows (.getLong buf) strtab-len (.getLong buf) _ (.getLong buf)
            strtab-base (long (.position buf)) rows-base (+ strtab-base strtab-len)]
        (loop [i 0 pos rows-base st (transient {})]
          (if (= i nrows)
            {:state (persistent! st) :frontier frontier :rows nrows}
            (let [l-off (.getInt buf (int pos)) p-off (.getInt buf (int (+ pos 4)))
                  nv (.getInt buf (int (+ pos 8))) vbase (+ pos 12)
                  l (read-cstr buf strtab-base l-off) p (read-cstr buf strtab-base p-off)
                  vals (mapv (fn [k] (read-cstr buf strtab-base (.getInt buf (int (+ vbase (* 4 k)))))) (range nv))
                  v (if (single-preds p) (first vals) (into #{} vals))]
              (recur (inc i) (+ vbase (* 4 nv)) (assoc! st [l p] v)))))))))

;; ---- boot paths ----
(defn boot-full-replay [dir] (fold-state (read-all-claims dir)))
(defn boot-image [dir snap-path]
  (let [{:keys [state frontier]} (hydrate-mmap snap-path)]
    (fold-into state (read-tail-claims dir frontier))))

;; ---- driver ----
(defn now [] (System/nanoTime))
(defn ms [t0] (/ (double (- (now) t0)) 1e6))
(defn gc [] (dotimes [_ 3] (System/gc)) (Thread/sleep 50))

(defn timed [label f n]
  (f)                                                       ; warm
  (let [times (vec (repeatedly n (fn [] (gc) (let [t0 (now)] (f) (ms t0)))))
        best (apply min times) med (nth (sort times) (quot n 2))]
    (println (format "  %-28s best %8.1f ms   median %8.1f ms" label best med))
    {:best best :median med}))

(defn -main [& args]
  (let [N (parse-long (or (first args) "100000"))
        K (parse-long (or (second args) "10000"))
        root (or (nth args 2 nil) (str "/dev/shm/jvm-snapboot-" N))
        nwriters 8 nsubj 2000]
    (println (str "## JVM BOOT BENCH — N=" N " cadence-K=" K " writers=" nwriters " root=" root))
    (let [claims (vec (mapcat #(gen-claims % (quot N nwriters) nsubj) (range nwriters)))]
      (println (str "generated " (count claims) " claims; building B store..."))
      (let [t0 (now)] (build-store! root claims) (println (format "  build: %.0f ms" (ms t0))))
      (let [snapdir (str root "/snapshots")
            _ (.mkdirs (io/file snapdir))
            all-ids (vec (sort (map :id claims)))
            frontier (nth all-ids (max 0 (- (count all-ids) K 1)))
            head (filter #(<= (compare ^String (:id %) frontier) 0) claims)
            snap-path (str snapdir "/snap-" frontier ".fbin")
            t0 (now)
            snap-state (fold-state head)
            meta (write-snapshot! snap-path snap-state frontier)
            _ (println (format "  snapshot(@T): fold+serialize %.0f ms -> %d rows, %.2f MB image"
                               (ms t0) (:rows meta) (/ (:bytes meta) 1e6)))
            snap-bytes (.length (io/file snap-path))
            store-bytes (reduce + (map #(.length ^java.io.File %)
                                       (filter #(str/ends-with? (.getName ^java.io.File %) ".log")
                                               (.listFiles (io/file root)))))]
        (println (format "  image %.2f MB  vs  full log %.2f MB  (%.0fx smaller)"
                         (/ snap-bytes 1e6) (/ store-bytes 1e6) (/ (double store-bytes) snap-bytes)))
        (println "\n-- BOOT (warm cache; daemon-restart steady state) --")
        (let [fr (timed "full-replay (refold all)" #(boot-full-replay root) 3)
              ir (timed "image-boot (mmap+tail)" #(boot-image root snap-path) 7)]
          (println "  MATCH =" (= (boot-full-replay root) (boot-image root snap-path)))
          (println "\n-- IMAGE-BOOT breakdown --")
          (let [t0 (now)
                _ (let [ch (FileChannel/open (Paths/get snap-path (into-array String []))
                                             (into-array StandardOpenOption [StandardOpenOption/READ]))
                        sz (.size ch) buf (.map ch FileChannel$MapMode/READ_ONLY 0 sz)
                        ba (byte-array sz)] (.get buf ba) (.close ch)
                        (areduce ba i acc (long 0) (+ acc (aget ba i))))
                raw-ms (ms t0)
                t0b (now) {:keys [state frontier]} (hydrate-mmap snap-path) hyd (ms t0b)
                t1 (now) tail (read-tail-claims root frontier) tread (ms t1)
                t2 (now) _ (fold-into state tail) tfold (ms t2)]
            (println (format "  raw mmap page-in (I/O)  : %8.1f ms   (%.2f MB resident; the true I/O term)" raw-ms (/ snap-bytes 1e6)))
            (println (format "  hydrate decode          : %8.1f ms   (%d keys; compiled buffer walk)" hyd (count state)))
            (println (format "  tail read (id > T)      : %8.1f ms   (%d tail claims)" tread (count tail)))
            (println (format "  tail fold-into state    : %8.1f ms" tfold))
            (println (format "  TOTAL image-boot        : %8.1f ms" (+ hyd tread tfold))))
          (println (format "\nRESULT N=%d K=%d full_best=%.1f image_best=%.1f image_median=%.1f snap_mb=%.3f log_mb=%.2f speedup=%.1f"
                           N K (:best fr) (:best ir) (:median ir)
                           (/ snap-bytes 1e6) (/ store-bytes 1e6) (/ (:best fr) (:best ir)))))))))

(apply -main *command-line-args*)
