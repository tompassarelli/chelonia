# Views & Branches — conflict is the shadow of a cardinality axiom

**Status: forward-looking design note (the §5 companion to [WHY_FRAM_EXISTS.md](WHY_FRAM_EXISTS.md)).**
Unlike `WHY_FRAM_EXISTS.md`, which describes the *running* engine, this note describes the
*model the substrate is heading toward*. Nothing here is a task; nothing gets implemented from
it yet. It exists so the idea is preserved precisely and the present codebase can be measured
against it. Where it makes a claim about today's code, that claim is marked **[today]** and is
backed by the apple-sweep in §6.

---

## 0. Vocabulary (the substrate stores *claims*)

Use these words precisely — they are what make the rest of this note unambiguous:

- **Assertion** — the *operation/event* of asserting (`do-assert` / `commit!`). An act, not a stored thing.
- **Claim** — the durable, immutable, **addressable object** an assertion mints (a `cid`). **This is the
  substrate atom:** a claim can be referenced, owned, selected, superseded, disputed, and cited. *The graph
  stores claims* — not assertions (acts vanish) and not facts (facts are view-relative, below).
- **Triple** — a claim's `(l p r)` payload.
- **Fact** — a claim *selected/accepted as true inside a view*. "Fact" is therefore always relative to a view;
  the substrate has no view-free facts.
- **View** — a selection predicate over claims (§8). `main` is the privileged default view.

