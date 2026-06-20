# Loop Spec — autonomous authoring-harness measurement (your constitution)

## Thesis
Fram stores code as a graph of identity-addressed claims, not text. A reference carries
refers_to <node-id> (identity, not spelling), so a rename is one edit to one node and every
reference re-points for free. Text addresses by spelling+position; both move on edit, so a
rename means reconstructing scattered spellings. The discriminator is reconstruction cost vs N.

## What is already settled (do NOT re-litigate, do NOT try to reopen)
- There is NO analyzer-based Tier-2 miss to find. Verified by direct read: refers_to is an
  unexpanded-surface lexical walk (syntax->datum, zero macroexpand; resolve.clj has no expand
  pass). The graph sees EXACTLY the reference classes clojure-lsp/clj-kondo see — across symbol,
  keyword, and macro — and no others. Hunting for a reference class the graph catches and lsp
  misses will run forever and is FORBIDDEN.
- The substrate advantage is entirely Tier-1 and is DEMONSTRATED, not benchmarked: O(1)
  identity re-point; no false-hits in strings/comments by construction; a durable slot for an
  edge text has nowhere to keep. State this as a property of the current implementation, never
  as "graphs can never compute more."
- (ii) building a graph arm to win a keyword-rename gap is DEAD (value-is-spelling: a
  wire-contract keyword is unrenameable for everyone; an internal one namespaces and lsp renames
  it). Do not build it.

## What this loop MEASURES (the only open empirical axis)
Cost curves under real refactors, with full PER-LAYER attribution. That is it.

## The iron rules (violating any one invalidates every number you produce)
1. TERMINATION: a scenario ends when it is CORRECTLY MEASURED — including an honest FALSE or a
   tie. You NEVER run "until the graph wins." There is no target result. If you ever notice
   yourself tuning toward a desired outcome, STOP and log it as a discipline breach.
2. GRADIENT DESCENT RUNS ON ENGINEERING EXECUTION ONLY. You may close MCP / render / daemon /
   tooling gaps so execution latency stops masquerading as substrate cost. You may NEVER tune
   the result. If the thesis is true, closing execution gaps reveals it on its own.
3. SUBSTRATE-VS-EXECUTION ATTRIBUTION: any time text beats the graph on any axis, decompose the
   loss BEFORE drawing any conclusion. Was it the MCP round-trip? Babashka startup? Render?
   Recompile? The daemon wire op? Or a real structural cost in the graph algorithm? Only the
   LAST is a statement about the substrate. A slow MCP path reported as "the graph loses" is a
   lie. Every loss is attributed to a named layer or it is not characterized at all.
4. SYMMETRIC ENGINEERING: the text arm always gets its strongest realistic tooling — the real
   clojure-lsp rename, never a strawman find-replace. The test for any fix: would you apply it to
   the arm you hope loses?
5. PRE-REGISTER BEFORE MEASURING: write your prediction to PREREGISTER.md and commit it BEFORE
   you run the numbers. The arrow points prediction -> measurement, never the reverse.
6. DISTRUST THE CLEAN NUMBER: a result you want to be true is the one to re-check. A
   graph-wins-every-column table is the tell to distrust — the graph PAYS latency/compute; if a
   column doesn't show it losing somewhere, suspect the measurement.
7. CLASSIFY EVERY RESULT: measured-with-config / argued / external-evidence. An honest FALSE is
   a result and gets banked like any other.
8. DISK IS TRUTH: read LEDGER.md and PREREGISTER.md FIRST every cycle; write LEDGER.md LAST.
   "What to do next" is a deterministic function of what is committed, reconstructable after any
   compaction.

## The cycle
read LEDGER.md + PREREGISTER.md  ->  pick next corpus scenario  ->  write prediction to
PREREGISTER.md, commit  ->  run harness.sh  ->  attribute every loss to a layer  ->  write
result + per-layer breakdown + classification to LEDGER.md, commit  ->  next scenario.

## Corpus
CAPPED to refactors already reachable in Beagle as it stands. NO porting step in the loop. A bad
port silently contaminates the cross-arm comparison; a thin corpus is a stateable limitation, a
confound is an invisible lie. If you exhaust Beagle-reachable scenarios, STOP — do not widen the
corpus by porting. Widening is an outer-loop decision reserved for Tom.

## When to halt
Halt when the corpus is exhausted OR the cost curves have flattened to clear diminishing returns.
On halt, write a SUMMARY block at the bottom of LEDGER.md: what was measured, the curve shapes,
every FALSE/tie, the per-layer attribution pattern, and the single question you could not answer.

## What is NOT yours to decide (reserved for Tom, the outer loop)
The strategic call: have we maxed out the technology, or is there a fundamental rethink the loop
can't reach? You MEASURE. You do NOT pivot the strategy, invent a new thesis, or change the
experiment's frame. If you believe a rethink is needed, write it as the open question in the
SUMMARY and halt — do not act on it.

## Hard constraints (never violate)
- NEVER touch port 7977 or ~/.local/state/lodestar/claims.log (the live lodestar coordinator).
  All runs use isolated /tmp logs + non-7977 ports.
- Work on main; commit straight to main. Do NOT push public main (that needs Tom).
- Gjoa owns Beagle. Beagle bugs you hit are FILED in ~/code/agentchat/agentchat.md, NOT fixed.
- chartroom/resolve.clj is the code under experiment — do not treat it as stable.
