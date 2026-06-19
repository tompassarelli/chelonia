> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# Reasoning costs fewer retrievals when the structure is already in the graph

Given the **same** structural task, an agent reaches the **same verified-correct
answer** with **far less reconstruction work** when it can **query the live claim
graph** than when it must rebuild the structure from **text** (grep → re-read →
classify hits → chase aliases → backtrack). The graph already *holds* the canonical
structure — `refers_to` identity, callers, the binding behind every spelling —
pre-paid once at projection and kept fresh incrementally (S1/S3). Text holds none of
it, so a text agent **rebuilds** that structure every time it needs a structural
fact. `reproduce.sh` boots the warm daemon over the projected corpus and lets you
watch the op-counts diverge.

The cleanest live instance: **the ultimate binding behind an aliased reference — 1
graph query vs 2 text ops, same single binding + reference set.** The gap *grows*
with structural distance — the transitive blast radius is **1 query vs 9 text ops**.
At the trivial-local case AND at the bare caller-MODULE-SET question, text ties (1
op each) and we **concede** both.

---

## Read this first: what this is NOT

- **This is NOT a wall-clock claim.** "Built in 2 min vs 5" is confounded by model
  speed, task luck, and serialization cost. The metric here is **retrieval
  operations** — graph queries vs greps + file-reads + alias-chases + backtracks.
  Op-count is *insensitive to model speed*, so it isolates "the graph already knows
  the structure" from "the model was fast today." Every number below is `wc -l` of a
  retrieval oplog where **one logged line == one retrieval**, by construction (the
  `ts`/`gq` wrappers each perform exactly one primitive; a single alternation regex
  is one pass, ten separate searches are ten log lines — compound-command gaming is
  structurally impossible).

- **This is NOT a strawman of grep.** The text baseline is the **minimal-correct**
  reconstruction a competent ripgrep-wielding engineer would do — single-pass
  alternation regexes (one search per BFS *level*, not one per node), the
  distinctive-suffix search that *catches* the intra-module caller a lazy
  qualified-only grep would drop. We do not pad the text arm and we do not
  oracle-feed the graph arm. **Where text ties or wins, we say so** (cal; the
  caller-module-set question).

- **The trivial-local case is CONCEDED, and so is the caller-module-set question.**
  For "read the body of a private one-line helper," `ts` is 1–2 ops and the graph
  has **no advantage** — the AST-as-claims store does *not* cheaply serve a
  pretty-printed body string, so text *wins or ties*. For "which modules call this
  def," the daemon's `:callers` returns reference-site `[module, spelling]` groups
  (= the caller module set), and text reads the same module set off **one** search's
  line content — a **1-vs-1 tie**. The graph's advantage is on **alias-resolution /
  transitive / erased-type** questions, not module-set lookups. We show both
  calibration rows and concede them openly.

- **This is NOT "two ways of parsing text."** The graph arm queries the **warm
  daemon's resolution-produced claims** — `refers_to` materialized over the projected
  corpus — never re-parses `.bclj` per query. The `gq` wrapper speaks *only* the
  daemon socket protocol; it is structurally incapable of shelling
  `racket --emit-edn` / `resolve.clj`. The projection-to-claims is the legitimate
  **one-time** "graph pre-pays" setup (46 s this run, 37 s of it JVM boot), amortized
  to ~0/query in a warm session. A boot guard *fails loud* if the warm store isn't
  corpus-backed.

## The phenomenon (the headline)

A code reference, in the claim graph, carries the **identity** of its binding
(`refers_to <node-id>`, resolved by a scope-correct lexical walk), not just its name.
So the graph already answers — in **one** reverse-edge lookup keyed on the
*reference* — questions that text can only answer by *rebuilding* the relation:

- **"What is the one binding behind the `k/->Claim` reference in `import.bclj`, and
  all references (any spelling) to it?"** We key the query on the **reference node**
  (`@import#398`, located once at setup) — NOT on the answer name. The daemon follows
  that reference's `refers_to` through `ultimate` to the binding (`@kernel#298`, the
  `defrecord Claim`), then does the reverse lookup — **all in one round-trip**. Text
  must run an alternation pass over all spellings **and then read the defrecord to
  convince itself they converge** — it can *see* a tally but cannot *prove* the
  identity without trusting the read.

