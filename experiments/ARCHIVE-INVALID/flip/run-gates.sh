#!/usr/bin/env bash
# ============================================================================
# THE FLIP — gate harness. Runs the 5 flip gates + the KEYSTONE over the CODE
# claim log, capturing evidence. Non-destructive: a throwaway daemon on a /tmp
# copy of .fram/code.log, a verified-free high port, trap-kill. NEVER touches
# port 7977 or the lodestar log.
#
#   experiments/flip/run-gates.sh
#
# Gates 3/4/5 + KEYSTONE-A/B/C are driven by cnf_code_flip_test.clj (in-process
# throwaway daemon). Gates 1 (rename = one triple) + 2 (recompiles) are driven
# here via resolve.clj + beagle-build-all over the render-from-log tree.
# ============================================================================
set -uo pipefail
FRAM="$(cd "$(dirname "$0")/../.." && pwd)"; cd "$FRAM"
export BEAGLE_HOME="${BEAGLE_HOME:-$HOME/code/beagle}"
export BEAGLE="${BEAGLE:-$BEAGLE_HOME}"
RT="$BEAGLE_HOME/beagle-lib/private/claims-roundtrip.rkt"
BUILD="$BEAGLE_HOME/bin/beagle-build-all"
export FRAM_OUT="$FRAM/out"
CODE_LOG="$FRAM/.fram/code.log"
hr(){ printf '%s\n' "============================================================"; }

hr; echo "STAGE 1 — INGEST: src/fram/schema.bclj -> $CODE_LOG (lossless re-key)"
bb -cp out bin/fram-ingest-code src/fram --module schema --out "$CODE_LOG"
echo "  code log: $(wc -l < "$CODE_LOG") AST claim lines"
echo "  refers_to/marker lines in the code log (must be 0): $(grep -cE ':p "(refers_to|keep_spelling|qualifier|ctor_prefix|accessor_field|supersedes)"' "$CODE_LOG" || true)"

hr; echo "STAGE 2 — RENDER-FROM-LOG + KEYSTONE-A (render(log) == render(text) byte-identical)"
W="$(mktemp -d)"; trap 'rm -rf "$W"' EXIT
bb -cp out bin/fram-render-code schema --log "$CODE_LOG" --out "$W/from-log.bclj"
# render-from-text (the existing authoring projection)
mkdir -p "$W/ro"
racket "$RT" --emit-edn src/fram/schema.bclj > "$W/ro/schema-emit.edn" 2>/dev/null
RESOLVE_OUT="$W/ro" bb -cp out chartroom/src/resolve.clj resolve "$W/ro/schema-emit.edn" >/dev/null 2>&1
racket "$RT" --render "$W/ro/resolved-schema.bclj.edn" > "$W/from-text.bclj" 2>/dev/null
if cmp -s "$W/from-log.bclj" "$W/from-text.bclj"; then
  echo "  [PASS] KEYSTONE-A: render(log) == render(text) BYTE-IDENTICAL"
else
  echo "  [FAIL] KEYSTONE-A: render(log) != render(text)"; diff "$W/from-log.bclj" "$W/from-text.bclj" | head
fi

hr; echo "GATE 1 — rename = ONE triple (resolve.clj supersedes ONE binding's v; refs follow refers_to)"
mkdir -p "$W/r"
EDITED=$(RESOLVE_OUT="$W/r" bb -cp out chartroom/src/resolve.clj rename cardinality cardinality2 schema "$W/ro/schema-emit.edn" 2>&1 | grep -oE 'CLAIMS EDITED: [0-9]+' || true)
echo "  $EDITED"
if [ "$EDITED" = "CLAIMS EDITED: 1" ]; then echo "  [PASS] GATE 1: rename is ONE triple"; else echo "  [FAIL] GATE 1: expected 'CLAIMS EDITED: 1'"; fi

hr; echo "GATE 2 — recompiles: the render-from-log tree builds '0 error'"
mkdir -p "$W/src" "$W/out"; cp "$W/from-log.bclj" "$W/src/schema.bclj"
BUILT=$("$BUILD" "$W/src" --out "$W/out" 2>&1 | grep -oE '[0-9]+ built, [0-9]+ error\(s\)' || true)
echo "  verdict: $BUILT"
if echo "$BUILT" | grep -qE '1 built, 0 error\(s\)'; then echo "  [PASS] GATE 2: render-from-log recompiles"; else echo "  [FAIL] GATE 2"; fi

hr; echo "GATES 3/4/5 + KEYSTONE-B/C — in-process throwaway daemon (cnf_code_flip_test.clj)"
bb -cp out cnf_code_flip_test.clj
RC=$?

hr
if [ "$RC" -eq 0 ]; then
  echo "THE FLIP gate harness: all gates GREEN — the .bclj is a pure function of the CODE log."
else
  echo "THE FLIP gate harness: cnf_code_flip_test.clj FAILED (rc=$RC)"; exit 1
fi
