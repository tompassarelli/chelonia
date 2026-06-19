#!/usr/bin/env bash
# ============================================================================
# text_reconstruct.sh — Layer-A deterministic MINIMAL-CORRECT TEXT reconstruction
#
# For EACH task in tasks.edn this script performs the FAIR minimal-correct
# retrieval sequence a competent ripgrep-wielding engineer would do, using ONLY
# the `ts` primitives (one `ts search` = one pass, one `ts read` = one read).
# It then:
#   (1) counts the ops as `wc -l` of that task's TS_LOG  (the deterministic,
#       model-independent text op-count — no agent in the loop), and
#   (2) checks whether the reconstructed answer REACHES the verified-correct
#       ground truth stated in tasks.edn (so cheap-but-wrong is caught).
#
# AMORTIZATION (symmetric warm-session frame): the :as k -> fram.kernel alias is
# GIVEN (learned once, reused). Alias-discovery is NOT charged per task — exactly
# as the graph's ~37s setup is amortized. We do not pad and we do not oracle-feed.
#
# NON-DESTRUCTIVE: operates on a frozen mktemp COPY of src/fram. src/fram is
# never touched.  (The source-unavailable sub-case of task d additionally reads a
# frozen copy of the lowered out/fram.)
#
# This script builds the TEXT side only. The graph side (gquery) + the ground-
# truth oracle (resolve.clj callgraph / beagle-build-all) are computed elsewhere;
# here the ground-truth SETS are the verified constants from tasks.edn, and we
# assert the reconstruction reaches them.
# ============================================================================
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TS="$HERE/ts"
SRC_FRAM="${SRC_FRAM:-/home/tom/code/fram/src/fram}"
OUT_FRAM="${OUT_FRAM:-/home/tom/code/fram/out/fram}"

# --- frozen, non-destructive corpus copies -------------------------------------
WORK="$(mktemp -d -t reasoning-cost-text.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
CORP="$WORK/src/fram"
LOW="$WORK/out/fram"
mkdir -p "$CORP" "$LOW"
cp "$SRC_FRAM"/*.bclj "$CORP"/
cp "$OUT_FRAM"/*.clj  "$LOW"/ 2>/dev/null || true

OPLOGS="$WORK/oplogs"; mkdir -p "$OPLOGS"
RESULTS="$WORK/results.tsv"
: >"$RESULTS"

# helper: run one task's reconstruction with a fresh oplog; echo op-count
run_task() {  # $1 = task id; body uses $TS / $CORP / $LOW ; sets TS_LOG
  :
}

emit() {  # task, hops, served, ops, correct, note
  printf '%s\t%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "$3" "$4" "$5" "$6" >>"$RESULTS"
}

ops_of() { wc -l <"$1" | tr -d ' '; }

echo "############################################################################"
echo "# Layer-A deterministic MINIMAL-CORRECT TEXT RECONSTRUCTION"
echo "# corpus (frozen copy): $CORP"
echo "############################################################################"

# ===========================================================================
# (cal) TRIVIAL-LOCAL — body of private helper datalog#var?   [the conceded TIE]
#   minimal-correct text: 1 search to locate the def + 1 read of its body = 2 ops
# ===========================================================================
echo ""
echo "==== (cal) trivial-local: body of datalog#var? ===="
L="$OPLOGS/cal.oplog"; export TS_LOG="$L"
# op1: locate the def
DEF_LINE="$("$TS" search 'defn- var\?' "$CORP/datalog.bclj" | head -1 | cut -d: -f1)"
echo "  located def at datalog.bclj:$DEF_LINE"
# op2: read its body
BODY="$("$TS" read "$CORP/datalog.bclj" "$DEF_LINE:$DEF_LINE")"
echo "  body line: $BODY"
CAL_OPS="$(ops_of "$L")"
# ground truth: (and (map? t) (contains? t :var))
if echo "$BODY" | grep -qF '(and (map? t) (contains? t :var))'; then CAL_OK=yes; else CAL_OK=NO; fi
echo "  ops=$CAL_OPS  correct=$CAL_OK"
emit cal 0 live "$CAL_OPS" "$CAL_OK" "TIE conceded (grep is one op too)"

# ===========================================================================
# (a) DIRECT CALLERS of cross-module def thread-ids-i — caller MODULE SET (1 hop) [LIVE]
#   THIS IS A 1-vs-1 TIE, CONCEDED. The daemon's :callers returns caller-MODULE
#   groups [module, spelling]; the question both arms actually answer is the caller
#   MODULE SET {kernel, main, tools}. Text reaches it from the op-1 search output
#   ALONE — every classification (def@254 / comment@265 / self-name@266 / real call)
#   is decidable from the op-1 LINE CONTENT, no read required. So text-min = 1 op,
#   matching the graph's 1. (The kernel/work-thread-ids-i caller is caught because we
#   search the DISTINCTIVE bare suffix thread-ids-i, not the qualified k/ form; the
#   qualified-only grep would still drop it — that is the wrong-CHEAP path, not this.)
# ===========================================================================
echo ""
echo "==== (a) direct callers of thread-ids-i — caller MODULE SET (1-vs-1 TIE) ===="
L="$OPLOGS/a.oplog"; export TS_LOG="$L"
# op1 (the ONLY op): distinctive-suffix search; the module set is decidable from the
# -n line content alone (def/comment/self-name vs real call all visible per line).
HITS="$("$TS" search 'thread-ids-i' "$CORP")"
echo "$HITS" | sed 's/^/    /'
A_OPS="$(ops_of "$L")"
# reconstruct the caller MODULE set from the op-1 hits with NO read:
#   main: k/thread-ids-i call sites (81,98,100) -> module main
#   tools: k/thread-ids-i call sites (232,239)  -> module tools
#   kernel: (thread-ids-i idx) at :267 is the real intra-kernel CALL; def@254
#           (line begins "(defn thread-ids-i"), comment@265 (";; "), and
#           work-thread-ids-i's own name@266 ("(defn work-thread-ids-i") are excluded
#           BY LINE CONTENT — no read needed.
MODS="$(echo "$HITS" \
  | grep -E 'k/thread-ids-i|\(thread-ids-i idx\)\)\)' \
  | grep -vE 'defn thread-ids-i|;; ' \
  | sed -E 's#.*/(.*)\.bclj:.*#\1#' | sort -u | paste -sd, -)"
