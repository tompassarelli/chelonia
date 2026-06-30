#!/usr/bin/env bash
# readme-check.sh — the README anti-rot gate. Run by CI; run locally with --local.
#
# A README rots silently: a renamed verb, a moved test, a stale org in a clone URL,
# a measured number no one re-ran. This gate fails CI on the mechanical ones, so the
# README stays true to the engine instead of drifting from it.
#
#   (1) stale repo URLs   — the confirmed-wrong org/repo forms must not appear.
#   (2) engine verbs      — every `bin/fram <verb>` named in README must be a real verb.
#   (3) bin entrypoints   — every `bin/fram-*` named in README must exist + be executable.
#   (4) referenced paths  — every relative link/path in README must exist.
#   (5) the core loop runs — import / validate / call / query / export on a SCRATCH copy
#                            of threads/ (the canonical claims.log is never touched).
#   --local additionally checks the toolchain (bb / clojure / java) is on PATH.
set -uo pipefail
cd "$(dirname "$0")/.."                      # repo root
README=README.md
fail=0
note() { printf '  %s\n' "$*"; }
bad()  { printf 'FAIL: %s\n' "$*"; fail=1; }

# (1) stale repo URLs — wrong org/repo forms. (tompassarelli/tern is CORRECT; not listed.)
echo "== (1) repo URLs =="
BANNED='tompassarelli/(fram|beagle|eddy|chartroom)|Autonymy/(tern|eddy|chartroom)'
if hits=$(grep -rnE "$BANNED" "$README" .github 2>/dev/null); then
  bad "stale/wrong repo URL(s):"; printf '%s\n' "$hits" | sed 's/^/    /'
else note "ok — no stale org/repo forms"; fi

# (2) engine verbs — `bin/fram <verb>` in README must appear in the no-arg usage.
echo "== (2) engine verbs =="
usage=$(bin/fram 2>&1 || true)
for v in $(grep -oE 'bin/fram [a-z][a-z-]*' "$README" | awk '{print $2}' | sort -u); do
  if printf '%s' "$usage" | grep -qw -- "$v"; then note "ok — bin/fram $v"
  else bad "README references 'bin/fram $v' but it is not in 'bin/fram' usage"; fi
done

# (3) bin entrypoints — `bin/fram-*` referenced in README must exist + be executable.
echo "== (3) bin entrypoints =="
for b in $(grep -oE 'bin/fram-[a-z]+' "$README" | sort -u); do
  if [ -x "$b" ]; then note "ok — $b"; else bad "README names $b but it is missing/not executable"; fi
done

# (4) referenced paths — relative markdown links + the layout paths must exist.
echo "== (4) referenced paths =="
for p in $(grep -oE '\]\(([^)#]+)\)' "$README" | sed -E 's/^\]\(//; s/\)$//' \
            | grep -vE '^https?://' | sort -u); do
  if [ -e "$p" ]; then note "ok — $p"; else bad "README links a missing path: $p"; fi
done

# (5) the core loop runs — on a scratch copy; canonical claims.log untouched.
echo "== (5) core engine loop (scratch copy) =="
WD=$(mktemp -d)
trap 'rm -rf "$WD"' EXIT
cp -r threads "$WD/threads"
export FRAM_THREADS="$WD/threads" FRAM_LOG="$WD/claims.log"
run() { echo "   \$ $*"; if "$@" >/dev/null 2>&1; then note "ok"; else bad "command failed: $*"; fi; }
run bin/fram import
run bin/fram validate
ID=$(grep -hoE '^@[0-9-]+' "$WD"/threads/*.md 2>/dev/null | head -1 | tr -d '@')
[ -n "${ID:-}" ] && run bin/fram call title-of "{:id \"$ID\"}"
run bin/fram query '{:find "po" :rules [{:head {:rel "po" :args [{:var "x"} {:var "y"}]} :body [{:rel "triple" :args [{:var "x"} "part_of" {:var "y"}]}]}]}'
run bin/fram export "$WD/regen"

# --local: toolchain present
if [ "${1:-}" = "--local" ]; then
  echo "== (local) toolchain =="
  for c in bb clojure java; do
    if command -v "$c" >/dev/null 2>&1; then note "ok — $c"; else bad "missing tool: $c"; fi
  done
fi

echo
if [ "$fail" -eq 0 ]; then echo "README OK — engine is the source of truth, and the README agrees."; else
  echo "README rot detected (see FAIL lines above)."; fi
exit "$fail"
