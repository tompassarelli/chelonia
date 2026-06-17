# Fram

**A claim engine — forward through time, append-only, never crushed.** Fram is a
self-hostable substrate for *claims*: relational facts `(subject, predicate,
object)` folded into a queryable in-memory graph, with stratified Datalog
derivation and a sole-writer coordinator over a durable append-only log. The
Markdown/text is a faithful, round-trippable *view* of the graph; you *query* —
derive — instead of maintaining anything.

> **Status: early, experimental — an *extracted* engine.** Fram was pulled out of
> a single coordination tool and is being made domain-neutral. **Already neutral:**
> the append-only log, the fold, the Datalog derivation, and the **live coordinator
> daemon — it carries no domain code.** **Not yet:** the kernel still ships the
> original lifecycle vocabulary as *defaults* (`committed`/`outcome`/`abandoned`,
> the single-valued predicate set) — overridable (`FRAM_SINGLE_VALUED`) but baked
> in. So: a working engine with an honestly-scoped genericization in progress.
> **CLI-shaped** — the payoff is the graph and the derived queries, not chrome.

> **The life/work app built on Fram is *[Lodestar](https://github.com/tompassarelli/lodestar)***
> (a separate repo): the daily-coordination verbs (`ready` / `blocked` / `leverage` /
> `next` / `capture`), the lifecycle rules, and the projections live there.
> *Chartroom* — a planned code-as-claims app that *authors* the graph and projects
> source — is also built on Fram. This repo is just the engine.

## What it looks like

The engine, on the bundled example threads (a fictional *"launch a personal
website"* project — no personal data). Run it yourself with `./demo.sh`:

```
$ bin/fram import                 # fold the Markdown threads into the claim graph
imported -> 162 claims -> ./claims.log

$ bin/fram validate               # structural integrity: cycles, dangling refs
OK — 17 threads, no violations.

$ bin/fram show 2026-01-01-090500 # one thread, as the claims it became
  title       Deploy the site to production
  depends_on  @2026-01-01-090200
  depends_on  @2026-01-01-090400
  part_of     @2026-01-01-090000
  ...

$ bin/fram export /tmp/regen      # regenerate the Markdown from the graph
exported 17 threads -> /tmp/regen
# round-trip: 162 claims in, 162 back — claim-identical (roundtrip_test.clj)
```

**On top of that graph, a consumer derives the queries that matter.** This is what
*Lodestar* computes from the same claims — domain projections, *not* engine
commands:

```
$ lodestar ready        # non-terminal threads whose every dependency is satisfied
$ lodestar blocked      # what's waiting, and on what
$ lodestar leverage     # rank threads by how many OTHER stuck threads finishing this unblocks
  unblocks 2  Set up CI / deploy pipeline
  unblocks 2  Write the site content
```

That last query names the unglamorous task that unblocks the most downstream
work — something a flat to-do list is *structurally* incapable of computing. (In
real life it's the chore version: `leverage` will happily tell you "do laundry"
unblocks more of your week than the exciting thing.) Fram is the substrate that
makes such queries cheap and always-current.

## The bet: every PM tool rots

Jira, Linear, Notion all drift from reality, for the same reason: keeping them
current is manual toil nobody does. Fram's bet is to make the data model
**relational** (dependencies, ownership, blocking, provenance are first-class),
store it as an **append-only log of claims**, and **derive** every view from the
graph. Then the questions that matter become cheap queries that are always
current — `ready`, `blocked`, `leverage`, deadline urgency — instead of fields
someone has to remember to update.

Because the structure is shared, a client invoice, a compiler refactor, a flight
to book, and "do laundry" can all live in the **same** claim graph; a consumer
queries from a **frame** (personal vs a given client) instead of switching tools.

## How it works

```
threads/*.md ──import──▶ claims.log (append-only) ──fold──▶ in-memory claim graph
                                                              │
                          coordinator daemon ◀── agents query + assert concurrently
                                                              │
                            consumer (e.g. Lodestar) derives ready / blocked / leverage
```

- A **thread** is one Markdown file: an `@id` header of claim triples, a `---`,
  then a prose body. It's any unit of intended action — a task, a project, a
  research thread, a life intention. A "project" is just a thread with children
  (`part_of`). See **[THREAD-FORMAT.md](THREAD-FORMAT.md)** and the bundled examples.
- **Claims** are `(left predicate right)` triples — `(@X depends_on @Y)`,
  `(@X owner personal)`. Entities referenced by `@` are **interned**: rename a
  person/repo/topic *once*, not in N files.
- **`export` is the verified-lossless inverse of import** (`roundtrip_test.clj`):
  the graph regenerates the Markdown claim-identically, so files are a *view*, not
  a competing source of truth — and you can always walk away with exactly what you
  put in.
- **Lifecycle is derived, not stored.** There is no `state` field; `committed` /
  `outcome` / `abandoned` / `driver` / `depends_on` are read off the facts.

## Multi-agent safety

All writes go through a single coordinator, so an AI agent you already run (Claude
Code and friends) can keep the graph current — and **multiple agents can do it
concurrently without corrupting state.**

The coordinator is a single-writer daemon: agents query and assert concurrently
over a localhost socket; writes serialize through one lock with **optimistic
versioning** (each assert carries a `base_version`; conflicts are rejected and
retried); and rule-breaking writes — dependency cycles, dangling refs — are
**rejected at commit**. Backed by an adversarial concurrency + durability suite
(`cnf_coord_test.clj`). The daemon carries **no domain code** — structural
integrity (`validate`) is kernel-level; lifecycle projections live in the
consumer. Honest framing: proven under local test load, single machine — not
distributed consensus.

## AI-native: tools, not a query DSL

The primary query author here is a model, not a person — so the surface is tuned
for *what a model emits correctly with zero examples*, not human terseness. That
points away from any bespoke query language and toward two surfaces:

- **A tool catalog generated from the claim vocabulary.** For each predicate `P`
  (cardinality from the kernel's single-valued vocabulary — `FRAM_SINGLE_VALUED`
  or its fallback — ref-vs-literal by the `@` convention): `P-of` / `P-list` read
  it, `set-P` / `add-P` / `remove-P` write it (through the coordinator), and for
  reference predicates `P-from` walks the reverse edge — plus structural
  `threads` / `show` / `dependents-of` / `validate`. The priors live in the
  *names* (`owner-of`, `depends_on-from`); the model fills **typed params** and a
  missing required param is **rejected server-side**, so it can't emit a broken
  call, and correctness lives in the engine, not the model. Point Fram at a
  different corpus and the catalog regenerates — the engine ships no domain tools
  of its own.
- **`query` — a structured Datalog *escape hatch*** for the rare multi-hop
  question no named tool covers. The model emits **data**, not text:
  `{:find R :rules [{:head {:rel R :args [terms]} :body [{:rel r :args [terms] :neg b}]}]}`
  (a term is `{:var "x"}` or a constant; base relations are `triple`/`claim`).
  That shape *is* the engine's internal rule data, so the only added layer is
  **validation at the boundary** — a *total* check that runs before anything else:
  it can't parse-fail (data, not text), can't reference an undefined relation,
  can't ground an **unbound head variable**, can't **shadow a base relation**, and
  unstratified negation is rejected. It then executes on the same fixpoint
  (recursion + stratified negation), **no query-library dependency**. (Evaluation
  is naive, so a deeply recursive query can be *expensive* — the MCP path bounds it
  with a time budget; the CLI runs it unbounded.)

Both are served over **MCP** (`bin/fram-mcp` — JSON-RPC over stdio): point a
model's MCP client at it and it gets the generated catalog + the structured
`query` tool, reads off the live fold, writes through the coordinator. The CLI
(`fram tools` / `fram call <tool> <edn>` / `fram query <edn>`) is the same surface
for humans.

## Self-host it, or host it for others

**You choose where the authority runs — and you can always leave with your data.**
It's a genuine *server-authority* design: one coordinator process owns the writes,
and clients (the CLI, your agents) connect to it over a socket. That same design
runs three ways with **no fork in the architecture** — on your laptop, on a server
you own, or as a multi-tenant service you host for others (one coordinator + log
per account). The only thing that differs between them is the transport in front
of the socket.

Where it runs out of the box: the coordinator binds **loopback only**
(`127.0.0.1`) — single-machine and unauthenticated by design. Remote and
multi-tenant hosting add an **authenticated gateway** in front of that socket
(bearer token → tenant → that tenant's coordinator); the claim model, the
write-safety, and the "export and walk away" guarantee are identical in every
mode. Whoever runs the server, the data is never locked in.

- **Your data is two plain-text things you can `grep`:** your Markdown threads,
  and an append-only `claims.log`. No proprietary format, no lock-in, no telemetry.
- **The log is the history, and it's recoverable.** Live writes (coordinator/CLI)
  append; each line records *who* wrote it and *when*. `fram history <id>` replays an
  entity's timeline — who · when · what, in `tx` order. (`import`/`merge` rebuild the
  log from your Markdown — refusing if that would drop un-exported writes — so the
  Markdown is the portable current-state view; the log, kept in Git, is the durable history.)
- **You can always leave.** `fram export` regenerates your Markdown
  claim-identically, so you walk away with exactly what you put in.
- **Nothing to build.** The compiled code is committed; running it needs only
  [babashka](https://babashka.org). An optional GraalVM native binary
  (`native/build.sh`) gives ~0.2s/command for larger corpora.
- **Warm, local reads.** The daemon serves ~1ms in-memory reads over that
  loopback socket.

```sh
# 1. Clone
git clone https://github.com/tompassarelli/fram && cd fram

# 2. Try the engine on the bundled example threads
bin/fram import
bin/fram validate
bin/fram show 2026-01-01-090500
bin/fram history 2026-01-01-090500   # the entity's timeline — who · when · what, in tx order
bin/fram export /tmp/regen      # verified-lossless round-trip

# 3. Point it at your own threads (any directory of .md files)
export FRAM_THREADS=/path/to/your/threads
export FRAM_LOG=/path/to/your/claims.log
bin/fram import

# 4. (Optional) the warm, multi-agent-safe daemon
bin/fram-up                     # ensure the coordinator is up (idempotent)
bin/fram tell <id> committed 2026-06-17   # writes go through the coordinator (serialized, rule-checked)
```

Engine command surface: `import · export <dir> · show <id> · history <id> ·
validate · watch · set <id> <pred> <val> · tell <id> <pred> <val> ·
untell <id> <pred> <val> · merge <from> <to>`, the AI-facing surface
(`tools · query <edn> · call <tool> <edn>`, also over MCP via `bin/fram-mcp`),
plus the daemon (`bin/fram-daemon`, `bin/fram-up`). The life verbs (`ready` /
`blocked` / `leverage` / `next` / `capture` / `agenda`) are a consumer's, not the
engine's.

## Built on Beagle

The logic (kernel, fold, Datalog, import/export, CLI) is written in **Beagle** — a
typed Lisp that compiles to Clojure — with host interop in a thin Clojure runtime
(`src/fram/rt.clj`). The compiled Clojure is committed and runs on babashka, so
**you don't need Beagle to run Fram** — only to rebuild from the `.bclj` sources
(`build.sh`). (Beagle is a personal language and a real dependency risk, disclosed
plainly.)

## Tests

```sh
bb -cp out roundtrip_test.clj    # claims <-> files round-trip is lossless
bb -cp out cnf_coord_test.clj    # adversarial concurrency + durability suite
bb -cp out schema_test.clj       # predicate vocab: cardinality + value-kind
bb -cp out datalog_test.clj      # stratified derivation
bb -cp out cnf_test.clj          # reified claim kernel
bb -cp out query_test.clj        # structured Datalog query + boundary rejections
bb -cp out tools_test.clj        # tool catalog generated from the vocabulary
bb mcp_test.clj                  # bin/fram-mcp end to end over JSON-RPC/stdio
```

## License

[MIT](LICENSE).
