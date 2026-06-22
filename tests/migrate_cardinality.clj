;; migrate_cardinality.clj — finding #23 backfill: make the GRAPH carry the
;; cardinality vocab that USED to live in FRAM_SINGLE_VALUED.
;;
;; The cold fold (fram.fold) + warm migrate (cnf_coord_daemon) now read a
;; predicate's cardinality from the log's `(<pred> cardinality "single")` claim,
;; defaulting to MULTI when none is present. A log written before #23 has NO
;; cardinality claims, so EVERY single-valued pred (title/owner/…) would now fold
;; as MULTI — accumulating duplicate live values instead of superseding. This
;; tool PREPENDS the missing `cardinality "single"` claims so the post-#23 fold
;; reproduces the pre-#23 (env-driven) behaviour exactly.
;;
;; USAGE (NEVER run on a live/canonical log in place — write to a NEW file):
;;   bb -cp out tests/migrate_cardinality.clj <in-log> <out-log> [--dry-run]
;;   bb -cp out tests/migrate_cardinality.clj <in-log> --dry-run        ; report only
;;
;; SAFETY:
;;   - Input is NEVER mutated. Output is a new file (or stdout under --dry-run).
;;   - Existing log lines are copied VERBATIM (same :tx/:ts/:frame); we only
;;     PREPEND new `cardinality` lines at tx 0 (schema bootstrap, below all data).
;;   - IDEMPOTENT: a pred that already has ANY `cardinality` claim in the log is
;;     skipped, so re-running adds nothing.
;;   - The single-valued SET is DECLARED below (NOT read from the env), reconciled
;;     against the live lodestar vocab (bin/lodestar:11). Verify it before running.
(require '[fram.rt]
         '[fram.fold :as fold]
         '[clojure.java.io :as io]
         '[clojure.string :as str])

;; ---------------------------------------------------------------------------
;; THE DECLARED SINGLE-VALUED SET (finding #23 migration target).
;; This is the EXACT set the live lodestar daemon treated as single-valued via
;; FRAM_SINGLE_VALUED (bin/lodestar:11) — so backfilling it reproduces current
;; behaviour byte-for-byte. `emoji_<condition>` presentation preds were single by
;; PREFIX (now removed); the known conditions are enumerated and added too.
;; (`assignee` is NOT here: it is single in the cnf_lifecycle_test fixture but was
;; NEVER in the live env, so the live daemon treated it as MULTI — see report.)
(def single-valued-preds
  ["title" "owner" "lead" "driver" "source" "part_of"
   "do_on" "valid_until" "estimate_hours" "created_at" "updated_at"
   "name" "display_name" "body" "created_by" "committed"
   "outcome" "abandoned" "superseded_by" "merged_into"
   "session_of" "start_time" "end_time" "clockify_id"])

;; emoji_<condition> presentation preds (lodestar/projections default-emoji set).
;; These were single-valued-by-prefix in the old kernel; now each carries a real
;; cardinality claim so cold + warm agree (and re-configuring a glyph REPLACES).
(def emoji-conditions ["active" "ready" "blocked" "dormant" "terminal" "draft"])
(def emoji-preds (mapv #(str "emoji_" %) emoji-conditions))

(def all-single (vec (concat single-valued-preds emoji-preds)))

;; ---------------------------------------------------------------------------
(def args *command-line-args*)
(when (< (count args) 1)
  (println "usage: bb -cp out tests/migrate_cardinality.clj <in-log> <out-log> [--dry-run]")
  (println "       bb -cp out tests/migrate_cardinality.clj <in-log> --dry-run")
  (System/exit 2))

(def in-log (first args))
(def dry-run? (some #(= % "--dry-run") args))
(def out-log (first (remove #(= % "--dry-run") (rest args))))

(when-not (.exists (io/file in-log))
  (println (str "migrate_cardinality: input log not found: " in-log))
  (System/exit 2))
(when (and (not dry-run?) (nil? out-log))
  (println "migrate_cardinality: an <out-log> is required unless --dry-run is given.")
  (System/exit 2))
(when (and out-log (= (.getCanonicalPath (io/file in-log)) (.getCanonicalPath (io/file out-log))))
  (println "migrate_cardinality: REFUSING to write over the input log (in-place). Choose a different <out-log>.")
  (System/exit 2))

;; preds that ALREADY carry a cardinality claim (any value) — leave them alone.
(def raw (fram.rt/read-log in-log))
(def already-declared
  (set (->> raw (filter #(= (:p %) "cardinality")) (map :l))))

;; the single-valued preds we will backfill: declared set minus already-declared.
(def to-add (vec (remove already-declared all-single)))

;; which of those actually APPEAR as a predicate in the log (informational —
;; we still emit ALL of to-add so a later write to an unused pred supersedes too).
(def used-preds (set (map :p raw)))
(def to-add-used (filterv used-preds to-add))
(def to-add-unused (filterv #(not (used-preds %)) to-add))

(println (str "migrate_cardinality: " in-log))
(println (str "  log lines:                 " (count raw)))
(println (str "  preds with a cardinality claim already: " (count already-declared)
              (when (seq already-declared) (str " " (sort already-declared)))))
(println (str "  single-valued preds to backfill:        " (count to-add)))
(println (str "    used in this log:        " (count to-add-used) " " to-add-used))
(println (str "    declared-but-unused:     " (count to-add-unused) " " to-add-unused))

;; the new lines: one `(<pred> cardinality "single")` assert per pred, tx 0.
(def card-assertions
  (mapv (fn [p] (fold/->Assertion 0 "assert" p "cardinality" "single" "migrate-23")) to-add))

(if dry-run?
  (do
    (println "\n  --dry-run: would PREPEND these lines (none written):")
    (doseq [a card-assertions]
      (println (str "    " (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a)}))))
    (println (str "\n  (re-run without --dry-run and with an <out-log> to write " (+ (count raw) (count card-assertions)) " lines.)")))
  (let [ts (fram.rt/now-ts)
        new-lines (mapv (fn [a] (pr-str {:tx (:tx a) :op (:op a) :l (:l a) :p (:p a) :r (:r a) :frame (:frame a) :ts ts}))
                        card-assertions)
        ;; existing lines copied VERBATIM (preserve original bytes/tx/ts/frame).
        orig-lines (->> (str/split-lines (slurp in-log)) (remove str/blank?) vec)
        out (str (str/join "\n" (concat new-lines orig-lines)) "\n")]
    (spit out-log out)
    (println (str "\n  wrote " out-log ": " (count new-lines) " cardinality lines + "
                  (count orig-lines) " original lines = " (+ (count new-lines) (count orig-lines)) " total."))
    ;; verify the round-trip folds: each backfilled pred now reads single.
    (let [folded (fram.rt/read-log out-log)
          claims (:claims (fold/fold folded))
          ;; the fold-local cardinality reader (kernel) — confirm single after migrate.
          card-of (fn [p] (let [hit (first (filter #(and (= (:l %) p) (= (:p %) "cardinality")) claims))]
                            (if hit (:r hit) "multi")))
          bad (filterv #(not= "single" (card-of %)) to-add)]
      (println (str "  verify: " (- (count to-add) (count bad)) "/" (count to-add) " backfilled preds fold as single"
                    (when (seq bad) (str " — FAILED for: " bad))))
      (when (seq bad) (System/exit 1)))))
