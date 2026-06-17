# Thread format

A **thread** is one Markdown file in `threads/`: a header of claim triples, a
`---` separator, then a free Markdown body. The filename is
`<id>-<snake_name>.md`; the first line, `@<id>`, is the canonical identity (the
filename is just for navigation).

To the engine there is no "thread" type — a thread is simply an entity id that
has a `title` claim. A "project" isn't a separate type either; it's just a thread
other threads point at with `part_of`. `import` folds these files into the claim
graph; `export` regenerates them from the graph, **claim-identically**
(`roundtrip_test.clj`), so the files are a *view*, not a competing source of
truth.

## File shape

```markdown
@2026-01-01-090500
title  "Deploy the site to production"
owner  personal
lead  @you
source  self
created_by  @you
created_at  2026-01-01
updated_at  2026-01-01
committed  2026-01-01
part_of  @2026-01-01-090000
depends_on  @2026-01-01-090200
depends_on  @2026-01-01-090400
relates_to  @topic-web
relates_to  @topic-infra
---

## Claim

Ship the finished site behind the custom domain over HTTPS.

## Log

2026-01-01 — created.
```

- **`@<id>`** — first line, the entity. ids are `YYYY-MM-DD-HHMMSS` (collision-safe,
  never change); named entities like `@topic-web` are also valid ids.
- **`predicate  object`** — one claim per line. The **object** is either a ref
  (`@some-id`, an edge to another entity) or an **EDN literal** (`"quoted strings"`,
  bare symbols like `personal`, numbers, dates). Entities referenced by `@` are
  **interned** — rename a person/topic/repo *once*, not in N files.
- **Multi-valued predicates repeat** — `depends_on` and `relates_to` above each
  appear twice. Single-valued predicates (e.g. `title`, `owner`) appear once;
  the cardinality vocabulary is configurable via `FRAM_SINGLE_VALUED`.
- **`---`** — separates the claim header from the prose body. The body is stored
  as the `body` claim and round-trips verbatim.

## Lifecycle is *derived*, never stored

There is **no `state` field**. Lifecycle is read from the facts:

| condition | how it's derived |
|---|---|
| committed (accepted / in play) | has a `committed` claim |
| done (terminal) | has an `outcome` claim |
| abandoned (terminal) | has an `abandoned` claim |
| active now | has a `driver` (a person/agent currently pushing it) |
| blocked | a `depends_on` target that isn't terminal |

These lifecycle predicates (`committed` / `outcome` / `abandoned` / `driver`) are
the vocabulary the engine currently ships as defaults — a consumer can supply its
own. (Fram is an extracted engine; this lifecycle vocabulary is one of the
remaining domain defaults being genericized — see the README.)

## Common predicates

| predicate | meaning |
|---|---|
| `title` | human-readable title — what makes an id a thread |
| `owner` | the entity the thread serves (`personal`, `work`, a client) |
| `lead` | who is accountable for it landing |
| `driver` | who/what is currently pushing it (a person, or an agent handle) |
| `source` | where it originated (`self`, `ai`, …) |
| `proposed_by` / `created_by` | who conceived / first recorded it |
| `created_at` / `updated_at` | ISO dates |
| `committed` | ISO date it was accepted into play |
| `outcome` / `abandoned` | terminal: completed, or canceled (value is a note) |
| `do_on` | ISO date you intend to act (feeds deadline urgency in a consumer) |
| `valid_until` | ISO date; expired if past and not terminal |
| `estimate_hours` | numeric estimate |
| `part_of` | parent thread id — composition ("a project is a thread with children") |
| `depends_on` | thread id this is blocked on until that target is terminal |
| `relates_to` | a non-blocking association (e.g. a `@topic-*` thread) |

`depends_on` and `part_of` must both be **acyclic**, and every `@`-ref must
resolve — `fram validate` rejects cycles and dangling refs.

## How it becomes claims

`import` turns each line into a `(left predicate right)` claim, minting interned
entities so a thing referenced by many threads is one object:

```
(@2026-01-01-090500  title       "Deploy the site to production")
(@2026-01-01-090500  owner       personal)
(@2026-01-01-090500  depends_on  @2026-01-01-090200)
(@2026-01-01-090500  depends_on  @2026-01-01-090400)
(@2026-01-01-090500  part_of     @2026-01-01-090000)
(@2026-01-01-090500  relates_to  @topic-web)
...
```

The engine validates structure (cardinality, acyclicity, no dangling refs). The
*value model* — how a consumer weighs and ranks threads — is defined on top.
