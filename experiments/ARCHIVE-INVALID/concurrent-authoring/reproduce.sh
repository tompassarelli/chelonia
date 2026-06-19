#!/usr/bin/env bash
# ============================================================================
# TIER-2 — concurrent authoring through the claim graph beats file-based editing.
# REPRODUCTION: a single self-contained script that runs BOTH arms over the SAME
# scenario (scenario.edn) and lets the BEAGLE COMPILER — not us — render the verdict.
#
# THESIS: many agents authoring ONE codebase CONCURRENTLY through the live claim
# graph beats file-based editing — on CORRECTNESS / CONVERGENCE under concurrency,
# NOT on speed. The oracle is the same as tier-1: the compiler. The graph arm's
# final tree must recompile `11 built, 0 error(s)` (or cleanly REJECT a bad edit,
# never commit a broken tree). The file arm's final tree, produced by the strongest
# real file workflow, is shown by the SAME compiler to FAIL — `10 built, 1 error(s)`.
#
# THE PRECISE DISCRIMINATOR (no overclaim): the file arm's MERGE TOOLING
# (git 3-way + grep + clojure-lsp-on-lowered-.clj) is BLIND to a stale cross-module
# reference and reports the merge GREEN. The file arm's COMPILER — run as CI, the
# SAME oracle — is NOT blind: it catches it LOUDLY as `10 built, 1 error(s)` and
# refuses to ship. So the honest comparison is:
#   FILE: CI catches the broken merge -> the merge is REJECTED -> MANUAL REWORK.
#   GRAPH: identity re-resolves the reference -> the broken tree is NEVER produced
#          -> ZERO rework. (correct-by-construction)
# The graph's win is NOT "the file arm silently ships broken code"; it is
# "no broken intermediate ever exists, so there is nothing to rework."
#
# You will watch, in front of you, the K=4 scenario play out on both arms:
#   - the FILE arm's DIVERGENT-rename pair (ops 1+3)  -> a real git CONFLICT in 8
#     files (VISIBLE — conceded as a file-arm strength);
#   - the FILE arm's STALE cross-module reference pair (ops 1+2) -> a merge CLEAN on
#     every MERGE/TEXTUAL tool (git: 0 conflicts; grep: k/Claim still present; LSP
#     blind to the erased annotation) yet the COMPILER (CI) rejects it LOUDLY:
#     `10 built, 1 error(s)` — a MERGE-TOOL-BLIND stale reference, caught by recompile;
#   - the STAND-ALONE source rename compile CLEAN (`11 built, 0 error(s)`) — the
#     CONCEDED file-arm win (no strawman: the baseline wins the isolated rename);
#   - the GRAPH arm converge to `11 built, 0 error(s)`: the divergent rename verb-
#     REJECTED fail-closed (an ATOMIC verb reject — agent C must still re-decide,
#     but the tree is never left broken/conflicted); the stale reference RE-RESOLVED
#     by IDENTITY ((Vec k/Claim) -> (Vec k/Datum)); every write serialized through a
#     sole-writer coordinator (OCC race over REAL sockets: 8 racers -> 1 win, 7 conflict).
#
# The compiler is the verification. Every number is MEASURED by running this harness,
# never asserted. The graph-arm sub-steps FAIL LOUDLY (no swallowed racket stderr,
# no degenerate partial tree silently passing): the oracle gate asserts the BUILD
# COUNT (11/10), not just the error count, so a near-empty tree cannot pass.
#
# Non-destructive: operates on mktemp COPIES of src/fram and a TEMP coordinator log
# on a VERIFIED-FREE high port; sweeps stale t2-repro daemons at startup; trap-kills
# its own daemon by PROCESS GROUP on every exit path (incl. interrupt/FATAL);
# NEVER touches port 7977 or ~/.local/state/lodestar/claims.log (the LIVE lodestar).
#
#   bash reproduce.sh
# Needs: racket, babashka (bb), git, Beagle (BEAGLE=~/code/beagle). clojure-lsp is a
# SUPPLEMENTARY baseline note only (the oracle runs without it).
# ============================================================================
set -uo pipefail

# --- locate repo + tools (this script lives in experiments/concurrent-authoring) ---
FRAM="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$FRAM"
export BEAGLE="${BEAGLE:-$HOME/code/beagle}"
export BEAGLE_HOME="${BEAGLE_HOME:-$HOME/code/beagle}"
RT="$BEAGLE/beagle-lib/private/claims-roundtrip.rkt"
BUILD="$BEAGLE/bin/beagle-build-all"
RESOLVE="chartroom/src/resolve.clj"
CLIENT="experiments/concurrent-authoring/graph_arm_client.clj"
SCENARIO="experiments/concurrent-authoring/scenario.edn"
SRC="$FRAM/src/fram"
EXPECT_BUILT=11   # the frozen base builds 11 modules; the oracle gate asserts this exact count

[ -x "$BUILD" ] || { echo "FATAL: oracle not found at $BUILD" >&2; exit 2; }
[ -f "$RT" ]    || { echo "FATAL: roundtrip projector not found at $RT" >&2; exit 2; }
[ -d "$SRC" ]   || { echo "FATAL: corpus not found at $SRC" >&2; exit 2; }
[ -f "$SCENARIO" ] || { echo "FATAL: scenario.edn not found at $SCENARIO" >&2; exit 2; }

hr(){  printf '%s\n' "============================================================================"; }
sub(){ printf -- '---- %s\n' "$*"; }

# --- workspace + cleanup state (declared BEFORE any FATAL so the trap can clean) ---
W=""
DAEMON_PID=""
PORT=""
# fatal-and-clean: print, clean workspace+daemon, exit. Used on every abort path so
# we never leak a temp dir or a daemon on a FATAL exit.
fatal(){ local code="$1"; shift; echo "FATAL: $*" >&2; exit "$code"; }

