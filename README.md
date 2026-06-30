<div align="center">

# Fram

**An append-only claim engine.** Every claim is a triple `(subject predicate object)`;
lifecycle is *derived*, never stored; every write serializes through one coordinator;
and the text is a **view** of the graph you can always walk away with.

[![license](https://img.shields.io/badge/license-Apache_2.0-blue.svg)](LICENSE)
[![status](https://img.shields.io/badge/status-early%2C%20experimental-orange.svg)](#what-it-isnt)
[![runtime](https://img.shields.io/badge/CLI%20%2B%20MCP-babashka-success.svg)](https://babashka.org)
[![daemon](https://img.shields.io/badge/coordinator-JVM%20%2B%20mTLS-informational.svg)](#multi-agent-safety)

</div>

Fram stores **claims** — relational triples — in a durable append-only log, folds them
into a queryable in-memory graph, derives over them with stratified Datalog, and
serializes all writes through a sole-writer coordinator. You **derive** answers, you
don't maintain them. References carry **identity, not spelling** (rename a thing once,
not in N files), and immutability buys corruption-freedom + lock-free reads — *not*
merge-freedom. The long argument, written to survive a skeptic with the negative space
conceded, is in **[docs/WHY_FRAM_EXISTS.md](docs/WHY_FRAM_EXISTS.md)**.

> **"Isn't this just Datomic / Datahike / an RDF store?"** No — and the reason is the
> *atom*, not the features. Fram's unit is the **claim-object**: a claim that is itself
> addressable and reifiable at per-claim granularity. A datom isn't; an RDF store treats
> statement-level reification as a bolt-on. Concurrency, Datalog, and schema-as-data are
> *not* why Fram exists (off-the-shelf stores tie or win there) — the primitive is.

## Terminology — it's a *claim* (never a loose "fact")

The substrate atom is a **claim**: an immutable, addressable triple `(subject predicate
object)`. The graph stores claims; lifecycle is *derived* from the claim set, never stored.
**Always call it a claim.**

Do **not** use *"fact"* as a synonym for *claim*. "Fact" connotes settled, stored, view-free
truth — exactly the model CNF (Claim Normal Form) rejects: a claim is **asserted and
defeasible**; it can be superseded, disputed, and coexist with its rival. The word *fact* is
reserved for two narrow, precise senses only:

1. **Datalog** — a ground tuple in a relation (the `d/facts` API, "delta fact", EDB/IDB).
   Strict Datalog-evaluation vocabulary.
2. **Views model** — a claim *selected/accepted as true inside a view*
   ([docs/VIEWS_AND_BRANCHES.md](docs/VIEWS_AND_BRANCHES.md) §0). Always view-relative; the
   substrate has no view-free facts.

Everywhere else — docs, MCP `instructions`, prompts, CLI help, lifecycle prose — the word is
**claim**. Rule of thumb: if "claim" fits and the sentence stays true, it should have been "claim".

## One engine, many consumers

Fram is the **engine**, not an app. The relational structure is shared, so the *same*
engine answers questions for very different domains — each in its **own** graph:

- **[Tern](https://github.com/tompassarelli/tern)** — life/work coordination
  (the `ready` / `blocked` / `leverage` verbs live there, not in the engine).
- **[Chartroom](chartroom/)** — code-as-claims (a module *inside* this repo): a Beagle
  module's AST *is* the claims, the `.bclj` text is a view.
- **[Beagle](https://github.com/Autonymy/beagle)** — the typed Lisp Fram itself is
  authored in; it projects source into the graph through Chartroom.

The engine ships **no** domain verbs of its own — new domain, new graph, same engine.
*How each consumer projects onto the claims is the [How it works](#how-it-works) section
below; the deep code story is [Identity-addressed code](#identity-addressed-code-chartroom).*

## What the graph buys you: reasoning + repair

These are the two reasons to put something in a claim graph instead of files.

**Reasoning — relational questions are cheap, exact, and always current.** "What
depends on this? what's unused? who calls this? what unblocks the most other work?" are
*relationship* questions, and over a graph a relationship question *is* a Datalog query —
no reconstruction tax, because the graph is canonical and incremental, not rebuilt per
question. Pointed at code (Chartroom), the same engine answers "what breaks if I change
this?" **scope-correctly**: a call binds the definition in its own module, so two
same-named functions in different modules don't collide — what bare-text grep gets wrong.

**Repair — change one node, the blast radius re-derives.** Because the graph knows the
real edges, a change propagates to exactly the affected sites, *deterministically* — a
graph operation, not a model guessing. Reasoning reads the graph; repair reads it and
acts.

## Try it (verbatim — every command here is run by CI)

```sh
git clone https://github.com/Autonymy/fram && cd fram
./demo.sh                 # import the bundled example threads, validate, round-trip
```

The bundled threads are a fictional *"launch a personal website"* project — no personal
data. Under the hood `./demo.sh` runs the engine loop:

```sh
bin/fram import           # fold the Markdown threads into the claim graph (claims.log)
bin/fram validate         # structural integrity: cycles, dangling refs, closed vocab
bin/fram call title-of '{:id "2026-01-01-090500"}'      # -> Deploy the site to production
bin/fram call dependents-of '{:id "2026-01-01-090200"}' # -> @2026-01-01-090500
bin/fram export /tmp/regen   # regenerate the Markdown from the graph (lossless round-trip)
```

Counts (claims, threads) are **computed from `threads/`**, never asserted here — run the
commands and read them. The round-trip is verified claim-set-identical by
`tests/roundtrip_test.clj`, so files are a *view*, not a competing source of truth — you
can always walk away with your data.

Point it at your own corpus:

```sh
export FRAM_THREADS=/path/to/threads
export FRAM_LOG=/path/to/claims.log
bin/fram import
```

## How it works

The engine is **domain-neutral**. Its only unit is the **claim** — a triple
`(left predicate right)`, e.g. `(@X depends_on @Y)`. Claims append to a durable log; the log
folds into an in-memory graph; consumers query and derive over that graph; every write
serializes through one coordinator. There is **no** notion of "thread", "module", or "task" in
the engine — only claims:

```
claims ──assert──▶ claims.log (append-only) ──fold──▶ in-memory claim graph
                                                       │
                   coordinator daemon ◀── agents query + assert concurrently
                                                       │
                     a consumer derives its own views over the claims (Datalog)
```

- Entities referenced by `@` are **interned** — rename a thing once, not in N files.
- **Derived state is never stored.** No `state` field exists in the engine; a consumer reads
  `committed` / `outcome` / `ready` / blast-radius *off the claims*.

A **consumer** is a projection + a vocabulary onto that neutral engine. Two ship today, and
they look nothing alike — which is the whole point:

**Fram with Tern (life/work).** Tern models work as **threads** — one Markdown file
each (`@id` header of claim triples, `---`, prose body; see
[THREAD-FORMAT.md](THREAD-FORMAT.md)). `bin/fram import` folds those files into claims, and
Tern derives `ready` / `blocked` / `leverage` from them. The bundled `threads/` corpus is
Tern-shaped *only because Fram was extracted from Tern* — that's the one reason
"threads" appear in the engine repo at all. `export` is the verified-lossless inverse of
`import` (`tests/roundtrip_test.clj`): the files are a view, not a second source of truth.

**Fram with Beagle (code-as-claims).** [Chartroom](chartroom/) projects **Beagle source** into
the graph with the claim log **canonical**: a module's AST *is* the claims; the `.bclj` text is
a rendered view. No threads here — the unit is the *def*, the projection is the *resolver*, and
references carry the binding's identity (`bound_to`), so a rename is a ~2-claim edit and code
intelligence (call graphs, blast radius) is Datalog.

**One engine, many memory-spaces.** Each consumer lives in its **own** graph (a separate log),
and one coordinator can host several — one log per account/tenant. So a hosted Tern and a
code graph are *separate memory-spaces in the same engine*, never co-mingled (see
[Isolation](#isolation-separate-graphs-not-access-control)). *(Hosting Tern as a tenant of
a shared engine is a direction, not yet shipped.)*

## Multi-agent safety

All writes go through one coordinator, so the AI agents you already run can keep the
graph current **concurrently, without corrupting state.** It's a single-writer daemon:
agents query and assert over a localhost socket; writes serialize through one lock with
**optimistic versioning** (each assert carries a `base_version`; conflicts are rejected
and retried); rule-breaking writes (dependency cycles, dangling refs) are **rejected at
commit**. Backed by an adversarial concurrency + durability suite
(`tests/cnf_coord_test.clj`).

The rule-check guarantees **referential integrity** — references resolve, the vocabulary
is closed, structure is sound. It does *not* judge whether a write is *semantically* what
you meant; that stays with the author. Honest framing: proven under local test load on a
single machine — **not** distributed consensus.

```sh
bin/fram-up                                    # start the warm, multi-agent-safe daemon
bin/fram tell 2026-01-01-090700 committed 2026-06-21   # writes route through the coordinator
```

## AI-native: tools, not a query DSL

The primary query author is a model, so the surface is tuned for what a model emits
correctly with zero examples — which points *away* from a bespoke query language toward
two surfaces, **both generated from the claim vocabulary** (point Fram at a different
corpus and they regenerate):

- **A generated tool catalog.** For each predicate `P`: `P-of` / `P-list` read,
  `set-P` / `add-P` / `remove-P` write (through the coordinator), `P-from` walks the
  reverse edge — plus structural `threads` / `show` / `dependents-of` / `validate`, and
  the code-authoring verbs Chartroom adds (`add-def` / `set-body` / `rename-def`). The
  priors live in the *names*; the model fills typed params and a missing required param is
  **rejected server-side**. The catalog is *generated*, so don't trust this prose — run
  **`bin/fram tools`** for the live list.
- **`query` — a structured Datalog escape hatch** for multi-hop questions no named tool
  covers. The model emits **data**, not text (the shape *is* the engine's internal rule
  data), so the only added layer is total validation at the boundary: it can't
  parse-fail, reference an undefined relation, leave a head variable unbound, or smuggle
  in unstratified negation. Same fixpoint as everything else (recursion + stratified
  negation), no query-library dependency.

```sh
bin/fram tools            # the live, generated catalog (count + signatures)
bin/fram query '{:find "po" :rules [{:head {:rel "po" :args [{:var "x"} {:var "y"}]}
                                      :body [{:rel "triple" :args [{:var "x"} "part_of" {:var "y"}]}]}]}'
```

Both are served over **MCP** (`bin/fram-mcp`, JSON-RPC over stdio); the CLI (`fram tools`
/ `fram call <tool> <edn>` / `fram query <edn>`) is the same surface for humans.

## Identity-addressed code (Chartroom)

[Chartroom](chartroom/) points the engine at *code*. The claim log is canonical: a
module's AST is the claims, and the `.bclj` source text is a rendered view of the log.

- **References carry identity, not spelling.** A call site resolves to the binding's
  stable id (`bound_to @module#int`), so renaming a definition is a ~2-claim edit and
  every reference re-points *by identity* — where a text tool must rewrite every site.
  Measured on the honeysql corpus: **238 distinct reference sites** that text must
  re-derive and rewrite, vs a 2-claim graph edit (receipt: the `after-text`
  experiments package, `owned-resolution-forcing/`).
- **The render is a pure function of the log.** `render(log) == render(text)`,
  byte-identical *to each other* (both derived from the graph). The general round-trip is
  *datum*-identical, not byte-identical to hand-authored source — comments and exact
  whitespace are not preserved (`chartroom/`).
- **Code intelligence as Datalog.** Scope-correct call graphs and transitive blast
  radius are queries, computed scope-correctly by binding identity (not name-match).

The categorical line under all of this is **node-identity vs no-node-identity**: text and
git lack a stable per-node id, so they re-derive the program to answer a relational
question or to coordinate a concurrent edit. (Identity-addressed concurrency itself is not
unique — a node-id CRDT has it too; what's distinctive here is pairing it with a faithful
*typed* projection into an existing language.)

<!-- regenerate: bb owned-resolution-forcing/probe.clj  (in the after-text experiments package) -->

## Anti-rot: the engine is the source of truth

Static reference docs rot. So this README hardcodes as little of the surface as
possible — the engine and its generators are the truth:

| You want… | Source of truth (always current) |
|---|---|
| the engine verbs | `bin/fram` (no args prints the full usage) |
| the AI tool catalog | `bin/fram tools` (generated from the vocabulary) |
| the claim-authoring API + signatures | `bb bin/fram-primer` (generated from `src/fram/*.bclj`) |
| the predicate vocabulary | `bin/fram doctor` (with `FRAM_SINGLE_VALUED` to override) |
| what's tested | `tests/` + [`.github/workflows/ci.yml`](.github/workflows/ci.yml) |

**Every fenced command in this README is executed in CI** by
[`scripts/readme-check.sh`](scripts/readme-check.sh): it runs each block against a scratch
copy of the bundled threads, asserts every `bin/fram <verb>` is real, `test -e`'s every
referenced path, and fails on a stale repo URL. A command that stops working turns CI red.

## Measured (each pinned to a receipt + a regen command)

- **Construction-path scaling vs zerolang** — building a medium app by incremental
  authoring, Fram is flat per-op while zerolang's per-patch cost rises (it reloads +
  validates + rewrites the whole graph each edit): **2.3× @250 defs, 4.2× @500, 7.5×
  @1000**, the gap *growing* with size — **"O(N²)-shaped"** (curve + pinned source, not a
  formal fit). This is construction-*path* scaling, not language speed; the honest
  companion is that Fram **loses** a single small edit (its sibling
  `zerolang-vs-fram/RESULTS.md`). Receipt: `zerolang-vs-fram/CONSTRUCTION-SCALING.md`
  (in the `after-text` experiments package).
- **Propagation under K concurrent disjoint writers** — graph propagation stays flat
  (~1.6–2.2 ms, K=1…8) where a git merge-queue climbs (~50→314 ms). Mirror cost, stated
  honestly: the graph **loses the write column** (~175 ms eager-index vs git's ~22–80 ms)
  — it front-loads at write to keep reads + propagation cheap. Receipt:
  `propagation/RESULTS.md` (in the `after-text` experiments package); the live
  perf-regression gate stays here as `bench/propagation/`.

<!-- regenerate: bb -cp out bench/propagation/sweep.clj  (SWEEP_KS=1,2,4,8) -->

## Isolation: separate graphs, not access control

One coordinator process owns the writes; clients connect over a socket. The same design
runs on your laptop, a server you own, or a service you host — **one coordinator + log per
account** — only the transport differs.

**Be honest about what isolates what.** Fram has **no access control**. Isolation is
**process + log + network** only: the coordinator binds loopback (`127.0.0.1`) by
default; remote/multi-tenant hosting puts an authenticated gateway (bearer token → tenant
→ that tenant's coordinator) in front. The rule is **one graph per trust domain** — your
personal life-graph, a client's data, and public code tooling are *separate logs in
separate processes*, never one. Share *machinery* across domains freely; never share
*data*.

- **Your data is two plain-text things you can `grep`:** your Markdown and an append-only
  `claims.log`. No proprietary format, no telemetry, no lock-in.
- **The log is the recoverable history.** Each line records *who* and *when*;
  `fram history <id>` replays an entity's timeline in `tx` order.
- **Nothing to build.** Compiled Clojure is committed under `out/`. The **CLI + MCP run
  on [babashka](https://babashka.org)** (fast startup); the long-lived **coordinator runs
  on the JVM** (real threads, `SSLServerSocket` for engine-terminated **mTLS**). An
  optional GraalVM native binary (`native/build.sh`) targets ~0.2 s/command.

## Built on Beagle

The logic (kernel, fold, Datalog, import/export, CLI) is authored in
**[Beagle](https://github.com/Autonymy/beagle)** — a typed Lisp that compiles to Clojure —
with host interop in a thin Clojure runtime (`src/fram/rt.clj`). The compiled Clojure is
committed and runs on babashka, so **you don't need Beagle to run Fram** — only to rebuild
from the `.bclj` sources (`build.sh`). (Beagle is a personal language and a real
dependency risk, disclosed plainly.)

## What it isn't

- **Not a database you'd pick for features.** Concurrency, Datalog, schema-as-data — an
  off-the-shelf store ties or wins. The reason to use Fram is the claim-object atom.
- **Not access control.** Isolation is process + log + network; co-mingling trust domains
  is an incident, not a mess.
- **Not distributed consensus.** The concurrency guarantees are proven under local test
  load on one machine.
- **Not stable.** Early and experimental; the kernel still ships the original lifecycle
  vocabulary as overridable *defaults* (`FRAM_SINGLE_VALUED`).

## Tests

Every suite lives in `tests/` and runs on babashka against the committed `out/`:

```sh
bb -cp out tests/roundtrip_test.clj   # claims <-> files round-trip is lossless
bb -cp out tests/cnf_coord_test.clj   # adversarial concurrency + durability
bb -cp out tests/query_test.clj       # structured Datalog query + boundary rejections
```

`ls tests/*_test.clj` is the full list; CI runs them all
([`.github/workflows/ci.yml`](.github/workflows/ci.yml)).

<details>
<summary><b>Engine surface &amp; project layout</b></summary>

`bin/fram` with no arguments prints the canonical verb list (the source of truth — don't
trust a copy here). The daemon is `bin/fram-daemon` / `bin/fram-up`; the AI surface is
also served over MCP by `bin/fram-mcp`. The life verbs (`ready` / `blocked` / `leverage`
/ `next` / `capture`) belong to the *consumer* (Tern), not the engine.

- `src/fram/*.bclj` — the engine, authored in Beagle: kernel, fold, Datalog, schema,
  import/export, CLI.
- `src/fram/rt.clj` — the thin Clojure host-interop runtime.
- `out/` — the **committed** compiled Clojure (so Fram runs without Beagle).
- `chartroom/` — code-as-claims: the resolver, minimal-op authoring verbs, code
  intelligence.
- `docs/` — conceptual sources of truth: `WHY_FRAM_EXISTS.md`,
  `VIEWS_AND_BRANCHES.md` (the write/read model), `adr/` (project boundaries).
- `tests/` — the suites. `bench/` — perf-regression gates (the propagation budget).
  The measured *receipts* cited above live in the separate `after-text` package.

</details>

## Design discipline

- **Removed, not deprecated.** No back-compat shims; correctness and the desired design
  decide, never "things depend on it."
- **Derive, don't store.** Lifecycle and code intelligence are views over the claims, not
  maintained fields.
- **One graph per trust domain.** Share machinery, never data.

See [`docs/WHY_FRAM_EXISTS.md`](docs/WHY_FRAM_EXISTS.md) and the [ADRs](docs/adr/) for the full argument.

## License

[MIT](LICENSE).
