# Reconstruction-cost curve — PRE-REGISTRATION (2026-06-20, before high-N measurement)

Written BEFORE measuring at N≫1 (anti-Edison: prediction→measurement). Chasing a SHAPE on a
defensible axis — not a magnitude, not the column the graph happens to win. Final frame; revisions in git.

## Arms — two; nothing invented, nothing built
- **arm-G (graph):** Beagle-as-graph, fram rename verb. Re-points by identity.
- **arm-LSP (incumbent):** raw Clojure + the **real `clojure-lsp` rename** — the best realistic
  dynamic-text tooling, **measured, not modeled.**
- **Held constant:** runtime (both execute as Clojure on bb), the logical program, the agent-blind oracle.
- **CUT — Beagle-text / find-replace.** Typed `.bclj` has no LSP (clojure-lsp/clj-kondo parse-fail) and
  we are NOT building one. Find-replace was only ever a tooling-reality sidecar — cutting it **deletes**
  that confound instead of annotating it, and it's truer to the thesis (the graph IS the representation;
  text is a projection). **Do NOT build or estimate `beagle-lsp`** — building measures my LSP effort,
  estimating models the number I want; the graph's point is correct rename *without that tooling existing*.
- **FUTURE, not core:** Clojure-graph vs Clojure-text — ingest plain Clojure as *untyped*-Beagle into the
  graph (untyped Beagle compiles) → same language, same dynamism, representation ALONE differs = the
  **pure-addressing isolation.** A separate exploratory arm, parked.

## What the two arms actually measure — TWO variables, named honestly
Beagle-graph vs Clojure-text moves **both addressing** (identity-graph vs positional-text) **and typing**
(typed vs dynamic); only runtime/program/oracle are held constant. So this is **NOT "representation
alone."** It is the **COMBINED typed-identity-graph stack vs the dynamic-text stack.** That combined
claim IS the AI-era "dynamic languages break under agent refactoring" frame, told cleanly with one
porting effort — which is exactly why there is **no third (TypeScript/Python) arm**: a foreign language
changes the runtime too and lets a skeptic dismiss the result as "unrelated languages benchmark
differently," collapsing the controlled comparison. The frame we want is already what two arms measure.

## TWO headline tiers — kept separate so Tier 1 stays unimpeachable
**Tier 1 — irreducible structural core (language- AND tooling-independent).** A rename that operates on
IDENTITY (a) **cannot false-hit** the old name in a string/comment, and (b) **cannot leave an unverified
reference** — both **by construction**, regardless of language, typing, or what it's compared against. A
structural property of the graph, stated on its own terms; nothing blends in. Demonstrated, not
benchmarked: the graph rename recompiles + passes the agent-blind oracle with **zero per-site
verification at every N.** State it flatly.

**Tier 2 — the combined empirical claim.** The typed-identity-graph stack refactors more reliably than
the dynamic-text stack **even on its best tooling (clojure-lsp).** Part of clojure-lsp's incompleteness
is **dynamism** (it can miss macro/dynamic/reflective refs *because Clojure is dynamic*); part is
**addressing**. Real and industry-relevant, but **explicitly COMBINED** — not pure addressing. This is
the head-to-head measured across the full vector below.

Keep them separate: Tier 1 is bulletproof; Tier 2 carries the broader story honestly.

## Full metric vector — predicted on EVERY axis, including where the graph LOSES
A graph-wins-every-column table is the tell to distrust. Predicted before measuring:

| axis | arm-G (graph) | arm-LSP (clojure-lsp) | tier / verdict |
|---|---|---|---|
| **collision / false-hit** (old name in str/comment) | immune by construction | also safe (semantic) | **TIE vs lsp** — a Tier-1 *guarantee* for the graph, but not a discriminator vs a semantic renamer (it discriminated vs the cut find-replace arm) |
| **unverified / missed refs** | none by construction | static analysis can miss dynamic/macro/reflective refs → must verify | **graph wins — Tier 2** (blends addressing + dynamism) |
| **flatness shape** (cost vs N) | ~constant | verification/completeness-risk grows with N | graph wins — the thesis shape |
| **machine wall-time** (raw s) | **slower**, ~fixed overhead (daemon + render + recompile) | fast (in-memory) | **graph LOSES at low N**; language-confounded (lsp→Clojure, graph→Beagle); cross-point if any reported honestly |
| **compute** | **higher** (render-recompile) | lower (in-memory) | **graph LOSES**; language-confounded |

**Honest thesis:** the graph **pays raw compute/latency to BUY a by-construction correctness guarantee
(Tier 1) + flatness, and an empirically more-reliable refactor than the best dynamic-text tooling
(Tier 2).** Lose latency, win correctness + flatness. Caveat, stated not papered: wall-time/compute and
part of the completeness gap carry a language/typing difference — **only Tier 1 is fully clean.**

## Falsifier (pre-committed)
If at N≈79 the graph is NOT flat (the verb scales with N, re-pointing isn't free, or recompile/oracle
fails), the constant-cost claim is **not supported at this scale** — reported straight. A broken curve
at high N beats a clean win.

## N points
- **N=1** — S3 (greet, one reference). Tie at the floor, as predicted.
- **N≈12** — honeysql `sql-kw` carve (`util/str`, 4 distinct callers).
- **N≈79 callers / ~200 :clj sites** — full `honey.sql` (`util/str`). Measured genuinely-scattered (79
  distinct callers), NOT self-ref-inflated. (Raw "241" was `.cljc`-doubled tokens; 79 callers is robust.)

## Honest labels
Mechanism-at-scale, **self-sourced** N (honeysql has no issue-sourced multi-site rename). Tier 1 is
unimpeachable + language-clean; Tier 2 is the typed-graph-vs-dynamic-text combined story. Not a
cherry-picked magnitude. Issue-sourced refactoring + pure-addressing (Clojure-graph) are later arms.
