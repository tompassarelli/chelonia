#!/usr/bin/env bash
# harness.sh <scenario-id>
# ============================================================================
# Runs BOTH arms of a rename refactor with PER-LAYER wall-time attribution.
#   arm-G   = Beagle-as-graph, fram rename verb (identity re-point through the daemon)
#   arm-LSP = raw Clojure (beagle-emitted .clj) + the real clojure-lsp rename
# Verifies BOTH arms against the scenario's behavioral oracle. Exits non-zero on
# any failure. All times in ms. Anything not isolated is printed as UNATTRIBUTED;
# it is NEVER folded into a named layer (LOOP-SPEC rule 3).
#
# Warm-vs-warm by construction: arm-G uses a persistent JVM daemon; arm-LSP is
# primed once (warms clj-kondo .cache) then timed. NOTE recorded by the harness:
# a persistent editor LSP server would be FASTER than this CLI invocation, so the
# arm-LSP number here is an UPPER bound on its warm latency (conservative for arm-G).
#
# Isolated: /tmp working dir + non-7977 port. Needs racket, babashka, beagle, clojure-lsp.
set -euo pipefail
SCN="${1:?usage: harness.sh <scenario-id>}"
FRAM="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"; cd "$FRAM"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle}"
PORT="${FRAM_TEST_PORT:-7993}"
[ "$PORT" = "7977" ] && { echo "FATAL: refusing port 7977 (live coordinator)"; exit 1; }
HDIR="experiments/authoring-harness"
WD="$(mktemp -d /tmp/harness-$SCN.XXXXXX)"

now(){ date +%s%N; }
ms(){ echo $(( ( $(now) - $1 ) / 1000000 )); }

kill_port(){ for p in $(ss -tlnpH "sport = :$PORT" 2>/dev/null | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u); do kill "$p" 2>/dev/null || true; done; }
trap 'kill_port; rm -rf harness "$WD"' EXIT
kill_port

# --- scenario registry --------------------------------------------------------
# Each scenario sets: SRC (bclj), MODULE, OLD, NEW (rename), CLJ_NS, and an oracle
# (ORACLE_FN/ORACLE_ARG/ORACLE_WANT). N is the count of references the rename touches
# (for the cost-vs-N curve); set descriptively.
case "$SCN" in
  greet)   # purpose-built shakedown; behavioral oracle
    SRC="$HDIR/greet.bclj"; MODULE="greet"; OLD="base"; NEW="greeting"
    CLJ_NS="harness.greet"; ORACLE_MODE=behavioral
    ORACLE_FN="greet"; ORACLE_ARG='"world"'; ORACLE_WANT='"hello world"'
    NREFS=1 ;;
  # --- cost-vs-N curve INSIDE one module (query.bclj): module size HELD CONSTANT, only N varies.
  #     all targets are intra-query, cross-module=0 (single-module rename is complete). recompile oracle.
  query-N1) SRC="src/fram/query.bclj"; MODULE="query"; OLD="lit-errors";  NEW="lit-errs";  CLJ_NS="fram.query"; ORACLE_MODE=recompile; NREFS=1 ;;
  query-N2) SRC="src/fram/query.bclj"; MODULE="query"; OLD="strata-of";   NEW="strata-fn"; CLJ_NS="fram.query"; ORACLE_MODE=recompile; NREFS=2 ;;
  query-N3) SRC="src/fram/query.bclj"; MODULE="query"; OLD="max-results"; NEW="max-res";   CLJ_NS="fram.query"; ORACLE_MODE=recompile; NREFS=3 ;;
  query-varsof|query-N4)   # private defn-, 4 intra-module refs, no cross-module use
    SRC="src/fram/query.bclj"; MODULE="query"; OLD="vars-of"; NEW="vars-set"
    CLJ_NS="fram.query"; ORACLE_MODE=recompile
    NREFS=4 ;;
  *) echo "unknown scenario: $SCN (add it to the registry)"; exit 2 ;;
esac

echo "## scenario=$SCN module=$MODULE rename $OLD->$NEW N(refs)=$NREFS port=$PORT"
echo "## wd=$WD"

