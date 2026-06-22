;; jvm_imageboot_only.clj — time ONLY image-boot against an ALREADY-BUILT store + snapshot.
;; Used for 10M where build+snapshot+full-replay setup blows the wall-clock budget; the
;; full-replay number is captured separately (data/jvm-boot-10m.txt: 99534 ms). This isolates
;; the image-boot path on the pre-existing /dev/shm store so we get the 10M image-boot point.
;;
;; usage: clojure -J-Xmx32g -M jvm_imageboot_only.clj <store-dir> <snap-path>
(ns jvm-imageboot-only
  (:require [clojure.java.io :as io] [clojure.string :as str] [clojure.edn :as edn])
  (:import [java.io RandomAccessFile ByteArrayOutputStream]
           [java.nio ByteBuffer ByteOrder]
           [java.nio.channels FileChannel FileChannel$MapMode]
           [java.nio.file Paths StandardOpenOption]))
(set! *warn-on-reflection* true)

(def single-preds #{"title" "kind" "owner"})

(defn fold-into [seed tail]
  (let [sorted (sort-by :id tail)
        killed (into #{} (mapcat (fn [c] (concat (:sup c) (:ret c))) sorted))
        alive (remove #(contains? killed (:id %)) sorted)]
    (reduce (fn [st c] (let [k [(:l c) (:p c)]]
                         (cond (= :retract (:op c)) (update st k (fn [v] (if (set? v) (disj v (:r c)) nil)))
                               (single-preds (:p c)) (assoc st k (:r c))
                               :else (update st k (fnil conj #{}) (:r c)))))
            seed alive)))

(defn- line-id ^String [^String line]
  (let [i (.indexOf line "\"") j (.indexOf line "\"" (inc i))]
    (when (and (pos? i) (> j i)) (subs line (inc i) j))))

(defn- tail-of-log [^java.io.File f ^String frontier]
  (with-open [raf (RandomAccessFile. f "r")]
    (let [len (.length raf) chunk 65536]
      (loop [end len ^ByteArrayOutputStream acc (ByteArrayOutputStream.)]
        (let [start (max 0 (- end chunk)) n (int (- end start)) ba (byte-array n)
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

(defn boot-image [dir snap-path]
  (let [{:keys [state frontier]} (hydrate-mmap snap-path)]
    (fold-into state (read-tail-claims dir frontier))))

(defn now [] (System/nanoTime))
(defn ms [t0] (/ (double (- (now) t0)) 1e6))
(defn gc [] (dotimes [_ 3] (System/gc)) (Thread/sleep 50))

(defn -main [& [dir snap]]
  (println (str "## IMAGE-BOOT ONLY — dir=" dir " snap=" snap))
  (boot-image dir snap)                                     ; warm
  (let [times (vec (repeatedly 7 (fn [] (gc) (let [t0 (now)] (boot-image dir snap) (ms t0)))))
        best (apply min times) med (nth (sort times) 3)
        snap-bytes (.length (io/file snap))]
    (println (format "  image-boot best %.1f ms  median %.1f ms" best med))
    ;; breakdown
    (let [t0 (now)
          _ (let [ch (FileChannel/open (Paths/get ^String snap (into-array String []))
                                       (into-array StandardOpenOption [StandardOpenOption/READ]))
                  sz (.size ch) buf (.map ch FileChannel$MapMode/READ_ONLY 0 sz) ba (byte-array sz)]
              (.get buf ba) (.close ch) (areduce ba i acc (long 0) (+ acc (aget ba i))))
          raw-ms (ms t0)
          t0b (now) {:keys [state frontier]} (hydrate-mmap snap) hyd (ms t0b)
          t1 (now) tail (read-tail-claims dir frontier) tread (ms t1)
          t2 (now) _ (fold-into state tail) tfold (ms t2)]
      (println (format "  raw mmap page-in (I/O)  : %8.1f ms   (%.2f MB)" raw-ms (/ snap-bytes 1e6)))
      (println (format "  hydrate decode          : %8.1f ms   (%d keys)" hyd (count state)))
      (println (format "  tail read (id > T)      : %8.1f ms   (%d tail claims)" tread (count tail)))
      (println (format "  tail fold-into state    : %8.1f ms" tfold)))
    (println (format "\nRESULT N=10000000 K=10000 full_best=99534.1 image_best=%.1f image_median=%.1f snap_mb=%.3f speedup=%.1f"
                     best med (/ snap-bytes 1e6) (/ 99534.1 best)))))

(apply -main *command-line-args*)