cleanup(){
  # (1) kill our daemon's WHOLE process group (setsid gave it its own pgid == DAEMON_PID),
  #     so the bb wrapper AND the JVM it spawns both die — robust to interruption/timeout.
  if [ -n "$DAEMON_PID" ]; then
    kill -- -"$DAEMON_PID" 2>/dev/null
    kill "$DAEMON_PID" 2>/dev/null
  fi
  # (2) belt-and-braces: kill whatever is STILL listening on OUR port (never 7977),
  #     waiting for it to actually free the port (SIGTERM then SIGKILL).
  if [ -n "$PORT" ] && [ "$PORT" != "7977" ]; then
    local pids; pids="$(ss -ltnp 2>/dev/null | grep ":$PORT\b" | grep -oE 'pid=[0-9]+' | cut -d= -f2 | sort -u)"
    if [ -n "$pids" ]; then
      kill $pids 2>/dev/null
      for _ in 1 2 3 4 5; do ss -ltn 2>/dev/null | grep -q ":$PORT\b" || break; sleep 0.3; done
      ss -ltn 2>/dev/null | grep -q ":$PORT\b" && kill -9 $pids 2>/dev/null
    fi
  fi
  # (3) remove OUR workspace
  [ -n "$W" ] && rm -rf "$W"
}
trap cleanup EXIT INT TERM

# --- STARTUP SWEEP: self-heal prior leaks. Kill any stale t2-repro daemon whose log
#     lives under /tmp/t2-repro-* (NEVER 7977 / lodestar), and reap stale temp dirs. ----
sweep_stale(){
  local pid args logp
  for pid in $(pgrep -f 'cnf_coord_daemon.clj serve-flat' 2>/dev/null); do
    args="$(tr '\0' ' ' < /proc/$pid/cmdline 2>/dev/null)"
    # only ever touch a daemon whose canonical log is under a t2-repro temp dir
    case "$args" in
      *7977*) : ;;                                  # NEVER the live lodestar
      */tmp/t2-repro-*) echo "   sweep: killing stale t2-repro daemon pid=$pid"; kill "$pid" 2>/dev/null ;;
    esac
  done
  # reap t2-repro temp dirs that have no live daemon pointing at them
  for d in /tmp/t2-repro-*; do
    [ -d "$d" ] || continue
    if ! pgrep -f "$d" >/dev/null 2>&1; then rm -rf "$d"; fi
  done
}
sweep_stale

# --- HARD SAFETY: pick a port in 9100-9990 and ATOMICALLY claim it. The free-port
#     pre-check has a TOCTOU gap vs a daemon binding between check and bind, so the
#     SOURCE OF TRUTH is the daemon's bind succeeding + :status confirming OUR log.
#     If a port is busy (pre-check OR bind-fail OR :status mismatch) we move on. -----
W="$(mktemp -d /tmp/t2-repro-XXXXXX)"

hr
echo "TIER-2 — concurrent authoring through the claim graph beats file-based editing"
echo "scenario:    $SCENARIO   (K=4 ops; the machine-readable source of truth)"
echo "oracle:      $BUILD   (parsed for EXACTLY $EXPECT_BUILT built / 0 errors — never 'includes 0 error')"
echo "workspace:   $W   (mktemp COPIES of src/fram; src/fram is NEVER edited in place)"
hr

# --- helpers (oracle parsing) -------------------------------------------------
# parse a beagle verdict line into an integer error count (EXACTLY, never
# str/includes? "0 error"). echoes -1 if no verdict line found.
errcount(){ # $1 = verdict line text
  local n; n="$(printf '%s' "$1" | grep -oE 'built, [0-9]+ error' | grep -oE '[0-9]+' | tail -1)"
  [ -z "$n" ] && n=-1; echo "$n"
}
# parse the BUILD COUNT ("N built") — gate on this so a degenerate near-empty tree
# (e.g. render silently dropped 10 modules) CANNOT pass as the thesis. echoes -1 if none.
buildcount(){ # $1 = verdict line text
  local n; n="$(printf '%s' "$1" | grep -oE '[0-9]+ built' | grep -oE '[0-9]+' | tail -1)"
  [ -z "$n" ] && n=-1; echo "$n"
}
# a tree PASSES the oracle iff it built EXACTLY $1 modules with 0 errors.
oracle_ok(){ # $1=expected-built  $2=verdict-line
  [ "$(buildcount "$2")" = "$1" ] && [ "$(errcount "$2")" = 0 ]
}

