#!/usr/bin/env bash
# fram self-host scorecard — how much of the engine is Beagle-authored vs the
# irreducible hand-Clojure host seam. Excludes tests/ + experiments/ (not the
# engine) and out/ (generated from .bclj). Run from anywhere; reports the rate.
set -euo pipefail
cd "$(dirname "$0")/.."

count() { # count() <ext> : total non-test/non-experiment/non-generated source lines
  find . -name "*.$1" \
    -not -path '*/out/*' -not -path '*/node_modules/*' -not -path '*/.git/*' \
    -not -path '*/.claude/*' -not -path '*/tests/*' -not -path '*/experiments/*' \
    -print0 2>/dev/null | xargs -0 wc -l 2>/dev/null | tail -1 | awk '{print $1}'
}

clj=$(count clj); bclj=$(count bclj)
externs=$(grep -rhE 'declare-extern' --include='*.bclj' . 2>/dev/null | grep -v node_modules | wc -l | tr -d ' ')
distinct=$(grep -rhoE 'fram\.rt/[a-z-]+' --include='*.bclj' . 2>/dev/null | grep -v node_modules | sort -u | wc -l | tr -d ' ')

echo "=== fram self-host scorecard ==="
echo "non-Beagle (hand-Clojure engine): ${clj} lines"
echo "Beagle-authored (.bclj):          ${bclj} lines"
awk -v b="$bclj" -v c="$clj" 'BEGIN{printf "  => %.0f%% Beagle-authored\n", 100*b/(b+c)}'
echo "host seam: ${distinct} distinct fns / ${externs} declare-extern sites"
echo "--- biggest non-Beagle files (port targets) ---"
find . -name '*.clj' -not -path '*/out/*' -not -path '*/node_modules/*' \
  -not -path '*/.git/*' -not -path '*/.claude/*' -not -path '*/tests/*' \
  -not -path '*/experiments/*' -print0 2>/dev/null | xargs -0 wc -l 2>/dev/null \
  | sort -rn | grep -vE ' total$' | head -6
