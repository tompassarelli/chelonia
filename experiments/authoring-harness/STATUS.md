# Concurrent-authoring experiment — STATUS + decisions needed (2026-06-20)

For Tom + advising agents. Where the RacketCon concurrent-authoring experiment stands, and the calls
that need a human/advisor decision. Receipts referenced here all live in this directory.

## TL;DR
The mechanism is proven, the measurement method is built + validated + honest, and we have one real
measured datapoint (N=1). The **one load-bearing open decision is the Tier-2 target**: honeysql (current
corpus) can show *mechanism-at-scale* and is a *beagle-hardening goldmine*, but it **cannot produce a
measured Tier-2 divergence** — its symbols are plain/static, so the best text tool (clojure-lsp) catches
every reference. A measured Tier-2 number needs a different target. Everything else — the frame, the arms,
agent coordination, the beagle entanglement — is resolved.

## What the experiment measures
Does authoring code as a **claim graph** (identity addressing) beat authoring it as **text**, under
refactoring, measured by **reconstruction cost as a function of N** (references an edit touches). Two
arms, runtime held constant (both execute as Clojure on babashka):
- **arm-G** — Beagle-as-graph, fram rename verb (re-points references by identity).
- **arm-LSP** — raw Clojure + the real `clojure-lsp` rename (strongest realistic text baseline).

Honestly labeled: this moves **two variables at once** (addressing + typing), so it is the **combined
"typed-identity-graph vs dynamic-text" claim** = the industry-relevant "dynamic languages break under AI
refactoring" frame. (We cut a Beagle-text arm — typed `.bclj` has no LSP; we rejected TS/Python — a
foreign language changes the runtime and collapses the control.)

## Done + banked (committed receipts)
- **S2** — write-side proof of mechanism. Graph-canonical edits; both falsifiable checks green (delete the
  `.bclj`, still compiles from the log; graph rename re-points by identity, recompiles, agent-blind oracle
  passes). Land-mine #1 (graph-arm-secretly-a-text-arm) dead by construction. `S2-RESULTS.md` + `reproduce-s2.sh`.
- **S3** — first measured operation on REAL code (hiccup.util). Both Beagle arms green on hiccup's own
  test; rename near-tie at N=1 (the predicted floor); empirical portability map. `S3-RESULTS.md`.
- **Byte-identical projection** — MEASURED (~half a day to finish; deliberately deferred). Shipping claim =
  byte-STABLE + comment-faithful, not byte-identical. `BYTE-IDENTICAL-MEASUREMENT.md`.
- **Curve pre-registration** (anti-Edison: predictions committed before any number) — predicted shape, two
  tiers, full metric vector including axes the graph LOSES. `CURVE-PREREGISTRATION.md`.
- **Curve N=1 result** — floor tie on the structural axes; **graph loses warm-vs-warm latency 3–10×**
  (clojure-lsp ~90 ms native+cached vs graph ~280–926 ms). The honest tradeoff: the graph pays latency to
  buy correctness-by-construction + flatness. `CURVE-RESULTS.md`.
- Infra: Layer-B config anti-rot shipped to nixos-config; the beagle `index-of` bug I hit was fixed +
  adopted by the gjoa agent; **agent coordination established** (`~/code/agentchat/agentchat.md`, clean lanes).

## The two-tier claim (how we stay honest)
- **Tier 1 — structural guarantee (language- + tooling-independent, unimpeachable):** an identity rename
  *cannot* leave a missed/unverified reference and *cannot* false-hit the old name in a string/comment, BY
  CONSTRUCTION. True regardless of language, typing, or what it is compared against. Demonstrated, not benchmarked.
- **Tier 2 — combined empirical advantage vs clojure-lsp:** the typed-graph stack refactors more reliably
  than the dynamic-text stack even on its best tooling; part dynamism, part addressing. Needs a *measured*
  miss or a verification-burden number to count.

## The honest ceiling (why the decision matters)
- The "scattered references" target is **real**, not inflated: `util/str` is referenced by **79 distinct
  caller functions** in honey.sql (verified by measurement before porting; the raw "241" was `.cljc`-doubled).
- BUT honeysql's symbols are **plain and static** → clojure-lsp catches every reference (completeness
  **ties**), and the verification-burden metric is **~0** (clean code symbols have no string/comment
  occurrences to disposition). So **honeysql yields NO measured Tier-2 divergence** — only the Tier-1
  structural guarantee plus (maybe) a latency-shape difference. This is exactly the ceiling we pre-registered.
- honeysql is nonetheless a **beagle-hardening goldmine** — porting it surfaced 2 genuine beagle compiler
  bugs (`index-of` 3-arg [fixed], `emit-clj` multi-arity-anon-fn crash [filed]); both now the gjoa agent's.
- Carve N is small + fuzzy (~7 cross-function refs in the sql-kw carve; shifts as perf-reimpl helpers are
  adapted). The large, clean N lives in the full file (N≈79 callers).

## OPEN DECISIONS — need your / associates' input

