#!/usr/bin/env bash
# ============================================================================
# git_append_baseline.sh — same-region structural-append baseline (RECEIPT)
#
# Question: when two agents make concurrent STRUCTURAL edits to one module,
# what does git do? Honest trade, calibrated by a control:
#   CTRL  disjoint, well-separated edits        -> MUST auto-merge (calibration gate)
#   DIFF  two appends at DIFFERENT, separated points (concurrent growth)
#   SAME  two appends at the SAME point (both before the end-marker)
#   QUEUE merge-queue emulation of the SAME-point case (the real git arm)
#   ANCH  two inserts before the SAME anchor def
#
# The CTRL case calibrates: if a control with proper context still conflicts,
# the harness is cramped and the other results are artifacts (the #11b bug).
#
# SAFE: operates ONLY in a fresh /tmp repo. Never the fram repo, never a real
# remote, never port 7977 / the canonical tern log.
# ============================================================================
set -u
WORK="$(mktemp -d /tmp/git-append-baseline.XXXXXX)"
cd "$WORK" || exit 1
git init -q
git config user.email r@x.test
git config user.name receipt
git config merge.conflictstyle merge

write_base () {
  cat > m.clj <<'EOF'
(ns m)

(def a 1)

(def b 2)

(def c 3)

;; --- end of module m ---
EOF
}

show () { echo "--- $1 ---"; cat m.clj; echo "--- (end) ---"; }
verdict () { if [ "$1" = 0 ]; then echo "RESULT: AUTO-MERGED (clean)"; else echo "RESULT: CONFLICT"; fi; }

echo "=== GIT SAME-REGION APPEND BASELINE (work: $WORK) ==="
echo
write_base; git add m.clj; git commit -qm base
BASE=$(git rev-parse HEAD)

run_merge_case () { # $1=name $2=awkA $3=awkB
  local name="$1" aw="$2" bw="$3"
  git checkout -q -b "${name}A" "$BASE"
  awk "$aw" m.clj > t && mv t m.clj; git commit -qam "A: $name"
  git checkout -q -b "${name}B" "$BASE"
  awk "$bw" m.clj > t && mv t m.clj; git commit -qam "B: $name"
  git checkout -q "${name}A"
  git merge --no-edit "${name}B" >"${name}.log" 2>&1; local rc=$?
  echo "### ${name}"; verdict $rc
  [ $rc != 0 ] && show "m.clj"
  git merge --abort 2>/dev/null; git checkout -q "$BASE"
  echo
}

# CTRL — disjoint, well-separated (edit (def a) vs edit (def c)) -> must auto-merge
run_merge_case CTRL \
  '{ if ($0=="(def a 1)") print "(def a 11)"; else print }' \
  '{ if ($0=="(def c 3)") print "(def c 33)"; else print }'

# DIFF — append at DIFFERENT separated points: after (def a 1) vs after (def c 3)
run_merge_case DIFF \
  '{ print; if ($0=="(def a 1)") print "(def fooA 9)" }' \
  '{ print; if ($0=="(def c 3)") print "(def fooB 9)" }'

# SAME — both insert a new def before the SAME end-marker line (realistic append-to-module body)
echo "### SAME (both insert before the end-marker — same insertion point)"
git checkout -q -b sameA "$BASE"
sed -i 's|;; --- end of module m ---|(def fooA 9)\n\n;; --- end of module m ---|' m.clj; git commit -qam "A: same-point"
git checkout -q -b sameB "$BASE"
sed -i 's|;; --- end of module m ---|(def fooB 9)\n\n;; --- end of module m ---|' m.clj; git commit -qam "B: same-point"
git checkout -q sameA
git merge --no-edit sameB >same.log 2>&1; rc=$?
verdict $rc; [ $rc != 0 ] && show "m.clj"
git merge --abort 2>/dev/null; git checkout -q "$BASE"
echo

# QUEUE — merge-queue emulation of the SAME-point case: land A, then rebase B
echo "### QUEUE (merge-queue emulation of SAME-point: land A, then integrate B)"
git checkout -q -B main "$BASE"
git merge -q --no-edit sameA
echo "  step 1: A landed on main."
git checkout -q sameB
if git rebase main >queue.log 2>&1; then
  echo "  RESULT: REBASE CLEAN — B serializes after A, both land (queue order)"; show "m.clj"
else
  echo "  RESULT: REBASE CONFLICT — even the queue needs manual resolution for B"; show "m.clj"
  git rebase --abort 2>/dev/null
fi
git checkout -q "$BASE"
echo

# ANCH — both insert before the SAME anchor (before (def b 2))
run_merge_case ANCH \
  '{ if ($0=="(def b 2)") print "(def fooA 9)\n"; print }' \
  '{ if ($0=="(def b 2)") print "(def fooB 9)\n"; print }'

echo "=== END (temp repo $WORK; rm -rf to clean) ==="
