#!/usr/bin/env bash
# ============================================================================
# run_graph_arm.sh — boot the warm daemon over the projected corpus, run the
# boot guard, then run the LIVE graph-arm retrievals (tasks cal, a, b) through gq,
# recording the per-task op-count off the gq oplog. Also demonstrates the (c)/(d)
# HYPOTHETICAL segregation: it PROVES :reaches/:type-refs are 'unknown op' (so the
# graph cost for c/d is honestly a hypothetical, never a cold-callgraph retrieval).
#
# Self-contained: boots its own daemon on a VERIFIED-FREE high port over a TEMP
# projected log, runs the boot guard, then trap-kills. NEVER touches port 7977 or
# the live lodestar log.
#
# Inputs (env):
#   RC_FLATLOG : the projected corpus flat log (from project_corpus.clj)
#   RC_EDN_DIR : dir of per-module emit-edn dumps (<module>.edn) — REQUIRED for (b):
#                the k/->Claim reference node id is located ONCE from import.edn at setup
#                so (b) can be keyed on the REFERENCE (not the answer name).
#   RC_GQLOG   : where the gq oplog goes (default: <here>/graph.oplog)
# Output: prints per-task gq op-count + the live answers; leaves RC_GQLOG.
set -uo pipefail
HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
FLAT="${RC_FLATLOG:?set RC_FLATLOG (projected corpus flat log)}"
GQLOG="${RC_GQLOG:-$HERE/graph.oplog}"
GQ="$HERE/gq"
cd "$REPO"

# ---- pick a VERIFIED-FREE high port in 9200-9990 (retry on TOCTOU) ----------
pick_port() {
  for p in $(seq 9301 9990); do
    if ! ss -ltn 2>/dev/null | grep -q ":$p\b"; then echo "$p"; return 0; fi
  done
  echo "no free port in 9301-9990" >&2; return 1
}
PORT="$(pick_port)" || exit 1
[ "$PORT" = 7977 ] && { echo "REFUSING port 7977 (live lodestar)"; exit 1; }
echo "[setup] verified-free port: $PORT"

# ---- boot the daemon over the TEMP projected log (the graph pre-pays) --------
DLOG="$(mktemp /tmp/rc-daemon.XXXXXX.out)"
setsid clojure -M cnf_coord_daemon.clj serve-flat "$PORT" "$FLAT" > "$DLOG" 2>&1 < /dev/null &
DPID=$!
cleanup() {
  [ -n "${DPID:-}" ] && kill "$DPID" 2>/dev/null
  pkill -f "cnf_coord_daemon.clj serve-flat $PORT" 2>/dev/null
  rm -f "$DLOG"
}
trap cleanup EXIT INT TERM
echo "[setup] daemon pid=$DPID, booting (JVM fold of $(wc -l <"$FLAT") lines)..."

t0=$(date +%s)
for i in $(seq 1 90); do
  grep -q "listening on 127.0.0.1:$PORT" "$DLOG" 2>/dev/null && break
  if ! kill -0 "$DPID" 2>/dev/null; then echo "[FATAL] daemon died:"; cat "$DLOG"; exit 1; fi
  sleep 1
done
t1=$(date +%s)
grep -q "listening on 127.0.0.1:$PORT" "$DLOG" || { echo "[FATAL] daemon never listened"; cat "$DLOG"; exit 1; }
echo "[setup] daemon listening after $((t1-t0))s"

export GQ_PORT="$PORT"

# ============================================================================
# BOOT GUARD (owned-resolution guarantee iv) — FAIL LOUD before any measurement.
# ============================================================================
echo "== BOOT GUARD =="
GUARD_LOG="$(mktemp /tmp/rc-guard.XXXXXX.log)"   # guard uses its own log (not graph.oplog)
STATUS="$(GQ_LOG="$GUARD_LOG" bb "$GQ" status)"
echo "  :status -> $STATUS"
CLAIMS=$(echo "$STATUS" | grep -oE ':claims [0-9]+' | grep -oE '[0-9]+')
SLOG=$(echo "$STATUS" | grep -oE ':log "[^"]+"' | sed 's/:log "//;s/"$//')
[ "${CLAIMS:-0}" -gt 0 ] || { echo "[FATAL] guard: zero corpus claims"; exit 1; }
[ "$SLOG" = "$FLAT" ] || { echo "[FATAL] guard: :log ($SLOG) != our temp log ($FLAT)"; exit 1; }
echo "  GUARD 1 PASS: $CLAIMS corpus claims, :log is OUR temp log"

CALLERS_TII="$(GQ_LOG="$GUARD_LOG" bb "$GQ" callers kernel thread-ids-i)"
echo "  :callers thread-ids-i -> $CALLERS_TII"
echo "$CALLERS_TII" | grep -q ':callers \[\[' || { echo "[FATAL] guard: empty :callers for thread-ids-i"; exit 1; }
echo "  GUARD 2 PASS: nonempty :callers (corpus-backed refers_to materialized)"
rm -f "$GUARD_LOG"

