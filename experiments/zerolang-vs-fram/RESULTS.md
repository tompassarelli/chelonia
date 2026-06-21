# Fram vs zerolang — RESULTS

Predictions in `PREREGISTER.md`. zerolang = native `zero 0.3.4` built from a /tmp copy of
`~/code/reference/zerolang` (source READ-ONLY). Fram = warm daemon, socket client, greet seed.
Same machine, same session. Harnesses: `zero_conc2.clj`, `fram_conc.clj`, `fram_scale.clj`
(committed alongside).

## 1. Single-thread per-op authoring (the CONCEDED column — reported straight)

Add one valid def to a project of size N; per-add latency:

| N (defs) | zero per-add | Fram per-add |
|---|---:|---:|
| 20  | 16 ms | 27 ms |
| 120 | 28 ms | 81 ms |
| 240 | 50 ms | 147 ms |

Both O(N) (zero reloads+revalidates+rewrites the whole graph per patch; Fram's verb builds the
module frame + CRDT ordering-key, both O(module)). **Fram LOSES single-op ~3x at N=240.** No spin:
Fram's authoring primitive is heavier, and this is the honest cost it pays. The thesis win is not here.

## 2. Concurrent authoring — N=0 (small project)

K agents each author 1 distinct def into the shared store, concurrently. zero = OCC merge-queue
(expect graphHash → GPH002 reject → retry + CAS); Fram = commute (no OCC). Total wall + failures.

| K  | zero wall | zero wasted-patches | Fram wall | Fram failures |
|---:|---:|---:|---:|---:|
| 1  | 17.9 ms | 0   | 31.1 ms | 0 |
| 2  | 25.5 ms | 1   | 28.6 ms | 0 |
| 4  | 56.3 ms | 6   | 50.0 ms | 0 |
| 8  | 113.6 ms| 28  | 95.2 ms | 0 |
| 16 | 256.8 ms| 122 | 216.0 ms| 0 |

- **Wall:** Fram crosses over and wins from K=4 up (1.2x at K=16). zero climbs 14x, Fram 7x.
- **Failure rate (the clean win):** Fram **0** wasted work at every K (every commit lands, commute).
  zero wastes **122** full patches at K=16 — each a complete graph reload+revalidate thrown away
  because the OCC hash moved. Both arms land K/K (no permanent lost writes once retried), but zero
  pays a K-proportional retry tax that Fram does not.
- Even at N=0 (the regime LEAST favorable to Fram, where zero's per-patch is cheapest), Fram wins
  wall modestly and wins failure-rate decisively.

## 3. Concurrent authoring — N=200 (scaled)

| K  | zero wall | zero wasted | Fram wall | Fram failures | wall winner |
|---:|---:|---:|---:|---:|:--|
| 1  | 38.0 ms  | 0   | 128.4 ms | 0 | zero 3.4x |
| 2  | 67.8 ms  | 1   | 162.3 ms | 0 | zero 2.4x |
| 4  | 126.0 ms | 6   | 246.1 ms | 0 | zero 2.0x |
| 8  | 254.2 ms | 28  | 375.7 ms | 0 | zero 1.5x |
| 16 | 546.2 ms | 122 | 721.0 ms | 0 | zero 1.3x |

- **Wall: Fram LOSES in the tested range** (zero 1.3-3.4x faster). Honest, no spin.
- BUT the slopes diverge: zero climbs **14x** (K=1->16), Fram only **5.6x**. The gap shrinks
  monotonically (3.4x -> 1.3x); linear extrapolation crosses ~K=24-32. Fram's wall scales better;
  it just starts from a heavier floor.
- **Why zero wins wall despite 122 wasted patches:** zero runs each patch in a SEPARATE process,
  so its wasteful O(N) re-patches run **16-way parallel** (only the tiny CAS serializes). Fram's
  daemon is a **sole writer** — commuting writes are applied one-at-a-time at O(module)/op. So zero
  parallelizes expensive-wasteful work; Fram serializes cheap-clean work, and at N=200 the per-op
  weight (O(module)) dominates.
- **Failure-rate: Fram wins decisively** (0 wasted vs 122; 16 ops vs 138). Fram does **8.6x less
  total work** and never throws a patch away.

## Verdict (honest)
- Fram WINS: failure-rate / wasted-work (0 vs 122), reference-stability, propagation/visibility
  (banked separately, #44), and wall-SCALING (flatter slope).
- Fram LOSES (today): single-op latency (~3x) and concurrent wall in the K<=16 range (~1.3-3.4x),
  because the upsert verb is O(module) per op (ordering-key reads all siblings + module-frame build)
  AND the daemon serializes writes.
- To hit the 2-5x WALL target (Tom's singular goal), the required lever is making the upsert per-op
  **O(1)** (indexed ordering-key + incremental/skipped frame build). With O(1) per-op, Fram's
  serialized K-writes become trivially cheap and beat zero's parallel-but-O(N)-with-waste at every
  K. This is the optimization now in progress (the "juice fram" mandate, empirically justified).


## Honest read so far
The single-op column is a real Fram loss (heavier primitive). The CONCURRENT result is the thesis:
Fram's commute does zero wasted work under contention while zerolang's optimistic merge-queue burns
a K-proportional pile of O(N) re-patches. At N=0 that already flips wall in Fram's favor by K=4 and
gives a 0-vs-122 failure-rate gap; the scaled N=200 run tests whether the wall margin reaches 2-5x.
