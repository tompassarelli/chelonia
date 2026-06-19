# Why Fram Exists

**Status:** Load-bearing rationale. Referenced by the Fram README.
**Revisit policy:** This document may only be retired by *refuting the argument in its own terms* (see [Decision Record](#decision-record)). It may not be retired because a faster store appeared, because a benchmark regressed, or because re-using an existing database "seems simpler." Those objections are pre-answered below.

---

## Verdict (one sentence)

Fram exists because the unit of Claim Normal Form is the **claim-object** — a fact that is itself an addressable, reifiable object — and no datom store, triple store, or graph database takes the claim-object as its atom; building CNF on top of one means emulating CNF's atom on a foreign atom, which is strictly worse than owning the engine.

The necessity is about the **primitive**, not about performance, concurrency, or query power. On every axis people *expect* to justify a custom code-store, an off-the-shelf store wins or ties. The justification is the atom, and only the atom.

---

## The question this settles

The recurring, reasonable doubt — the one that will resurface every time someone new reads this code — is:

> "This is an EAV store with a Datalog-ish query layer and an MVCC-ish write path. That's Datomic. That's Datahike. Why was any of it built instead of imported?"

This document answers that airtight. It does so the only way an airtight answer can be written: by first **granting** that the doubt is correct on every axis except one, and then showing that the one remaining axis is dispositive.

---

## 1. What CNF actually is (the primitive)

CNF is built on a single primitive and one fact-shape.

```
Object  =  addressable identity                       — the sole primitive
Entity  =  object                          (entity!)  — bare identity, nothing more
Value   =  object + literal                (value!)   — interned, canonical (one identity per literal)
Claim   =  object + (l p r)                (claim!)   — a fact; itself an object
```

Two properties follow, and together they *are* CNF:

1. **Every slot is an object.** In a claim `(l p r)`, each of `l`, `p`, `r` is one of {entity, value, claim}, drawn from the *same* universe of objects. No slot is privileged. `p` is not a schema-registered, typed attribute — it is an object, identical in kind to `l` and `r`. The triple is **symmetric**.

2. **The claim is itself an object** — so it has identity, so it can appear in the `l`, `p`, or `r` slot of *another* claim. **Reification is the default, not a bolt-on.**

Property (2) is the load-bearing one. Because a claim is an object, every piece of metadata that other systems treat as privileged machinery is, in CNF, *just another claim about a claim*:

| Concern | In CNF it is simply… |
|---|---|
| Supersession | `(claim₁ supersedes claim₀)` |
| Provenance | `(claim₁ proposed-by agent₅)` |
| Transaction membership | `(claim₁ in-tx tx₇)` |
| Justification | `(claim₁ because claim₃)` |
| Naming | a claim named like any other object |

**The unit of CNF — the thing stored, addressed, indexed, and superseded — is therefore the claim-object: a fact that can itself be the subject of facts, at the granularity of the individual fact.** Hold onto that phrase. The entire argument is whether a vendor store can take *that* as its atom.

---

## 2. The core argument: the datom is the wrong atom

Datomic's atom is the **datom**:

```
[E  A  V  Tx  Op]
 │  │  │  │   └─ assert / retract
 │  │  │  └───── transaction entity that added this datom
 │  │  └──────── value
 │  └─────────── attribute
 └────────────── entity
```

Three structural facts about the datom make it the wrong atom for CNF — not slower, *wrong*:

**(a) The datom is not an object you can reference.**
Entities are addressable; datoms are not. There is no datom-id you can place in the `E` slot of another datom. The finest granularity at which Datomic permits reification is the **transaction**: transaction entities are real entities and *can* carry attributes (this is how transaction metadata works). But you cannot make an assertion about an *individual fact*. CNF requires exactly that — `(claim₁ supersedes claim₀)` is a statement about two specific facts. Datomic's reification granularity is the transaction (coarse); CNF's is the claim (fine). The datom cannot express per-fact reification because the fact is not addressable.

**(b) The attribute slot is privileged.**
Attribute value types and cardinalities must be declared before an attribute is used; an undeclared attribute is rejected by the transactor. `E` and `V` carry no such obligation. The three slots are not peers — `A` is a typed, pre-registered, cardinality-bearing slot the others are not. CNF's `(l p r)` is symmetric by definition; `p` is a plain object. This is a category difference in what a slot *is*.

**(c) The datom hard-codes the two reifications Datomic cannot live without.**
`Tx` and `Op` exist precisely because Datomic needs *some* reification (for time, and for assert/retract) but has no general claim-as-object mechanism. So it welds the two it requires directly into the struct layout and offers no general facility for the rest. CNF welds nothing on: time, retraction, provenance, and justification are all just claims about claims, because the claim is an object. Datomic reifies **twice, by force, in the layout**. CNF reifies **without bound, by default, because of the primitive.**

Datomic's atom is *the typed property of an entity*. CNF's atom is *the reifiable proposition*. These are different primitives. A store built on the first cannot natively hold the second.

---

## 3. Why "just use Datomic" collapses into building Fram anyway

The argument above is not "it is impossible to put CNF on Datomic." It is **possible**, and naming exactly what that possibility costs is what makes this document airtight.

To represent a claim-object on Datomic, you model each claim as **its own entity**, with attributes `:claim/l`, `:claim/p`, `:claim/r`. Claims-about-claims then point at that claim-entity. This works. And the moment you do it:

1. **You have abandoned the datom as your unit.** Your real atom (the claim-object) is now *emulated* as a 3-attribute entity. The datom is no longer the thing you reason about; it is plumbing beneath an emulation layer.

2. **You bypass everything Datomic is good at.** EAVT/AEVT/AVET indexing, entity-projection (the `E → {A:V}` map view), the datom-as-atom model — all of it now indexes the *emulation*, not your model. You are paying, in full, for machinery you are using sideways.

3. **You re-implement your real engine on top.** Claim addressing, reification semantics, supersession, and per-claim provenance are not served by Datomic — you build them above it, against an atom that fights you. Datomic degrades to a vestigial byte-bucket underneath the engine you actually needed.

This is the decisive point: **trying to avoid building Fram by adopting a datom store does not avoid building Fram. It builds Fram anyway — as an emulation layer over a foreign atom you then bypass.** The necessity is not logical impossibility; it is architectural inevitability. Either way you build the claim-object engine. The only choice is whether it sits on a primitive that fits (Fram) or one that doesn't (Datomic, plus a vestige).

Datahike inherits Datomic's model and the same verdict applies without modification.

---

## 4. The closest near-miss: RDF, and why it is still the wrong shape

RDF deserves explicit treatment because it is the *closest* existing thing, and a future reader who knows RDF will raise it.

RDF is genuinely closer than Datomic on one axis: an RDF triple is `(subject predicate object)` where the predicate is a first-class **resource**, not a pre-typed schema attribute. That is nearer to CNF's symmetric `(l p r)` than the datom is.

But RDF treats reification as **exceptional**, where CNF treats it as the **ground state** — and that difference is the whole game:

- **Classic RDF reification** (`rdf:Statement` with `rdf:subject` / `rdf:predicate` / `rdf:object`) is the notorious "reification quad": to make one statement addressable you emit four extra triples describing it. It is verbose, semantically awkward, and universally understood as a bolt-on.
- **Named graphs** and the later **RDF-star** effort (embedding a triple as the subject of another) are precisely *retrofits* of the claim-as-subject property onto a model that did not have it natively.

That the RDF world keeps building toward "a statement can be a subject" is positive evidence that the property is real and load-bearing — it is the same property CNF is built on. But a retrofit pays the bolt-on tax in perpetuity: reification is the special case, addressing a single statement is the verbose path, and the data model's center of gravity is the un-reified triple. CNF inverts this. The claim is an object **first**; addressing it, superseding it, and annotating it are the *default* operations, not the exceptional ones. Adopting an RDF store would mean building CNF's default on top of RDF's exception — the same emulation tax as §3, in a different dialect.

---

## 5. What was NOT the reason (the negative space)

This section exists so that no future maintainer can resurrect a *wrong* justification for Fram, conclude it is weak, and use that to discredit the engine. Each item below is an axis where an off-the-shelf store ties or beats us. **None of them is why Fram exists.**

- **Concurrency was not the reason.** CNF's write model — parallel lock-free compute (clone, resolve, harvest, serialized id allocation), then a brief serialized commit with per-`(te,p)` optimistic concurrency — **converges on Datomic's single-writer architecture.** Datomic has been single-writer-with-parallel-readers since 2012, by deliberate design, because a correct implementation of perception requires no coordination. We re-derived this model and validated it; we did not transcend it. Anyone who says "Datomic already does parallel-compute / serial-commit for code edits" is **right** — and it was never the reason to build Fram.

- **Graph / Datalog queries were not the reason.** "Find all callers," "all references to X," reachability, type-reference traversal — these are exactly what datom stores and triple stores are *best* at. If query power were the only requirement, the correct answer would be Datahike, not Fram.

- **Schema-as-data was not a differentiator.** It is tempting to claim CNF is special because vocabulary is data. It is not special on this axis: in Datomic, **attributes are themselves entities**, and schema is asserted with the same transaction mechanism as application data. Datomic already collapses the meta-level into the object-level for schema. Do not rebuild the engine on this argument; it is false.

- **Predicate typing was a *cost we accepted*, not a *win we earned*.** Datomic's mandatory per-attribute cardinality is genuinely *useful*: single-valued cardinality is what lets a store know that two writes to the same `(entity, attribute)` conflict — it is infrastructure for uniqueness and conflict detection. By making `p` a plain object with no mandatory cardinality, CNF **gives that up** and must recover single-valued semantics by other means in the write path. This is a real tradeoff against us, stated plainly. It is not evidence for Fram; it is a debt Fram has to service.

The point of enumerating these is that the argument for Fram **survives every concession.** Strip away concurrency, queries, schema-as-data, and the cardinality convenience — hand all of them to Datomic — and the claim-object primitive *still* cannot be expressed on the datom without emulation. That residue is the entire reason, and it is load-bearing precisely because nothing else is propping it up.

---

## 6. What Fram therefore is

> **Fram is the storage-and-indexing engine whose native atom is the claim-object** — a fact that is itself an addressable, reifiable object, annotatable at the granularity of the individual fact.

Everything else about Fram — the OCC write path, the index layout, the scoped invalidation keyed on binding-set-delta, the resolver coupling — is *implementation in service of that atom*. The concurrency model is borrowed (correctly) from Datomic. The query model is conventional Datalog. The reason the engine is ours, and had to be, is the unit it stores. The atom is not the datom. CNF's atom has no vendor.

---

## Decision Record

**Decision:** Build a custom claim engine (Fram) whose atomic unit is the reifiable claim-object. Do not adopt Datomic, Datahike, or an RDF store as the substrate.

**This decision is correct as long as both of the following hold. To overturn it, refute one of them in its own terms — not with a benchmark, a "simpler" appeal, or a new vendor that is fast:**

1. **CNF requires per-claim reification.** The claim is an object; metadata (supersession, provenance, transaction membership, justification) is expressed as claims about individual claims, at the granularity of the individual fact. *If this is ever false — if CNF can be redefined so that reification is needed only at transaction granularity or coarser — re-evaluate, because the datom becomes admissible.*

2. **No available store takes the reifiable claim-object as its native atom.** Datom stores reify at transaction granularity and privilege a typed attribute slot; RDF stores treat statement-level reification as a bolt-on/retrofit. *If a store ever ships whose native, non-emulated atom is the per-fact-reifiable proposition, re-evaluate, because adoption may then beat ownership.*

Absent a refutation of (1) or (2), Fram is not incidental, not a reinvented database, and not optional. It is the only store shape that holds CNF's primitive, and the primitive is the point.
