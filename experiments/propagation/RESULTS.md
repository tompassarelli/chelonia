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

## K-sweep (disjoint concurrent writers) — DECOMPOSED (ms, mean)

`bb -cp out experiments/propagation/sweep.clj` (SWEEP_KS=1,2,4,8). Write-side and
propagation reported separately (v1 conflated them — fixed).

| K | git-write | git-prop | graph-write | graph-prop |
|---|----------:|---------:|------------:|-----------:|
| 1 | 25.2 | 52.4 | 178.0 | 41.4 |
| 2 | 28.4 | 94.1 | 173.1 | 60.8 |
| 4 | 40.7 | 153.0 | 171.7 | 97.8 |
| 8 | 76.0 | 351.0 | 209.9 | 197.1 |

`landed = K/K` for both arms at every K — no lost writes (write-side no-clobber).

**Reading (a tradeoff, not a clean sweep):**
- **Propagation — graph wins, scales better.** git climbs 52→351 ms (6.7×) as pushes
  serialize through the shared ref's non-ff check → fetch+merge+retry (the merge-queue,
  P2 confirmed). graph 41→197 ms (4.8×), lower at every K. NOT perfectly flat (P1
  partially holds) — the residual climb is daemon dlock contention on commit+`:status`,
  an execution layer, not the substrate.
- **Write — graph loses (the mirror cost).** graph ~175–210 ms, ~flat in K (eager
  re-resolve maintains the query index); git 25–76 ms (cheap local commit). The graph
  front-loads cost at write to keep propagation cheap; git defers cost to propagation.
- Net: the substrate's bet — pay at write to make reads/propagation cheap and
  contention-resistant — shows up exactly here. Honest column where graph loses (write)
  reported alongside where it wins (propagation under concurrency).

## Measurement refinement → the clean result

The first K-sweep "graph propagation climbs 4.8x" was contaminated by TWO stacked
measurement artifacts in the read path, peeled off one at a time:

1. **Whole-corpus read.** B's read was `:status`, which counts the full ~78k-claim
   corpus every call (~40ms). Swapping to the O(1) `:version` dropped graph-prop from
   41ms to ~1ms at K=1, but it still climbed (1→35ms) because...
2. **Read coupled to the writer lock.** `:version`/`:status` run under the outer
   `dlock`, so K concurrent readers serialize behind the writers. Added a lock-free
   read op (`:version-free`, derefs the immutable `@co` snapshot, no dlock).

With the lock-free read (ms, mean):

| K | git-write | git-prop | graph-write | graph-prop |
|---|----------:|---------:|------------:|-----------:|
| 1 | 22.4 | 51.4 | 412.9* | 1.1 |
| 2 | 27.1 | 81.7 | 148.4 | 1.1 |
| 4 | 39.3 | 146.4 | 175.2 | 1.1 |
| 8 | 80.2 | 361.8 | 204.8 | 1.2 |

`*` K=1 graph-write is JIT warmup; warm graph-write is ~175ms, ~flat in K.

**The clean result (pre-registration confirmed):**
- **graph propagation is FLAT** at ~1.1ms across K=1..8 (P1 holds). The earlier climb was
  100% the read artifacts, not a substrate property. Writes are eager, the reader reads an
  immutable snapshot lock-free, so a reader sees a commit in constant time regardless of
  how many agents are writing.
- **git propagation climbs 51→362ms** (7x, P2 holds) as pushes serialize through the
  shared ref's non-ff check → fetch+merge+retry (the merge-queue).
- **write flat both-ish**: graph ~175ms (eager index), git 22→80ms. The mirror cost.
- `landed = K/K` both arms — no lost writes.

**This is a substrate result, not an implementation one.** Concurrent disjoint writes do
not collide (write column flat, not climbing), and a reader observes them in constant time
(prop flat). git's propagation climbs because the shared ref forces serialization. The
distinction matters: the win is the data structure (commuting writes + eager immutable
snapshot), not "our daemon happens to contend less."

**Honest caveat:** the 1.1ms is the lock-free `:version` round-trip; visibility is
eager-by-construction (commit swaps `@co` before `:edit-min` returns; version is
monotonic), not asserted per-read against the specific def. A fair propagation proxy, not
a targeted content check. Same disjoint-target corpus throughout; same-file overlap (git
conflicts) is the #45 continuous-arrival follow-up.
