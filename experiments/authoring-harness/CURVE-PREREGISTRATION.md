# Reconstruction-cost curve — PRE-REGISTRATION (2026-06-20, before high-N measurement)

Written BEFORE measuring at N≫1 (anti-Edison: the arrow points prediction→measurement). The
thing chased is a **shape**, on a defensible axis — not a magnitude, and not the column the graph
happens to win. Revised before step 1 to widen the metric vector and name a confound (history in git).

## Arms — nothing invented
- **arm-G (graph):** Beagle-as-graph, fram rename verb. Re-points by identity.
- **arm-LSP (Clojure-text, the STRONG text baseline):** raw Clojure + the **real `clojure-lsp`
  rename** (exists, runs on real Clojure). **The graph-vs-text SUBSTRATE claim rides on
  arm-G vs arm-LSP** — strongest text arm, measured not modeled.
- **arm-FR (Beagle-text, find-replace):** `.bclj` + find-replace. Kept ONLY as an honestly-labeled
  finding about *current typed-source tooling reality* — **NOT** the comparison that proves the
  thesis. **Do not present "graph beats find-replace" as the substrate result.**
- **DO NOT build or estimate `beagle-lsp`.** Building it measures *my LSP-building effort*, not the
  substrate; estimating its performance is modeling the number I want. The graph's actual point is
  that it gives correct rename *without that tooling needing to exist*. Neither build nor estimate.

## CONFOUND on the crown jewel — named, not neutral
The crown jewel (arm-FR Beagle-text vs arm-G Beagle-graph) was meant to isolate SUBSTRATE at the
same language. But Beagle-text has **no LSP** — `clojure-lsp`/`clj-kondo` parse-fail on typed `.bclj`
(`(def x :- T v)` is a 4-arg def; `rename-identity/RESULTS.md`). So part of any arm-FR-vs-arm-G gap
is a **tooling-maturity gap, not substrate**. This is a CONFOUND on that comparison.
→ The claim the measurement supports is: **"the graph delivers correct rename without any tooling
needing to exist; the typed-text surface would need an LSP built to compete, and it doesn't exist
yet."** A real point FOR the graph — but NOT "same language, substrate alone separates them." Report
the supported claim, never the cleaner one.

## The headline axis — language-independent, zero-estimation (be precise about which arm-pair)
The two axes separate DIFFERENT pairs — don't conflate them:
- **vs arm-LSP (the substrate claim): COMPLETENESS-BY-CONSTRUCTION + zero-verification.** clojure-lsp's
  rename is the output of a STATIC ANALYSIS that can miss references (macro-generated, dynamic/`resolve`,
  reflective) — so you must **verify** it caught all N, and it can be silently incomplete. The graph's
  references are **explicit identity edges**: complete by construction, nothing to miss, **0 verification.**
  This is the durable, language-independent graph-vs-best-tooling win.
- **vs arm-FR (the tooling-gap finding, NOT the substrate claim): COLLISION-SAFETY.** find-replace
  corrupts the old name in strings/comments; the graph is immune. **clojure-lsp is ALSO collision-safe**
  (it's semantic) — so collision does NOT separate graph from arm-LSP. Report collision as the
  find-replace-vs-graph gap (= the typed-source-has-no-LSP finding), never as the substrate result.

Predicted shape on a rename-of-referenced-def as N grows (1 → ~12 → ~79 callers):
- **arm-G:** verification ~0, completeness total, collision 0 — **flat by construction.**
- **arm-LSP:** ≈ 1 command + **verification grows with N** (confirm completeness across N sites; risk it
  missed a dynamic/macro ref grows with codebase complexity). Collision-safe.
- **arm-FR:** N edits + **collision count grows with N** (the tooling-gap finding).

## Full metric vector — predicted on EVERY axis, including where the graph LOSES
A graph-wins-every-column table is the tell to distrust. Predicted, before measuring:

| axis | arm-G (graph) | arm-LSP (clojure-lsp) | arm-FR (find-replace) | who, and is it clean? |
|---|---|---|---|---|
| **completeness + verification** | total + ~0, flat | static-analysis output: verify N; can miss dynamic/macro refs | n/a | **graph wins vs arm-LSP — language-independent, the substrate claim** |
| **collision-safety** (old-name in str/comment) | immune by construction | also safe (semantic) | corrupts; risk grows with N | **graph & lsp both win vs arm-FR — the tooling-gap finding, NOT the substrate claim** |
| **machine wall-time** (raw s) | **slower**, ~fixed overhead (daemon + render + recompile) | fast (in-memory) at low N | fast | **graph LOSES at low N**; lsp/graph wall-time carries a *language* difference (lsp→Clojure, graph→Beagle) — NOT pure substrate; cross-point, if any, reported honestly |
| **compute** | **higher** (render-recompile) | lower (in-memory) | lower | **graph LOSES**; also language-confounded |
| **flatness shape** | ~constant in N | verification grows with N | edits+collision grow with N | graph wins on completeness/verification (the thesis) |

**Honest thesis:** the graph **pays raw compute/latency to BUY correctness-by-construction +
flatness on verification/collision.** That tradeoff — lose latency, win correctness+flatness — is
stronger and more honest than winning every column. **Caveat stated, not papered:** wall-time/compute
compare clojure-lsp-on-Clojure vs graph-on-Beagle, so they carry a language difference, not pure
substrate; only verification/collision is language-clean.

## Falsifier (pre-committed)
If at N≈79 the **graph** verification/collision cost is NOT flat (the verb scales with N, re-pointing
isn't free, recompile/oracle fails), the constant-cost claim is **not supported at this scale** —
reported straight. A broken curve at high N beats a clean win: it's a finding, not a confirmation.

## N points
- **N=1** — S3 (greet, one reference). Tie at the floor, as predicted.
- **N≈12** — honeysql `sql-kw` carve (`util/str`, 4 distinct callers).
- **N≈79 callers / ~200 :clj sites** — full `honey.sql` (`util/str`). Measured genuinely-scattered
  (79 distinct caller functions), NOT self-ref-inflated. (Raw "241" was `.cljc`-doubled tokens;
  distinct-callers = 79 is doubling-robust.)

## Honest labels
Mechanism-at-scale, **self-sourced** N (honeysql has no issue-sourced multi-site rename). The claim
earned is the **curve's shape on the language-clean axis, on real code, under symmetric good
engineering** — not a cherry-picked magnitude. Issue-sourced refactoring is a separate, later arm.
