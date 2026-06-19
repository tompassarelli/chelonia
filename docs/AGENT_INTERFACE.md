# The agent interface to Fram — investigation (overnight)

**Status:** Empirical investigation + two built+measured wins + a mapped remainder.
Question: *we have query support — what's the missing interface that lets an agent
drive this substrate well?* Method: inventory the agent-facing surfaces, find the
gaps by driving the real thing, build the top win, measure it, map the rest. Every
piece here is **rep-stable** — keyed on `(l,p,r)`/Datalog/identity, **not** the integer
`fN` ordering internals the CRDT rewrite (#36) will change — so it survives that swap.

## The agent-facing surfaces (inventory)

- **MCP tools** (`fram_mcp.clj` + generated): per-predicate `P-of`/`P-list`/`set-P`/
  `add-P`/`remove-P`/`P-from`; structural `threads`/`show`/`dependents-of`/`validate`;
  the `query` Datalog escape hatch; edit-tools `add-def`/`set-body`/`rename-def`.
- **Daemon read ops** (`cnf_coord_daemon.clj` handle): `:query` (warm Datalog),
  `:callers` (refers_to), `:refers-ensure`, `:version`/`:status`/`:validate`/`:warm-check`.
- **Semantic resolution** (`resolve.clj`): `refers-target`/`ultimate`/`def-binding`/
  `callers-of`/`select-main-1`.
- **Datalog + raw claims** (`fram.query`, `fram.cnf` by-l/by-p/by-lp/by-pr).

## The ranked friction (what an agent pays)

1. **Warm reads were unreachable from the agent path** (headline). Every MCP read
   COLD-FOLDS the whole log per request (`load-state` → `fold(read-log)`), ~**450ms**
   on the canonical log, vs ~**7ms** warm — a ~60× tax on *every* read, compounding
   across multi-hop walks. The MCP client had no `coord-query`/`coord-callers`; and the
   live daemon predates the warm ops (returns `unknown op`).
2. **No lifecycle/derived-status tool.** Lifecycle is the selling point but the fram
   surface exposes no `ready`/`blocked`/`status-of` — an agent must re-implement it as
   a multi-round-trip walk (`show` → follow each `depends_on` → `show` again).
3. **Resolution is a selection, not a uniqueness proof.** `P-of`/`select-main-1` return
   first-live with no multiplicity signal (#19); `refers-target nil` is 3-way overloaded;
   `refers_to` is derived + never persisted (a cold reader sees "unresolved").
4. **`callers-of` is a whole-corpus scan** (O(all references), not O(callers)), and can
   trigger a hidden re-resolve walk first.
5. **Ref-vs-literal write normalization is data-driven, not declared** — a silent
   dangling-claim hazard (a bare value's ref-ness depends on current corpus contents).
6. **Multi-hop drops straight to hand-authored Datalog**, with hard failure modes
   (10s budget, non-cooperative interrupt, concurrency cap, no vocab introspection).

## BUILT + MEASURED tonight (rep-stable)

**#1 — Warm read path** (`111c038`). `coord-query`/`coord-callers` in `rt.clj` (via
`warm-read`, which returns nil on daemon-down/old-op = the capability handshake);
`fram_mcp.clj` routes the `query` tool through warm with **cold fallback** (safe even
against the old live daemon). **Measured** (`cnf_warm_read_receipt.clj`, synthetic
3600-claim /tmp log, in-process): warm `:query` **4.5ms** vs cold fold+query **107ms**
= **24× per read**, same result both paths; dead-port → nil → cold. (~60× on the larger
canonical, per the inventory's live measurement.)

**#3 — Resolution multiplicity signal** (`e2b55ee`). New daemon `:resolved` op returns
`{:value :members :ambiguous? :values}` for a `(te,pred)` group + `coord-resolved`
client — surfacing contention instead of hiding it behind first-live. **Demonstrated**
(`cnf_resolved_receipt.clj`): uncontested `ambiguous?=false` (1 live), contested
`ambiguous?=true` (2 live `[alice bob]`). Resolves friction #3's first failure (and a
genuinely-single field diverging to >1 live is caught the same way).

## MAPPED (candidate builds, rep-stable, not built tonight)

- **#2 `status-of` derived-lifecycle view** (highest agent value): a maintained warm
  Datalog view (`blocked = depends_on(x,y) ∧ ¬outcome(y,_)`, `ready = committed ∧ ¬blocked`)
  exposed as one `:status-of`/`:ready`/`:blocked` op — collapses the multi-hop lifecycle
  walk into one read. Stratified-negation over `triple(l,p,r)`, fN-independent.
- **#4 `callers-of` ultimate-keyed reverse index** — O(callers) probe instead of an
  O(all-references) scan, maintained alongside the already-incremental refers_to.
  **DEFER until after the CRDT rekey** — its feeder walk reads fN-ordered children, so
  build it on the settled rep (the index *keys* — identity + membership — are rep-stable).
- **#5 declared ref-ness** — normalize writes off the predicate's *declared* `ref|literal`
  type (already recorded by `register-pred!`), not `all-ref?` over current claims; surface
  it in the MCP input-schema. Kills the silent dangling-claim hazard.
- **#6 canned closure tools + vocab introspection** — `reaches`/`blast-radius`/
  `unblocked-committed` compiled to validated Datalog (agent fills typed params, not
  rules); a vocab tool (distinct preds + declared types + arity); make the fixpoint
  deadline-cooperative.

## Hypotheses — verdict

- **Deterministic semantic resolution:** NOT yet single+unambiguous+cheap — the agent
  *had* to disambiguate (first-live multiplicity + nil-overloading + walk-state) and the
  answer was cheap only when warm. **#1 makes it cheap; #3 makes multiplicity checkable.**
  The nil-3-way + never-persisted-refers_to remain (a 4-way `resolution-status` is the
  next small read).
- **Hot-pathed query views:** YES — `callers-of`/`refs-in-module`/`resolve-in-scope`
  should be maintained views, and the maintenance is **already mostly paid** (refers_to
  is incrementally re-resolved scope-correctly); only an index upsert is new. #1 already
  serves them warm; #4 (the ultimate-keyed reverse index) is the remaining upsert,
  deferred to post-rekey.

## Deploy note (touches port 7977 — NOT done; for Tom)

The live daemon predates the warm ops, so it returns `unknown op` for `:query`/`:resolved`.
A `lodestar up` restart makes it serve them. **Until then the wired client falls back to
cold** (no regression), so this code is safe to ship without coordinating the restart.