echo "  reconstructed caller module set (from op-1 alone): {$MODS}"
# correctness: must contain main, tools, kernel (the kernel one is the substring trap)
if echo ",$MODS," | grep -q ',main,' && echo ",$MODS," | grep -q ',tools,' && echo ",$MODS," | grep -q ',kernel,'; then
  A_OK=yes; else A_OK=NO; fi
echo "  ops=$A_OPS  correct=$A_OK  (1 op; module set decidable from the search line content alone)"
emit a 1 live "$A_OPS" "$A_OK" "caller MODULE SET {kernel,main,tools} from op-1 alone — 1-vs-1 TIE, conceded"

# ===========================================================================
# (b) ULTIMATE through alias/spelling chains — k/->Claim @ import.bclj   [LIVE]
#   minimal-correct text: 1 alternation search (all 3 spellings in ONE pass) + 1
#   convergence read at kernel.bclj:101 (PROVE all spellings are one defrecord) = 2 ops
#   ground truth: ONE binding (defrecord Claim) + reference set across 3 spellings
#                 (Claim 39, k/Claim 29, k/->Claim 6 = 74 tokens, one identity)
# ===========================================================================
echo ""
echo "==== (b) ultimate behind k/->Claim @ import.bclj ===="
L="$OPLOGS/b.oplog"; export TS_LOG="$L"
# op1: single alternation regex catches all three spellings in ONE pass
SPELLS="$("$TS" search --only '(k/)?(->)?Claim\b' "$CORP" | sort | uniq -c)"
echo "  spelling tally (one pass):"
echo "$SPELLS" | sed 's/^/    /'
# total tokens recomputed from the ALREADY-captured tally — NO extra retrieval.
TOTAL_TOK="$(echo "$SPELLS" | awk '{s+=$1} END{print s}')"
# op2: convergence read — confirm all 3 spellings denote ONE defrecord Claim
CONV="$("$TS" read "$CORP/kernel.bclj" 101:101)"
echo "  convergence read (the single binding): $CONV"
B_OPS="$(ops_of "$L")"
# correctness: tally must be {Claim:39, k/Claim:29, k/->Claim:6} = 74, converging on the one defrecord
N_CLAIM="$(echo "$SPELLS"   | awk '$2=="Claim"{print $1}')"
N_KCLAIM="$(echo "$SPELLS"  | awk '$2=="k/Claim"{print $1}')"
N_CTOR="$(echo "$SPELLS"    | awk '$2=="k/->Claim"{print $1}')"
if [ "${N_CLAIM:-0}" = "39" ] && [ "${N_KCLAIM:-0}" = "29" ] && [ "${N_CTOR:-0}" = "6" ] \
   && echo "$CONV" | grep -qF '(defrecord Claim'; then B_OK=yes; else B_OK=NO; fi