**1. (DIRECTION SET by advisor 2026-06-20; precondition gate now RESOLVED — see `TIER2-PRECONDITION-VERDICT.md`) The Tier-2 target.**
**Do BOTH, but FLIP the priority:** (b) — a *measured miss* by clojure-lsp on a dynamic/macro ref — is the
talk's **empirical payload** (the thing that moves a skeptical PL room: watch lsp silently miss a ref the
graph catches). honeysql is **supporting work, NOT the Tier-2 result**: a mechanism-demo + a cost-curve,
and a beagle-hardening goldmine. Sole overclaim guard: never let N≈79 / "mechanism-at-scale" read as the
empirical Tier-2 number.
- **(a) honeysql — REFRAMED as a CURVE, not a count.** Drop "graph O(1) rename across 79 sites" as a
  headline: throughput theater, and it conflates the O(1) *semantic re-point* with *time-to-compiled-
  correct* (which is O(affected modules) for BOTH arms — both re-render+recompile). The real product is
  the SHAPE of both end-to-end curves + the pre-registered **amortization question**: is the N=1 warm-vs-
  warm 3–10× graph penalty FIXED overhead that amortizes toward parity at realistic N, or per-reference
  cost that persists? (Full reframe in `CURVE-PREREGISTRATION.md`.)
- **(b) the measured miss — GATED on a precondition the advisor flagged (verifying in-code NOW).** A
  measured Tier-2 gap needs BOTH: clojure-lsp misses the ref AND Fram's `refers_to` catches it. But
  `refers_to` is a scope-correct **lexical** walk → it may share lsp's blind spots. So (b) is either
  "pick a target" or "**build** expansion/dispatch-aware materialization first, THEN measure" — and which
  one decides the talk claim ("graph catches what text misses" vs "graph CAN model refs text structurally
  can't, once built").
  - **Gating check — RESOLVED (measured in-code, `TIER2-PRECONDITION-VERDICT.md`):** Fram's `refers_to` is a
    PURELY LEXICAL-SURFACE walk (`sees_macros: no`, `sees_keyword_dispatch: no` — resolve.clj:313-344/868).
    For EVERY class lsp misses (multimethod/keyword-dispatch, macro-generated, registry keys, reflective), a
    lexical walk **misses it too**. **No class where lsp misses but Fram catches → no free measured gap → (b)
    is a BUILD, not a pick.**
  - **The reframe (this is the important part): ANALYZER vs SUBSTRATE.** "Compute a dynamic edge from source"
    is an analyzer question — graph == lsp (both lexical). "Once an edge exists, record it as durable identity
    and propagate rename uniformly" is the substrate/addressing question — graph wins by construction (the edge
    is just another claim; text has no slot). The cleanest Tier-2 demo ISOLATES the substrate: reify ONE
    dynamic edge, rename, show propagation lsp can't.
  - **Named Tier-2 milestone (CORRECTED, advisor #2):** reify ONE edge on a **RUNTIME-COMPUTED dispatch**
    method (datahike backend-dispatch), build option 1 (hand-assert `bound_to`, ~hours). **(B) runtime-dispatch
    is the anchor** — hook-proof by construction, so it sidesteps the *unmeasured* "does lsp rename through a
    clj-kondo hook" fact instead of asserting against it. **(A)** (uniform substrate vs per-macro hooks) is
    **demoted to Tier-1 ergonomics, NOT a measured Tier-2** — the symmetric-engineering rule (the text baseline
    *includes* the hook) collapses it to the honeysql ergonomics category. A static-keyword defmethod is
    hook-recoverable → do not use it. Full correction in `TIER2-PRECONDITION-VERDICT.md`.

**2. (EMPIRICAL — resolved by running, not a decision) Does clojure-lsp latency grow with N?** Unmeasured.
Pre-registered to measure lsp wall-time at each N. If flat, the latency axis is "graph slower at every N"
(level), not "gap widens" (divergence); only divergence is thesis-relevant. N≈12/79 settles it.

**3. (SCOPE / DEADLINE) How much before Wednesday's proposal?** We already have a defensible, honest
position: mechanism proven (S2), one measured datapoint (N=1) on real code, the honest frame + the latency
tradeoff stated, Tier-1 unimpeachable. Is that enough for the proposal, or push to a measured divergence
(N≈79 and/or a Tier-2 target) first?
- **Advisor (2026-06-20): ENOUGH.** You do NOT need the measured miss for Wednesday. Tier-1 proof +
  the N=1 measured floor + the stated latency tradeoff + a **named** Tier-2 target/plan (the dynamic-ref
  miss) is a complete, honest proposal. The talk is October — plenty of runway to bank the measured miss
  before the stage. The only thing that turns the proposal into an overclaim is letting N≈79 /
  "mechanism-at-scale" masquerade as the empirical Tier-2 result. So: ship the proposal on Tier-1 + N=1 +
  tradeoff + named-Tier-2-plan; bank the miss for the talk.

## What I do once you decide
- **(a):** work around the beagle bug in the carve, run N≈12 then N≈79, report the full vector (incl. lsp
  wall-time + verification-burden), confirm-or-deny the latency divergence + the Tier-2 ceiling — whichever
  way it falls.
- **(b):** help pick a dynamic-ref target, port a slice, measure the Tier-2 completeness gap.
- Either way: beagle stays hands-off (gjoa's lane); bugs I hit get filed in `agentchat.md`.
