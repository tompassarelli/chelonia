# Fram

**A claim engine — forward through time, append-only, never crushed.** Fram is a
self-hostable, **domain-neutral** substrate for *claims*: relational facts
`(subject, predicate, object)` folded into a queryable graph, with stratified
Datalog derivation and a sole-writer coordinator over a durable append-only log.

The engine knows **nothing** about your domain — cardinality vocabulary,
lifecycle, and projections are **injected by the app on top** (the live
coordinator depends on zero domain code). The Markdown/text is a faithful,
round-trippable *view* of the graph; you *query* — derive — instead of
maintaining anything.

> **[Lodestar](../lodestar) is the life/work app built on Fram.** This repo is the
> engine; the daily coordination CLI, lifecycle rules, and projections live in
> Lodestar. The examples below (`lodestar …`) are illustrative of a consumer.

> **Status: early and experimental.** Extracted from the Fram coordination
> tool and made domain-neutral (cardinality + projections injected by the
> consumer). **CLI-shaped** — the payoff is the graph and the derived queries, not
> chrome. Expect rough edges and churn.

## What it looks like

On the bundled example threads (a fictional *"launch a personal website"*
project — no personal data). Nobody maintained a board; this is all computed from
the same Markdown you'd write anyway (`./demo.sh`):

```
$ lodestar import                 # fold the Markdown threads into a claim graph
imported -> 141 claims

$ lodestar ready                  # what's actually actionable now
READY NOW
  Write the site content
  Set up CI / deploy pipeline
  Design the layout
  Launch a personal website
  ...

$ lodestar blocked                # what's waiting, and on what
BLOCKED — 2
  Deploy the site to production  (waiting on 2)
  Announce the launch  (waiting on 1)

$ lodestar leverage               # the boring keystone a flat list CAN'T surface
TOP UNBLOCKERS — finishing this transitively frees the most stuck threads
  unblocks 2  Set up CI / deploy pipeline
  unblocks 2  Write the site content
  unblocks 1  Deploy the site to production
```

That last command just named the unglamorous task that unblocks the most
downstream work — something a flat to-do list is *structurally* incapable of
computing. (In real life it's the chore version: `leverage` will happily tell you
that "do laundry" unblocks more of your week than the exciting thing.)

## The bet: every PM tool rots

Jira, Linear, Notion all lie, for the same reason: keeping them current is manual
toil nobody does, so the board drifts from reality. Fram's bet is to make the
data model **relational** (dependencies, ownership, blocking, provenance are
first-class), store it as an **append-only log of claims**, and **derive** the
board from the graph. Then the questions that matter become cheap queries that are
always current:

- **ready** — non-terminal threads whose every dependency is satisfied (do these now)
- **blocked** — what's waiting, and on what
- **leverage** — rank threads by how many *other* stuck threads finishing this one
  transitively unblocks. The "do the boring keystone first" list — a flat to-do
  list structurally cannot compute it; a dependency graph does it for free.
- **next** — leverage + deadline urgency + momentum, ranked
- **agenda** — overdue / today / upcoming, from `do_on` dates

## Work and life in one graph

You don't run a second app for life. A client invoice, a compiler refactor, a
flight to book, and "do laundry" all live in the **same** claim graph;
you query from a **frame** (personal vs a given client) instead of switching
tools. Because the structure is shared, `leverage` can surface that a mundane
chore unblocks the most of your week — exactly what a flat list hides.

## How it works

```
threads/*.md ──import──▶ claims.log (append-only) ──fold──▶ in-memory claim graph
                                                              │
                                          projections: ready / blocked / leverage / next
                                                              │
                            coordinator daemon ◀── agents query + assert concurrently
```

- A **thread** is one Markdown file: YAML frontmatter + prose. It's any unit of
  intended action — a task, a project, a research thread, a life intention. A
  "project" is just a thread with children (`part_of`). See
  **[THREAD-FORMAT.md](THREAD-FORMAT.md)** and the bundled examples.
- **Claims** are `(left predicate right)` triples — `(thread:X depends_on
  thread:Y)`, `(thread:X owner owner:personal)`. Entities are **interned**:
  rename a person/repo/tag *once*, not in N files.
- **`export` is the verified-lossless inverse of import** (`roundtrip_test.clj`):
  the claim graph regenerates the Markdown claim-identically, so files are a
  *view*, not a competing source of truth — and you can always walk away with
  exactly what you put in.

