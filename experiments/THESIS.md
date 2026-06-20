# THESIS — the actual claim, and the only test that can prove it

> Read this FIRST, before any experiment doc. It outranks any other experiment write-up.

## The claim
The graph is the **canonical source of truth** — *not* derived from text — and that uniquely enables
two things a derived index / warm cache over text cannot:
1. **Structure that cannot go stale** — because it *is* the truth, not a copy that drifts.
2. **Edits as transactions on structure** — which is what makes **multi-agent coordination** work.

## What is NOT the thesis
**Query speed is not the thesis.** A warm cache / read-only inferred index over text gives fast
structural queries too (that's what clojure-lsp / Glean / "graphify" already do). "1 query vs N
greps" is the *graphify* result. It proves nothing unique to a canonical graph. Likewise
refactor-safety via "rebuild and check" (recompile-gate) is substrate-independent — bolt it onto text
and it works there too.

## The only experiments that can prove the thesis
**Coordination experiments** — because staleness-immunity and transactional edits are the *only*
unique wins, and they only show up under concurrent multi-agent authoring.
- Baseline: a *strong* file workflow — git worktrees + a merge-queue + CI gate (NOT a strawman).
- Measure: do N agents coordinate with less ceremony / fewer stale-state failures on the claim log
  than on the git baseline? Does the graph make a class of coordination failure *impossible by
  construction* that the git baseline can only catch after the fact?

## The hard precondition: the flip must be REAL first
A coordination experiment is meaningless until the graph is actually canonical. "Real flip" means:
1. **Code claims are persisted in the canonical log** — the log, not the text, is the stored source.
2. **Edits source FROM the claim graph** (mint/supersede on log-resident node identity), with `.bclj`
   emitted as a downstream projection — NOT read-`.bclj` → edit → re-emit.
3. **Proof that counts:** delete the `.bclj` entirely and the system still **resolves, queries, and
   compiles** purely from the claim log. *If it can't run from the log alone with no `.bclj` present,
   it isn't flipped.*

Until the flip is real, **NO experiment tests the thesis.** Anything claiming otherwise is testing
graphify — a warm index over text gives the same query speed and proves nothing unique to a canonical graph.

## Validity-scope habit (so this never happens again)
Every experiment output must state at the TOP what it does and does NOT prove — e.g. "this tests X;
this does NOT test the thesis if Y." An output that omits its validity scope is how a future run
inherits a false belief from disk.