> **The graph is an ocean of claims, not facts.** A program/view *selects* claims and treats them as facts.
> Assertions are operations that mint claims; provenance lives on the claim — or on claims-about-claims, e.g.
> `(C123 asserted-by agent-7)`, `(view-main selects C123)`. (Today provenance is recorded at transaction
> granularity — the claim's tx carries who/when — with per-claim `asserted-by` available as the CNF capability.)
>
> ⚠️ **"Fact" here means exactly one of the two precise senses above (Datalog ground tuple, or
> a claim accepted-true in a view) — never a loose synonym for "claim."** Canonical rule:
> [README → Terminology](../README.md).

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

- **The graph is append-only.** Claims are immutable; the log only grows. (Proven today; see
  `WHY_FRAM_EXISTS.md` and the immutability analysis.)
- **An edit is a re-pointing, not a mutation.** To "change" a thing you assert new claims; the
  old claims are not erased. What is "current" is a *selection* over the claims (i.e. which
  claims are facts in this view), not a property stamped on them.
- **Divergent claims may coexist indefinitely.** Two rival writes are, at the substrate level,
  just two claims. The substrate is not obligated to pick one (that is a view's job — selecting a
  claim makes it a fact). It can hold both, forever, without being wrong.
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

## 4. Identity allocation is the only *substrate-global* forced convergence

There is exactly one convergence the substrate cannot avoid: **identity allocation** — distinct
entities/values have distinct ids, and minting a new thing must not collide with another
writer's new thing. This is definitional (it is what "an id" *means*), it is **substrate-global**
(every view shares one id space), and it is already enforced (serialized name allocation; raced
16-thread/0-dup). **Precision (matters later, does not block anything now):** identity allocation
is the only convergence forced *at the substrate*. Every *other* single-valued predicate may still
exist as an axiom, but it is **view-local / declared-single** — a cardinality a domain (or a view)
chooses, enforced for that view, *not* a substrate-global obligation. So "single-valued" is not one
thing: identity is global and forced; declared-single predicates are local and optional. References, in particular, assert
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

**Raw `:retract` of a referenced binding — INTENDED view-local incoherence, not "deferred":**
distinguish two surfaces. The `:edit-min` *verb* surface is guarded (rename rejects; set-body/upsert
are name-stable/name-keyed — §6, and verb dispatch is exhaustively `{set-body, upsert-form, rename}`).
The lower-level raw `:assert`/`:retract` op surface is **not** guarded and does **not** reject: a raw
`:retract` that drops a referenced binding's claim **commits**, and `main` becomes *view-locally
incoherent* (its references re-derive to an undefined name) until someone cold-renders/recompiles main.
There is no global gate forcing that recompile (the convergence gate was deliberately not built), so the
incoherence is **latent until observed**. This is **intended** under §2 (no write-time conflicts), §4
(dangling = view-local, not corruption), and §5 (convergence is a per-publication choice) — it is an
explicit author op leaving a view-local incoherence, **not** substrate corruption and **not** a guarded
"deferred" path. (Contrast: the *rename verb* is genuinely deferred — it rejects. `:retract` is intended,
not deferred. The word matters: a guarded path is deferred; an unguarded-by-design path is intended.)
So: someone who finds `main` un-resolvable after a raw `:retract` should read this as designed behavior,
not file a corruption bug.

## 8. Verified findings + the minimal "view" representation

A 4-perspective adversarial check (steelman each invariant, code-ground, hunt failure modes; each
load-bearing claim then adversarially verified) settled the empirics:

- **The substrate is already view-capable; single-head lives entirely in the read/resolve layer.**
  *Verified true:* the append-only log holds divergent claims (multi-valued AST preds; supersession
  is an appended claim, not a delete). "Current" is **one global subtraction** — `live? = (not
  (superseded? cid))` over a single `:superseded` set (`cnf.bclj:115`), with **no second selector
  anywhere** in the read layer. So global-head is **not a designed invariant** — it is the only
  shape single-storage can represent, materialized in the read layer (`live?`, one `current-seq`,
  one warm cache, the take-firsts).
- **Global-head is not even an *enforced* invariant.** *Verified:* there is **no in-commit
  whole-graph resolution gate** in the running engine. "The committed corpus resolves" is emergent
  from single-storage, not checked. (So there is nothing to *remove* — only a gate to *not add*.)
- **`*corpus-scope*` / `resolve-modules!` is NOT a proto-view.** *Verified (a steelman claim to the
  contrary was refuted):* it is a **performance scope**, proven set-**equal** to the whole-corpus
  walk (sym-diff 0) — it selects the *same* answer faster, never a *different* answer. It is the
  **seam** a view system would reuse, not a view.
- **The forced axiom is identity, and view-relative does not escape it** — name allocation is
  globally serialized (the one genuinely-global thing), and that is correct and closed.

**The build form everyone converged on (including the global-head defender): one head + named
overlays.** Keep one head materialized efficiently — *that is `main`, the privileged default
view*. Deliver divergence as **named selection overlays layered above the one head**, materialized
on demand and GC'd by dropping the name. This gives exploratory divergence **without** paying N×
the warm-`refers_to` materialization for views nobody is reading (the real implementation cost the
failure-mode pass surfaced). Convergence is then a **policy on a chosen view** (`main`, the build
view), not a substrate axiom — git's branch/main social layer, recovered as policy.

**The minimal "view" in CNF terms** = a **selection predicate over claims**, generalizing the one
selector that already exists: replace the global `live?` with `select? : view → cid → Bool`. The
engine today is the special case *view = the global superseded-fold*. Three CNF-native encodings,
cheapest first:
1. **Per-view superseded-set** — a view chooses which `supersedes` claims to honor. Cheapest:
   supersession is already append-only data.
2. **Root + reachability** — a view = a root claim plus its coherent closure. Matches "a program is
   a traversal under a view"; reuses the renderer's existing reachability filter.
3. **View-as-claim** — `(view selects @claim)` triples; views become first-class subjects in the
   same graph (the exact pattern tern threads/topics/`@ui` already use). Most CNF-native — no
   new atom, consistent with `WHY_FRAM_EXISTS` ("a view is just more claims about which claims count").

**The read-side take-firsts (§6) are the attach points.** Each `(first …)` becomes
`(select-for-view view …)`. So the apple-sweep cleanup and the view machinery are the *same work
from two ends* — which is why neither is urgent today (one view = `main` = first-wins is correct),
and both wait until a second resolving view actually earns its keep. **Nothing here is built yet;
this section records the verified design target and the next decision (which encoding), not a task.**
