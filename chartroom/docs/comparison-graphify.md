# Chartroom vs. derived-graph code tools (Graphify, CRG, …)

> **Derived-graph tools extract a graph from text and spend effort keeping it from
> going stale. Chartroom authors the graph and projects text — so staleness is not
> a concept.**

This is a positioning document, not a results document. It proves nothing new; it
contrasts what Chartroom already proved (each claim below links to the turtle and
commit that proved it) against the derived-graph category. The point is that the
real difference is sharp enough that we don't need to overstate it — and in one
important way (retrieval) we are flatly *not* the better tool.

> **Provenance & dating.** Graphify is a single-maintainer tool at roughly v0.8.x,
> moving weekly; its facts here reflect its behavior as characterized in mid-2026
> (this doc: 2026-06-17). Treat every "Graphify does X" as *verify against the
> current repo* — it may have changed. Our claims, by contrast, are pinned to
> commits.

---

## 1. Direction of truth

Graphify (and the category — CRG and friends) **derives** a graph from source text:
Tree-sitter parses, an LLM extracts semantic relations, and the result is a graph
you query. **Text is the source of truth; the graph is a downstream view.**

Chartroom **inverts** it: the **claim graph is the source of truth, and text is the
projected view**. This is not aspirational — it's the property we gated on in
**Turtle #3** (beagle `7cfe313`, `a2e85c5`; chartroom `4632213`):

- `claims → datum → idiomatic source → re-read` is identity on **97/97 files** of
  the gjoa corpus, and
- the program round-trips **through a persisted `fram.cnf` store** (helpers.bjs,
  3,858 claims) and reconstructs datum-identically.

Because text regenerates *from* the graph losslessly (semantically; comments and
layout are a view, not preserved — see RESULTS.md), the graph can *be* the source.
That is the line that makes the inversion real rather than a slogan.

## 2. Staleness is structural, not optimized-away

A derived graph perpetually chases the text it came from, so Graphify ships
**auto-update machinery** — hooks that re-extract when a human edits a file, when
git commits, when the agent itself writes code. That entire apparatus exists for
exactly one reason: **text is the source, so the graph is always one edit behind.**

Chartroom has **nothing to sync**. We do not re-derive the graph from text; we
**project text from the graph**. There is no second representation racing to catch
up, so staleness cannot occur.

Be precise about the verb: we did **not** make re-sync *faster*. We made it
**absent** — there is no re-sync, because there is no derived copy to reconcile.
This is the same structural property Unison gets from content-addressing and MPS
gets from projectional editing: the thing you edit and the thing you query are the
same thing.

## 3. Complete vs. selective — and we do **not** win at their job

Graphify's graph is deliberately **selective**: call edges, imports, inheritance,
community clusters — the skeleton you need to *navigate* and to retrieve code
token-cheaply (its pitch is on the order of ~70× token compression). **It is a
retrieval tool**, and a good one: it makes *reading* a large codebase cheaper for
an agent.

Chartroom's truth projection is **complete** — the whole reader-datum tree, types
included (**Turtle #3**) — because it has to *regenerate the program*, not just
locate things in it. That completeness has a price, and the honest consequence is
blunt:

> **As a grep/retrieval replacement, Chartroom's truth projection is absurd
> overkill** — ~238 triples per top-level form to answer "where is this called."
> We are **not** a better retrieval tool, and we should not pretend to be.

These are different categories, not competitors at one task:

- Graphify makes **reading** code cheaper (selective view over text-as-truth).
- Chartroom makes the graph **the thing you edit** (complete graph-as-truth).

(We *do* also keep a selective **query** projection — the Turtle #2 call-graph
overlay, ~18 triples/form, used for transitive-leverage queries: chartroom
`e0ee98e`. But even that is built for *relational* questions, not token-compression
retrieval. On Graphify's actual job — cheap retrieval over a big repo — Graphify is
the right tool and we are not. We claim no superiority there.)

## 4. The shared rock: identity vs. spelling

Both projects hit the **same** underlying problem from opposite directions.

Graphify reported a **"ghost duplicate"** bug: a symbol appeared twice in the
graph — once from AST extraction (carrying a source location) and once from
semantic/LLM extraction (without one) — because a node's **identity was
spelling-plus-provenance, not structure**. The fix was a build-time **merge** that
collapses the duplicates.

Chartroom's reader-datum projection had the *same shape* of weakness:
**occurrence-per-leaf** references, identified by spelling. Our fix is the
**`refers_to` resolution pass** (**Turtle #5**, chartroom `13064e9`): each
reference is resolved to the *binding it actually denotes* and carries that
identity — so two `red`s that mean different things are two nodes, and one `red`
referenced ten times is one binding referenced ten times.

Two approaches to one problem:

| | identity problem | fix | direction |
|---|---|---|---|
| **Graphify** | duplicate nodes for one symbol | build-time **merge** (collapse after the fact) | derive → patch |
| **Chartroom** | one node per occurrence of a spelling | **resolve** references to bindings up front | author → resolve |

Their bug report is effectively a **free test case for our resolver**: a correct
`refers_to` keeps *one* node where they needed a *merge*. We even ran the harder
version of it — **shadowing** (`test/shadow.bjs`): a `let`-bound `red` shadowing a
top-level `red`. Our resolver points each occurrence at the correct binding, so a
rename touches exactly one; `sed` (and any spelling-keyed graph) touches both.

---

## What we have *not* shown

This doc inherits the project's discipline: claims point at proof, and unproven
things are named, not implied.

- **Type *checking*.** Type-name references resolve (renaming a `defrecord` updates
  its `:- T` annotations), but no invariant refuses an ill-typed refactor. (Cross-module
  `:refer`/`:as`/`:rename`/re-export all resolve now — RESULTS.md, Turtle #5.)
- **Macro-introduced bindings** are invisible to the surface resolver (correct for
  surface refactoring; a through-macro refactor would need post-expansion resolution).
- **No type-aware refactor.** Types round-trip as opaque tokens (Turtle #3); nothing
  type-checks a refactor.
- **Binding-form coverage is partial** (map-destructuring, `for`/`doseq` modifiers).
- **Retrieval is not our category** (see §3) — we make no token-compression claim.
