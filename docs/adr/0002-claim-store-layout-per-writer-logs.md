# ADR 0002 — Claim-store physical layout is per-writer append-logs + a derived index

**Status:** Accepted — 2026-06-22
**Decides the physical layout that backs the (fixed) semantic model of ADR-0001.**
If you are about to re-open "should the store be one log / a file per claim / sqlite",
read this and the bake-off data first. The semantic model is settled; this picks the
access paths under it.

## Context

The substrate stores **immutable monotonic claims that don't conflict** — the only
forced coordination is identity allocation (`docs/VIEWS_AND_BRANCHES.md`: *conflict is
the shadow of a cardinality axiom*; the only cardinality axiom the substrate **must**
assert is identity). Today's store does not exploit that algebra: it is a single shared
append-log behind a **sole-writer coordinator** that fsyncs **inside** one global lock.
That layout has two structural failures:

- **It anti-scales.** Adding writers *lowers* aggregate throughput — the real `commit!`
  path measured **1139 → 213 w/s from 1 → 32 writers** on this box (reproducing the
  historical 951 → 151 anchor). The global lock convoys; fsync-in-lock makes durability
  and concurrency trade off directly.
- **It cannot federate.** Its `:tx` sequence is single-origin, so two replicas collide
  on merge and cannot be unioned without a full rewrite pass — the substrate's
  CRDT/union story is unreachable from this layout.

A coordinator-free layout is required. We ran a four-candidate bake-off
(`experiments/store-bakeoff/`) to let **scale data, not priors**, pick it. The
**semantic model is identical across all four candidates** — every layout folds the same
claim set to the same state (verified to the claim at every M: state = 10 000 for all
four at both 100k and 1M). This ADR records a pure layout/access-path decision.

The candidates:

