# Chelonia

**I ramble into Markdown. Chelonia turns that into a queryable dependency graph —
then tells me what's ready, what's blocked, and what boring thing unlocks the
most progress.**

A claim-native, agent-first substrate for coordinating work and life. The source
of truth is a **graph of claims** (relational facts) — not a board you drag cards
around, and not rows in a database. You don't maintain it by hand: an agent reads
your prose and keeps the graph true, and you *ask* it instead of *updating* it —
"what's ready?", "what should I work on?", "what's blocked, and on what?".

> **Status: early and experimental.** Built as a personal tool; shared because
> the *shape* might be useful to others. Expect rough edges and churn.

## The bet

Every project-management tool rots, for the same reason: keeping it current is
manual toil nobody does, so the board is always lying. Chelonia's bet is to make
the data model relational (dependencies, ownership, blocking, provenance are
first-class), store it as an append-only log of claims, and let an agent keep it
true. Then the questions that actually matter become cheap derived queries:

- **ready** — non-terminal threads whose every dependency is satisfied (do these now)
- **blocked** — what's waiting, and on what
- **leverage** — rank threads by how many *other* stuck threads finishing this one
  transitively unblocks. The "do the boring keystone first" list — something a
  flat to-do list structurally cannot tell you.
- **next** — a ranked suggestion combining leverage + deadline urgency + momentum

## How it works

```
threads/*.md ──import──▶ claims.log (append-only) ──fold──▶ in-memory claim graph
                                                              │
                                          projections: ready / blocked / leverage / next
                                                              │
                            coordinator daemon ◀── agents query + assert concurrently
```

- A **thread** is one Markdown file: YAML frontmatter + prose body. It's any unit
  of intended or possible action — a task, a project, a research thread, a life
  intention. See **[THREAD-FORMAT.md](THREAD-FORMAT.md)**.
- **Claims** are `(left predicate right)` triples: `(thread:X owner owner:personal)`,
  `(thread:X depends_on thread:Y)`. Import turns each thread's frontmatter into
  claims, interning entities so a person/repo/tag referenced by many threads is
  *one* object (rename once, not in N files). The current state is a fold over the
  append-only log. **`export` is the faithful inverse** — the claim graph
  regenerates the markdown (verified claim-identical by `roundtrip_test.clj`), so
  files are a *view* of the claims, not a competing source of truth.
- The **coordinator** is a single-writer daemon: many agents query and assert
  concurrently over a socket; writes are serialized; and contradictory or
  rule-breaking writes — dependency cycles, dangling references, an `active`
  thread with no driver — are rejected at commit. An adversarial concurrency
  suite (`coord_test.clj`, C1–C9) checks this under load.

## Quickstart

Needs [babashka](https://babashka.org) (`bb`). The compiled code is committed, so
nothing else is required to run it.

```sh
bin/chelonia import      # threads/*.md  ->  claims.log
bin/chelonia ready       # actionable now
bin/chelonia blocked     # waiting, and on what
bin/chelonia leverage    # highest-impact threads to finish
bin/chelonia next        # ranked suggestion
bin/chelonia show <id>   # a thread's claims
bin/chelonia validate    # obligation / consistency check
bin/chelonia export DIR  # claims.log -> markdown (faithful inverse of import)
```

Try it on the bundled `threads/` examples, then point it at your own:

```sh
CHELONIA_THREADS=/path/to/your/threads bin/chelonia ready
```

Run the multi-agent daemon:

```sh
bin/chelonia-daemon 7977   # loads the log, serves agents on 127.0.0.1:7977
```

## Built on Beagle

The logic (kernel, fold, projections, import, CLI) is written in **Beagle** — a
typed Lisp that compiles to Clojure — and the host interop (files, sockets,
locking) is a thin Clojure runtime (`src/chelonia/rt.clj`). The compiled Clojure
lives in `out/` and runs on babashka, so **you don't need Beagle to run
Chelonia** — only to rebuild from the `.bclj` sources (`build.sh`).

A native binary (GraalVM, ~0.2s/command, scales to large corpora via an indexed
query layer) builds with `native/build.sh` (needs GraalVM CE + the Clojure CLI).

## Tests

```sh
bb -cp out coord_test.clj       # C1–C9 adversarial concurrency suite (9/9)
bb -cp out roundtrip_test.clj   # claims<->files round-trip is lossless
```

## License

[MIT](LICENSE).
