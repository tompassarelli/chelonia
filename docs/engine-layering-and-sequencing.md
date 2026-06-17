# Engine layering & sequencing (north star)

Authoritative architecture + sequencing decisions for the graph-engine bet.
Supersedes the "eddy × Beagle are the same bet" synthesis — that conflated two
layers. Get the layering exactly right; everything else follows.

## The three layers — one engine, two customers (NOT "Beagle = eddy")

1. **Layer 1 — Beagle: a general-purpose language.** Code → runnable program.
   Opinions about *nothing* except being a language. It stays clean. **No graph
   machinery is bolted into the language.** parse → check → emit (clj/js/nix/odin)
   is the whole of it.

2. **Layer 2 — the graph engine (the actual product).** Fram (claim store +
   Datalog + coordinator) + the code→claims projection + the repair/reasoning
   machinery. A *separate thing that sits on top of Beagle and reasons about
   code.* It is **not** the language and should be pulled out of it (see
   Reconciliation — some of it currently lives in the beagle repo).

3. **Layer 3 — consumers.** Beagle's own tooling rents Layer 2 for code. **eddy is
   a second, different product** renting the *same* Layer 2 engine, pointed at apps
   instead of code. The relationship is **one engine, two customers** — not one
   product wearing two hats. Fix every place a plan says "same bet."

## Layer 2 has TWO co-equal pillars

Repair has been over-weighted; reasoning is just as important. Lead with both.

- **Repair** — change one node → the blast radius re-derives → fixes emit
  deterministically, *because it's a graph operation, not an LLM guessing*. Shipped
  + CI-gated for code (`beagle-cascade`, the `mod_a`/`mod_b` scope-correctness
  receipt).
- **Reasoning** — NL question → Datalog query → exact, scope-correct answer. **No
  grep, no reassembly, no reconstruction tax.** An agent reasoning over *files*
  rebuilds structure on every question (parse → who-calls-what → get scope wrong →
  retry). The dominant questions about any codebase or app — what calls this, what
  depends on this, what breaks if I change it, where does this value flow, what's
  unused — are *all relationship questions*, and over a graph a relationship
  question *natively IS a Datalog query*. CodeQL / Glean prove the value (tens of
  thousands of stars) but pay the reconstruction tax (parse → build → query → throw
  away → rebuild). **We don't: the graph is canonical, always there, incrementally
  maintained.** That's the moat for this pillar.

**Same engine, two intensities:** reasoning = query the graph, return the answer
(read). Repair = query the graph, then act on it (read → write), deterministically.
One asks, one acts. Not two systems.

## Three claims to downgrade (do not let them harden)

1. **"fram structurally forces you to do the right thing" — FALSE.** We own fram;
   caller-supplied frames are an afternoon's work. The isolation safety is
   **behavioral** (refuse to build the merge), not structural. Say it that way.
2. **"LLM never correctness-bearing" — overclaim → "never *referential-integrity*-
   bearing."** The coordinator rule-check guarantees referential integrity
   (references resolve, vocabulary is closed, arity matches). It does **not**
   guarantee the artifact does what the user *meant* — semantics still escapes to
   the human.
3. **move-2 emit: idempotent/fixpoint guarantees *determinism*, NOT *locality*.** A
   deterministic formatter that reflows still turns a one-node change into a
   file-wide diff. The contract is proven only when the formatter is also **local**
   (small change → small diff) **and handles comments**. (Already split out as
   separate gate properties in the live move-2 work — keep it that way.)

## Boundaries — unify the verbs, bound the nouns

One engine, one method, one toolbox — **many separate graphs.** Share machinery
across a boundary (good); never share data (the trap). Tools cross boundaries;
claims don't.

- **Trust/privacy is the liability axis, not an elegance one.** Personal life-graph
  (lodestar) vs client data (kea/MSA) vs public tooling. fram has **no access
  control**; isolation is process + log + network only. Co-mingling client data
  isn't messy, it's an incident. Discipline degrades under deadline → make it
  **structurally hard**: refuse two logs from different *trust domains* in one
  process. Everywhere else, separate-logs + "don't build the merge" is enough.
