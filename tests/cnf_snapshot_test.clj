;; cnf_snapshot_test.clj — thread 019f100f-7fff gate.
;; Proves the incremental head-materialization (snapshot image + tail-fold reload +
;; incremental boot) reconstructs EXACTLY a from-scratch whole migrate of the flat log,
;; and that after a snapshot the head can be COMPACTED away (boot still reconstructs it
;; from the image). Run: bb -cp out tests/cnf_snapshot_test.clj
(require '[fram.cnf :as c] '[fram.schema :as s] '[clojure.string :as str])
(load-file "cnf_coord_daemon.clj")

(def LOG "/tmp/cnf-snapshot-test.log")
(defn ln [tx op l p r] (pr-str {:tx tx :op op :l l :p p :r r :ts "t" :by "test"}))
(defn write-lines! [path lines] (spit path (str (str/join "\n" lines) "\n")))
(defn append-lines! [path lines] (spit path (str (str/join "\n" lines) "\n") :append true))
(defn full-migrate-triples [log] (live-name-triples (migrate-flat->co log)))

(let [checks (atom [])
      chk (fn [nm ok] (swap! checks conj [nm (boolean ok)]))]

  ;; clean any prior snapshot dir/sidecar so a stale image can't mask a regression
  (doseq [p [(str LOG ".snap") (str LOG ".snapshots/snap-5.v2log")]]
    (let [f (java.io.File. p)] (when (.exists f) (.delete f))))

  ;; --- (A) seed a flat log through tx 5, boot flat (drop-in) mode ---
  (write-lines! LOG
    [(ln 1 "assert" "@T1" "title" "First")     ; single (kernel single-valued)
     (ln 2 "assert" "@T1" "tag"   "a")         ; multi
     (ln 3 "assert" "@T1" "tag"   "b")         ; multi
     (ln 4 "assert" "@T2" "title" "Two")
     (ln 5 "assert" "@T1" "owner" "alice")])   ; single
  (boot-flat! LOG)
  (chk "boot(cold): live == whole-migrate" (:ok (snapshot-reconcile)))

  ;; --- (B) checkpoint: dump-log! image + @snapshot:<seq> claims + sidecar ---
  (def snap (write-snapshot! @co LOG))
  (chk "snapshot: image file exists"  (.exists (java.io.File. (str (:image snap)))))
  (chk "snapshot: sidecar exists"     (.exists (java.io.File. (str LOG ".snap"))))
  (chk "snapshot: covers_through == boot seq (5)" (= 5 (:ok snap)))

  ;; --- (C) out-of-band tail: supersede a single, add+retract a multi, new subject ---
  (let [base (current-seq @co)]        ; past the snapshot's own @snapshot:* claims
    (append-lines! LOG
      [(ln (+ base 1) "assert"  "@T1" "title" "Updated")  ; supersede single ACROSS boundary
       (ln (+ base 2) "assert"  "@T1" "tag"   "c")        ; new multi edge
       (ln (+ base 3) "retract" "@T1" "tag"   "a")        ; retract a snapshot-era multi edge
       (ln (+ base 4) "assert"  "@T3" "title" "Three")])) ; brand-new subject
  (def full-after-tail (full-migrate-triples LOG))   ; ground truth (independent whole fold)
  (maybe-reload!)                                    ; mtime+len changed -> TAIL-FOLD path
  (chk "reload(tail-fold): live == whole-migrate" (:ok (snapshot-reconcile)))
  (chk "reload(tail-fold): live == saved ground truth" (= (live-name-triples @co) full-after-tail))
  (let [st (:store @co) t1 (s/resolve-name st "@T1")]
    (chk "single superseded across boundary -> 'Updated'"
         (= "Updated" (s/lookup st t1 "title")))
    (chk "multi: 'a' retracted, 'b'+'c' live"
         (= #{"b" "c"} (set (s/lookup-all st t1 "tag"))))
    (chk "new subject @T3 materialized" (some? (s/resolve-name st "@T3"))))

  ;; --- (D) RESTART: a fresh boot must use the snapshot image + tail (incremental) ---
  (boot-flat! LOG)
  (chk "boot(incremental): live == whole-migrate" (:ok (snapshot-reconcile)))
  (chk "boot(incremental): live == ground truth"  (= (live-name-triples @co) full-after-tail))

  ;; --- (E) COMPACTION proof: drop every flat line <= covers_through; only the image
  ;; carries the head now. Boot must STILL reconstruct the full state -> the head was
  ;; served by the snapshot, not re-folded from the log (the O(history) cost is gone). ---
  (let [tail-only (->> (str/split-lines (slurp LOG))
                       (remove str/blank?)
                       (filter #(> (long (:tx (read-string %))) 5)))]
    (write-lines! LOG tail-only)
    (write-sidecar! LOG (assoc (read-sidecar LOG) :byte_offset 0)))  ; tail now starts at byte 0
  (boot-flat! LOG)
  (chk "boot(post-compaction): full state from image+tail" (= (live-name-triples @co) full-after-tail))
  (let [st (:store @co)]
    (chk "post-compaction: head 'owner alice' survived (from image)"
         (= "alice" (s/lookup st (s/resolve-name st "@T1") "owner")))
    (chk "post-compaction: @T2 (head-only subject) survived (from image)"
         (some? (s/resolve-name st "@T2"))))

  ;; --- (F) REGRESSION GUARD (thread 019f1184-d851): a log reverted/truncated BELOW the
  ;; live state must be REFUSED, not silently adopted (the git-checkout data-loss vector). ---
  (boot-flat! LOG)                                   ; fresh good boot (image + tail)
  (let [before (live-name-triples @co)
        bt     (long @built-through)]
    (write-lines! LOG [(ln 1 "assert" "@T1" "title" "First")])   ; revert to an ancient 1-line log
    (.setLastModified (java.io.File. LOG) (+ 1000000 (.lastModified (java.io.File. LOG))))
    (maybe-reload!)                                  ; must REFUSE: log max-tx=1 << built-through
    (chk "regression: reverted log REFUSED — live state preserved" (= before (live-name-triples @co)))
    (chk "regression: built-through NOT rolled back" (= bt (long @built-through))))

  ;; --- report ---
  (let [cs @checks fails (remove second cs)]
    (println "\n=== snapshot / tail-fold / incremental boot (thread 019f100f-7fff) ===")
    (doseq [[nm ok] cs] (println (if ok "  [PASS] " "  [FAIL] ") nm))
    (if (empty? fails)
      (do (println (str "\nSnapshot: " (count cs) " / " (count cs) " PASS")) (System/exit 0))
      (do (println (str "\nSnapshot: " (count fails) " FAILED")) (System/exit 1)))))
