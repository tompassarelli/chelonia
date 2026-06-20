# S3 — first measured operation on real code (2026-06-20)

**Milestone (a checkpoint, NOT the experiment):** the S2 backbone scaled to a real OSS slice.
A real(-adapted) `hiccup.util` slice runs end-to-end through **both Beagle arms** (text + graph),
with **hiccup's own test as the agent-blind behavioral oracle**, and a relationship-touching
rename measured per arm. Both arms green. Result is a **near-tie — the predicted outcome at the
floor of the ladder**, not a win.

## The slice (logic faithful, host-type-machinery adapted)
Real hiccup `escape-html` (verbatim char-replacement + the `:sgml` branch) calling `to-str` on its
arg (real call structure), plus the `*html-mode*` dynamic var. Adaptation: hiccup's `to-str` is a
protocol; here it's a String-typed helper (`to-str` of a string IS the string → behavior-preserving
for `test-escaped-chars`, which only passes strings). **Both Beagle arms start from the IDENTICAL
ported `util.bclj`**, so the adaptation is a shared baseline and CANCELS in the Beagle-text vs
Beagle-graph (crown-jewel) comparison. Adaptation is only an asymmetry for the Clojure-text arm —
flagged there, never in the crown jewel. Oracle = hiccup's `test-escaped-chars`, verbatim (7 asserts
incl. the dynamic `binding [*html-mode* :sgml]` case).

## Portability (empirically mapped; corrected 3 premature wrong reads via `beagle check` probes)
Real hiccup ports to Beagle with **idiomatic adjustments, not a wall**: JVM interop (`..`/`.replace`)
✅, dynamic vars + `binding` ✅ (earmuff convention emits a genuine `:dynamic` var — verified bb is
NOT lenient), `defprotocol`/`defmacro` ✅ (syntax differs). The one hard rewrite is **`deftype`
(removed → `defrecord` + `extend-type`)**. Corpus filter gains one axis beyond "pure (no I/O)":
watch `deftype`/host-class `extend-protocol`, NOT interop or dynamic vars.

## The first measured operation — rename `to-str` → `stringify`
`to-str` is a REAL internal referent (hiccup's `escape-html` genuinely calls it) and is NOT
referenced by the oracle (so the oracle stays invariant — the harness's identical-oracle rule).

| arm | reconstruction cost | correctness |
|---|---|---|
| **Beagle-text** | **2 edit sites** (the `defn` + 1 call site); + 4 comment-prose mentions a naive find-replace would corrupt (avoided only by targeted patterns) | recompiles, oracle **7/7** |
| **Beagle-graph** | **1 rename verb → 2 claim ops** (1 assert + 1 retract on the binding's name node); the 1 reference **re-points by identity for free**; comment prose untouched **by construction** | recompiles (`beagle-build-all`), oracle **7/7** |

**Near-tie at N=1 reference — prediction confirmed.** The thesis says separation tracks relational
complexity; at one local reference there is almost nothing to separate (graph: 1 verb; text: 2
sites). The graph's advantage here is **qualitative** (free re-point + comment-safety by
construction; text correctness is care-dependent, scaling with spelling-collisions). It becomes
**quantitative up-ladder**, where renames scatter across many coupled references.

**Classification:** measured-with-config (bb runtime; hand-driven, NOT yet real agents). Honest
non-headline: this is a green pipeline + a floor-of-ladder tie, not a substrate win. The separating
number lives at the ~5k rung (honeysql coupled-refs / sci scattered renames), not here.

## Captured, NOT measured — the boundary B-insight (corrected), a talk-slide asset
A rename of a *public* symbol the oracle pins by name would break the oracle — but that is a
**methodological artifact of holding the oracle outside the graph**, not a fundamental limit. In a
real authored codebase the tests are code too and would be ingested → they'd re-point for free. The
genuine boundary is **published external consumers** (other repos, API users) — the **same boundary
every refactoring tool has: its index scope.** Within that boundary the graph's honest, narrower,
stronger claim: it re-points **O(1)-correct, including across the impl-file/test-file split** that
text must find-replace across two files (and can corrupt — the S2 comment case). That is the
honest-frontier slide; it stays OUT of the measured run.

## Next (do NOT start until this is confirmed green)
The ~5k rung (honeysql): a coupled-ref / multi-site rename — the first operation where N > 1, hence
the first number that actually *separates* the arms. S3's job was a green pipeline on real code; done.
