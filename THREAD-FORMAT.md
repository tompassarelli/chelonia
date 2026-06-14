# Thread format

A **thread** is one Markdown file in `threads/`: YAML frontmatter, then a prose
body. The filename is `<id>-<snake_name>.md`; the `id` field is the canonical
identity (the filename is just for navigation).

A thread is any unit of intended or possible action — task-sized, project-sized,
a research thread, a life intention, or a container for other threads. A
"project" isn't a separate type; it's just a thread with children (via `part_of`).

## Frontmatter

**Required:** `id`, `title`, `state`, `owner`, `lead`, `driver`, `source`,
`proposed_by`, `created_by`, `created_at`, `updated_at`.
**Optional:** `do_on`, `valid_until`, `estimate_hours`, `repo`, `part_of`,
`depends_on`, `tags`.

| field | meaning |
|---|---|
| `id` | 14-digit timestamp `YYYYMMDDHHMMSS` — collision-safe identity, never changes |
| `title` | human-readable title |
| `state` | `draft` \| `ready` \| `active` \| `done` \| `canceled` (the only validated field) |
| `owner` | the entity the thread serves (e.g. `personal`, `work`, a client) |
| `lead` | person accountable for it landing |
| `driver` | who/what is currently pushing it (a person, or an agent handle) |
| `source` | where it originated (`self`, `ai`, …) |
| `proposed_by` | who conceived it (person/agent handle, or a list) |
| `do_on` | ISO date you intend to act — feeds deadline urgency in `next` |
| `valid_until` | ISO date; thread is *expired* if past and not done/canceled |
| `depends_on` | list of thread ids; a thread is *blocked* until all are done/canceled |
| `part_of` | parent thread id — composition ("a project is a thread with children") |
| `tags` | freeform grouping |

`depends_on` and `part_of` must both be acyclic.

## Example

```markdown
---
id: 20260101090500
title: Deploy the site to production
state: ready
owner: personal
lead: you
driver: you
source: self
proposed_by: you
created_by: you
created_at: 2026-01-01
updated_at: 2026-01-01
part_of: 20260101090000
depends_on:
  - 20260101090200
  - 20260101090400
tags:
  - web
  - infra
---

## Claim

Ship the finished site behind the custom domain over HTTPS.

## Why this matters

It's the last step of the launch project; everything else is in service of this.

## Log

2026-01-01 — created.
```

## How it becomes claims

`import` turns frontmatter into `(left predicate right)` claims, minting interned
entities (`thread:`, `person:`, `owner:`, `tag:`, `repo:`) so a thing referenced
by many threads is one object — rename it once, not in N files:

```
(thread:20260101090500  state        ready)
(thread:20260101090500  owner        owner:personal)
(thread:20260101090500  depends_on   thread:20260101090200)
(thread:20260101090500  depends_on   thread:20260101090400)
(thread:20260101090500  part_of      thread:20260101090000)
(thread:20260101090500  tag          tag:web)
...
```

`state` is the only validated field (must be one of the five). Everything else is
free-form — the *value model* (how you weigh threads) is yours to define on top.