- **Cross-boundary reasoning** ("which life-thread is blocked on a code change in
  repo X") → a **read-only federation / union view** for queries while logs stay
  physically separate. That's a *tool* crossing, not data co-mingling — inside
  "tools cross, data doesn't." Carve-out: federation is fine across
  purpose/runtime/vocabulary but **must NOT span the client-trust boundary.**
- Decision rule: same reason to change → unify (kernel/method/machinery). Differ on
  any of {vocabulary, trust, runtime, purpose} → BOUND (separate log + process),
  OR-gated (one trip is enough). Default when unsure: BOUND (re-merging two logs is
  a script; un-merging a contaminated store may be a breach you can't un-ring).

## Sequencing — the most important part

**Do NOT open the full eddy build.** Beagle is at move 1. Moves 2–4 are unbuilt for
*code* — the cheaper, already-half-burned domain. Building eddy means constructing
move-2/3/4 for *apps* before they're proven on *code*. The shared kernel makes eddy
*feel* like less work than it is; unifying the substrate does not shrink the product
surface. Same discipline at the project level as at the tool level: **finish the
lane before opening the next front.**

The 4 moves (Beagle/code is the spine; eddy layers on each, later):

1. **Cascade-on-graph** — ✅ shipped, CI-gated (`beagle-cascade`).
2. **Byte-stable emit** — IN PROGRESS. Gate = idempotent fixed-point **+ locality
   + comment-preserving**. *This is the gate the whole sequence waits on.*
3. **Flip to claim-canonical** — claims = source of truth, text = lowering
   (lodestar already lives here for threads).
4. **Dictate-to-claims** — NL → validated claims → artifact falls out (the AI-era
   moat). Authoring leads renderer for eddy (higher leverage, lower research risk).

### The eddy unification spike (run this, build nothing else)

Take this session's `crm-v2` claims, run Beagle's already-shipped reaches-closure:
*"if `contact.company` changes, what's the app blast radius?"* Receipt must
**include the genuinely-dependent nodes and EXCLUDE a planted same-named decoy** —
the app-level mirror of the `mod_a`/`mod_b` scope-correctness receipt.

- Kill-criteria, in order: (1) decoy leaks in → kill unification, eddy stays
  standalone. (2) closure wrong on real deps → fix the claim schema first.
  (3) >10% of real kea views are recursive → re-weight toward server-fixpoint.
  (4) move-2 fixpoint won't hold for eddy's emitters → eddy stays text-canonical,
  gets reasoning but not authoring (the true gate).
- A **passing** spike answers *whether* unification is real. It does **NOT**
  authorize building eddy. "When" = **after Beagle's lane is finished and move-2 is
  proven on code once.** Report the spike result and stop there.
- Owner: the spike consumes eddy's `crm-v2` claims (eddy agent's artifact) + the
  shipped Fram reaches-closure (this repo). Coordinate; do not let it slide into an
  eddy build.

## Open decisions (the user's calls to make)

1. **Frame question** — bounded graphs as separate-logs (today's reality, most
   Hickey-correct; recommended) vs in-engine frames (unstarted, reintroduces the
   merge danger). Recommendation: separate-logs.
2. **Cross-boundary reasoning** — resolved direction: read-only federation/union
   view, never across client-trust. Confirm scope before it justifies an in-engine
   frame.
3. **Rename/identity engine home** — upstream into Beagle (like the call graph was)
   vs owned by Chartroom; and whether a shared resolve/rename/identity layer should
   be factored out for code (chartroom) + apps (eddy), or kept as parallel proofs
   on the one kernel.

## Reconciliation with shipped work (2026-06-18)

- Move 1 (cascade-on-graph) + the repair-pipeline kills (`beagle-callgraph`,
  graph-native `beagle-cascade`, structured semantic suspicions in `beagle-repair`,
  `blame.rkt` JSON emission) are **Layer-2 machinery currently HOUSED in the beagle
  repo** (`bin/`, `beagle-lib/private/blame.rkt`) and in the `claims` backend
  (`emit-claims.rkt`). Per the layering, their eventual home is a dedicated Layer-2
  package, **not** the language. This is a **queued refactor — not now**: it doesn't
  block moves 2–4 and must not preempt them. The Beagle *language core*
  (parse/check/emit-clj/js/nix) was not touched and stays clean.
- Current work = **move 2 (byte-stable emit)**, which is exactly the gate the whole
  sequence waits on. The locality+comments correction is already encoded as
  first-class gate properties. **Continue it.**
- The latent-bug ledger (`beagle/docs/text-as-source-latent-bugs.md`) is the
  evidence stream for the reasoning/repair thesis — keep logging.

## Layer-2 boundary — verified (2026-06-18)

The layering **principle** (Beagle the language stays clean; the graph engine is
separate) is **satisfied and verified**, distinct from the physical repo question:

- **Language core is clean.** parse / check / emit-clj / emit-js / emit-nix / types
  / ast carry **zero** graph-engine machinery — the only `claim` mentions are
  tombstone comments for the *removed* `(claim …)`/`:` surface form. `emit-claims.rkt`
  is the `'claims` emit *target* (a backend, like emit-js); `claims-roundtrip.rkt` is
  source round-trip + the byte-stable formatter (a language/formatting concern). So
  no graph machinery is bolted into the language.
- **The code-intelligence ENGINE is Layer 2 and lives in chartroom:** the `refers_to`
  lexical resolver + scope-correct rename (`resolve.clj`), the namespace-correct call
  graph (`chartroom.clj`). Beagle's gates *rent* it (the scope-correct-rename gate
  drives chartroom's resolver) — a consumer using the engine, not the language owning
  it. That's the layering working as intended.
- **The repair/reasoning TOOLING in `beagle/bin`** (cascade, callgraph, the
  code-as-claims gates) is separate scripts that depend on Fram — not in the compiler.
  A *physical* relocation to a dedicated repo is **org churn the principle does not
  require** (the language is already clean) and would risk breaking beagle's dev loop
  + CI gates + `beagle-repair` refs + raco links for marginal benefit. So the honest
  call: the boundary is clean and verified; a repo-split is a separate, deliberate
  migration to scope on its own merits, not a cleanliness fix.
