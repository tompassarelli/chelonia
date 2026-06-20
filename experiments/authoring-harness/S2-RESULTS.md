# S2 — the write side, proven: edits source from the graph; references re-point by identity

`experiments/rename-identity/RESULTS.md` proved the **read** side (a complete rename
recompiles `0 error(s)`; an incomplete one fails) and explicitly left the **write side
owed**: *"the actual thesis — many agents authoring one codebase concurrently through a
live graph — has zero numbers here and is still owed... The write-side is untouched."*

S2 is the smallest end-to-end **write-side proof of mechanism**: an authoring edit
**sources from the claim graph** (not from text), the `.bclj` is a pure downstream view,
and a rename **re-points references by identity, not spelling**. It is the backbone every
concurrent-authoring arm (E1 and E2) stands on. Reproduce: `bash reproduce-s2.sh`.

## The two falsifiable checks (the point of S2 — not the rename demo)

The source `.bclj` is **physically deleted** before both checks, so "reads zero source"
is a filesystem fact, not an assertion. The **recompile is the discriminator**: an
un-re-pointed reference ⇒ undefined `base` ⇒ `beagle-build-all` fails.

### CHECK A — the graph is canonical  ✅
Delete `greet.bclj`; render `greet` from the claim log alone; recompile.
→ render = 861 B, *pure function of the log*; `beagle-build-all` → **`1 built, 0 error(s)`**;
agent-blind oracle → **PASS**. The module resolves + compiles with **no source on disk**.
(This is precondition #1 of `private-docs/CONCURRENT_AUTHORING_EXPERIMENT.md` and the
falsifiable check in `experiments/THESIS.md`, now demonstrated.)

### CHECK B — the canary: a graph edit lands as claims and regenerates correctly  ✅
Graph rename `base → greeting` via `bin/fram-edit-code` (daemon on a /tmp code.log,
port 7993 — never 7977), with the source `.bclj` already deleted:
- **committed 2 ops: 1 assert + 1 retract, 0 new nodes** — node `@greet#14`'s value flips
  `"base" → "greeting"`. **One node touched** = the O(1) identity rename. (log: 539 → 544.)
- The body reference node `@greet#30` still **stores** `"base"`, yet the render emits
  `(str greeting " " who)` — it **re-points via `refers_to` identity**, not spelling.
- The comment prose still says `` `base` `` — **correct**: comments are prose, not
  references; a text find-replace would have corrupted them. The graph touched only the binding.
- `beagle-build-all` → **`0 error(s)`** (the discriminator: proves the reference re-pointed),
  oracle → **PASS** (behavior preserved under the rename).

**Net: land-mine #1 ("a graph arm that's secretly a text arm") is dead by construction**
here — the edit path is `fram-edit-code` over the log, source deleted, verified by recompile.

## Honest findings (don't lose these — they bite the harness)
- **Recompile gate = `beagle-build-all`, NOT the `beagle build` CLI.** `fram-render-code`
  emits the roundtrip dialect (`(define-target clj)` header); `beagle build` expects
  `#lang beagle/clj` source and rejects the render ("expected a `module' declaration").
  `beagle-build-all` consumes the render dialect directly — it is the gate the flip itself
  uses. **The experiment harness must recompile rendered modules with `beagle-build-all`.**
- **Render is byte-STABLE + comment-faithful, not byte-identical** to hand-written source
  (header dialect + intra-form layout reflow; comments preserved). This is the **shipping claim.**
  Byte-identical is **MEASURED at ~half a day out and deliberately deferred** — exact-whitespace
  preservation trades against the local-edit→local-diff property the concurrency thesis depends on;
  in-form comments and exact layout are the **named, understood gaps, not unknowns.** Full
  measurement + method: `BYTE-IDENTICAL-MEASUREMENT.md` (10/11 modules byte-identical in a 1m52s
  spike; one-line lexeme fix). Fine for the experiment regardless: the oracle is behavioral and the
  canonical recompile gate consumes the byte-stable render.

## What this IS / ISN'T
- **IS:** the write-side backbone — graph-canonical edits + identity re-pointing, end-to-end,
  recompile-verified, on one module. Framing-agnostic (holds for E1 and E2).
- **ISN'T:** concurrency (one edit, one agent), scale (one 2-def module), the language/substrate
  arms, or any scored result. Those are the next gates (S3 = scale to a hiccup slice; then the
  #39 build gates). **No number claimed beyond "the mechanism holds."**
