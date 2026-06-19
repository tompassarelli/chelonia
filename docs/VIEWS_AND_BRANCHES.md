# Views & Branches — conflict is the shadow of a cardinality axiom

**Status: forward-looking design note (the §5 companion to [WHY_FRAM_EXISTS.md](WHY_FRAM_EXISTS.md)).**
Unlike `WHY_FRAM_EXISTS.md`, which describes the *running* engine, this note describes the
*model the substrate is heading toward*. Nothing here is a task; nothing gets implemented from
it yet. It exists so the idea is preserved precisely and the present codebase can be measured
against it. Where it makes a claim about today's code, that claim is marked **[today]** and is
backed by the apple-sweep in §6.

---

## Verdict (one sentence)

On an append-only claim graph, **writes do not conflict** — a program is a coherent *traversal*
of the graph under a chosen view, divergent claims may coexist indefinitely, and the only thing
that ever forces two writers to disagree is a **cardinality axiom**. Identity (distinct things
have distinct ids) is the one cardinality axiom the substrate *must* assert; every other
"single value" is optional. So there are **no write-time conflicts, only read-time
path-selection obligations.**

---

## 1. The model

- **The graph is append-only.** Facts are immutable; the log only grows. (Proven today; see
  `WHY_FRAM_EXISTS.md` and the immutability analysis.)
- **An edit is a re-pointing, not a mutation.** To "change" a thing you assert new facts; the
  old facts are not erased. What is "current" is a *selection* over the facts, not a property
  stamped on them.
- **Divergent claims may coexist indefinitely.** Two assertions that a reader might consider
  rivals are, at the substrate level, just two facts. The substrate is not obligated to pick
  one. It can hold both, forever, without being wrong.
- **A program is a traversal under a view.** "What is the code" is the answer to a query —
  *which coherent set of claims do I select and walk?* — not a single privileged mutable state.
  Different views can select differently from the same graph.

## 2. No write-time conflicts — only read-time path-selection obligations

Because writes only ever *add* immutable facts, two concurrent writes can always both land. The
graph after `base + A + B` contains everything in `A` and everything in `B`; nothing was
overwritten, nothing destroyed. The "merge" that other systems force at write time does not
exist here as a *write* event. What remains is a **read-time** question: when a consumer
traverses the graph and reaches a point where two divergent claims both apply, *it* must select
which to follow. That selection is an obligation of the **reader**, deferred to **use-time**,
not a conflict resolved at write-time. (This is the precise antidote to the "but eventually you
must merge" objection: no — eventually some *consumer must choose a traversal*, which is a
different and smaller claim.)

## 3. Conflict is the shadow of a cardinality axiom

A genuine conflict — "these two facts cannot both stand" — only arises when something has
**declared** that a given `(subject, predicate)` may hold *one* value. That declaration is a
cardinality axiom (`single`). Absent the axiom, two values for `(subject, predicate)` are not a
conflict; they are a multi-valued fact with two members. So:

> **Every conflict is the shadow cast by a cardinality axiom.** Remove the axiom and the
> conflict dissolves into peaceful coexistence; add one and you have re-introduced exactly the
> write-time disagreement that append-only otherwise eliminates.

This reframes the whole concurrency-safety surface (see the immutability analysis): the conflict
sites are exactly the places a cardinality axiom has been asserted — and the engineering job is
to assert them *deliberately*, never *accidentally*.

## 4. Identity is the only forced axiom

There is exactly one cardinality axiom the substrate cannot avoid: **identity** — distinct
entities/values have distinct ids, and minting a new thing must not collide with another
writer's new thing. This is definitional (it is what "an id" *means*) and it is already
enforced (serialized name allocation; raced 16-thread/0-dup). Every *other* single-valued
predicate is an **optional** axiom the domain may choose. References, in particular, assert
**none**: on the mainline a reference is a spelling that resolves-or-fails-as-undefined, so a
"dangling reference" is not a substrate conflict — it is a reader's traversal arriving at an
undefined name **[today]** (measured: `cnf_gate_v2_read.clj`; refs are spelling, 0 authored
id-refs, 0 persisted derived claims).

## 5. Convergence is optional

Because divergent claims coexist without harm, *converging* them — collapsing two branches into
one selected line — is a **discretionary** act, motivated by hygiene, memory reclamation,
refactoring, or publication. It is **not** required for substrate correctness. The graph is
correct while divergent. Convergence is a garden-tending operation a human or tool *chooses* to
run, the way one chooses to squash a branch — not a debt the substrate forces you to pay.

## 6. Evidence from the code: imposed exclusivity is a read-side phenomenon

The apple-sweep (an audit for single-valuedness the code enforces that no axiom declared)
found a clean, directional result that *confirms* this model empirically:

- **The write side is disciplined.** Every supersession in the engine is gated on a declared
  cardinality axiom: `schema/replace!` fires only `(when (= "single" (cardinality …)))`
  (`assert!`/`link!`); `kernel/apply-assert`/`apply-retract` branch on `single?`;
  `cnf_coord/ensure-single-cardinality!` and `commit!` consult `ck/single?` / stored
  cardinality; an undeclared predicate defaults to `multi`. **No write imposes exclusivity it
  was not told to.**
- **The exclusivity that *is* un-axiomed lives entirely on the read side** — "take-first"
  selections with no cardinality check: `schema/lookup` = `(first (lookup-all …))`;
  `kernel/one` = `(:r (first hits))` (no `single?` guard); `resolve/pred-val` →
  `kind-of`/`sym-val` reading "the" kind/v of an AST node over **multi-valued** AST preds;
  `resolve/refers-target` taking the first `refers_to`. These are exactly **read-time
  path-selections**, made silently rather than as declared choices.

That is §2 made literal: the substrate has no write-time conflicts; the only place "pick one"
appears is at read-time, and where it appears un-axiomed it is a quiet first-wins rather than a
principled selection. The cleanup is not "add a guard" — it is to make each read-side selection
*honest*: either declare the cardinality it assumes, or select explicitly (and surface the
surprise when more than one value is present) instead of silently taking the first.

## 7. Relationship to the identity model and gate-v2

The dangling-reference *safety* hazard (gate-v2) is the shadow of a cardinality/identity axiom
references do not assert today and *would* assert in the identity-carrying model (stored
id-pointers). It is therefore correctly **deferred** to that model. When references become
stored identity, they will assert an existence axiom, a conflict shadow will appear, and a
guard will be owed — read at head, under the commit lock, never against a stale base (the
liveness-vs-`refers_to` distinction in the immutability analysis). Until then, the reference
case is path-selection, not conflict, and the substrate's safety story rests on the two axioms
it *does* assert: identity (closed) and whatever single-valued predicates a domain declares
(enforced at the write side, as §6 shows).