- **"Who calls this cross-module def?"** is a `:callers` reverse lookup. At
  *caller-module-set* granularity (what `:callers` actually serves) this is a tie —
  text reads `{kernel, main, tools}` off one search's line content. The graph's win
  is **not** here; it is the alias-resolution (b) and the transitive closure (c).

This is the same idea as Unison's content-addressed definitions and JetBrains MPS's
node-id references: **names are metadata; the structure is the identity.** The graph
pre-computes that structure once and keeps it warm; text reconstructs it per query.

## The demonstration

### Layer A — deterministic structural-cost (model-INDEPENDENT, the reproducible headline)

Per task: the single graph query vs the **minimal-correct** text reconstruction,
both verified `== ground truth`. Fully scripted; stable across runs. **Every number
below is regenerated by `reproduce.sh`** (graph ops off the live `gq` oplog; text-min
ops off `text_reconstruct.sh`'s `results.tsv`; the (c) count cross-checked against the
model-free `text_bfs_cost.clj`; the (e) oracle is `beagle-build-all`, run for real).
Measured this run (live corpus, 11 modules, 101 671 warm claims):

| task | hops | served | **graph ops** | **text-min ops** | both correct? |
|------|------|--------|---------------|------------------|---------------|
| **cal** (body of a local helper) | 0 | live | 1 (locate) | **2** | text: yes / graph: **locates-only (body not served)** — TIE, conceded |
| **a** (caller MODULE SET, cross-module) | 1 | **live** | **1** (`:callers`) | **1** | yes — `{kernel, main, tools}` — **1-vs-1 TIE, conceded** |
| **b** (ultimate binding behind an aliased reference) | 1 | **live** | **1** (`:callers` keyed on the **reference** node) | **2** | yes — 1 binding `@kernel#298`, 74 tokens (39/29/6) |
| **c** (transitive blast radius) | k | Layer-A only | 1 *(hypothetical)* | **9** | yes — 7-member reaches-closure |
| **d** (which fns take `(Vec Claim)`) | 1 | Layer-A only | 1 *(hypothetical)* | 3 (on source) | source: yes / **lowered: WRONG (erased)** |
| **e** (rename `Claim`→`Datum`, recompile) | k | **edit (measured)** | **1 claim edit** + recompile | sed + recompile + fix-loop | **`11 built, 0 error(s)`** |

**THE CLEAN LIVE HEADLINE IS (b), NOT (a).** (a) is a genuine **1-vs-1 tie**: at the
caller-module-set granularity the daemon serves, the op-1 search's line content
already decides `{kernel, main, tools}` (def@254, comment@265, self-name@266 all
excluded *by line text*; no read needed). (b) is the clean win: the op-1 spelling
tally cannot *prove* single-identity convergence, so the op-2 read of the defrecord
at `kernel.bclj:101` is genuinely required — text 2, graph 1.

**HONESTY ON (c)/(d):** the live warm store today materializes `refers_to` (so
`:callers` — tasks **a** and **b** — is canonical and served live), but it does **not**
yet expose a `:reaches`/`:type-refs` op. `reproduce.sh` *proves* this: `{:op :reaches}`
and `{:op :type-refs}` both return `{:error "unknown op"}`. So the graph "1" for (c)/(d)
is **explicitly HYPOTHETICAL** — what the principled `:reaches`/`:type-refs` op *would*
cost, never routed through the cold callgraph CLI to fake a live answer. They are
**visually segregated** from the live `a`/`b` rows for exactly this reason. The text
numbers for (c)/(d) are real and measured.