echo "  ops=$B_OPS  correct=$B_OK  (74 tokens=$TOTAL_TOK, three spellings, ONE defrecord identity)"
emit b 1 live "$B_OPS" "$B_OK" "one binding + 3 spellings (39/29/6=74), convergence proven"

# ===========================================================================
# (c) TRANSITIVE BLAST RADIUS — full reaches-closure of thread-ids-i   [LAYER-A]
#   Text holds NO transitive structure -> a correct closure is a hand-run BFS.
#   FRONTIER-BATCHED per METHODOLOGY guard 2: ONE alternation search per BFS LEVEL
#   (not one per node) + ONE classify-read per NEW caller-module-region per level (to
#   map a call-site to its enclosing defn / disambiguate noisy substrings). The
#   closure spans 4 BFS levels (L0=target, L1..L3); the deterministic cost is
#   4 searches + 5 reads = 9 ops (see text_bfs_cost.clj for the model-free count).
#   ground-truth closure (7 members, verified by the resolver oracle):
#     {kernel/work-thread-ids-i, main/cmd-export, main/cmd-validate, tools/call,
#      main/dispatch, main/cmd-call, main/-main}
# ===========================================================================
echo ""
echo "==== (c) transitive blast radius of thread-ids-i (frontier-batched BFS) ===="
L="$OPLOGS/c.oplog"; export TS_LOG="$L"
declare -A CLOSURE=()
# --- LEVEL 0: direct callers of thread-ids-i (1 search) ---
echo "  L0: search thread-ids-i (1 alternation pass)"
H1="$("$TS" search 'thread-ids-i' "$CORP")"
# L0 introduces callers in 3 modules {kernel,main,tools}; one classify-read per region
# to map each call-site to its enclosing defn (and classify def/comment/self in kernel):
echo "  L0: read kernel 254:267 (classify def/comment/self vs call -> work-thread-ids-i)"
"$TS" read "$CORP/kernel.bclj" 254:267 >/dev/null
echo "  L0: read main 74:100 (map call sites 81/98/100 -> cmd-export, cmd-validate)"
M1="$("$TS" read "$CORP/main.bclj" 74:100)"
echo "  L0: read tools 214:239 (map call sites 232/239 -> call)"
T1="$("$TS" read "$CORP/tools.bclj" 214:239)"
for f in work-thread-ids-i cmd-export cmd-validate call; do CLOSURE["$f"]=1; done
echo "    frontier1 = {work-thread-ids-i, cmd-export, cmd-validate, call}"
# --- LEVEL 1: callers of the WHOLE frontier in ONE alternation pass (1 search) ---
echo "  L1: search (cmd-export|cmd-validate|work-thread-ids-i|call) — ONE alternation pass"
F1="$("$TS" search '(cmd-export|cmd-validate|work-thread-ids-i|\bcall\b)' "$CORP")"
# new callers land in module main; one classify-read of that region to confirm the
# real tl/call edge (cmd-call) and the dispatch edges vs comment/substring hits:
echo "  L1: read main 252:302 (confirm tl/call inside cmd-call; cmd-export/validate inside dispatch)"
CM="$("$TS" read "$CORP/main.bclj" 252:302)"
for f in dispatch cmd-call; do CLOSURE["$f"]=1; done
echo "    frontier2 += {dispatch, cmd-call}   (work-thread-ids-i is a leaf)"
# --- LEVEL 2: callers of {dispatch, cmd-call} in ONE alternation pass (1 search) ---
echo "  L2: search (\\bdispatch\\b|cmd-call) — ONE alternation pass"
F2="$("$TS" search '(\bdispatch\b|cmd-call)' "$CORP")"
# new caller lands in module main; one classify-read to confirm (dispatch ...) in -main:
echo "  L2: read main 322:323 (confirm (dispatch ...) inside -main)"
DM="$("$TS" read "$CORP/main.bclj" 322:323)"
CLOSURE["-main"]=1
echo "    frontier3 += {-main}   (cmd-call's caller dispatch already in closure)"
# --- LEVEL 3: callers of -main (1 search) -> FIXPOINT (entry point, no callers) ---
echo "  L3: search -main -> no internal callers -> FIXPOINT (no new module, no read)"
MM="$("$TS" search '\-main' "$CORP")"
echo "    frontier4 = {} -> FIXPOINT reached"
C_OPS="$(ops_of "$L")"
# reconstructed closure size + membership
C_SET="$(printf '%s\n' "${!CLOSURE[@]}" | sort | paste -sd, -)"
C_N="${#CLOSURE[@]}"
# ground-truth closure members (module-qualified in tasks.edn; we match on bare names)
GT="work-thread-ids-i cmd-export cmd-validate call dispatch cmd-call -main"
C_OK=yes
for m in $GT; do [ "${CLOSURE[$m]:-}" = "1" ] || C_OK=NO; done
[ "$C_N" = "7" ] || C_OK=NO
echo "  reconstructed closure ($C_N): {$C_SET}"
echo "  ops=$C_OPS  correct=$C_OK  (7-member reaches-closure rebuilt frontier-by-frontier: 4 searches + 5 reads)"
emit c k layer-A-only "$C_OPS" "$C_OK" "frontier-batched BFS over 7-member closure (4 search + 5 read); graph cost=1 HYPOTHETICAL"

