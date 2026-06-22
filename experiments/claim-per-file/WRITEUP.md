# Claim-per-file store — architecture spike

**Status:** working prototype + measured. Decides a foundational store direction.
**Built by:** @fram-engine (2026-06-22), on Tom's reasoned-through direction.
**Code:** `cpf.clj` (store), `demo.clj` (4 correctness behaviors, all green), `bench.clj` (perf).

## The proposal in one paragraph

Replace the append-only `claims.log` with **per-file claims**: every claim is its own
small EDN file named by a **UUIDv7** (a 48-bit ms timestamp in the high bits, so the
hex filename sorts chronologically). There is **no log and no central tx counter.**
*Order* comes from the time-sortable ids; *causality* is edges inside the claim
(`:supersedes` / `:retracts` / `:depends_on`); *atomic multi-claim transactions* come
from a **commit-claim** that lists its members — members go live only when the commit
file lands (the git model: an object is dangling until a commit points at it). To read
the graph you list the directory, gate out uncommitted members, sort by id, and fold.

This is git's object store applied to claims: loose objects (claims), commits, and a
content/time address instead of a position in a log.

## What was built and verified

`demo.clj` exercises the four load-bearing behaviors — **all assertions pass**:

1. **Write + fold.** UUIDv7 ordering gives last-write-wins for single-valued preds
   (cardinality graph-sourced via `(P "cardinality" "single")`, mirroring `kernel/single-in?`).
2. **Supersede / retract** via causal edges — an explicit `:supersedes [id]` kills its
   target regardless of fold order (order-independent kill-set, then fold).
3. **Atomic tx via commit-claim** — two members staged `:pending` are *invisible*
   (`@order` reads `nil`) until a commit-claim listing both lands, at which point they
   appear together. All-or-nothing, no log-level BEGIN/COMMIT, no lock.
4. **Federation** — alice writes `owner=alice` in store A (offline), bob writes
   `owner=bob` later in store B (offline); merge = pure file union into a third dir;
   the merged graph deterministically resolves to `bob` by UUIDv7 time-order. No
   coordinator ran.

Writes are torn-read-proof: each claim is written to a temp file then **atomically
renamed** (POSIX rename within a dir is atomic), unlike the live log which is appended
*without* per-line fsync and can tear a line (`kernel.bclj:453`).

## Performance vs the log (measured, `bench.clj`, no per-line fsync either side)

```
N=1000    WRITE  log=  21ms  file-atomic=  75ms (3.6x)  file-direct=  34ms (1.6x)
          LOAD   log=  15ms  file=  30ms (1.9x)         [state log=900  file=900]
N=5000    WRITE  log=  71ms  file-atomic= 294ms (4.2x)  file-direct= 186ms (2.6x)
          LOAD   log=  82ms  file= 152ms (1.9x)         [state log=2236 file=2236]
N=10000   WRITE  log= 133ms  file-atomic= 602ms (4.5x)  file-direct= 353ms (2.7x)
          LOAD   log= 160ms  file= 314ms (2.0x)         [state log=2476 file=2476]
```

- **Write: 3.6–4.5x slower (atomic), 1.6–2.7x (direct).** The append-log wins per-claim
  decisively — one fd, sequential, no directory-inode churn. The atomic rename roughly
  doubles the per-file cost (temp-write + rename = 2 syscalls + 2 dir updates vs one
  inode create). Absolute: ~60µs/claim atomic vs ~21µs/claim log — both sub-millisecond.
- **Load: ~2x slower**, flat in the multiplier (listdir + N file opens vs one sequential
  read). This is the real scaling worry, not write.
- **State counts are identical** (900/900, 2236/2236, 2476/2476): the two substrates
  fold to the *same graph*. The model is a behavior-preserving swap of the durability
  format, not a semantic change.

**The write comparison is a trap if read as "log is faster."** The log is faster
*per claim* but it **serializes every writer** — the `:tx` counter is a contended
resource, which is exactly why fram needs a sole-writer coordinator per log. The
per-file model has **no contended counter**: N agents mint N collision-free UUIDv7
ids and write N files with zero coordination. For a swarm, aggregate write throughput
can *favor* per-file even though each write is individually slower. That tradeoff —
single-node per-claim speed for coordinator-free concurrency — is the whole point.

## Federation-fit (the real payoff)

**Merge = set union of files.** Two agents (or machines) that never coordinated produce
two directories; `cp`/`rsync`/`cat` them together and the merged store is correct. The
fold is a deterministic function of the file set, so:

- **Idempotent** — re-merging the same file is a no-op (same id ⇒ same filename).
- **Commutative & associative** — union doesn't care about arrival order.
- **Convergent** — every replica that has seen the same claim set computes the same graph.