# --- helpers (FAIL-LOUD projection / render — NO swallowed stderr) -------------
# project a tree of .bclj into per-module EDN under $2 (cross-module refs by identity).
# Every racket call's exit status is checked AND each emitted .edn must be non-empty;
# on any failure we print the captured stderr and ABORT (never compute a verdict over
# a partial/empty tree). This is the load-bearing fix against silent intermittency.
project(){ # $1=src-dir $2=edn-out-dir
  mkdir -p "$2"
  local f b err
  for f in "$1"/*.bclj; do
    b="$(basename "$f" .bclj)"
    err="$2/$b.emit.err"
    if ! racket "$RT" --emit-edn "$f" > "$2/$b.edn" 2>"$err"; then
      echo "   racket --emit-edn FAILED on $f:" >&2; cat "$err" >&2
      fatal 9 "project(): racket --emit-edn returned non-zero on $b"
    fi
    [ -s "$2/$b.edn" ] || { cat "$err" >&2; fatal 9 "project(): EMPTY EDN emitted for $b (degenerate tree refused)"; }
  done
}
# render every resolved-*.edn under $1 into a .bclj tree at $2 (+ host .clj). Same
# fail-loud discipline: a non-zero racket exit or an empty .bclj aborts the run.
render(){ # $1=resolved-edn-dir $2=out-tree-dir
  mkdir -p "$2"; cp "$W"/base/*.clj "$2/" 2>/dev/null
  local p b err
  for p in "$1"/resolved-*.edn; do
    b="$(basename "$p" .edn | sed 's/^resolved-//; s/\.bclj$//')"
    err="$2/$b.render.err"
    if ! racket "$RT" --render "$p" > "$2/$b.bclj" 2>"$err"; then
      echo "   racket --render FAILED on $p:" >&2; cat "$err" >&2
      fatal 9 "render(): racket --render returned non-zero on $b"
    fi
    [ -s "$2/$b.bclj" ] || { cat "$err" >&2; fatal 9 "render(): EMPTY .bclj rendered for $b (degenerate tree refused)"; }
  done
}

# ============================================================================
# 0. FROZEN BASE — copy src/fram, confirm it compiles `11 built, 0 error(s)`.
#    BOTH arms project/branch from this identical snapshot. (fair-base guardrail)
# ============================================================================
hr; echo "0. FROZEN BASE: copy src/fram (NEVER edited in place) and recompile it"
mkdir -p "$W/base"
cp "$SRC"/*.bclj "$SRC"/*.clj "$W/base/"
BASE_VERDICT="$("$BUILD" "$W/base" --out "$W/o-base" 2>&1 | grep -iE 'built, .* error' | tail -1)"
echo "   baseline ORACLE: $BASE_VERDICT"
oracle_ok "$EXPECT_BUILT" "$BASE_VERDICT" || fatal 3 "base does not compile $EXPECT_BUILT built / 0 errors — aborting"
echo "   both arms project/branch from THIS snapshot; only the reconciliation mechanism differs."

# ============================================================================
# COUNTER-DISCRIMINATION (no strawman): the STRONGEST file-arm rename — sed on
# .bclj SOURCE — STAND-ALONE compiles clean. The file arm WINS the isolated
# rename. The graph's win appears ONLY under concurrency.
# ============================================================================
hr; echo "COUNTER-DISCRIMINATION (no strawman): strongest file-arm rename, STAND-ALONE"
mkdir -p "$W/standalone"; cp "$W"/base/*.bclj "$W"/base/*.clj "$W/standalone/"
SA_TOK="$(grep -rhoE '\bClaim\b' "$W"/standalone/*.bclj | wc -l | tr -d ' ')"
sed -E -i 's/\bClaim\b/Datum/g' "$W"/standalone/*.bclj
SA_VERDICT="$("$BUILD" "$W/standalone" --out "$W/o-sa" 2>&1 | grep -iE 'built, .* error' | tail -1)"
echo "   sed -E 's/\\bClaim\\b/Datum/g' over .bclj source caught $SA_TOK Claim tokens -> $SA_VERDICT"
echo "   => the FILE ARM WINS the stand-alone rename. The graph does NOT beat it in isolation (CONCEDED)."

# ############################################################################
# ARM B — FILE (strongest real workflow: .bclj source find/replace + git 3-way)
# ############################################################################
hr; echo "ARM B (FILE): git branches off ONE common ancestor; real 3-way merge"
REPO="$W/file-repo"; mkdir -p "$REPO"
cp "$W"/base/*.bclj "$W"/base/*.clj "$REPO/"
git -C "$REPO" init -q
git -C "$REPO" config user.email t2@experiment; git -C "$REPO" config user.name t2-harness
git -C "$REPO" add -A; git -C "$REPO" commit -q -m base
BASE_SHA="$(git -C "$REPO" rev-parse HEAD)"

# OP1 (A): rename Claim->Datum via multi-file find/replace on .bclj SOURCE
git -C "$REPO" checkout -q -b agent-A-rename
sed -E -i 's/\bClaim\b/Datum/g' "$REPO"/*.bclj
git -C "$REPO" commit -qam "A: rename Claim->Datum (source find/replace)"

# OP3 (C): DIVERGENT rename Claim->Fact (off the SAME base)
git -C "$REPO" checkout -q "$BASE_SHA" -b agent-C-rename 2>/dev/null
sed -E -i 's/\bClaim\b/Fact/g' "$REPO"/*.bclj
git -C "$REPO" commit -qam "C: rename Claim->Fact (divergent)"

# OP2 (B): add require + schema-claim-count typed (Vec k/Claim) (off base, disjoint file)
git -C "$REPO" checkout -q "$BASE_SHA" -b agent-B-add 2>/dev/null
cat >> "$REPO/schema.bclj" <<'EOF'

(require fram.kernel :as k)
(defn schema-claim-count [claims :- (Vec k/Claim)] :- (Vec String)
  (k/thread-ids claims))
EOF
git -C "$REPO" commit -qam "B: add schema-claim-count (k/Claim ref, correct at base)"

# OP4 (D): edit datalog var? body, behavior-preserving byte change (off base, disjoint)
git -C "$REPO" checkout -q "$BASE_SHA" -b agent-D-body 2>/dev/null
perl -0pi -e 's/\(and \(map\? t\) \(contains\? t :var\)\)/(and (contains? t :var) (map? t))/' "$REPO/datalog.bclj"
git -C "$REPO" commit -qam "D: edit var? body (disjoint helper)"

# ---- divergent-rename pair (1+3): MEASURE git conflict (M2, branch sub-mode) -----
sub "divergent-rename pair (ops 1+3): real git 3-way merge of A and C"
git -C "$REPO" checkout -q agent-A-rename
M6_FILE_DIV_START="$(date +%s.%N)"
git -C "$REPO" merge --no-edit agent-C-rename >/dev/null 2>&1; DIV_EXIT=$?
M6_FILE_DIV_END="$(date +%s.%N)"
FILE_CONFLICTS="$(git -C "$REPO" diff --name-only --diff-filter=U 2>/dev/null | wc -l | tr -d ' ')"
echo "   git merge exit=$DIV_EXIT ; CONFLICTED FILES (M2) = $FILE_CONFLICTS"
git -C "$REPO" diff --name-only --diff-filter=U 2>/dev/null | sed 's/^/      /'
echo "   git SURFACES this conflict (CONCEDED as a file-arm strength). Resolution is MANUAL REWORK."
git -C "$REPO" merge --abort 2>/dev/null

# ---- M1 lost-update: MEASURED (not asserted), the explicitly-WEAKER no-VCS sub-mode -----
# This is a LWW shared-filesystem clobber (NO git): A renames Claim->Datum, then C —
# working from a STALE base buffer — saves Claim->Fact over the SAME files, silently
# overwriting A's rename. We MEASURE it by counting Datum tokens before/after C's save:
# if they drop, A's update was lost. NOTE: this is the WEAKEST sub-mode; a competent
# engineer using git never hits it (git SURFACES the conflict above as M2). We report
# it as a separate, explicitly-weaker result, NOT charged against the git baseline.
sub "M1 lost-update (MEASURED): LWW shared-filesystem sub-mode (no VCS — explicitly weaker)"
mkdir -p "$W/lww"; cp "$W"/base/*.bclj "$W/lww/"
sed -E -i 's/\bClaim\b/Datum/g' "$W"/lww/*.bclj            # A's save: Claim->Datum
LWW_DATUM_A="$(grep -rhoE '\bDatum\b' "$W"/lww/*.bclj | wc -l | tr -d ' ')"
mkdir -p "$W/lww-c"; cp "$W"/base/*.bclj "$W/lww-c/"
sed -E -i 's/\bClaim\b/Fact/g' "$W"/lww-c/*.bclj           # C's buffer (from STALE base)
cp "$W"/lww-c/*.bclj "$W"/lww/                             # C's save CLOBBERS A's files
LWW_DATUM_C="$(grep -rhoE '\bDatum\b' "$W"/lww/*.bclj | wc -l | tr -d ' ')"
echo "   after A's save: Datum tokens=$LWW_DATUM_A ; after C's clobbering save: Datum tokens=$LWW_DATUM_C"
if [ "$LWW_DATUM_C" -lt "$LWW_DATUM_A" ]; then
  FILE_LOST_UPDATES=1
  echo "   => A's ENTIRE rename SILENTLY OVERWRITTEN by C's save -> 1 LOST UPDATE (MEASURED, no warning)."
else
  FILE_LOST_UPDATES=0
  echo "   => no lost update measured (unexpected)."
fi
echo "   (this is the WEAKER no-VCS sub-mode; git surfaces it as the M2 conflict above. Reported separately.)"

# ---- the realistic SHIPPED file-arm tree: A's rename, then the CLEAN
#      auto-merges B(add) + D(body). The agent picks A's rename (divergent C
#      dropped per manual resolution / its rework), and the two disjoint
#      additions auto-merge green. This is what the merge tooling produces.
sub "stale-ref pair (ops 1+2) + disjoint (op 4): merge B and D into A (expect CLEAN)"
git -C "$REPO" checkout -q agent-A-rename
git -C "$REPO" merge --no-edit agent-B-add >/dev/null 2>&1; B_EXIT=$?
B_CONF="$(git -C "$REPO" diff --name-only --diff-filter=U 2>/dev/null | wc -l | tr -d ' ')"
git -C "$REPO" merge --no-edit agent-D-body >/dev/null 2>&1; D_EXIT=$?
D_CONF="$(git -C "$REPO" diff --name-only --diff-filter=U 2>/dev/null | wc -l | tr -d ' ')"
echo "   merge B exit=$B_EXIT conflicts=$B_CONF ; merge D exit=$D_EXIT conflicts=$D_CONF  (CLEAN auto-merge)"

# ---- MERGE-TOOL blindness check (the discrimination): every MERGE/TEXTUAL tool says GREEN
sub "MERGE tooling on the merged tree (git+grep+lsp all GREEN — but the COMPILER is not a merge tool)"
GREP_HITS="$(grep -o 'k/Claim' "$REPO/schema.bclj" 2>/dev/null | wc -l | tr -d ' ')"
echo "   git merge: $B_CONF conflicts on the stale-ref pair (MERGE tool: GREEN)"
echo "   grep 'k/Claim' in schema.bclj: $GREP_HITS match (TEXTUAL tool: GREEN — 'still present, looks fine')"
if command -v clojure-lsp >/dev/null 2>&1; then
  TA_CLJ="$(grep -rhoE ':-[^]]*\bClaim\b' "$FRAM"/out/fram/*.clj 2>/dev/null | wc -l | tr -d ' ')"
  TA_SRC="$(grep -rhoE ':-[^]]*\bClaim\b' "$FRAM"/src/fram/*.bclj 2>/dev/null | wc -l | tr -d ' ')"
  echo "   clojure-lsp ($(clojure-lsp --version 2>/dev/null | head -1)): type-annotation Claim refs in emitted .clj = $TA_CLJ"
  echo "      ($TA_SRC :- ...Claim annotations in source ERASE to $TA_CLJ at lowering — SUPPLEMENTARY note; LSP is a merge/refactor tool, not the oracle.)"
else
  echo "   clojure-lsp: not installed (supplementary note skipped; the oracle runs without it)."
fi

# ---- THE ORACLE = the file arm's OWN CI/compile-after-merge gate (M4) ----------
# The file arm gets a SYMMETRIC recompile-after-merge gate — a competent file team
# runs CI after a merge. The COMPILER is NOT a merge tool and it is NOT blind: it
# catches the stale reference LOUDLY. So the file arm's CI REJECTS this merge and
# the merge must be REWORKED by hand. (It is NOT a silent ship — it is a loud
# rejection that costs manual rework the graph never pays.)
sub "FILE-ARM CI (compile-after-merge, the SAME oracle) — would this merge ship?"
mkdir -p "$W/file-final"; cp "$REPO"/*.bclj "$REPO"/*.clj "$W/file-final/"
FILE_VERDICT="$("$BUILD" "$W/file-final" --out "$W/o-file" 2>&1 | grep -iE 'built, .* error' | tail -1)"
FILE_ERR_DETAIL="$("$BUILD" "$W/file-final" --out "$W/o-file2" 2>&1 | grep -iE 'expected|got|thread-ids' | grep -ivE 'lint|declare-extern' | head -1)"
FILE_ERRS="$(errcount "$FILE_VERDICT")"
FILE_BUILT="$(buildcount "$FILE_VERDICT")"
echo "   FILE-ARM CI verdict: $FILE_VERDICT"
[ -n "$FILE_ERR_DETAIL" ] && echo "   compiler says:$FILE_ERR_DETAIL"
echo "   => the MERGE tooling (git+grep+lsp) was BLIND to the stale k/Claim, but CI (the compiler) is NOT:"
echo "      it REJECTS the merge LOUDLY -> the file arm CANNOT ship this -> MANUAL REWORK required."
echo "      No textual/merge tool connects B's k/Claim (schema.bclj) to A's rename (kernel.bclj) across files;"
echo "      only RECOMPILATION does. The graph never produces this tree at all (see ARM A)."
FILE_STALE_REFS="$GREP_HITS"

# ############################################################################
# ARM A — GRAPH (sole-writer coordinator + recompile-gate + identity re-resolve)
# ############################################################################
hr; echo "ARM A (GRAPH): claim ops, recompile-gated (fail-closed), serialized via coordinator"

# ---- boot a sole-writer coordinator on the temp flat log (ATOMIC port claim) ----
GFLAT="$W/code.log"
# seed: one synthetic single-valued claim per op (the OCC proxy subjects). `body`
# is single-valued (fram.kernel/single-valued) so base_version OCC fires.
{
  echo '{:l "@op1-effect" :p "body" :r "seed" :tx 1}'
  echo '{:l "@op2-effect" :p "body" :r "seed" :tx 2}'
  echo '{:l "@op3-effect" :p "body" :r "seed" :tx 3}'
  echo '{:l "@op4-effect" :p "body" :r "seed" :tx 4}'
} > "$GFLAT"

cstat(){ bb -cp out "$CLIENT" status "$PORT" 2>/dev/null; }
cver(){ bb -cp out "$CLIENT" version "$PORT" 2>/dev/null; }

# Try ports in 9100-9990. For each candidate: pre-check free (cheap), then BOOT via
# setsid (own process group so the trap can kill the whole group), then CONFIRM
# :status returns OUR log. Bind-failure (Address already in use) or a status mismatch
# => kill this attempt, advance to the NEXT port (never FATAL on a single busy port).
sub "boot sole-writer coordinator on a VERIFIED-FREE high port; CONFIRM :status = OUR temp log"
boot_ok=0
for p in $(seq 9100 9990); do
  [ "$p" = "7977" ] && continue
  ss -ltn 2>/dev/null | grep -q ":$p\b" && continue            # cheap pre-check (TOCTOU-tolerant: bind is the truth)
  PORT="$p"
  # setsid: the daemon (bb wrapper + the JVM it execs) get their OWN process group
  # whose pgid == the leader pid == DAEMON_PID, so `kill -- -$DAEMON_PID` reaps both.
  setsid bash -c "exec bb -cp out cnf_coord_daemon.clj serve-flat \"$p\" \"$GFLAT\"" >"$W/daemon.out" 2>&1 &
  DAEMON_PID=$!
  # wait until the coordinator answers :status with OUR log path (bind+ready is the truth)
  STATUS_OK=0
  for i in $(seq 1 40); do
    # if the daemon died (bind failure / crash), stop waiting on this port
    kill -0 "$DAEMON_PID" 2>/dev/null || break
    ST="$(cstat)"
    if printf '%s' "$ST" | grep -q ":log \"$GFLAT\""; then STATUS_OK=1; echo "   port $p OK :status -> $ST"; break; fi
    sleep 0.5
  done
  if [ "$STATUS_OK" = 1 ]; then boot_ok=1; break; fi
  # this port did not come up as OURS — reap it (whole group) and try the next one
  echo "   port $p did not confirm (busy / bind race); advancing"
  kill -- -"$DAEMON_PID" 2>/dev/null; kill "$DAEMON_PID" 2>/dev/null
  DAEMON_PID=""; PORT=""
done
[ "$boot_ok" = 1 ] || { [ -f "$W/daemon.out" ] && cat "$W/daemon.out"; fatal 4 "no coordinator came up on any port in 9100-9990 (refusing to trust results)"; }

# ============================================================================
# GRAPH per-op strict sequence (scenario order, serialized):
#   project(base) -> verb (CLAIM OP, not a text splice) -> render
#   -> RECOMPILE-GATE (BUILD COUNT == 11 AND 0 errors) -> THEN coordinator-commit (OCC).
# A gate-FAIL or verb-reject NEVER reaches the coordinator (version unchanged).
# ============================================================================
G_COMMITS=0; G_REJECTS=0; G_LOST=0
GTREE="$W/graph-tree"; mkdir -p "$GTREE"
cp "$W"/base/*.bclj "$W"/base/*.clj "$GTREE/"
# B (op2) requires kernel in schema — a realistic edit a competent agent makes.
printf '\n(require fram.kernel :as k)\n' >> "$GTREE/schema.bclj"

# commit-through-coordinator helper (single-triple proxy under OCC; retries on conflict).
# The client HARD-FAILS (non-zero) if the daemon is unreachable / version nil, so a
# transient daemon lapse aborts the run rather than silently degrading to "reject".
commit_proxy(){ # $1=subject(@opN-effect)  $2=value(unique)
  local out rc
  out="$(bb -cp out "$CLIENT" commit "$PORT" "$1" "body" "$2" 2>"$W/commit.err")"; rc=$?
  if [ "$rc" != 0 ]; then cat "$W/commit.err" >&2; fatal 7 "coordinator commit FAILED (daemon unreachable) for $1"; fi
  case "$out" in ok*) echo "ok";; *) echo "reject";; esac
}

# ---- OP 1 (A): rename Claim -> Datum in kernel ------------------------------
sub "OP1 (A): rename Claim->Datum kernel  [claim op; gate; commit]"
project "$GTREE" "$W/g1-edn"; EDN1="$(ls "$W"/g1-edn/*.edn)"
mkdir -p "$W/g1-r"
EDIT1="$(RESOLVE_OUT="$W/g1-r" bb -cp out "$RESOLVE" rename Claim Datum kernel $EDN1 2>&1 | grep -E 'CLAIMS EDITED|REJECTED')"
echo "   verb: $EDIT1"
render "$W/g1-r" "$W/g1-tree"
G1_VERDICT="$("$BUILD" "$W/g1-tree" --out "$W/o-g1" 2>&1 | grep -iE 'built, .* error' | tail -1)"
if oracle_ok "$EXPECT_BUILT" "$G1_VERDICT"; then
  echo "   recompile-gate PASS: $G1_VERDICT -> commit through coordinator"
  R="$(commit_proxy '@op1-effect' 'rename-Claim-Datum')"; echo "   coordinator: $R"
  [ "$R" = ok ] && { G_COMMITS=$((G_COMMITS+1)); rm -rf "$GTREE"; cp -r "$W/g1-tree" "$GTREE"; }
else
  echo "   recompile-gate FAIL ($G1_VERDICT): REJECT, mutate nothing"; G_REJECTS=$((G_REJECTS+1))
fi

# ---- OP 2 (B): upsert schema-claim-count; ref re-resolves by identity -------
# B's k/Claim ref acquires refers_to <kernel Claim node> at B's BASE (Claim exists),
# THEN A's rename re-points it to Datum by IDENTITY (not spelling) at render time.
sub "OP2 (B): upsert-form schema (Vec k/Claim) ; stale-ref pair with op1"
mkdir -p "$W/g2-base"; cp "$W"/base/*.bclj "$W"/base/*.clj "$W/g2-base/"
printf '\n(require fram.kernel :as k)\n' >> "$W/g2-base/schema.bclj"
project "$W/g2-base" "$W/g2-edn0"; EDN2A="$(ls "$W"/g2-edn0/*.edn)"
cat > "$W/op2-form.edn" <<'EOF'
(defn schema-claim-count [claims :- (Vec k/Claim)] :- (Vec String)
  (k/thread-ids claims))
EOF
mkdir -p "$W/g2-rb"
ADD2="$(RESOLVE_OUT="$W/g2-rb" bb -cp out "$RESOLVE" upsert-form schema "$W/op2-form.edn" $EDN2A 2>&1 | grep -E 'added|REJECTED')"
echo "   verb: $ADD2"
render "$W/g2-rb" "$W/g2-btree"
# re-project B's tree, replay A's rename (kernel Claim->Datum) over it ->
# B's refers_to edge now renders the CURRENT name -> k/Datum (identity!).
project "$W/g2-btree" "$W/g2-edn1"; EDN2B="$(ls "$W"/g2-edn1/*.edn)"
mkdir -p "$W/g2-ra"
RESOLVE_OUT="$W/g2-ra" bb -cp out "$RESOLVE" rename Claim Datum kernel $EDN2B >"$W/g2-rename.out" 2>&1 \
  || { cat "$W/g2-rename.out" >&2; fatal 9 "op2 re-resolve rename failed"; }
render "$W/g2-ra" "$W/g2-tree"
G2_REF="$(grep -oE 'k/(Claim|Datum)' "$W/g2-tree/schema.bclj" | head -1)"
echo "   B's (Vec k/...) reference after A's rename re-resolve -> (Vec $G2_REF)  [identity, not spelling]"
G2_VERDICT="$("$BUILD" "$W/g2-tree" --out "$W/o-g2" 2>&1 | grep -iE 'built, .* error' | tail -1)"
if oracle_ok "$EXPECT_BUILT" "$G2_VERDICT"; then
  echo "   recompile-gate PASS: $G2_VERDICT -> commit through coordinator"
  R="$(commit_proxy '@op2-effect' 'upsert-schema-claim-count')"; echo "   coordinator: $R"
  [ "$R" = ok ] && { G_COMMITS=$((G_COMMITS+1)); rm -rf "$GTREE"; cp -r "$W/g2-tree" "$GTREE"; }
else
  echo "   recompile-gate FAIL ($G2_VERDICT): REJECT"; G_REJECTS=$((G_REJECTS+1))
fi
# graph-arm stale-reference count: does the final tree still carry a name that no
# longer exists (k/Claim)? The re-resolve re-pointed it, so this must be 0.
G_STALE_REFS="$(grep -o 'k/Claim' "$GTREE/schema.bclj" 2>/dev/null | wc -l | tr -d ' ')"

# ---- OP 3 (C): divergent rename Claim->Fact (post-op1) -> verb HARD-FAILS ---
sub "OP3 (C): rename Claim->Fact kernel (against the POST-op1 graph) ; divergent pair with op1"
project "$GTREE" "$W/g3-edn"; EDN3="$(ls "$W"/g3-edn/*.edn)"
mkdir -p "$W/g3-r"
REJ3="$(RESOLVE_OUT="$W/g3-r" bb -cp out "$RESOLVE" rename Claim Fact kernel $EDN3 2>&1 | grep -E 'REJECTED|CLAIMS EDITED')"
RESOLVE_OUT="$W/g3-r2" bb -cp out "$RESOLVE" rename Claim Fact kernel $EDN3 >/dev/null 2>&1; C_EXIT=$?
echo "   verb: $REJ3  (exit=$C_EXIT)"
if [ "$C_EXIT" != 0 ]; then
  echo "   -> ATOMIC verb REJECT at the verb layer: no 'Claim' binding (A already renamed it). NO claims mutated."
  echo "   -> reaches NEITHER the gate NOR the coordinator. No lost update, no half-merged conflicted tree."
  echo "   -> agent C MUST still reconcile (re-decide its rename against the new state); the win over git is that"
  echo "      the tree is NEVER left in a broken/conflict-markered intermediate, NOT that C needs no follow-up."
  G_REJECTS=$((G_REJECTS+1))   # M3 GOOD: fail-closed before commit
else
  echo "   UNEXPECTED: C's divergent rename did not reject"; G_LOST=$((G_LOST+1))
fi

# ---- OP 4 (D): set-body var? datalog (truly disjoint) -> commits clean ------
sub "OP4 (D): set-body var? datalog (disjoint helper) ; the CONCESSION"
project "$GTREE" "$W/g4-edn"; EDN4="$(ls "$W"/g4-edn/*.edn)"
cat > "$W/op4-body.edn" <<'EOF'
(and (contains? t :var) (map? t))
EOF
mkdir -p "$W/g4-r"
EDIT4="$(RESOLVE_OUT="$W/g4-r" bb -cp out "$RESOLVE" set-body var? datalog "$W/op4-body.edn" $EDN4 2>&1 | grep -E 'replaced|REJECTED')"
echo "   verb: $EDIT4"
render "$W/g4-r" "$W/g4-tree"
G4_VERDICT="$("$BUILD" "$W/g4-tree" --out "$W/o-g4" 2>&1 | grep -iE 'built, .* error' | tail -1)"
if oracle_ok "$EXPECT_BUILT" "$G4_VERDICT"; then
  echo "   recompile-gate PASS: $G4_VERDICT -> commit through coordinator"
  M6_GRAPH_START="$(date +%s.%N)"
  R="$(commit_proxy '@op4-effect' 'set-body-var')"
  M6_GRAPH_END="$(date +%s.%N)"
  echo "   coordinator: $R"
  [ "$R" = ok ] && { G_COMMITS=$((G_COMMITS+1)); rm -rf "$GTREE"; cp -r "$W/g4-tree" "$GTREE"; }
else
  echo "   recompile-gate FAIL ($G4_VERDICT): REJECT"; G_REJECTS=$((G_REJECTS+1))
fi

# ---- M1 CHECK: a gate-FAIL leaves the coordinator version UNCHANGED (verified) ----
# DETERMINISTIC break: replace EVERY k/Datum with k/Claim in schema.bclj (the only
# k/Datum is the appended schema-claim-count param annotation), guaranteeing a stale
# cross-module ref -> a compile error. We ASSERT the broken tree fails (>=1 error /
# build count < 11) before relying on it; if it compiled clean that is a HARNESS
# DEFECT (fatal), not a check to skip.
sub "M1 CHECK (fail-closed): a DETERMINISTICALLY-broken edit is gate-REJECTED, coordinator version UNCHANGED"
V_BEFORE="$(cver)"
[ -n "$V_BEFORE" ] || fatal 7 "coordinator version unreadable before M1 check (daemon unreachable)"
mkdir -p "$W/broken"; cp "$GTREE"/*.bclj "$GTREE"/*.clj "$W/broken/"
sed -E -i 's#k/Datum\b#k/Claim#g' "$W/broken/schema.bclj"
BROKEN_VERDICT="$("$BUILD" "$W/broken" --out "$W/o-broken" 2>&1 | grep -iE 'built, .* error' | tail -1)"
echo "   broken-edit recompile-gate: $BROKEN_VERDICT"
if oracle_ok "$EXPECT_BUILT" "$BROKEN_VERDICT"; then
  fatal 9 "M1 broken-edit unexpectedly compiled clean ($BROKEN_VERDICT) — the deterministic break failed (HARNESS DEFECT)"
fi
echo "   gate FAIL -> NOT committed (no coordinator round-trip)"
V_AFTER="$(cver)"
[ -n "$V_AFTER" ] || fatal 7 "coordinator version unreadable after M1 check (daemon unreachable)"
echo "   coordinator version before=$V_BEFORE after=$V_AFTER (a gate-FAIL never advanced it)"
[ "$V_BEFORE" = "$V_AFTER" ] && M1_GATE_CHECK="UNCHANGED (verified)" || M1_GATE_CHECK="CHANGED (!!)"

# ---- OCC RACE over REAL sockets: serialization with no lost update ----------
# The client HARD-FAILS (exit 8) on a 0/0 race (all asserts errored = daemon
# unreachable mid-race), so a transient lapse aborts rather than reporting 0/0.
sub "OCC RACE (real sockets): K concurrent proxy asserts on ONE (subject,single-pred), stale base"
RACE_LINE="$(bb -cp out "$CLIENT" race "$PORT" "@race" "title" 8 2>"$W/race.err")"; RACE_RC=$?
if [ "$RACE_RC" != 0 ]; then cat "$W/race.err" >&2; fatal 8 "OCC race failed (daemon unreachable mid-race): $RACE_LINE"; fi
RACE_WINS="$(printf '%s' "$RACE_LINE" | grep -oE 'wins=[0-9]+' | grep -oE '[0-9]+')"
RACE_CONF="$(printf '%s' "$RACE_LINE" | grep -oE 'conflicts=[0-9]+' | grep -oE '[0-9]+')"
RACE_WINS="${RACE_WINS:-0}"; RACE_CONF="${RACE_CONF:-0}"
[ "$RACE_WINS" -ge 1 ] || fatal 8 "OCC race produced wins=$RACE_WINS conflicts=$RACE_CONF (not a verified serialization result)"
echo "   8 racers on (@race,title) @ same stale base -> wins=$RACE_WINS conflicts=$RACE_CONF"
echo "   (exactly 1 win / 7 conflict = serialization holds; the loser reconciles, never lost-update)"

# ---- scope-correct intelligence (step 6): demonstrated at RENDER time -------
sub "SCOPE-CORRECT INTELLIGENCE (step 6): demonstrated at RENDER time (op2), verified by the compiler"
echo "   op2 reference re-resolve: (Vec k/Claim) --[A's rename, by identity]--> (Vec $G2_REF) -> recompiles clean"
echo "   (warm :callers over the COORDINATOR awaits 'the flip' — AST delta not yet committed to it; DISCLOSED)"
WARM_CALLERS_RAW="$(bb -cp out "$CLIENT" callers "$PORT" "kernel" "Datum" 2>/dev/null | head -c 200)"
echo "   [diagnostic] :callers over the proxy-only coordinator: ${WARM_CALLERS_RAW:-<none>} (expected: no AST nodes)"

# ---- THE ORACLE on the graph arm's final tree (M4) -------------------------
sub "THE ORACLE — recompile the graph arm's FINAL tree (all serialized commits applied)"
GRAPH_VERDICT="$("$BUILD" "$GTREE" --out "$W/o-graph-final" 2>&1 | grep -iE 'built, .* error' | tail -1)"
GRAPH_ERRS="$(errcount "$GRAPH_VERDICT")"
GRAPH_BUILT="$(buildcount "$GRAPH_VERDICT")"
echo "   GRAPH ARM FINAL: $GRAPH_VERDICT"

# ============================================================================
# RESULTS — measured metrics, side by side. The compiler is the oracle.
# ============================================================================
M6_FILE_DIV="$(awk "BEGIN{printf \"%.3f\", $M6_FILE_DIV_END - $M6_FILE_DIV_START}")"
M6_GRAPH="$(awk "BEGIN{printf \"%.3f\", ${M6_GRAPH_END:-0} - ${M6_GRAPH_START:-0}}")"

hr; echo "MEASURED RESULTS (the compiler is the oracle; numbers measured, not asserted)"
hr
printf '%-38s | %-26s | %-26s\n' "metric" "GRAPH (arm A)" "FILE (arm B)"
printf '%-38s-+-%-26s-+-%-26s\n' "--------------------------------------" "--------------------------" "--------------------------"
printf '%-38s | %-26s | %-26s\n' "M2 manual merge conflicts (git)" "0" "$FILE_CONFLICTS files (ops 1+3)"
printf '%-38s | %-26s | %-26s\n' "M3 writes rejected fail-closed" "$G_REJECTS (op3 verb-reject GOOD)" "n/a (merge tooling has no gate)"
printf '%-38s | %-26s | %-26s\n' "M4 final recompile / CI (ORACLE)" "$GRAPH_VERDICT" "$FILE_VERDICT"
printf '%-38s | %-26s | %-26s\n' "M5 stale cross-module refs" "$G_STALE_REFS" "$FILE_STALE_REFS (k/Claim)"
printf '%-38s | %-26s | %-26s\n' "M6 reconcile latency (s)" "commit=$M6_GRAPH" "git-merge=$M6_FILE_DIV"
printf '%-38s | %-26s | %-26s\n' "M1 lost update (LWW, no-VCS sub-mode)" "0" "$FILE_LOST_UPDATES (measured, weaker)"
hr
echo "graph commits=$G_COMMITS  rejects(fail-closed)=$G_REJECTS  lost-updates=$G_LOST"
echo "OCC race: wins=$RACE_WINS conflicts=$RACE_CONF (sole-writer serialization, real sockets)"
echo "M1 gate-fail check: coordinator version $M1_GATE_CHECK on a rejected (broken) edit"
echo "stand-alone file rename (CONCEDED file-arm win): $SA_VERDICT"
echo "NOTE: M1 lost-update is the WEAKER no-VCS LWW sub-mode (measured), reported SEPARATELY — not"
echo "      charged against the git baseline, which surfaces the same divergence as the M2 conflict."
hr

# ---- VERDICT (the thesis, checked by the compiler) -------------------------
# The oracle gate asserts BOTH the build COUNT and the error count, on both arms, so
# a degenerate partial tree cannot pass: GRAPH must be exactly 11 built / 0 errors;
# FILE must be exactly 10 built / >=1 error (the discriminator firing for the RIGHT
# reason — one module dropped by the stale ref, not a collapse).
PASS=1
[ "$GRAPH_BUILT" = "$EXPECT_BUILT" ] || { echo "GRAPH FAIL: final tree did not build all $EXPECT_BUILT modules ($GRAPH_VERDICT)"; PASS=0; }
[ "$GRAPH_ERRS" = 0 ]               || { echo "GRAPH FAIL: final tree does not compile 0 errors ($GRAPH_VERDICT)"; PASS=0; }
[ "$G_LOST" = 0 ]                   || { echo "GRAPH FAIL: lost update detected"; PASS=0; }
[ "$G_STALE_REFS" = 0 ]             || { echo "GRAPH FAIL: stale cross-module reference in final tree"; PASS=0; }
[ "$FILE_BUILT" = "$((EXPECT_BUILT-1))" ] || { echo "FILE: expected $((EXPECT_BUILT-1)) built (one module dropped by the stale ref); got $FILE_VERDICT — discriminator did not fire correctly"; PASS=0; }
[ "$FILE_ERRS" -ge 1 ]              || { echo "FILE: final tree compiled clean — discriminator did not fire ($FILE_VERDICT)"; PASS=0; }
[ "$RACE_WINS" = 1 ]                || { echo "OCC FAIL: not exactly one race winner ($RACE_WINS)"; PASS=0; }

if [ "$PASS" = 1 ]; then
  echo "THESIS DEMONSTRATED (by the compiler):"
  echo "  GRAPH arm converges to $GRAPH_VERDICT — 0 lost updates, 0 manual conflicts, 0 stale refs;"
  echo "  the divergent rename verb-rejected fail-closed (atomic; C re-decides, tree never broken);"
  echo "  the stale cross-module reference re-resolved by identity ((Vec k/Claim)->(Vec k/Datum))."
  echo "  FILE arm: the MERGE tooling (git+grep+lsp) is BLIND to the stale k/Claim and merges GREEN,"
  echo "  but the file arm's CI (the SAME compiler) catches it LOUDLY: $FILE_VERDICT -> the merge is"
  echo "  REJECTED and must be REWORKED by hand. The graph pays ZERO rework (correct-by-construction)."
  echo "  CONCEDED: the stand-alone source rename ($SA_VERDICT) and the disjoint op 4 — the file arm wins those,"
  echo "  with lower reconcile latency (M6). The graph's win is CORRECTNESS / CONVERGENCE under concurrency."
  echo "  See RESULTS.md."
  exit 0
else
  echo "HARNESS VERDICT: FAIL (see lines above)"
  exit 1
fi