| tag | layout | write coordination |
|---|---|---|
| **baseline** | single shared log + sole-writer coordinator (today's `commit!`) | ONE global lock, fsync-in-lock |
| **A / per-file** | one EDN file per claim, atomic temp+rename, shared dir (git loose-objects) | none (distinct filenames) |
| **B / per-log** | each writer owns ONE append log (`O_APPEND`); graph = union of all logs | none (own fd, no lock) |
| **C / sqlite** | embedded KV baseline: one shared WAL-mode sqlite db | sqlite single-writer WAL lock |

## Decision

**B — per-writer append-logs — is the authoritative write/merge substrate.** Each writer
owns one sequential append-only log; global state is the **union/merge of all logs**.
This is sound precisely because claims don't conflict: union is the merge, and the only
thing writers must coordinate is id allocation. Cross-writer ordering is carried by an
**HLC baked into the claim id** (total order respecting causality without trusting wall
clocks; node-id breaks genuine-concurrency ties so the fold is deterministic and
arrival-order-independent). The semantic core is `experiments/claim-per-file/cpf2.clj`
(G-Set + HLC total order + two-pass cardinality-precedence fold).

Paired with B is a **rebuildable derived index** (sqlite/LMDB or a warm in-memory
`{subject → claims}` map) for subject-scoped reads. **The index is a cache, never the
source of truth** — it is always reconstructible by folding the logs. The materialized
index doubles as a **Lisp-image snapshot for ≤-couple-seconds boot**: state is
`fold(claims ≤ T)`, so an image is that fold memoized at HLC `T` and boot is
`load(image) + fold(tail after T)` — I/O-bound, not recompute-bound
(`experiments/snapshot-boot/`).

This is candidate **C-hybrid** in its best form: B for writes + federation (the
primitive's algebra), a derived index projection for the one axis B is weak on.

### Why the alternatives lost

- **A / per-file** is the *safest per write* (atomic temp+rename = no torn read ever) and
  merges correctly, but pays an **inode tax that compounds at scale**: it is one inode per
  claim (literally **1 000 000 files at 1M claims**, measured), the slowest cold-load
  (62.5 s @1M, 1.65× the log), and on real disk it **anti-scales** — its shared directory
  inode is a hidden serialization point even though claims never conflict (peaks 20k w/s
  @8 writers, drops to ~14k @128). A's costs are exactly the costs B avoids. It is git's
  loose-object model and inherits git's need to *pack* — at which point it becomes C-hybrid
  anyway.
- **C / sqlite** has the best *cold indexed read* out of the box (B-tree, 7.9 ms @1M) and
  the fastest *cold full-fold @1M* (31.7 s — native rows, no EDN parse), but it is a
  **single-writer store**: a flat **~3.5k → 11.8k w/s** ceiling that never scales with N
  (WAL single-writer lock), and merge is a full O(#claims) re-insert. It is a fine
  read/index cache *projected from B*, not the authoritative write substrate.
- **baseline** anti-scales (above) and cannot federate at all.

## The data

Source of truth: `experiments/store-bakeoff/RESULTS.md` + raw logs in
`experiments/store-bakeoff/data/`. Runtime is babashka/SCI (matches the spike harness);
absolute numbers are SCI (~6× under the compiled JVM daemon) but the **shapes** — scaling
sign, merge cost, inode tax — are runtime-independent. 24-core / 62 GB box; tmpfs =
`/dev/shm` (RAM), real disk = `/tmp` (btrfs on LUKS SSD). Concurrency axis = real OS
processes (each writer its own runtime + fds), so B's "no shared lock across writers" is
tested honestly.

### Axis 1 — write throughput @ N concurrent writers (writes/sec; higher = better)

**tmpfs, per-writer=4000, fsync off:**

| N writers | baseline (real `commit!`) | A / per-file | B / per-log | C / sqlite |
|---:|---:|---:|---:|---:|
| 1   | 1139 | 17 697 | **26 382** | 3 457 |
| 8   | 736¹ | 82 492 | **159 186** | 8 157 |
| 32  | 344² | 80 773 | **266 283** | 11 027 |
| 128 | 213² | 81 588 | **300 802** | 11 790 |

¹ baseline N=4, ² N=16 / N=32 (the heavy `commit!` path was swept at 1/4/16/32).

**real disk (/tmp btrfs SSD), per-writer=4000:**

| N | B fsync=0 | A fsync=0 | B fsync=1 | A fsync=1 |
|---:|---:|---:|---:|---:|
| 1   | 24 414 | 12 609 | 23 781 | 11 092 |
| 8   | 144 826 | 20 372 | 147 920 | 18 601 |
| 32  | 238 006 | 15 699 | 224 639 | 15 921 |
| 128 | **281 422** | 14 038 | **235 011** | 14 038 |

**B is the only candidate that turns more writers into more throughput** — it scales the
same on disk as in RAM (24k → 281k), and **fsync barely dents it** (235k vs 281k @128:
one fsync per writer per flush batch, outside any shared lock). The baseline anti-scales
(global lock). sqlite plateaus (WAL single-writer). A plateaus in RAM and anti-scales on
disk (shared-dir inode contention — A's "no coordination" is illusory at the fs layer).
The durability asymmetry that decides it: the authoritative baseline fsyncs **inside** the
lock, so a simplified append-floor collapses **48 291 → 1 774 w/s** (27×) just turning on
fsync-per-write at N=1; B pays fsync per-writer, outside any shared lock, so it gets
durability essentially for free.

### Axis 3 — federation merge: union two divergent replicas of M each (ms; lower = better)

| M | A / per-file (file union) | B / per-log (concat logs) | C / sqlite (INSERT-OR-IGNORE) | baseline |
|---:|---:|---:|---:|---:|
| 100k | 2 910 | **4.2** | 4 159 | N/A — single-origin tx-seq collides |
| 1M | 24 697 | **39** | 37 457 | N/A |

**B wins by ~700× (100k) and ~635× (1M), and the gap is structural.** B's merge is
**O(#writers)** — a replica is 8 log files; merge is copying them in (**39 ms to union two
1M-claim replicas**), independent of M. A and sqlite are **O(#claims)** and scale linearly
with the store. The baseline cannot federate at all. This is the CRDT-union payoff the
whole exercise is about, realized at a cost that doesn't grow with the data.

### Axes 2, 4, 5 — load, scoped read, footprint

- **Cold full-fold (Axis 2):** B fastest @100k (2393 ms); at 1M the EDN parse dominates all
  text-log candidates equally (~37 s, layout-independent) so sqlite's native-row read pulls
  ahead (31.7 s vs B 37.8 s). A is the consistent loser (62.5 s @1M, the inode tax). In
  steady state the daemon folds incrementally and never cold-loads from scratch, so this
  axis matters less than it looks.
- **Subject-scoped read (Axis 4) — B's one genuinely weak axis:** a cold scoped read over
  raw logs is a full scan (3968 ms @1M). But the gap to an index is **5–6 orders of
  magnitude** and the fix is cheap — a warm `{subject → claims}` index is 0.008 ms (the
  daemon already folds the whole graph, so `group-by :l` is free), or project into sqlite
  (7.9 ms B-tree @1M cold). This is the **only** reason to keep a C-component, and exactly
  why the decision pairs B with a derived index.
- **Footprint (Axis 5):** B = **#writers files** (8, flat in M); A = **#claims files** (1M
  inodes @1M, measured). Bytes are within 8% across all four — the differentiator is inode
  count.

## Consequences

- **Subsumes the write-lock-striping backlog.** Per-writer (and per-file) logs *are*
  striping's limit case — one stripe per writer, zero shared lock. The striping work is
  retired by this decision, not deferred.
- **Migration is dual-write behind the warm store → promote.** Land B alongside the
  existing coordinator (dual-write), validate fold-equivalence against the live log, then
  promote B to authoritative and demote the single-log coordinator to one-writer-among-many
  / interop. No flag-day.
- **Enables image-boot + time-travel/fork.** The derived index doubles as a snapshot image
  (`load(image) + fold(tail)`); the G-Set + HLC fold makes `fold(claims ≤ T)` a first-class
  affordance (time-travel, branch/fork as view selection).
- **B's two honest weak edges, both with known fixes:** (1) **trailing-line torn write** on
  power-loss mid-append — cheaply closed by length-prefixed records or fsync-on-flush, and
  the fold already tolerates a dropped trailing partial; (2) **writer-count, not
  claim-count, governs fan-out** — at *thousands* of ephemeral writers B's "8 files" trends
  toward A's many-files; mitigation is segment compaction (the git-packfile move). At
  realistic writer counts (agents + apps, tens to low hundreds) this is a non-issue.
- **The one open research edge is cross-writer clock trust** — HLC is the model, with
  HLC-in-id as the implemented start; a Byzantine/untrusted-writer regime would need more.

## Reproducibility

Worktree: `/home/tom/code/fram-lease`, branch **`coord-lease`**, commit **`1e9c336`** (the
bake-off commit; all bench code + raw logs are in that tree). Runs touch **scratch only**
(`/dev/shm`, `/tmp`) — never `~/.local/state/lodestar/claims.log`, the canonical log, or
the live `:7977/:7978` daemons.

```sh
# from the worktree root
export BAKEOFF_PER_WRITER=4000

# Axis 1 — write throughput @ N (the bench sweeps N=1,8,32,128 internally), tmpfs:
#   usage: bb write_bench.clj <candidate> <store-root> [iters]
bb experiments/store-bakeoff/write_bench.clj per-log  /dev/shm/bk 3
bb experiments/store-bakeoff/write_bench.clj per-file /dev/shm/bk 3
bb experiments/store-bakeoff/write_bench.clj sqlite   /dev/shm/bk 3
bb experiments/store-bakeoff/baseline_bench.clj       /dev/shm/bk-base 3   # simplified floor
bb -cp out /tmp/coord_load_anchor.clj                                      # REAL commit! anchor

# Axis 1 on real disk (inode/fsync) + fsync=0/1 variants (writes data/write-disk.txt):
bash experiments/store-bakeoff/run_disk.sh

# Axes 2–5 — load / merge / scoped-read / footprint at M:
#   usage: bb load_bench.clj <M> <store-root>
bb experiments/store-bakeoff/load_bench.clj 100000  /dev/shm/bk-load
bb experiments/store-bakeoff/load_bench.clj 1000000 /dev/shm/bk-load
```

Raw run logs land in `experiments/store-bakeoff/data/` (`write-tmpfs.txt`,
`write-disk.txt`, `load-100k-tmpfs.txt`, `load-1m-tmpfs.txt`).

**Honest caveats (from RESULTS.md):**

- **SCI vs JVM ≈ 6×.** All numbers are babashka/SCI; the compiled JVM daemon is ~6× faster
  in absolute w/s. The *shapes* (scaling sign, O(#writers) merge, inode tax) do not change.
- **The fsync=1 baseline disk run is truncated** at N≥32 (fsync-per-write under a global
  lock serializes fsyncs to ~500 w/s and falling — a non-authoritative simplified floor).
  The durability collapse is already shown at N=1 (48 291 → 1 774, 27×). The **authoritative**
  baseline is the real `commit!` anchor (`coord_load.clj`), 1139 → 213.
- **The authoritative baseline was swept at N=1/4/16/32**, not 1/8/32/128 — the heavy
  `commit!` path (name-resolve + OCC + delta + fsync-in-lock) is slow, so it uses a lighter
  per-writer count; hence the ¹/² footnotes on the Axis 1 tmpfs table.
- **tmpfs vs real disk.** tmpfs isolates layout cost from device I/O (warm cache); the
  inode/fsync points (A's anti-scaling, B's fsync-near-free) only show on real disk —
  which is why both surfaces are reported.