# behavioral oracle (only for ORACLE_MODE=behavioral): load a clj, call the fn, assert ==.
# recompile mode uses recompile-clean as the correctness gate instead (an un-re-pointed ref =>
# undefined symbol => build fails); used when the target is an internal helper with no clean oracle.
if [ "$ORACLE_MODE" = behavioral ]; then
cat > "$WD/oracle.bb" <<ORACLE
(let [f (first *command-line-args*)]
  (load-file f)
  (let [r ((resolve '$CLJ_NS/$ORACLE_FN) $ORACLE_ARG)]
    (when (not= $ORACLE_WANT r) (println "ORACLE FAIL:" f "->" (pr-str r)) (System/exit 1))
    (println "ORACLE PASS:" f "->" (pr-str r))))
ORACLE
fi

cp "$SRC" "$WD/$MODULE.bclj"

# ============================== arm-LSP =====================================
# source = beagle-emitted .clj of the ORIGINAL module (the same program as text).
echo; echo "### arm-LSP: raw Clojure + real clojure-lsp rename"
PROJ="$WD/lsp-proj"; NSPATH="${CLJ_NS//.//}"; NSPATH="${NSPATH//-/_}"
mkdir -p "$PROJ/src/$(dirname "$NSPATH")"
echo '{:paths ["src"]}' > "$PROJ/deps.edn"
"$BEAGLE/bin/beagle" build "$WD/$MODULE.bclj" "$PROJ/src/$NSPATH.clj" >/dev/null
# prime: read-only analysis to warm the clj-kondo cache WITHOUT mutating the file
# (renaming as a prime would pollute the cache with the post-rename symbol -> stale)
clojure-lsp diagnostics --project-root "$PROJ" >/dev/null 2>&1 || true
t=$(now)
clojure-lsp rename --project-root "$PROJ" --from "$CLJ_NS/$OLD" --to "$CLJ_NS/$NEW" >/dev/null 2>&1
LSP_RENAME=$(ms "$t")
# completeness: emitted clj has NO doc comments, so OLD must be FULLY absent post-rename (a missed
# ref would leave OLD) and NEW present. This is clojure-lsp's rename completeness, directly checked.
{ grep -qw "$NEW" "$PROJ/src/$NSPATH.clj" && ! grep -qw "$OLD" "$PROJ/src/$NSPATH.clj"; } || { echo "arm-LSP FAIL: rename incomplete ($OLD->$NEW) — lsp left a ref or missed the def"; exit 1; }
if [ "$ORACLE_MODE" = behavioral ]; then
  t=$(now); bb "$WD/oracle.bb" "$PROJ/src/$NSPATH.clj" >/dev/null; LSP_RECOMPILE="$(ms "$t")ms(clj-load,dynamic)"
else
  LSP_RECOMPILE="n/a(single-module project lacks cross-module deps to load)"
fi
echo "arm-LSP  rename(lsp,warm)=${LSP_RENAME}ms  recompile=${LSP_RECOMPILE}"

# ============================== arm-G =======================================
echo; echo "### arm-G: Beagle-as-graph, fram rename verb (identity re-point)"
t=$(now); bin/fram-ingest-code "$WD/$MODULE.bclj" --out "$WD/code.log" >/dev/null 2>&1; G_INGEST=$(ms "$t")
FRAM_PORT="$PORT" FRAM_LOG="$WD/code.log" bin/fram-up >/dev/null   # persistent daemon (warm)
sleep 0.3
before=$(grep -c . "$WD/code.log")
t=$(now)
bb -cp out bin/fram-edit-code rename "$MODULE" --old "$OLD" --new "$NEW" --port "$PORT" --log "$WD/code.log" >/dev/null 2>&1
G_EDIT=$(ms "$t")
after=$(grep -c . "$WD/code.log")
t=$(now); bb -cp out bin/fram-render-code "$MODULE" --log "$WD/code.log" --out "$WD/G.bclj" >/dev/null 2>&1; G_RENDER=$(ms "$t")
grep -qw "$NEW" "$WD/G.bclj" || { echo "arm-G FAIL: new name absent in render"; exit 1; }   # OLD may persist in COMMENTS — that is the Tier-1 no-false-hit property, NOT a failure; correctness = recompile+oracle
t=$(now); "$BEAGLE/bin/beagle-build-all" "$WD/G.bclj" >/dev/null; G_RECOMPILE=$(ms "$t")   # 0 errors == every ref re-pointed (correctness gate for BOTH modes)
if [ "$ORACLE_MODE" = behavioral ]; then bb "$WD/oracle.bb" harness/$MODULE.clj >/dev/null; fi
rm -rf harness
echo "arm-G    ingest(setup,one-time)=${G_INGEST}ms  edit(daemon-repoint,warm)=${G_EDIT}ms  render=${G_RENDER}ms  recompile(beagle,typed)=${G_RECOMPILE}ms"
echo "arm-G    log: $before -> $after claims (rename = +1 assert/+1 retract = O(1))"

# ============================== report ======================================
echo
echo "RESULT scenario=$SCN N=$NREFS"
echo "  arm-LSP  rename=${LSP_RENAME}ms  recompile=${LSP_RECOMPILE}"
echo "  arm-G    edit=${G_EDIT}ms  render=${G_RENDER}ms  recompile=${G_RECOMPILE}ms(typed-build)"
echo "  UNATTRIBUTED(arm-G edit): bb-startup + socket round-trip vs daemon-op-core not yet split"
echo "  CAVEAT: recompile layers are NOT apples-to-apples (arm-G beagle typecheck+emit vs arm-LSP clj dynamic load); compare RENAME op as the clean cross-arm number"
echo "  NOTE: arm-LSP via CLI pays native-binary startup; a persistent editor LSP server is faster, so arm-LSP rename here is an UPPER bound"
echo "OK"
