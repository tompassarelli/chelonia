#!/usr/bin/env bash
# Tier-2 FILE-BASED BASELINE arm — concurrent authoring via the strongest realistic
# file workflow: source find/replace (.bclj) + real git branches + real git 3-way merge,
# plus clojure-lsp / grep as supplementary textual tools. The Beagle compiler is the ORACLE.
#
# This is the FAIR baseline a competent engineer/agent actually uses. It CONCEDES where
# file-based is correct (disjoint edits merge clean & compile; stand-alone rename compiles
# clean) and HOLDS the real failure modes under concurrency:
#   - divergent same-region rename -> real git CONFLICT (manual resolution; lost-update in LWW)
#   - stale cross-module reference -> MERGE-tooling-clean auto-merge the COMPILER (run as CI)
#     rejects LOUDLY (`10 built, 1 error(s)`). NOT a silent ship: the file arm's OWN CI catches
#     it; the cost is MANUAL REWORK. The merge TOOLING (git/grep/lsp) is blind; recompilation
#     is not. The graph's win is that it NEVER produces the broken tree (zero rework), not that
#     the file arm ships undetectably-broken code.
#
# Runs the SAME scenario.edn K=4 ops as the graph arm. Operates on mktemp COPIES only —
# never edits src/fram or any repo claims.log; touches no daemon/port.
#
# Usage:  bash file_arm.sh           # human-readable run, oracle verdicts in front of you
#         FILE_ARM_JSON=1 bash file_arm.sh   # machine block (METRICS=...) for the harness
#
# Needs: racket(unused here), bb(unused here), Beagle (BEAGLE=~/code/beagle), git,
#        clojure-lsp + grep (supplementary). The oracle runs without the LSP.
set -uo pipefail

# ---- locate repo + tools -----------------------------------------------------
HERE="$(cd "$(dirname "$0")" && pwd)"
FRAM="$(cd "$HERE/../.." && pwd)"
export BEAGLE="${BEAGLE:-$HOME/code/beagle}"
export BEAGLE_HOME="${BEAGLE_HOME:-$HOME/code/beagle}"
BUILD="$BEAGLE/bin/beagle-build-all"
SRC="$FRAM/src/fram"
JSON="${FILE_ARM_JSON:-0}"

[ -x "$BUILD" ] || { echo "FATAL: oracle not found at $BUILD" >&2; exit 2; }
[ -d "$SRC" ]   || { echo "FATAL: corpus not found at $SRC" >&2; exit 2; }

# ---- non-destructive workspace (COPIES only) ---------------------------------
W="$(mktemp -d -t t2-filearm.XXXXXX)"
trap 'rm -rf "$W"' EXIT
hr(){ printf '%s\n' "============================================================"; }
say(){ [ "$JSON" = "1" ] || printf '%s\n' "$*"; }