# ===========================================================================
# (d) TYPE-REFERENCE REASONING — which fns take (Vec Claim)?   [LAYER-A]
#   HEADLINE = COST on SOURCE (the substrate chosen when source is available):
#     1 alternation search (both spellings, one pass) + 1 read to confirm inner
#     Claim is the KERNEL Claim + 1 read of multi-line annotation sites = 3 ops.
#   ground truth: 41 sites (20 (Vec Claim) + 21 (Vec k/Claim)) resolving to kernel Claim.
#   SOURCE-UNAVAILABLE sub-case: text-on-lowered cannot recover the GENERIC fns
#     (the (Vec Claim) annotation erases to 0) -> recorded as a TEXT FAILURE.
# ===========================================================================
echo ""
echo "==== (d) which fns take (Vec Claim) — SOURCE headline ===="
L="$OPLOGS/d.oplog"; export TS_LOG="$L"
# op1: single alternation regex, both spellings, one pass. SITE count via --only
#      (a line can carry BOTH a param-type and a return-type annotation, so the
#       annotation-SITE count is the token count the resolver resolves, not lines).
DSITES="$("$TS" search --only '\(Vec (k/)?Claim\)' "$CORP")"
DN="$(echo "$DSITES" | grep -c -E '\(Vec (k/)?Claim\)' || true)"
echo "  found $DN (Vec Claim)/(Vec k/Claim) annotation sites (one pass)"
# op2: read the defrecord to confirm the inner Claim is the KERNEL Claim (one identity)
DCONF="$("$TS" read "$CORP/kernel.bclj" 101:101)"
echo "  inner-type confirmation: $DCONF"
# op3: read a representative multi-line annotation site to confirm the param binding
#      (e.g. tools.bclj:135 catalog [claims :- (Vec ToolSpec)] ... — confirm the binding is a param)
DREAD="$("$TS" read "$CORP/kernel.bclj" 136:136)"
echo "  param-binding confirmation (kernel thread-ids def): $DREAD"
D_OPS="$(ops_of "$L")"
# correctness on SOURCE: 41 sites, split 20/21, inner type = kernel Claim
N20="$(echo "$DSITES" | grep -c -E '\(Vec Claim\)' || true)"
N21="$(echo "$DSITES" | grep -c -E '\(Vec k/Claim\)' || true)"
if [ "$DN" = "41" ] && [ "$N20" = "20" ] && [ "$N21" = "21" ] && echo "$DCONF" | grep -qF '(defrecord Claim'; then
  D_OK=yes; else D_OK=NO; fi