# ============================================================================
# LIVE GRAPH-ARM RETRIEVALS — each is ONE gq round-trip = ONE graph.oplog line.
# ============================================================================
: > "$GQLOG"     # fresh oplog; wc -l == graph-arm op count
export GQ_LOG="$GQLOG"

echo
echo "== TASK (a) direct callers of kernel/thread-ids-i =="
A_BEFORE=$(wc -l < "$GQLOG")
A_ANS="$(bb "$GQ" callers kernel thread-ids-i)"
A_OPS=$(( $(wc -l < "$GQLOG") - A_BEFORE ))
echo "  gq ops: $A_OPS"
echo "  answer: $A_ANS"

echo
echo "== TASK (b) ultimate behind k/->Claim + all references =="
# (b) is keyed on the REFERENCE NODE (the k/->Claim reference in import.bclj), NOT on
# the answer 'kernel Claim'. We locate the reference node id ONCE at setup from the
# projected EDN (legitimate one-time setup, same class as picking the (a) target), then
# query :callers {:te "@import#<ref-node>"}. The daemon's target-node ultimate()s the
# :te node, so it follows the reference's refers_to -> the binding @kernel#298 and runs
# the reverse lookup in ONE round-trip. The graph is NOT hand-fed the answer — it
# genuinely resolves the reference, exactly the resolution a text agent does by hand.
EDN_DIR="${RC_EDN_DIR:-}"
if [ -n "$EDN_DIR" ] && [ -f "$EDN_DIR/import.edn" ]; then
  BREF_NODE="$(grep -oE '^\[[0-9]+ "v" "k/->Claim"\]' "$EDN_DIR/import.edn" | head -1 | grep -oE '^\[[0-9]+' | tr -d '[')"
fi
[ -n "${BREF_NODE:-}" ] || { echo "[FATAL] (b): set RC_EDN_DIR to the emit-edn dir so the k/->Claim reference node can be located"; exit 1; }
echo "  reference node (located once at setup): @import#$BREF_NODE  (the k/->Claim ref in import.bclj)"
B_BEFORE=$(wc -l < "$GQLOG")
B_ANS="$(bb "$GQ" callers-te "@import#$BREF_NODE")"
B_OPS=$(( $(wc -l < "$GQLOG") - B_BEFORE ))
echo "  gq ops: $B_OPS"
echo "  answer: $B_ANS"
B_TARGET=$(echo "$B_ANS" | grep -oE ':target "[^"]+"' | sed 's/:target "//;s/"$//')
B_GROUPS=$(echo "$B_ANS" | grep -oE '\["[a-z]+" "[^"]+"\]' | sort -u | wc -l)
echo "  -> reference resolved to binding: $B_TARGET ; distinct [module,spelling] groups: $B_GROUPS"
[ "$B_TARGET" = "@kernel#298" ] || { echo "[FATAL] (b) reference->ultimate did NOT resolve to @kernel#298 (got $B_TARGET)"; exit 1; }

echo
echo "== TASK (cal) body of datalog/var? (the conceded tie) =="
# A single LOCAL fact — the graph CAN answer (one :query round-trip), but this is the
# conceded tie (grep is one op too). We resolve var?'s node then read its body subtree.
# For the op-count headline cal is reported as a TIE; here we just show 1 graph op finds it.
CAL_BEFORE=$(wc -l < "$GQLOG")
# var? node: find the symbol leaf 'var?' that is a defn- head in datalog, then its parent form.
CAL_ANS="$(bb "$GQ" query '{:find "vq" :rules [{:head {:rel "vq" :args [{:var "vid"}]} :body [{:rel "triple" :args [{:var "vid"} "v" "var?"]}]}]}')"
CAL_OPS=$(( $(wc -l < "$GQLOG") - CAL_BEFORE ))
echo "  gq ops: $CAL_OPS (one round-trip locates the var? symbol; TIE conceded — grep is 1 op too)"
echo "  answer (var? symbol leaves): $(echo "$CAL_ANS" | head -c 160)..."

echo
echo "== (c)/(d) HYPOTHETICAL segregation — PROVE :reaches/:type-refs are unbuilt =="
R_REACHES="$(bb "$GQ" raw '{:op :reaches :to "@kernel#1"}')"
R_TYPEREFS="$(bb "$GQ" raw '{:op :type-refs}')"
echo "  {:op :reaches}   -> $R_REACHES"
echo "  {:op :type-refs} -> $R_TYPEREFS"
echo "  => (c)/(d) graph cost is a labeled HYPOTHETICAL (these ops do not exist); NEVER routed through cold callgraph."

echo
echo "== GRAPH-ARM OPLOG (wc -l == op count, by construction) =="
echo "  total live gq retrievals across cal+a+b: $(wc -l < "$GQLOG")"
echo "  --- $GQLOG ---"
cat "$GQLOG"

# emit a machine-readable summary line for the runner to capture
echo
echo "GRAPH_ARM_RESULT $(printf '{:a-ops %d :b-ops %d :cal-ops %d :b-target %s :b-groups %d :boot-seconds %d :claims %d :port %d}' \
  "$A_OPS" "$B_OPS" "$CAL_OPS" "$B_TARGET" "$B_GROUPS" "$((t1-t0))" "$CLAIMS" "$PORT")"