## Optional: let an agent keep it true

The graph is derived from what you write, and all writes go through a single
coordinator — so an AI agent you already run (Claude Code and friends) can read
your prose, keep the graph current, and **multiple agents can do it concurrently
without corrupting state**.

> **Honest scope:** the **deterministic import** path (Markdown frontmatter →
> claims) ships today and is what the demo above uses. The **LLM prose-extraction**
> layer — pulling implied claims out of free-form prose — is validated *safe*
> (zero fragmentation, every entity grounded, nothing dangling) but measured
> **conditional**: it's most valuable on *fresh/unstructured* prose, not a corpus
> you already keep tidy. The solo CLI value stands on its own without it.

**Multi-agent safety.** The coordinator is a single-writer daemon: agents query
and assert concurrently over a localhost socket; writes serialize through one lock
with optimistic versioning (each assert carries a `base_version`; conflicts are
rejected and retried); and rule-breaking writes — dependency cycles, dangling
refs, an `active` thread with no driver — are **rejected at commit**. Backed by an
adversarial concurrency suite (`coord_test.clj`, C1–C9, 9/9). Honest framing:
proven under local test load, single machine — not distributed consensus.

## Self-hostable and private

**You run the authority — always. No hosted SaaS, no account, no cloud;
self-hosting isn't a tier, it's the only mode.** And it's a genuine
*server-authority* design, not a single-binary toy: one coordinator process owns
the writes, and clients (the CLI, your agents) connect to it over a socket. So
"self-hosted" means *a client/server you operate* — you decide where the server
runs.

Where it runs today: the coordinator binds **loopback only** (`127.0.0.1`) — one
machine, the one you work on. Putting that same authority on a **remote box you
own** (so several machines, or a second person, share one graph) is the planned
next step — gated on auth + a network-safe transport, because the socket is
currently unauthenticated by design. In short: *self-hosted always; single-machine
today; a remote server you own on the roadmap — never a vendor cloud.*

- **Your data is two plain-text things you can `grep`:** your Markdown threads,
  and an append-only `claims.log`. No database, no account, no cloud, no telemetry.
- **Nothing is ever overwritten.** The log is the history; every change is
  provenanced and recoverable. Git is your backup.
- **You can always leave.** `lodestar export` regenerates your Markdown
  claim-identically, so you walk away with exactly what you put in.
- **Nothing to build.** The compiled code is committed; running it needs only
  [babashka](https://babashka.org). An optional GraalVM native binary gives
  ~0.2s/command for larger corpora.
- **Warm, local reads.** The daemon serves ~1ms in-memory reads over that
  loopback socket.

```sh
# 1. Clone
git clone https://github.com/tompassarelli/lodestar && cd lodestar

# 2. Try it on the bundled example threads
bin/lodestar import
bin/lodestar ready
bin/lodestar leverage        # the keystone a flat list can't surface

# 3. Point it at your own threads (any directory of .md files)
export CHELONIA_THREADS=/path/to/your/threads
export CHELONIA_LOG=/path/to/your/claims.log
bin/lodestar import
bin/lodestar capture "book the flight"   # a thought -> a ready thread, folded into the graph
bin/lodestar next

# 4. (Optional) the warm, multi-agent-safe daemon
bin/lodestar-daemon 7977             # serves on 127.0.0.1:7977
bin/lodestar tell <id> state done    # writes go through the coordinator (serialized, rule-checked)
bin/lodestar ready                   # warm ~1ms read from the in-memory index
```

Full command surface: `capture <title> · import · export · ready · blocked ·
leverage · next · agenda · plate · show <id> · validate · audit · merge · tell ·
untell · doctor`.

## Built on Beagle

The logic (kernel, fold, projections, import, CLI) is written in **Beagle** — a
typed Lisp that compiles to Clojure — with host interop in a thin Clojure runtime
(`src/lodestar/rt.clj`). The compiled Clojure is committed and runs on babashka,
so **you don't need Beagle to run Fram** — only to rebuild from the `.bclj`
sources (`build.sh`). (Beagle is a personal language and a real dependency risk,
disclosed plainly.)

## Tests

```sh
bb -cp out coord_test.clj       # C1–C9 adversarial concurrency suite (9/9)
bb -cp out roundtrip_test.clj   # claims<->files round-trip is lossless
```

## License

[MIT](LICENSE).
