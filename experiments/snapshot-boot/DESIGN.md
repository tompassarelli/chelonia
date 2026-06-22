# Snapshot-boot — image-boot a claim-backed program in ≤ a couple seconds at scale

**Thesis (one sentence):** a program's state is `fold(claims ≤ T)`, a deterministic
pure function of the effect log — so an *image* is that fold memoized at HLC `T`, and
*boot* is `load(image) + fold(tail after T)`. Snapshot often ⇒ tiny tail ⇒ boot is the
cost of **loading materialized state**, not recomputing it. That makes boot I/O-bound,
and mmap makes it near-instant.

This builds on the bake-off winner: **B / per-writer append-logs** (`store-bakeoff`,
committed `1e9c336`) — the durable source of truth, which scales writes UP and merges in
`O(#writers)`. The semantic core (G-Set + HLC total order + two-pass cardinality fold) is
`claim-per-file/cpf2.clj`. Both are reused read-only.

---

## 1. The architecture in one diagram

```
  SOURCE OF TRUTH (never deleted, append-only, mergeable)
  ┌──────────────────────────────────────────────────────┐
  │  per-writer logs:  w0.log  w1.log  …  wN.log          │   <- candidate B
  │  each = O_APPEND stream of EDN claim records          │
  └──────────────────────────────────────────────────────┘
            │  fold(claims ≤ T)  (pure, deterministic)
            ▼
  ┌──────────────────────────────────────────────────────┐
  │  MATERIALIZED INDEX  =  fold result at HLC T          │   <- derived, rebuildable
  │  {[l p] -> r | #{r}}  +  card axioms  +  frontier T   │      NEVER source of truth
  └──────────────────────────────────────────────────────┘
            │  serialize (mmap-friendly arena)
            ▼
  ┌──────────────────────────────────────────────────────┐
  │  SNAPSHOT / IMAGE  snap-<T>.fbin                       │   <- a file you can mmap
  │  [header][frontier T][interned strings][packed rows]  │
  └──────────────────────────────────────────────────────┘

  BOOT = mmap(latest snap) -> live state, THEN fold only the tail (claims with id > T
         across the per-writer logs) into it. Tail is bounded by the snapshot cadence.
```

The image is **derived, not authoritative**. Lose every snapshot and the system is
unharmed: re-fold the logs. This is the property a Lisp heap-dump *doesn't* have — see §6.

---

## 2. Snapshot format (mmap-friendly, not re-parsed EDN)

The whole point of the speedup is *not paying EDN parse on boot*. So the snapshot is a
flat binary arena laid out for `mmap` + zero-copy page-in. Layout (`snap-<T>.fbin`):

```
  ┌── HEADER (fixed 64 B) ─────────────────────────────────────┐
  │  magic "FBSNAP\0\0" (8)                                     │
  │  version u32                                               │
  │  frontier-len u32 + frontier-HLC-id bytes (var)            │  <- T: the memoized fold point
  │  n-rows u64                                                │
  │  strtab-off u64, strtab-len u64                           │
  │  rows-off  u64, rows-len  u64                             │
  └────────────────────────────────────────────────────────────┘
  ┌── STRING TABLE (interned) ─────────────────────────────────┐
  │  every distinct l / p / r string, length-prefixed, once.   │  <- dedup; rows hold u32 offsets
  └────────────────────────────────────────────────────────────┘
  ┌── ROWS (fixed-width, sorted by [l p]) ─────────────────────┐
  │  each row: l_off u32 | p_off u32 | card u8 | n_vals u32 |  │
  │            val_off u32 [* n_vals]                          │  <- single: n_vals=1; multi: set
  └────────────────────────────────────────────────────────────┘
```

Key properties:
- **mmap-able:** rows are fixed-stride; a reader `mmap`s the file and walks rows without
  allocating or parsing. Strings are paged in lazily on first touch (the OS page cache does
  the work). A boot that only needs a few keys touches only those pages.
- **Interned strings:** the materialized state for a 1M-claim corpus is ~10k keys — the
  arena is *tiny* (≈ state size, not log size). Dedup keeps it tiny.
- **Sorted rows:** binary-search by `[l p]` for a point read without building a hashmap —
  the "time-travel point read" affordance needs no full deserialize.
- **Self-describing frontier:** the header carries `T`, so boot knows exactly which tail to
  fold (everything with `id > T`).

> Prototype note: babashka/SCI has no cheap struct-mmap FFI, so the prototype writes this
> arena as a real file and (a) `mmap`s it via `java.nio.MappedByteBuffer` for the
> point-read / lazy path, and (b) bulk-reads it for the full-hydrate path. EDN is used
> ONLY as a parallel correctness oracle, never on the hot boot path. The arena read is the
> measured number.

