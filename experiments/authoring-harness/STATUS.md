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

**1. (LOAD-BEARING) The Tier-2 target.** honeysql can't show a *measured* Tier-2 divergence. Options:
- **(a) Stay on honeysql** → push to N≈79 for the **mechanism-at-scale** number (graph O(1) rename across
  ~79 caller sites) + keep surfacing beagle bugs. Accept Tier-2 = the Tier-1 structural guarantee only (no
  measured miss).
- **(b) Pivot Tier-2 to a dynamic-ref target** — code with genuine dynamic/macro/reflective references,
  where clojure-lsp *actually misses* → a measured completeness gap. (Needs picking + porting a new slice.)
- **(both)** a for mechanism-at-scale, b for the Tier-2 divergence — strongest, most work.
- *My read:* name honestly that honeysql gives Tier-1 + mechanism-at-scale but not a measured Tier-2; the
  call is whether the talk *needs* a measured Tier-2 number (→ b), or whether Tier-1 (unimpeachable) +
  mechanism-at-scale + the honest latency tradeoff is a strong enough story (→ a).

**2. (EMPIRICAL — resolved by running, not a decision) Does clojure-lsp latency grow with N?** Unmeasured.
Pre-registered to measure lsp wall-time at each N. If flat, the latency axis is "graph slower at every N"
(level), not "gap widens" (divergence); only divergence is thesis-relevant. N≈12/79 settles it.

**3. (SCOPE / DEADLINE) How much before Wednesday's proposal?** We already have a defensible, honest
position: mechanism proven (S2), one measured datapoint (N=1) on real code, the honest frame + the latency
tradeoff stated, Tier-1 unimpeachable. Is that enough for the proposal, or push to a measured divergence
(N≈79 and/or a Tier-2 target) first?

## What I do once you decide
- **(a):** work around the beagle bug in the carve, run N≈12 then N≈79, report the full vector (incl. lsp
  wall-time + verification-burden), confirm-or-deny the latency divergence + the Tier-2 ceiling — whichever
  way it falls.
- **(b):** help pick a dynamic-ref target, port a slice, measure the Tier-2 completeness gap.
- Either way: beagle stays hands-off (gjoa's lane); bugs I hit get filed in `agentchat.md`.