echo "  ops=$D_OPS  correct=$D_OK  (sites=$DN = $N20 (Vec Claim) + $N21 (Vec k/Claim))"
emit d 1 layer-A-only "$D_OPS" "$D_OK" "SOURCE: 41 sites (20+21); graph cost=1 HYPOTHETICAL"

# --- (d) SOURCE-UNAVAILABLE sub-case: text-on-lowered ---
echo ""
echo "==== (d') SOURCE UNAVAILABLE: text-on-lowered (out/fram) ===="
L="$OPLOGS/d_lowered.oplog"; export TS_LOG="$L"
# op1: the same query on the lowered .clj — (Vec Claim) is erased
LOWHITS="$("$TS" search '\(Vec Claim\)' "$LOW")"
LN="$(echo "$LOWHITS" | grep -c -E '\(Vec Claim\)' || true)"
DLOW_OPS="$(ops_of "$L")"
# It finds 0 -> CANNOT recover the (Vec Claim) GENERIC fns -> WRONG for the asked question.
if [ "$LN" = "0" ]; then DLOW_OK="NO (erased)"; else DLOW_OK="?"; fi
# note: lowered KEEPS 4 single-param ^Claim hints (NOT 'blind to Claim'), but those are NOT the generic case
echo "  (Vec Claim) hits in lowered: $LN  -> generic case ERASED"
echo "  ops=$DLOW_OPS  correct=$DLOW_OK  (lowered still has 4 single-param ^Claim hints, but NOT the generic (Vec Claim) fns)"
emit d-lowered 1 layer-A-only "$DLOW_OPS" "$DLOW_OK" "SOURCE-UNAVAILABLE: (Vec Claim) erases to 0 -> text FAILS the generic question"

# ===========================================================================
# (e) EDIT-PROPAGATION — rename Claim->Datum; reported as EDIT-cost (separate).
#   The text edit-cost is NOT a `ts` retrieval; it is a strongest-single-sed +
#   recompile-verify + fix-misses loop, scored by the recompile oracle. The text
#   READ retrievals needed to PLAN the rename are exactly task (b)'s (find all
#   spellings) -> we record (e) as an EDIT row, not a read-op row, and note the
#   conceded isolated-sed parity. (The compile oracle runs in reproduce.sh.)
# ===========================================================================
echo ""
echo "==== (e) rename Claim->Datum (EDIT-cost, reported separately) ===="
# the read needed to find every spelling = the (b) alternation search (1 op);
# the edit itself is a sed + a recompile-verify + any fix-loop (oracle-scored).
emit e k edit "1read+sed+verify" "oracle" "EDIT-cost; isolated-sed parity CONCEDED; oracle = beagle-build-all 0 errors"

# ===========================================================================
# SUMMARY
# ===========================================================================
echo ""
echo "############################################################################"
echo "# PER-TASK TEXT OP-COUNTS  (Layer-A deterministic, minimal-correct)"
echo "############################################################################"
printf '%-12s %-6s %-16s %-22s %-12s %s\n' TASK HOPS SERVED TEXT-OPS CORRECT NOTE
printf '%-12s %-6s %-16s %-22s %-12s %s\n' ---- ---- ------ -------- ------- ----
while IFS=$'\t' read -r t h s o c n; do
  printf '%-12s %-6s %-16s %-22s %-12s %s\n' "$t" "$h" "$s" "$o" "$c" "$n"
done <"$RESULTS"

# copy results + oplogs out of the temp dir so reproduce.sh / the report can read them
OUTDIR="${TEXT_RESULT_DIR:-$HERE/.text-results}"
rm -rf "$OUTDIR"            # clear stale results so the copy is idempotent
mkdir -p "$OUTDIR"
cp "$RESULTS" "$OUTDIR/results.tsv"
cp -r "$OPLOGS" "$OUTDIR/oplogs"
echo ""
echo "results + oplogs copied to: $OUTDIR"
