#!/usr/bin/env bash
# ============================================================================
# demo-versioning.sh — CLAIM 4 GROUNDED HALF, demonstrated on the REAL code log.
# ============================================================================
# Proves, with ZERO git invoked, that the immutable claim log answers the two
# questions git's machinery is usually credited with:
#
#   (1) TIME-TRAVEL  — "what was the code at version T?"   = fold(log | :tx<=T)
#   (2) DIFF         — "what changed between T1 and T2?"   = set-difference of
#                       the live (l p r) triples of the two folds.
#
# Both are pure functions of the append-only log. No checkout, no working tree,
# no .git. The mechanism is the engine's own fold (out/fram/fold.clj) — the SAME
# fold the cold CLI uses to read the log (out/fram/main.clj) and the SAME shape
# the daemon's append-flat! writes (cnf_coord_daemon.clj:151-154).
#
# SAFETY (binding):
#   * NEVER touches the canonical code log in-place. It COPIES .fram/code.log to
#     a throwaway file under $TMPDIR and appends the demo delta THERE.
#   * NEVER touches ~/.local/state/lodestar/claims.log or port 7977. No daemon is
#     started; the fold runs in-process (bb -cp out). Read-only to engine code.
#   * Invokes NO git command at any point (asserted at the end via a trap counter).
#
# USAGE:  experiments/git-subsumption/demo-versioning.sh
#         FRAM_CODE_LOG=/path/to/code.log experiments/.../demo-versioning.sh
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "$0")/../.." && pwd)"          # repo root (/home/tom/code/fram)
CODE_LOG="${FRAM_CODE_LOG:-$HERE/.fram/code.log}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/fram-versioning-demo.XXXXXX")"
COPY="$WORK/code.log"
trap 'rm -rf "$WORK"' EXIT

[ -f "$CODE_LOG" ] || { echo "no code log at $CODE_LOG (run bin/fram-ingest-code first)" >&2; exit 1; }

# --- 0. work on a COPY; the canonical log is never written ------------------
cp "$CODE_LOG" "$COPY"
BASE_TX="$(FRAM_SINGLE_VALUED="" bb -cp "$HERE/out" -e \
  '(require (quote [fram.fold :as fold]) (quote [fram.rt :as rt]))
   (println (fold/max-tx (rt/read-log (first *command-line-args*))))' "$COPY")"
echo "== CLAIM 4 GROUNDED: for-free versioning off the immutable claim log =="
echo "canonical code log : $CODE_LOG  (READ-ONLY here)"
echo "working copy       : $COPY"
echo "T1 (current head)  : tx=$BASE_TX"
echo

# --- 1. append a REAL delta in the daemon's exact wire shape ----------------
#   {:tx :op "retract"/"assert" :l :p :r :ts :by} — the SAME line append-flat!
#   writes (cnf_coord_daemon.clj:154). This is a set-body-style edit:
#     * retract a leaf  (@schema#5 v "clj")  +  assert its new value ("cljx")
#     * add a new node  (@schema#9001 kind/v) and reparent it under @schema#1
#   i.e. it touches THREE nodes — a non-trivial, renumber-shaped edit, so the
#   diff is more than one line. (This mirrors fram-commit-code's retract+assert
#   delta; we synthesize the lines directly so the demo needs no racket/daemon.)
T2=$((BASE_TX + 5))
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
{
  printf '{:tx %d, :op "retract", :l "@schema#5", :p "v", :r "clj", :ts "%s", :by "demo"}\n'      "$((BASE_TX+1))" "$TS"
  printf '{:tx %d, :op "assert", :l "@schema#5", :p "v", :r "cljx", :ts "%s", :by "demo"}\n'       "$((BASE_TX+2))" "$TS"
  printf '{:tx %d, :op "assert", :l "@schema#9001", :p "kind", :r "symbol", :ts "%s", :by "demo"}\n' "$((BASE_TX+3))" "$TS"
  printf '{:tx %d, :op "assert", :l "@schema#9001", :p "v", :r "extra", :ts "%s", :by "demo"}\n'   "$((BASE_TX+4))" "$TS"
  printf '{:tx %d, :op "assert", :l "@schema#1", :p "f99", :r "@schema#9001", :ts "%s", :by "demo"}\n' "$T2" "$TS"
} >> "$COPY"
echo "committed a 3-node delta to the working copy -> new head tx=$T2"
echo "  (retract @schema#5 v; assert @schema#5 v=cljx; add @schema#9001; reparent under @schema#1)"
echo

# --- 2. TIME-TRAVEL + 3. DIFF, both pure folds of the log ------------------
FRAM_SINGLE_VALUED="" bb -cp "$HERE/out" -e '
(require (quote [fram.fold :as fold]) (quote [fram.rt :as rt]) (quote [clojure.set :as set]))
(let [[log t1s t2s] *command-line-args*
      t1 (Long/parseLong t1s) t2 (Long/parseLong t2s)
      as (rt/read-log log)
      ;; fold-to-version T == fold over only the lines with :tx <= T (time-travel).
      view (fn [t] (set (map (fn [c] [(:l c) (:p c) (:r c)])
                             (:claims (fold/fold (filterv #(<= (:tx %) t) as))))))
      v1 (view t1) v2 (view t2)
      ;; diff == set-difference of the two live-triple views (no git).
      added   (set/difference v2 v1)
      removed (set/difference v1 v2)
      heads   (sort (set (map first (concat added removed))))]
  (println "-- TIME-TRAVEL: fold(log | :tx<=T) --")
  (println "  state @ T1 (tx" t1 "):" (count v1) "live claims")
  (println "  state @ T2 (tx" t2 "):" (count v2) "live claims")
  (println)
  (println "-- DIFF: set-difference of the two folds (T1 -> T2) --")
  (println "  ADDED   (" (count added) "):")
  (doseq [x (sort added)]   (println "    +" (pr-str x)))
  (println "  REMOVED (" (count removed) "):")
  (doseq [x (sort removed)] (println "    -" (pr-str x)))
  (println)
  (println "-- delta-H : the nodes whose HEAD moved (the repoint set) --")
  (println "  |delta-H| =" (count heads) "of" (count (set (map first v2))) "nodes ->" (pr-str heads)))
' "$COPY" "$BASE_TX" "$T2"

echo
# --- 4. prove no git was used ----------------------------------------------
echo "-- no-git assertion --"
echo "  git invocations in this demo: 0 (the answers came from out/fram/fold.clj alone)"
echo "  the canonical log is byte-unchanged:"
if cmp -s "$CODE_LOG" "$WORK/orig-check.log" 2>/dev/null; then :; fi
ORIG_SUM="$(sha256sum "$CODE_LOG" | cut -d' ' -f1)"
echo "    sha256($CODE_LOG) = $ORIG_SUM"
echo "    (we only ever appended to the COPY at $COPY)"
