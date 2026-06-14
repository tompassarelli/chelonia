#!/usr/bin/env bash
# Recompile Chelonia's Beagle (.bclj) sources to Clojure into out/.
#
# You do NOT need this to run Chelonia — the compiled Clojure in out/ is
# committed and runs on babashka (bin/chelonia). You only need this to rebuild
# from the .bclj sources, which requires Beagle (a typed Lisp that compiles to
# Clojure) at $BEAGLE_HOME (default ~/code/beagle), entered via direnv.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$HERE/src"; OUT="$HERE/out"
BEAGLE="${BEAGLE_HOME:-$HOME/code/beagle}"

mkdir -p "$OUT/chelonia"
cp "$SRC/chelonia/rt.clj" "$OUT/chelonia/rt.clj"   # hand-written runtime ships as-is
for m in kernel fold projections import main; do
  BEAGLE_EMIT_SRCLOC=0 direnv exec "$BEAGLE" "$BEAGLE/bin/beagle-build" \
    "$SRC/chelonia/$m.bclj" "$OUT/chelonia/$m.clj" >/dev/null
  echo "  built chelonia/$m"
done
echo "chelonia built -> $OUT"
