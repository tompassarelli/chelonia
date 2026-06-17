#!/usr/bin/env bash
# Recompile Fram's Beagle (.bclj) sources to Clojure into out/.
#
# You do NOT need this to run Fram — the compiled Clojure in out/ is
# committed and runs on babashka (bin/fram). You only need this to rebuild
# from the .bclj sources, which requires Beagle (a typed Lisp that compiles to
# Clojure) at $BEAGLE_HOME (default ~/code/beagle), entered via direnv.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"; OUT="$HERE/out"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle}"

mkdir -p "$OUT/fram"
cp "$SRC/fram/rt.clj" "$OUT/fram/rt.clj"     # hand-written runtime ships as-is
cp "$SRC/fram/json.clj" "$OUT/fram/json.clj" # JSON runtime for the clockify module
for m in cnf schema datalog kernel fold import export query tools main; do
  BEAGLE_EMIT_SRCLOC=0 direnv exec "$BEAGLE" "$BEAGLE/bin/beagle-build" \
    "$SRC/fram/$m.bclj" "$OUT/fram/$m.clj" >/dev/null
  echo "  built fram/$m"
done
echo "fram built -> $OUT"