# helper: parse the oracle verdict "N built, M error(s)" -> emits "N M"; M=999 if unparsable
file_arm_oracle() {  # $1 = tree dir, $2 = out dir
  local tree="$1" out="$2" line built errs
  line="$("$BUILD" "$tree" --out "$out" 2>&1 | grep -iE "[0-9]+ built, [0-9]+ error" | tail -1)"
  if [ -z "$line" ]; then echo "0 999 (UNPARSABLE)"; return; fi
  built="$(printf '%s' "$line" | grep -oE '[0-9]+ built' | grep -oE '[0-9]+')"
  errs="$(printf '%s' "$line" | grep -oE '[0-9]+ error' | grep -oE '[0-9]+')"
  echo "${built:-0} ${errs:-999} :: $line"
}
# helper: the specific compiler error text (filtering lint noise)
file_arm_oracle_error() {  # $1 = tree, $2 = out
  "$BUILD" "$1" --out "$2" 2>&1 | grep -iE "expected.*got|: type errors|call to " | grep -ivE "lint|declare-extern" | head -3
}
# helper: a fresh git repo seeded with the frozen base, returns its path on stdout
file_arm_seed_repo() {  # $1 = repo dir name under $W
  local g="$W/$1"; rm -rf "$g"; mkdir -p "$g"
  cp "$SRC"/*.bclj "$SRC"/*.clj "$g/"
  git -C "$g" init -q
  git -C "$g" config user.email t2@example.com
  git -C "$g" config user.name  t2-file-arm
  git -C "$g" add -A; git -C "$g" commit -qm "frozen base: unmodified corpus" >/dev/null
  echo "$g"
}

# ---- metric accumulators -----------------------------------------------------
M1_lost_updates=0          # writes accepted then dropped (LWW no-VCS sub-mode; explicitly weaker)
M2_manual_conflicts=0      # git merge conflicts needing manual resolution
M4_built=0; M4_errs=0      # final-tree oracle verdict
M5_stale_refs=0            # stale cross-module refs (MERGE tools green, compiler/CI red)
MISC_SILENT=0              # merge-tool-blind stale refs (clean merge, CI rejects >=1 error) — NOT silent ships

# =============================================================================
say; hr
say "TIER-2 FILE-BASED BASELINE ARM  (strongest real workflow: source find/replace + git 3-way merge)"
say "corpus: $SRC  (COPIED to $W; src/fram is NEVER edited)"
say "oracle: $BUILD  (parsed for EXACTLY 0 errors — never substring 'includes 0 error')"
hr

# ---- 0. baseline sanity: the unmodified corpus compiles clean ----------------
say
say "0. BASELINE — the unmodified frozen base compiles:"
cp -r "$SRC" "$W/base"
B0="$(file_arm_oracle "$W/base" "$W/base-out")"
say "   ORACLE: ${B0#* * :: }   (we start from $(echo "$B0" | awk '{print $1}') built, $(echo "$B0" | awk '{print $2}') errors)"

# =============================================================================
# COUNTER-DISCRIMINATION (fairness): the STAND-ALONE rename — the file arm WINS it.
#   The strongest real rename = multi-file find/replace on .bclj SOURCE.
# =============================================================================
say
hr
say "A. COUNTER-DISCRIMINATION — STAND-ALONE rename (file arm's CONCEDED win)"
say "   strongest baseline: multi-file find/replace on .bclj SOURCE  (sed -E 's/\\bClaim\\b/Datum/g')"
cp -r "$SRC" "$W/standalone"
TOK_BEFORE=$(grep -rhoE '\bClaim\b' "$W/standalone"/*.bclj | wc -l)
MODS_TOUCHED=$(grep -lE '\bClaim\b' "$W/standalone"/*.bclj | wc -l)
sed -E -i 's/\bClaim\b/Datum/g' "$W/standalone"/*.bclj
TOK_AFTER=$(grep -rhoE '\bClaim\b' "$W/standalone"/*.bclj | wc -l)
SA="$(file_arm_oracle "$W/standalone" "$W/standalone-out")"
SA_ERR=$(echo "$SA" | awk '{print $2}')
say "   Claim tokens caught: $TOK_BEFORE across $MODS_TOUCHED modules; Claim remaining after: $TOK_AFTER"
say "   ORACLE: ${SA#* * :: }"
say "   => the source find/replace catches EVERY annotation and recompiles 0 error(s)."
say "      THE FILE ARM WINS THE ISOLATED RENAME. The graph does NOT beat it here. (conceded)"

# =============================================================================
# THE K=4 SCENARIO under concurrency (real git branches off ONE common ancestor)
# =============================================================================
say
hr
say "B. THE SCENARIO — K=4 concurrent authoring ops, each on its OWN branch off ONE base"
say "   op1 A: rename Claim->Datum (kernel)      op2 B: add schema-claim-count (schema, refs k/Claim)"
say "   op3 C: rename Claim->Fact  (kernel)      op4 D: set-body var? (datalog, disjoint)"

# ---- pair 1+3: DIVERGENT same-region rename -> real git CONFLICT --------------
say
say "--------------------------------------------------------------------"
say "PAIR (1+3) — DIVERGENT concurrent rename  (Claim->Datum  vs  Claim->Fact)"
G13="$(file_arm_seed_repo divergent)"
BASE13="$(git -C "$G13" rev-parse HEAD)"
# branch A
git -C "$G13" checkout -q -b agent-A-rename
sed -E -i 's/\bClaim\b/Datum/g' "$G13"/*.bclj
git -C "$G13" commit -qam "op1 A: rename Claim->Datum (source find/replace)" >/dev/null
# branch C off the SAME base
git -C "$G13" checkout -q "$BASE13"
git -C "$G13" checkout -q -b agent-C-rename
sed -E -i 's/\bClaim\b/Fact/g' "$G13"/*.bclj
git -C "$G13" commit -qam "op3 C: rename Claim->Fact (divergent)" >/dev/null
# REAL 3-way merge
git -C "$G13" checkout -q agent-A-rename
git -C "$G13" merge agent-C-rename -m "merge C into A" >"$W/merge13.log" 2>&1
CONF13=$(git -C "$G13" diff --name-only --diff-filter=U | wc -l)
say "   git merge (branch-merge sub-mode): $(grep -c '^CONFLICT' "$W/merge13.log") CONFLICT line(s)"
say "   CONFLICTED FILES NEEDING MANUAL RESOLUTION: $CONF13"
git -C "$G13" diff --name-only --diff-filter=U | sed 's/^/     - /' | { [ "$JSON" = 1 ] || cat; }
say "   (git SURFACES this — a real file-arm strength; conceded. But it costs MANUAL resolution.)"
[ "$CONF13" -ge 1 ] && M2_manual_conflicts=$((M2_manual_conflicts + CONF13))
git -C "$G13" merge --abort 2>/dev/null

# LWW (last-writer-wins working-copy) sub-mode -> a LOST UPDATE
say
say "   LWW sub-mode (sequential working-copy save, no branch): C saves over A's working copy"
cp -r "$SRC" "$W/lww"
sed -E -i 's/\bClaim\b/Datum/g' "$W/lww"/*.bclj          # A renames Claim->Datum
DATUM_AFTER_A=$(grep -rhoE '\bDatum\b' "$W/lww"/*.bclj | wc -l)
# C, unaware, opened the ORIGINAL base and saves its Claim->Fact over the same files:
cp -r "$SRC" "$W/lww-c-buffer"
sed -E -i 's/\bClaim\b/Fact/g' "$W/lww-c-buffer"/*.bclj  # C's buffer (from stale base)
cp "$W/lww-c-buffer"/*.bclj "$W/lww"/                    # C's save CLOBBERS A's files
DATUM_AFTER_C=$(grep -rhoE '\bDatum\b' "$W/lww"/*.bclj | wc -l)
say "     after A's save: Datum tokens=$DATUM_AFTER_A ; after C's clobbering save: Datum tokens=$DATUM_AFTER_C"
if [ "$DATUM_AFTER_C" -lt "$DATUM_AFTER_A" ]; then
  say "     => A's ENTIRE rename was SILENTLY OVERWRITTEN by C's save — LOST UPDATE (no warning)."
  M1_lost_updates=$((M1_lost_updates + 1))
fi

# ---- pair 1+2: STALE cross-module reference -> clean merge the COMPILER rejects ----
say
say "--------------------------------------------------------------------"
say "PAIR (1+2) — STALE CROSS-MODULE REFERENCE  (THE discriminator)"
say "   op1 A: rename Claim->Datum (kernel etc.)   op2 B: add schema-claim-count (schema, (Vec k/Claim))"
say "   schema.bclj has 0 Claim tokens -> A's rename never touches it -> DIFFERENT files."
G12="$(file_arm_seed_repo stale-ref)"
BASE12="$(git -C "$G12" rev-parse HEAD)"
# branch A (rename)
git -C "$G12" checkout -q -b agent-A-rename
sed -E -i 's/\bClaim\b/Datum/g' "$G12"/*.bclj
git -C "$G12" commit -qam "op1 A: rename Claim->Datum" >/dev/null
# branch B off SAME base: append require + new fn referencing k/Claim
git -C "$G12" checkout -q "$BASE12"
git -C "$G12" checkout -q -b agent-B-add
cat >> "$G12/schema.bclj" <<'BFORM'

(require fram.kernel :as k)
(defn schema-claim-count [claims :- (Vec k/Claim)] :- (Vec String)
  (k/thread-ids claims))
BFORM
git -C "$G12" commit -qam "op2 B: add schema-claim-count (refs k/Claim)" >/dev/null
# REAL 3-way merge (B into A): disjoint files -> auto-merge clean
git -C "$G12" checkout -q agent-A-rename
git -C "$G12" merge agent-B-add -m "merge B into A" >"$W/merge12.log" 2>&1
CONF12=$(git -C "$G12" diff --name-only --diff-filter=U | wc -l)
say "   git merge (disjoint files): CONFLICTS = $CONF12   (git: '$(grep -m1 -iE 'merge made|already' "$W/merge12.log" | sed 's/^ *//')')"
# textual tools all say GREEN:
GREP_KCLAIM=$(grep -c 'k/Claim' "$G12/schema.bclj")
say "   TEXTUAL TOOLS on the merged tree (every one says 'fine'):"
say "     - git:  exit 0, $CONF12 conflicted files"
say "     - grep: 'k/Claim' in schema.bclj -> $GREP_KCLAIM match (grep reports 'reference still present, fine')"
# clojure-lsp supplementary note (blind to erased type annotation on emitted .clj)
if command -v clojure-lsp >/dev/null 2>&1; then
  LSP="$W/lsp12"; mkdir -p "$LSP/src/fram"
  cp "$FRAM"/out/fram/*.clj "$LSP/src/fram/" 2>/dev/null || true
  printf '{:paths ["src"] :deps {org.clojure/clojure {:mvn/version "1.11.1"}}}\n' > "$LSP/deps.edn"
  mkdir -p "$LSP/.clojure-lsp"; printf '{:source-paths #{"src"}}\n' > "$LSP/.clojure-lsp/config.edn"
  TA_CLJ=$(grep -rhoE ':-[^]]*\bClaim\b' "$FRAM"/out/fram/*.clj 2>/dev/null | wc -l)
  TA_SRC=$(grep -rhoE ':-[^]]*\bClaim\b' "$FRAM"/src/fram/*.bclj 2>/dev/null | wc -l)
  say "     - clojure-lsp (on emitted .clj): type-annotation Claim refs there = $TA_CLJ"
  say "       (source has $TA_SRC :- ...Claim annotations; they ERASE to $TA_CLJ at lowering -> the LSP has nothing to rename. SUPPLEMENTARY note only.)"
else
  say "     - clojure-lsp: not installed (supplementary note skipped)"
fi
# THE ORACLE on the same merged tree:
O12="$(file_arm_oracle "$G12" "$W/g12-out")"
O12_ERR=$(echo "$O12" | awk '{print $2}')
say "   THE ORACLE on that SAME 'all-green' merged tree:"
say "     ${O12#* * :: }"
file_arm_oracle_error "$G12" "$W/g12-out2" | sed 's/^/     /' | { [ "$JSON" = 1 ] || cat; }
if [ "$CONF12" -eq 0 ] && [ "$O12_ERR" -ge 1 ]; then
  say "   => MERGE-tooling-CLEAN merge, NON-COMPILING tree. No MERGE/textual tool (git/grep/lsp)"
  say "      could connect B's k/Claim (schema.bclj) to A's rename (kernel.bclj) across files;"
  say "      only RECOMPILATION (the file arm's OWN CI) caught it -> the merge is REJECTED, not"
  say "      shipped -> MANUAL REWORK. (NOT silent: caught loudly by CI. The graph pays zero rework.)"
  M5_stale_refs=$((M5_stale_refs + 1))
  MISC_SILENT=$((MISC_SILENT + 1))
fi

# ---- op 4: TRULY-DISJOINT edit -> file arm WINS (clean + compiles) ------------
say
say "--------------------------------------------------------------------"
say "OP 4 — TRULY-DISJOINT edit  (set-body var? in datalog — THE CONCESSION)"
G4="$(file_arm_seed_repo disjoint)"
BASE4="$(git -C "$G4" rev-parse HEAD)"
git -C "$G4" checkout -q -b agent-D-body
# behavior-preserving, byte-different body edit of datalog/var?
sed -E -i 's/\(defn- var\? \[t :- Any\] :- Bool \(and \(map\? t\) \(contains\? t :var\)\)\)/(defn- var? [t :- Any] :- Bool (and (contains? t :var) (map? t)))/' "$G4/datalog.bclj"
git -C "$G4" commit -qam "op4 D: set-body var? (behavior-preserving)" >/dev/null
git -C "$G4" checkout -q "$BASE4"
git -C "$G4" merge agent-D-body -m "merge D" >"$W/merge4.log" 2>&1
CONF4=$(git -C "$G4" diff --name-only --diff-filter=U | wc -l)
O4="$(file_arm_oracle "$G4" "$W/g4-out")"
say "   git merge: CONFLICTS = $CONF4 ;  ORACLE: ${O4#* * :: }"
say "   => clean merge, compiles, ZERO coordination cost. THE FILE ARM WINS. (conceded)"

# =============================================================================
# THE FILE ARM'S FINAL DELIVERED TREE (the realistic ship): merge the
# auto-mergeable concurrent ops the agents would actually integrate. The divergent
# pair (1+3) forced a manual conflict above; a competent team resolves it by
# PICKING ONE rename — model the realistic outcome: A's Datum wins, C is dropped
# (lost update already counted), and B + D land. This is the tree they SHIP.
# =============================================================================
say
hr
say "C. THE FILE ARM'S FINAL SHIPPED TREE  (A's rename + B's add + D's edit; C resolved by picking A)"
GF="$(file_arm_seed_repo final)"
BASEF="$(git -C "$GF" rev-parse HEAD)"
# A
git -C "$GF" checkout -q -b agent-A-rename
sed -E -i 's/\bClaim\b/Datum/g' "$GF"/*.bclj
git -C "$GF" commit -qam "op1 A" >/dev/null
# B
git -C "$GF" checkout -q "$BASEF"; git -C "$GF" checkout -q -b agent-B-add
cat >> "$GF/schema.bclj" <<'BFORM'

(require fram.kernel :as k)
(defn schema-claim-count [claims :- (Vec k/Claim)] :- (Vec String)
  (k/thread-ids claims))
BFORM
git -C "$GF" commit -qam "op2 B" >/dev/null
# D
git -C "$GF" checkout -q "$BASEF"; git -C "$GF" checkout -q -b agent-D-body
sed -E -i 's/\(defn- var\? \[t :- Any\] :- Bool \(and \(map\? t\) \(contains\? t :var\)\)\)/(defn- var? [t :- Any] :- Bool (and (contains? t :var) (map? t)))/' "$GF/datalog.bclj"
git -C "$GF" commit -qam "op4 D" >/dev/null
# integrate B and D into A (auto-merge clean). C (divergent) dropped per manual resolution.
git -C "$GF" checkout -q agent-A-rename
git -C "$GF" merge agent-B-add -m "merge B" >>"$W/mergeF.log" 2>&1
git -C "$GF" merge agent-D-body -m "merge D" >>"$W/mergeF.log" 2>&1
CONFF=$(git -C "$GF" diff --name-only --diff-filter=U | wc -l)
OF="$(file_arm_oracle "$GF" "$W/gF-out")"
M4_built=$(echo "$OF" | awk '{print $1}')
M4_errs=$(echo "$OF" | awk '{print $2}')
say "   merged conflicts during integration of B,D into A: $CONFF (auto-merge clean)"
say "   THE ORACLE on the FINAL SHIPPED tree:"
say "     ${OF#* * :: }"
file_arm_oracle_error "$GF" "$W/gF-out2" | sed 's/^/     /' | { [ "$JSON" = 1 ] || cat; }

# =============================================================================
# METRICS SUMMARY (all MEASURED above)
# =============================================================================
say
hr
say "FILE-ARM METRICS (MEASURED):"
say "  M1 lost updates ................. $M1_lost_updates   (LWW no-VCS working-copy clobber; WEAKER sub-mode — git surfaces this as M2)"
say "  M2 manual merge conflicts ...... $M2_manual_conflicts   (op1 vs op3 divergent rename: conflicted files)"
say "  M3 writes rejected fail-closed . n/a  (no PRE-commit gate; the file arm's CI catches bad merges AFTER, see M4 — at a manual-rework cost)"
say "  M4 final recompile / CI (ORACLE) $M4_built built, $M4_errs error(s)   <- THE VERDICT (the file arm's own CI rejects this merge)"
say "  M5 stale cross-module refs ..... $M5_stale_refs   (op2 k/Claim: git+grep green, LSP blind, compiler/CI red)"
say "  M_blind merge-tool-blind refs .. $MISC_SILENT   (MERGE-clean merge, CI rejects >=1 error — caught loudly, NOT a silent ship)"
say
say "  CONCESSIONS (file arm WINS / ties): stand-alone rename = $SA_ERR error(s); op4 disjoint = clean+compiles."
say "  M6 serialization cost: file arm pays NONE (parallel branches) — its real advantage; the graph pays it."
hr

# ---- machine-readable block for the orchestrating harness ---------------------
if [ "$JSON" = "1" ]; then
  printf 'METRICS arm=file M1_lost_updates=%s M2_manual_conflicts=%s M3_rejected=n/a M4_built=%s M4_errors=%s M5_stale_refs=%s merge_tool_blind_refs=%s standalone_rename_errors=%s op4_disjoint=clean_compiles\n' \
    "$M1_lost_updates" "$M2_manual_conflicts" "$M4_built" "$M4_errs" "$M5_stale_refs" "$MISC_SILENT" "$SA_ERR"
fi

# ---- exit status: the FILE ARM's CI is EXPECTED to REJECT the merge (>=1 error) -----
# Return 0 if the harness ran and produced the predicted discriminating verdict
# (final tree has >=1 error AND the failing merge was MERGE-tool-clean — caught by CI,
# not by any merge tool); else 1.
if [ "$M4_errs" -ge 1 ] && [ "$MISC_SILENT" -ge 1 ]; then
  say "RESULT: file arm reproduced the predicted failure — its CI REJECTS the merge ($M4_errs error(s)),"
  say "        and the failing merge was MERGE-tooling-CLEAN (caught only by recompilation, not silent)."
  say "        The compiler is the witness; the cost is manual rework the graph never pays."
  exit 0
else
  say "RESULT: file arm did NOT reproduce the predicted discriminator (unexpected) — investigate."
  exit 1
fi