---

## 3. Boot path

```
  boot(store-dir):
    snap   = newest snap-<T>.fbin in store-dir/snapshots/   (by T, lexical == HLC order)
    state  = hydrate(snap)            # mmap + walk rows  -> {[l p] -> r|#{r}} + card axioms
    T      = snap.frontier
    tail   = claims across w*.log with id > T              # bounded by cadence
    state' = fold-into(state, tail)   # same fold op, seeded with the memoized state
    return state'
```

- `hydrate` is the I/O-bound step (mmap page-in). With a warm cache it is ~memcpy-fast;
  cold it is one sequential read of a tiny file.
- `fold-into` is the *only* compute, and it touches **only the tail**. Snapshot cadence
  bounds the tail, so this term is ~constant regardless of total `N`.
- **No snapshot yet?** Boot degrades gracefully to full-replay (the naive path) — identical
  result, just slow. Snapshots are an optimization layered on a always-correct fallback.

The fold seeded with prior state is *the same fold* re-pointed: cardinality axioms from the
snapshot are carried forward, and tail claims fold under them (a tail schema change is rare
and, if present, forces a fuller re-fold of the affected predicate — out of scope for the
hot path; the snapshot writer can refuse to memoize across an unsettled axiom).

---

## 4. Snapshot cadence — bounding the tail

Two triggers, whichever first (classic checkpoint policy):
- **every K claims** appended across all writers (e.g. K = 50k), or
- **every τ seconds** of wall time (e.g. τ = 60s).

This bounds the tail at boot to ≤ K claims (or one τ-window of writes). The benchmark
sweeps tail size to show boot ≈ `hydrate(tiny image) + fold(K)` and is **flat in total N**
— the property that makes "instant boot at scale" hold.

Snapshots are content-addressed by frontier `T` and are themselves immutable facts; keeping
the last few lets you boot-at-an-earlier-image for free (§5 time-travel). Old snapshots are
GC'd by a retention policy (keep last n, or one-per-day) — they are pure derivations, so GC
is never lossy.

---

## 5. Free affordances (each = the same fold op, re-pointed)

The fold is a pure function of `(claim-set, T)`. Move `T` or restrict the set and you get,
for free:
- **time-travel:** boot at an earlier `T` = pick the snapshot ≤ T and fold the tail up to T
  (not past it). Read-only view of history at any HLC.
- **unwind / redo:** the fold point is a cursor; decrement/increment it over the log to step
  state backward/forward. No undo-stack — the log *is* the undo history.
- **fork:** snapshot at `T`, then apply two divergent tails ⇒ two states sharing one image.
  Cheap because the shared prefix is the single memoized image; only the tails differ.

These are demoed tiny in `demo_affordances.clj`, not argued in prose.

---

## 6. Rebuild-from-logs guarantee — why this beats a Lisp heap-dump

A Common-Lisp `save-lisp-and-die` image is an opaque heap blob: not diffable, not
mergeable, not reconstructable from a more-fundamental source, and tied to one VM build.

This snapshot is the opposite on every axis, because it is a **pure derivation of an
append-only log**:
- **Reconstructable:** `snapshot == serialize(fold(logs ≤ T))`. Delete every image; re-fold
  the logs; bit-identical (the fold is deterministic, the serialization canonical). Tested
  by `prototype.clj`'s `snapshot-equals-refold?` check.
- **Diffable:** two images are two materialized states; diff = key-wise set diff. (Two heap
  blobs diff to noise.)
- **Mergeable:** you never merge *images* — you merge *logs* (`O(#writers)`, candidate B) and
  re-snapshot. The image is downstream of the merge, so federation Just Works.
- **Verifiable / not load-bearing:** because it's never the source of truth, a corrupt or
  stale image is a performance bug, never a correctness bug. Boot can always fall back to
  full-replay and get the same answer.

---

## 7. HONEST BOUNDARY — what this is and is NOT

**Covers:** the program *as authored* — its full source graph (every claim) and its
complete edit history — plus instant boot, time-travel, unwind/redo, and fork over that
history. This is the authoring/state image.

**Does NOT cover:** a *running* program's runtime execution state — call stacks, live heap,
in-flight coroutines. Beagle emits to Nix / JS / Clojure, targets with **no runtime
`eval`/reified environments** (see beagle CLAUDE.md: "Do not build a runtime operative/fexpr
evaluator… we emit Nix, which has no runtime eval"). There is no live process to freeze.
"Image-boot" here = boot the *authored state* instantly, not "snapshot a live process mid-
execution." That latter thing (continuation capture / process checkpointing) is a separate
project and is **not** claimed here. Don't oversell "freeze a live process."