That is a **CRDT** in the precise sense: the store is a grow-only set (G-Set) of immutable
claim-objects, and the graph is a deterministic fold over a total order (UUIDv7) with
causal edges. Convergence is free. This *generalizes* fram's existing top-level
insert-commute result (`[[crdt-ordering-scope-top-level-only]]`) from "top-level form
insertion commutes" to "**the whole store is a CRDT**."

None of this is possible with an append-log: two logs have colliding `:tx` numbers and
no way to interleave by time without a rewrite pass. The log is inherently single-origin.

## The transaction mechanism (commit-claim)

A commit-claim is itself a claim-file listing member ids; members are written `:pending`
and stay invisible until some commit lists them. The commit's own atomic rename is the
**single linearization point** — all members appear at once.

- **All-or-nothing on crash:** a writer that dies before the commit lands leaves staged
  members as inert, GC-able garbage — never partially visible (git's dangling objects).
- **Merge-safe in both partial-arrival directions:** if members arrive but the commit
  doesn't → they stay `:pending` → nothing shows (safe). If the commit arrives but
  members don't → it references absent ids → nothing shows (safe). So transactions
  compose with federation with no extra protocol.

## Addressing: UUIDv7 vs content-addressed

- **UUIDv7 filename** — filename *is* the position ⇒ ordering and incremental tail
  ("claims with id > last-seen") are free filename comparisons. No dedup, no tamper-evidence.
- **Content-addressed (sha256)** — dedup + integrity + the claim names itself (pure git);
  but the filename encodes *nothing about time*, so you must carry a clock inside the
  claim and lose tail-by-filename.
- **Recommendation: name by UUIDv7, carry a content-hash field** (integrity/dedup-check
  without losing ordering). `cpf.clj` provides both (`uuidv7`, `content-hash`).

## What breaks / what it costs (honest)

1. **Load is O(N) syscalls.** 78k claims = 78k file opens on cold start. **Fix = packing**
   (git's loose-objects + packfiles): coalesce cold claims into one read-optimized segment,
   keep only recent loose claims per-file. Known engineering, not research.
2. **Inode / filesystem pressure.** Millions of tiny files exhaust inodes, slow `ls`, and
   blow up rsync/backup (78k files ≫ one log to sync). Packing fixes this too.
3. **GC of dead claims.** The store is grow-only; superseded/retracted claims and
   abandoned `:pending` members accumulate forever until a reachability-from-live-state
   compaction runs (git gc). Disk grows unbounded without it.
4. **Cardinality is itself a claim** — so a *late-arriving* `(P "cardinality" "single")`
   from a merge can retroactively re-collapse a pred's history from multi to single. The
   meaning of past claims depends on a claim that may arrive later. Needs schema-claims
   treated specially (pin early, or special merge precedence).
5. **Cross-writer ordering trusts the wall clock.** UUIDv7's "order from time" is only as
   good as each writer's clock; skew misorders last-write-wins across federated writers.
   Within one writer the intra-ms counter saves you. **This is the one genuinely-hard
   open problem.** The principled fix is a **Hybrid Logical Clock** in the id (still
   time-sortable, but monotonic across the federation given message exchange) instead of
   raw wall-ms. Worth flagging as needing more than UUIDv7.
6. **Subject-scoped reads aren't free** — the filename encodes *time*, not subject, so
   "all claims about `@x`" is a full fold (O(N)) unless a warm store or a sidecar index
   exists. Content-addressing makes this worse (the name encodes nothing queryable). fram
   already has a warm derived store, so this is a cold-path-only cost.

**Note** what is *not* broken: "what changed since X" — the log's `:tx` cursor is replaced
by a UUIDv7 filename comparison, which is just as cheap (sort + binary-search the prefix),
and `load-store` already sorts by id so tail-by-id drops out for free.

## Verdict / recommendation

The claim-per-file model is the **right substrate for the federation + multi-agent
concurrency thesis**: it dissolves the sole-writer coordinator — currently fram's central
write bottleneck and the reason logs must be partitioned per-coordinator — and it makes the
whole store a free-converging CRDT with offline-first merge. Its costs (load-at-scale,
inode pressure, GC) are **exactly the costs git already solved** with loose-objects +
packfiles + gc, so the path is known engineering, not open research. The single genuinely
hard problem is cross-writer ordering correctness (clock trust), where UUIDv7 is a good
start and an HLC is the principled endpoint.

**Recommended shape:** a *hybrid* — per-file loose claims as the durable, syncable,
federation format; periodic packing for cold claims; and fram's existing warm in-memory
graph as the hot read path (so per-file layout never sits on the interactive read path).
Keep the append-log only as a single-node fast-write option, or retire it once packing
lands. This is "loose objects + pack," not naive file-per-claim-forever — and it buys the
coordinator-free, offline-first, mergeable store the federation roadmap needs.

## Reproduce

```sh
cd experiments/claim-per-file
bb demo.clj     # 4 correctness behaviors, all assertions pass
bb bench.clj    # write+load perf table above
```
