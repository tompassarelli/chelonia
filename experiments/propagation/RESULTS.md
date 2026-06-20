# Propagation latency — RESULTS (#44 / PROMPT 2)

Measured against `PREREGISTER.md`. Harness: `experiments/propagation/harness.clj`
(`bb -cp out experiments/propagation/harness.clj`). Both fences held: metric =
commit-to-visible against the maintained store (NOT re-resolve); git baseline =
post-receive **push-hook** fetch (NOT a poll loop).

## N=1, no contention (ms)

| arm | commit (write) | commit→visible (propagation) | total |
|-----|---------------:|-----------------------------:|------:|
| git (shared bare + push-hook) | 13.9 | **47.4** | 61.3 |
| graph (warm daemon, corpus-from-store) | 143.0 | **37.2** | 180.3 |

Both `commit→visible` figures are a **full-state sync**: git fetch+reset brings B's
whole tree; graph `:status` reads the whole warm store. Apples-to-apples.

## Honest read (no overclaim)
- **Propagation is ~comparable at N=1** — graph 37 ms vs git 47 ms (same order; graph
  marginally faster). The pre-registered "graph propagation flat" (P1) is consistent so
  far but **not yet demonstrated** — that needs the N/overlap sweep.
- **Graph LOSES on total at N=1** (180 vs 61 ms). The cause is the **write** side: the
  graph commit (143 ms) eagerly maintains the queryable index (socket round-trip +
  scoped re-resolve over the 78k-claim warm store); git's commit (14 ms) maintains no
  index. This is the **write-cost-for-read-capability** tradeoff, and it is the
  EXECUTION layer (process/round-trip/re-resolve), not the substrate — same attribution
  as the authoring-harness loop. Reported as a column the graph loses, per discipline.

## What this does and does not show
- **Does**: with the two PL-room killers fenced off, the bare propagation latencies are
  the same order of magnitude; the graph's single-write penalty is write-side index
  maintenance, not propagation.
- **Does NOT**: show the thesis win. That is the **concurrency** behavior:
  - write-side no-coordination-barrier (P3) — already banked in #32 (concurrent appends
    both land) and #35 (scoped gate vs merge-queue across K). Not re-measured here.
  - propagation FLAT under overlap while git's climbs with merge cost (P1/P2) — the
    remaining piece: sweep agent count N and overlap rate, measure per-commit
    commit→visible as git accrues fetch+merge(+conflict) and the graph stays eager.

## Next (the headline the concurrency arc still owes)
Sweep N ∈ {1,2,4,8} and overlap ∈ {disjoint, same-file}. Predict (pre-registered):
graph propagation flat in N; git propagation climbs with overlap (non-ff rejects →
fetch+merge, conflicts on same-file). An honest null vs push-hook git is a result.
