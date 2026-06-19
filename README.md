# Fram

**A claim engine — append-only, forward through time.** Fram stores *claims* —
relational facts `(subject predicate object)` — in a durable append-only log,
folds them into a queryable in-memory graph, derives over them with stratified
Datalog, and serializes all writes through a sole-writer coordinator. The
Markdown/text is a round-trippable *view* of the graph: you **derive** answers,
you don't maintain them.

> **Status: early, experimental — an *extracted* engine.** Fram was pulled out of
> a single coordination tool and is being made domain-neutral. **Already neutral:**
> the log, the fold, the Datalog derivation, and the live coordinator daemon (it
> carries no domain code). **Not yet:** the kernel still ships the original
> lifecycle vocabulary as *defaults* (`committed`/`outcome`/`abandoned`, a
> single-valued predicate set) — overridable (`FRAM_SINGLE_VALUED`) but baked in.
> CLI-shaped: the payoff is the graph and the derived queries, not chrome.

> **"Isn't this just Datomic / Datahike / an RDF store?"** No — and the reason is
> the *atom*, not the features. Fram's unit is the **claim-object**: a fact that is
> itself addressable and reifiable at per-fact granularity. The datom isn't, and an
> RDF store treats statement-level reification as a bolt-on. Concurrency, Datalog,
> and schema-as-data are *not* why Fram exists (off-the-shelf stores tie or win
> there) — the primitive is. The full argument, written to survive a skeptic and
> with the negative space conceded, is in **[docs/WHY_FRAM_EXISTS.md](docs/WHY_FRAM_EXISTS.md)**.

