#!/usr/bin/env bash
# Lodestar demo — the value loop over the bundled fictional "launch a personal
# website" threads. Nobody maintains a board; it's all computed from the same
# Markdown you'd write anyway.
#
# Record a cast:  asciinema rec -c ./demo.sh demo.cast  &&  agg demo.cast demo.gif
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"

# Work on a throwaway copy of the bundled threads so `capture` can write into it
# without touching the committed examples.
WORK="$(mktemp -d)"; trap 'rm -rf "$WORK"' EXIT
cp "$HERE"/threads/*.md "$WORK"/
export CHELONIA_THREADS="$WORK" CHELONIA_LOG="$WORK/claims.log"

pause() { [ -t 1 ] && sleep 1 || true; }   # pace for recording; instant non-interactively
run()   { printf '\n\033[1;36m$ lodestar %s\033[0m\n' "$*"; "$HERE/bin/lodestar" "$@"; pause; }

printf '\033[2m# Lodestar — a queryable dependency graph for work + life, computed from Markdown\033[0m\n'
run import                              # fold the Markdown threads into a claim graph
run ready                               # what is actually actionable now
run blocked                             # what is waiting, and on what
run leverage                            # the boring keystone that unblocks the most — a flat list CANNOT show this
run next                                # ranked: leverage + deadline + momentum
printf '\n\033[2m# A thought arrives. Capture it in one line — no form, no board.\033[0m\n'
run capture "Write the launch tweet"    # thought -> a ready thread, folded into the graph
run ready                               # ...and it is already actionable, with no maintenance step
printf '\n\033[2m# Nobody updated a board. leverage named the unglamorous keystone; capture turned a thought into structure.\033[0m\n'
