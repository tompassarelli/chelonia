#!/usr/bin/env bash
# Fram engine demo — the substrate loop over the bundled fictional "launch a
# personal website" threads (no personal data). Shows what the ENGINE does:
# fold Markdown into a claim graph, inspect it, check structural integrity, and
# regenerate the Markdown claim-identically. The life verbs you'd actually live
# in (ready / blocked / leverage / next) belong to a consumer like Lodestar,
# which derives them from this same graph — they are not part of the engine.
#
# Record a cast:  asciinema rec -c ./demo.sh demo.cast  &&  agg demo.cast demo.gif
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# Work on a throwaway copy of the bundled threads so the demo never touches the
# committed examples. FRAM_THREADS / FRAM_LOG point the engine at the copy.
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cp "$HERE"/threads/*.md "$WORK"/
export FRAM_THREADS="$WORK" FRAM_LOG="$WORK/claims.log"

pause() { [ -t 1 ] && sleep 1 || true; }   # pace for recording; instant non-interactively
run()   { printf '\n\033[1;36m$ fram %s\033[0m\n' "$*"; "$HERE/bin/fram" "$@"; pause; }

printf '\033[2m# Fram — an append-only claim graph computed from the Markdown you already write\033[0m\n'
run import                              # fold the Markdown threads into the claim graph
run show 2026-01-01-090500             # one thread, as the (left predicate right) claims it became
run validate                            # structural integrity: dependency cycles, dangling refs

printf '\n\033[2m# export is the verified-lossless inverse of import — files are a view, not a source of truth\033[0m\n'
run export "$WORK/regen"                # regenerate the Markdown from the graph
a=$("$HERE/bin/fram" import | grep -oE '[0-9]+ claims')
b=$(FRAM_THREADS="$WORK/regen" "$HERE/bin/fram" import | grep -oE '[0-9]+ claims')
printf '\033[2m# round-trip: %s in, %s back from the regenerated files — claim-identical (roundtrip_test.clj)\033[0m\n' "$a" "$b"

printf '\n\033[2m# On top of this graph, a consumer (e.g. Lodestar) derives ready / blocked /\n# leverage / next. Those are domain projections, not engine commands.\033[0m\n'