> **The write/read model** — why many agents can author one graph without clobbering
> each other — is in its companion **[docs/VIEWS_AND_BRANCHES.md](docs/VIEWS_AND_BRANCHES.md)**:
> the graph is append-only and *plural* (it stores **claims**, not facts), writes don't
> conflict (only *read-time* path-selection does), a program is a coherent **traversal
> under a view**, and conflict is the shadow of a cardinality axiom — identity the only
> forced one. (Part design-direction: it flags what's shipped vs. where the model is heading.)

## One engine, many consumers

Fram is the **engine**, not an app. The relational structure is shared, so the
*same* engine answers questions for very different domains — each living in its
**own** graph:

- **[Lodestar](https://github.com/tompassarelli/lodestar)** — life/work
  coordination. The lifecycle rules and the daily verbs (`ready` / `blocked` /
  `leverage` / `next` / `capture`) live there, derived from the claims.
- **Chartroom** — code-as-claims, with the claim log **canonical**: a module's AST
  *is* the claims, the `.bclj` source text is a rendered **view** of the log (a pure
  function of it — verified byte-identical), and edits are authored **through the
  graph** by minimal-op verbs (`set-body` / `upsert-form`) that commit a verb's
  *exact* claim delta, not a whole-file rewrite — so disjoint edits to the same
  module commute. On top it derives **code intelligence** — scope-correct call
  graphs, transitive blast radius — as Datalog queries.

The engine ships **no** domain verbs of its own. New domain → new graph (a
separate log), same engine.

## What the graph buys you: reasoning + repair

These are the two reasons to put something in a claim graph instead of files.

**Reasoning — relational questions are cheap, exact, and always current.** "What
depends on this? what's unused? who calls this? what unblocks the most other
work?" are all *relationship* questions, and over a graph a relationship question
*is* a Datalog query. There's no reconstruction tax: the graph is canonical and
incremental, not rebuilt per question.

```
$ lodestar leverage     # rank threads by how many OTHER stuck threads this unblocks
  unblocks 2  Set up CI / deploy pipeline      # the unglamorous keystone a flat
  unblocks 2  Write the site content           # to-do list can't compute
```
The same engine, pointed at code (Chartroom), answers "what breaks if I change
this function?" **scope-correctly** — a call binds the definition in its own
module, so two same-named functions in different modules don't collide (what
bare-text grep gets wrong).

**Repair — change one node, the blast radius re-derives.** Because the graph
knows the real edges, a change can propagate to exactly the affected sites,
*deterministically* (a graph operation, not a model guessing). Reasoning reads
the graph; repair reads it and then acts on it.

## What it looks like

On the bundled example threads (a fictional *"launch a personal website"*
project — no personal data). Run `./demo.sh`:

```
$ bin/fram import                 # fold the Markdown threads into the claim graph
imported -> 162 claims -> ./claims.log

$ bin/fram validate               # structural integrity: cycles, dangling refs
OK — 17 threads, no violations.

$ bin/fram show 2026-01-01-090500 # one thread, as the claims it became
  title       Deploy the site to production
  depends_on  @2026-01-01-090200
  part_of     @2026-01-01-090000

$ bin/fram export /tmp/regen      # regenerate the Markdown from the graph
exported 17 threads -> /tmp/regen
# round-trip: 162 claims in, 162 back — claim-identical (roundtrip_test.clj)
```

## How it works

```
threads/*.md ──import──▶ claims.log (append-only) ──fold──▶ in-memory claim graph
                                                              │
                          coordinator daemon ◀── agents query + assert concurrently
                                                              │
                            consumer (e.g. Lodestar) derives ready / blocked / leverage
```

- **Claims** are `(left predicate right)` triples — `(@X depends_on @Y)`,
  `(@X owner personal)`. Entities referenced by `@` are **interned**: rename a
  person/repo/topic *once*, not in N files.
- A **thread** (Lodestar's unit) is one Markdown file: an `@id` header of claim
  triples, a `---`, then a prose body. See **[THREAD-FORMAT.md](THREAD-FORMAT.md)**.
- **`export` is the verified-lossless inverse of `import`** (`roundtrip_test.clj`):
  the graph regenerates the Markdown claim-identically, so files are a *view*, not
  a competing source of truth — and you can always walk away with your data.
- **Lifecycle is derived, not stored.** There is no `state` field; `committed` /
  `outcome` / `abandoned` / `driver` are read off the facts.

## Multi-agent safety

All writes go through one coordinator, so the AI agents you already run can keep
the graph current — **concurrently, without corrupting state.** It's a
single-writer daemon: agents query and assert over a localhost socket; writes
serialize through one lock with **optimistic versioning** (each assert carries a
`base_version`; conflicts are rejected and retried); rule-breaking writes
(dependency cycles, dangling refs) are **rejected at commit**. Backed by an
adversarial concurrency + durability suite (`cnf_coord_test.clj`).

The rule-check guarantees **referential integrity** — references resolve, the
vocabulary is closed, structure is sound. It does *not* judge whether a write is
*semantically* what you meant; that stays with the author. Honest framing: proven
under local test load on a single machine — not distributed consensus.

## AI-native: tools, not a query DSL

The primary query author is a model, so the surface is tuned for what a model
emits correctly with zero examples — which points away from a bespoke query
language toward two surfaces:

- **A tool catalog generated from the claim vocabulary.** For each predicate `P`:
  `P-of` / `P-list` read, `set-P` / `add-P` / `remove-P` write (through the
  coordinator), `P-from` walks the reverse edge — plus structural `threads` /
  `show` / `dependents-of` / `validate`. The priors live in the *names*; the model
  fills typed params and a missing required param is **rejected server-side**, so
  correctness lives in the engine, not the model. Point Fram at a different corpus
  and the catalog regenerates.
- **`query` — a structured Datalog escape hatch** for multi-hop questions no named
  tool covers. The model emits **data**, not text (the shape *is* the engine's
  internal rule data), so the only added layer is total validation at the boundary:
  it can't parse-fail, reference an undefined relation, leave a head variable
  unbound, or smuggle in unstratified negation. Executes on the same fixpoint
  (recursion + stratified negation), no query-library dependency.

Both are served over **MCP** (`bin/fram-mcp`, JSON-RPC over stdio); the CLI
(`fram tools` / `fram call <tool> <edn>` / `fram query <edn>`) is the same surface
for humans.

## Isolation: separate graphs, not access control

You choose where the authority runs: one coordinator process owns the writes;
clients connect over a socket. The same design runs on your laptop, on a server
you own, or as a service you host for others — **one coordinator + log per
account** — with only the transport in front of the socket differing.

**Be honest about what isolates what.** Fram has **no access control**. Isolation
is **process + log + network** only: the coordinator binds loopback (`127.0.0.1`)
by default; remote/multi-tenant hosting puts an authenticated gateway (bearer
token → tenant → that tenant's coordinator) in front. So the rule is **one graph
per trust domain** — your personal life-graph, a client's data, and public code
tooling are *separate logs in separate processes*, never one. Co-mingling them
isn't messy, it's an incident. Share *machinery* across domains freely; never
share *data*. (Cross-domain questions, when you need them, go through a read-only
union *view* — a tool crossing the boundary, not data merging.)

- **Your data is two plain-text things you can `grep`:** your Markdown and an
  append-only `claims.log`. No proprietary format, no telemetry, no lock-in.
- **The log is the recoverable history.** Each line records *who* and *when*;
  `fram history <id>` replays an entity's timeline in `tx` order. Kept in Git, the
  log is the durable history; the Markdown is the portable current-state view.
- **Nothing to build.** Compiled code is committed. The **CLI + MCP run on
  [babashka](https://babashka.org)** (fast startup); the long-lived **coordinator
  daemon runs on the JVM** (real threads, `SSLServerSocket` for engine-terminated
  **mTLS**). An optional GraalVM native binary (`native/build.sh`) gives
  ~0.2s/command.

```sh
git clone https://github.com/tompassarelli/fram && cd fram
bin/fram import                       # try it on the bundled example threads
bin/fram validate
bin/fram show 2026-01-01-090500
bin/fram export /tmp/regen            # verified-lossless round-trip

export FRAM_THREADS=/path/to/threads  # point it at your own .md files
export FRAM_LOG=/path/to/claims.log
bin/fram import

bin/fram-up                           # (optional) the warm, multi-agent-safe daemon
bin/fram tell <id> committed 2026-06-17   # writes route through the coordinator
```

Engine surface: `import · export <dir> · show <id> · history <id> · validate ·
watch · set/tell/untell <id> <pred> <val> · merge <from> <to>`, the AI-facing
`tools · query <edn> · call <tool> <edn>` (also over MCP), plus the daemon
(`bin/fram-daemon`, `bin/fram-up`). The life verbs (`ready` / `blocked` /
`leverage` / `next` / `capture`) belong to the consumer, not the engine.

## Built on Beagle

The logic (kernel, fold, Datalog, import/export, CLI) is written in
**[Beagle](https://github.com/tompassarelli/beagle)** — a typed Lisp that compiles
to Clojure — with host interop in a thin Clojure runtime (`src/fram/rt.clj`). The
compiled Clojure is committed and runs on babashka, so **you don't need Beagle to
run Fram** — only to rebuild from the `.bclj` sources (`build.sh`). (Beagle is a
personal language and a real dependency risk, disclosed plainly.)

## Tests

```sh
bb -cp out roundtrip_test.clj     # claims <-> files round-trip is lossless
bb -cp out cnf_coord_test.clj     # adversarial concurrency + durability
bb -cp out schema_test.clj        # predicate vocab: cardinality + value-kind
bb -cp out datalog_test.clj       # stratified derivation
bb -cp out cnf_test.clj           # reified claim kernel
bb -cp out query_test.clj         # structured Datalog query + boundary rejections
bb -cp out tools_test.clj         # tool catalog generated from the vocabulary
bb -cp out datalog_scale_test.clj # semi-naive scales (200-chain closure)
bb mcp_test.clj                   # bin/fram-mcp over JSON-RPC/stdio
bb bind_test.clj                  # FRAM_BIND modes (loopback vs 0.0.0.0)
bb tls_test.clj                   # engine-terminated mTLS
```

## License

[MIT](LICENSE).