- (c) text = **9** ops, a **frontier-batched** hand-BFS rebuilding the 7-member
  closure: **4 alternation searches** (one per BFS level — `thread-ids-i`, then
  `(cmd-export|cmd-validate|work-thread-ids-i|call)`, then `(dispatch|cmd-call)`, then
  `-main`) **+ 5 classify-reads** (one per new caller-module-region per level, to map a
  call-site to its enclosing defn and disambiguate noisy substrings). This is the
  *fair* baseline: METHODOLOGY guard 2 permits single-pass alternation, so we charge
  ONE search per BFS level, **not** a separate grep per node. The model-free
  `text_bfs_cost.clj` (computed from the callgraph's level structure) and the scripted
  hand-BFS in `text_reconstruct.sh` **both give 9** — `reproduce.sh` cross-checks them
  equal. This is where the gap is widest (1 vs 9).
- (d) source text = **3** ops (alternation pass → 41 sites = 20 `(Vec Claim)` + 21
  `(Vec k/Claim)`, + inner-type + param-binding confirm). On the **lowered** `.clj`
  the `(Vec Claim)` annotation **erases to 0** — text-on-lowered *cannot* recover the
  generic fns. That failure is itself a result (it's the tier-1 erased-reference case).
- (e) is **measured this run** (not asserted): `resolve.clj` renames the one binding
  (`CLAIMS EDITED: 1`), render regenerates **71 `Datum` tokens** (1 definition + 70
  references re-pointed from that single edit), and the Beagle compiler — the oracle —
  reports **`11 built, 0 error(s)`**. The text edit-cost is a sed + recompile +
  fix-miss loop; the read needed to *plan* it is exactly (b)'s spelling fan-out.
  *(Reconciling (e)'s 71 with (b)'s 74: 71 regenerated `Datum` tokens + 3 surviving
  `Claim` spellings = 74. The 3 survivors are not references that get re-pointed — 1 is
  an ns docstring string literal (kernel.bclj:3), 1 is a genuine comment (cnf.bclj:5),
  and 1 is a regex false-positive (types.bclj:14 `CnfClaim`, a different record whose
  suffix the pattern matches) — so the harness is correct, and the 74 baseline itself
  over-counts the real references by one.)*

### Layer B — paired-agent behavioral demo (DESIGNED, NOT RUN)

**Status: Layer B has NOT been run. It is a design only — there is no measured agent
behavior here, and the static artifacts under `.behavioral/` are NOT a reproducible
measurement.** The empirical result of this experiment is **Layer A** (above): the
deterministic op-count reconstruction-cost table, regenerated by `reproduce.sh`.

The full Layer B protocol — paired text-arm and graph-arm sub-agents, wrapper-confined,
N≥10 per task per arm, bypass-detected, reported as a distribution — is specified in
`METHODOLOGY.md` (*Layer B*). It is **not implemented**: `reproduce.sh` does not spawn
sub-agents, there is no bypass detector, and the `.behavioral/` directory holds
**static artifacts captured once by hand**, not output regenerated by the harness. Do
NOT read `.behavioral/` as measured agent behavior or as a Layer-B result. Running
Layer B for real (per the METHODOLOGY spec) remains future work.

The headline empirical claim of this experiment is therefore the **Layer A** structural
reconstruction-cost result, not a behavioral measurement.

## THE ORACLE — ground truth is the verified resolver, not "trust us"

Both arms are scored against **ground truth computed once** from the verified-correct
resolver (`chartroom/src/resolve.clj` callgraph mode: scope-correct edges, `refers_to`
+ ultimate-through-aliases, the transitive reaches-closure as a Fram Datalog fixpoint)
plus the source for spelling/type-site counts, and — for (e) — the **Beagle compiler**.
This oracle is **never** a graph-arm retrieval (owned-resolution guarantee ii) — it
runs once into `ground_truth.json`; both arms are checked against it. Measured this run:

- (a) **4** direct callers (the oracle counts *enclosing defns*: kernel/work-thread-ids-i,
  main/cmd-export, main/cmd-validate, tools/call); module-set `{kernel, main, tools}`.
  Note the two cardinalities count different things: the daemon's `:callers` returns
  **reference-site `[module, spelling]` groups** (3 entries over 6 raw call sites),
  the oracle's "direct callers" counts **enclosing defns** (4). Both arms are scored on
  the **module set** `{kernel, main, tools}`, which they match exactly.
- (b) `Claim` 39 + `k/Claim` 29 + `k/->Claim` 6 = **74** tokens, **one** binding
  (`@kernel#298`).
- (c) closure size **7**: `{kernel/work-thread-ids-i, main/-main, main/cmd-call,
  main/cmd-export, main/cmd-validate, main/dispatch, tools/call}`.
- (d) **41** source sites (20 + 21); lowered `(Vec Claim)` = **0**; 4 surviving
  `^Claim` single-param hints (5 tokens) — *not* the generic case.
- (e) graph rename `Claim`→`Datum` = **1** claim superseded; oracle =
  `beagle-build-all` → **`11 built, 0 error(s)`** (run for real this run).

The graph arm's live answers match the oracle exactly: (a) target `@kernel#1518`,
caller modules `{tools, kernel, main}`; (b) keyed on the **reference** `@import#398`,
the daemon resolves it through `ultimate` to `@kernel#298` (the single defrecord) and
returns 10 distinct `[module, spelling]` reference groups across all three spellings —
one identity, as the oracle says.

## Scope — what this does and does NOT show

- **The graph PRE-PAYS, once.** Projecting the corpus to claims + booting the warm
  daemon + resolving `refers_to` cost **46 s** this run (37 s of it JVM boot). That is
  the legitimate amortized setup — the cheap per-query cost afterwards (~0 ops of
  reconstruction, **fewer retrievals**) is *exactly what "reasons faster" means* for an
  agent inside a context window. S3 keeps that warm store fresh incrementally, so the
  structure stays pre-paid across edits.

- **Live today: (a) callers and (b) ultimate-through-aliases only.** Those read
  resolution-produced `refers_to` straight off the warm store. (b) is the honest live
  *headline* (1 vs 2); (a) is a conceded 1-vs-1 tie at module-set granularity. (c)
  transitive-reaches and (d) type-refs need a `:reaches`/`:type-refs` op the daemon
  does not yet materialize; their graph cost is presented as **hypothetical** in the
  deterministic layer, clearly labeled, and **never** faked via the cold callgraph CLI.
  The principled fix (materialize a `calls-defn` relation + add `:reaches`/`:type-refs`
  ops that read warm claims, exactly as `:callers` reads `refers_to`) would move them
  onto the live headline.

- **Reference-keyed, not answer-fed.** (b) is keyed on the **reference node** in
  `import.bclj` (located once at setup from the projected EDN — the same class of
  one-time setup as picking the (a) target), and the daemon performs
  reference→ultimate→reverse-lookup itself (`target-node` now `ultimate`s a `:te`
  node). The graph is **not** handed the answer name; it resolves the reference, the
  same resolution the text arm does by hand. `reproduce.sh` asserts the reference
  resolves to `@kernel#298` or fails loud.

- **The two calibration cases are conceded** (text wins/ties: the local body, and the
  caller-module-set question), and the AST-as-claims store not cheaply serving a
  literal **body string** is a real, honest limitation surfaced by the cal task.

- **This is the read/reason side.** It does not, by itself, prove the concurrent
  multi-agent authoring thesis — that is the *sibling* `concurrent-authoring`
  experiment (the SAFETY axis: does text BREAK under concurrency). This experiment is
  the REASONING-COST axis: same correct answer, fewer retrievals. (e) is the
  edit-propagation corollary, verified here by the compiler and also in the sibling
  `rename-identity` experiment.

## Reproduce

```
bash reproduce.sh
```

Boots the daemon (JVM) over the projected corpus on a **verified-free** high port
(9301–9990; never 7977/the live lodestar log), runs the **boot guard** (fail-loud:
asserts a nonzero corpus claim count, `:log` == our temp log, and a nonempty
`:callers` for a known target — proving the warm store holds resolution-produced
`refers_to`), computes ground truth, runs the live graph arm (`gq`, one socket
round-trip per op — including the **reference-keyed** (b) `callers-te @import#398`),
proves `:reaches`/`:type-refs` are unbuilt (segregating the hypothetical c/d cost),
**runs the minimal-correct text reconstruction** (`text_reconstruct.sh`) and reads its
per-task op-counts into the Layer-A table (cross-checking (c) against the model-free
`text_bfs_cost.clj`), **runs the (e) rename + recompile for real** (rename on a
`mktemp` copy via `resolve.clj`, render, `beagle-build-all` as the compiler oracle),
and prints the side-by-side deterministic table. Everything operates on `mktemp`
copies + a temp log; every daemon is trap-killed.

Needs `racket`, `babashka`, Clojure (the daemon is JVM), and Beagle
(`BEAGLE=~/code/beagle`).

**What `reproduce.sh` regenerates (Layer A — the empirical result):** `graph.oplog`
(graph ops off the live `gq`), `ground_truth.json` (the once-computed oracle), and the
text-min op-counts (`.text-results`), plus the (e) rename+recompile run. The Layer A
table is reproduced live every run.

**What is static, captured once (NOT regenerated):** the artifacts under `.behavioral/`
(`summary.tsv` + per-subject `*.oplog`). These are hand-captured and `reproduce.sh` does
**not** touch, spawn, or validate them — Layer B is DESIGNED, NOT RUN (see *Layer B*
above). Do not treat `.behavioral/` as a reproducible Layer-B measurement.
