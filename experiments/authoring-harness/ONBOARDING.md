# Onboarding — the Fram concurrent-authoring experiment (read this first)

Audience: a fresh agent joining this work. Goal: one file that gets you fully oriented — the thesis, the
systems, the experiment, where we are, the working discipline, the constraints, and where to read more.
Companion docs in this directory: `STATUS.md` (current state + open decisions), `CURVE-PREREGISTRATION.md`,
`CURVE-RESULTS.md`, `S2-RESULTS.md`, `S3-RESULTS.md`, `BYTE-IDENTICAL-MEASUREMENT.md`.

---

## 1. The one-breath version
Fram is a **claim engine**: code (and todos, and anything) is stored as a graph of triples
(subject predicate object) interned to stable identities, not as text files. The bet — the **addressing
thesis** — is that addressing code by **identity** (what a thing *is*) beats addressing it by **position**
(name + line in a text file) for the AI era, where many agents refactor one codebase. We are running a
controlled experiment to measure that, for a RacketCon talk. This directory is that experiment.

## 2. The thesis (what we're proving and why it matters)
**Positional addressing → identity addressing — the tape→RAM transition for code, with a faithful text
projection.** Text addresses a definition by its spelling and location; both shift when you edit, so a
rename means *finding every scattered spelling* and a tool that reads the lowered/compiled form is blind
to references that didn't survive lowering. A graph addresses a definition by a stable node identity;
references carry `refers_to <node-id>` (identity, not spelling), so a rename is **one edit to one node**
and every reference re-points for free.

The physics makes a falsifiable prediction about *where* this matters:
- **Local ops TIE** (append / insert a new def): text-position and structure-position coincide, so
  positional addressing is already adequate. We concede these up front.
- **Relationship ops SEPARATE** (rename of a referenced def; coupled mutual refs): text must reconstruct
  scattered references; identity touches one edge. The discriminator metric is **reconstruction cost** —
  the price text pays to recover what serialization threw away.

Two pillars (same property, two levels): **machine level** = reconstruction cost (the physics);
**agent level** = CODESTRUCT (2025, external evidence) — LLMs zero-shot drove a structured AST action
space over *named* entities and beat text/diff, because scope-grounding (not surface familiarity) aligns
with LLM reasoning. So identity addressing is both the better substrate and the better agent interface.

DISCIPLINE: the talk is the **addressing frame + the tie/separate measurement** (now). The "code as a
physics field" dissertation is *later*. Don't let the dissertation eat the talk. Full brief:
`private-docs/ADDRESSING_THESIS.md`.

## 3. The systems (and how they relate)
- **fram** (`~/code/fram`) — the CNF **claim engine**. Every fact is a triple interned to a claim-object
  with a stable id. Canonical append-only log; a JVM **coordinator daemon** (`bin/fram-daemon`,
  `cnf_coord_daemon.clj`) serializes writes + serves warm reads. The CLI + MCP are babashka.
- **The flip** — for code, the `.bclj` text file is a **rendered VIEW** of the canonical
  `.fram/code.log` (the graph). Edits **source from the graph** (`bin/fram-edit-code` → the daemon's
  `:edit-min` wire op), not from text; the `.bclj` is regenerated. "Delete the `.bclj`, the module still
  compiles from the log alone" is the falsifiable check that the graph is canonical.
- **beagle** (`~/code/beagle`) — a typed compiler, "**Clojure plus types**" (`:- Type` annotations),
  `parse → check → emit` to clj / cljs / js / nix / odin. Source files are `.bclj` etc. Its repair loop
  (pointed errors, fix-plans) is why one authors in Beagle at all. **Owned by the gjoa agent now** (see §7).
- **chartroom** (`fram/chartroom`, `resolve.clj`) — the resolution / graph-query layer. `refers_to` is
  materialized by a scope-correct lexical walk over the warm store. `bin/beagle-callgraph` is the
  scope-correct call graph (vs the legacy `beagle callers`, which under-reports). **This is the code under
  experiment — do not treat it as stable.**
- **lodestar** (`~/code/lodestar`) — a *consumer* that dogfoods the claim graph for project/work tracking.
  Its canonical log is `~/.local/state/lodestar/claims.log` on port **7977** — the LIVE system; never touch it.
- **gjoa** (`~/code/gjoa`) — a Firefox-fork *consumer* of beagle, and the **other agent** in this work.

Graph mechanics worth knowing: AST nodes are identities `@<module>#<n>`; references carry `refers_to`
(identity); `bound_to` is the durable identity edge that makes rename O(1); CRDT (path,tie) order-keys make
top-level inserts commute. Edit verbs: `add-def / set-body / rename-def / insert-after`.

## 4. The experiment
**Question:** does authoring code as a claim graph (identity) beat authoring it as text, under refactoring,
measured by **reconstruction cost as a function of N** (references an edit touches)?

**Two arms, runtime held constant** (both execute as Clojure on babashka, so a difference is not a runtime
artifact):
- **arm-G** — Beagle-as-graph, fram rename verb (re-points by identity).
- **arm-LSP** — raw Clojure + the **real `clojure-lsp` rename** (the strongest realistic text baseline).

**Honest labeling (critical):** Beagle-graph vs Clojure-text moves **two variables** — addressing
(identity-graph vs positional-text) AND typing (typed vs dynamic) — with only the runtime/program/oracle
held constant. So it is the **combined "typed-identity-graph vs dynamic-text" claim**, which is exactly the
industry-relevant "dynamic languages break under AI refactoring" frame. It is NOT "representation alone."
- We **cut** a Beagle-text arm (typed `.bclj` has no LSP — clojure-lsp parse-fails on it — and we are not
  building one; that would measure our LSP effort, not the substrate).
- We **rejected** a TypeScript/Python arm (a foreign language changes the runtime too and lets a skeptic
  dismiss it as "unrelated languages benchmark differently").

**Two headline tiers, kept separate:**
- **Tier 1 — structural guarantee** (language- + tooling-independent, unimpeachable): an identity rename
  *cannot* leave a missed/unverified reference and *cannot* false-hit the old name in a string/comment, by
  construction. Demonstrated, not benchmarked.
- **Tier 2 — combined empirical** advantage vs clojure-lsp: needs a *measured* miss or verification-burden
  to count; honestly blends addressing + dynamism.

**Metric vector** includes axes the graph **LOSES** (machine wall-time, compute — the graph pays
render/recompile/daemon latency). A graph-wins-every-column table is the tell to distrust.

## 5. Where we are (done + banked; receipts in this dir)
- **S2** — write-side proof of mechanism: graph-canonical edits, identity re-point, both falsifiable checks
  green. Land-mine #1 (a "graph arm" that's secretly editing text) dead by construction.
- **S3** — first measured op on REAL code (hiccup.util): both arms green on hiccup's own test; rename
  near-tie at N=1 (the predicted floor); empirical Beagle-portability map.
- **Byte-identical projection** — MEASURED (~half a day to finish; deliberately deferred). Shipping claim =
  byte-STABLE + comment-faithful (not byte-identical).
- **Curve N=1** — floor tie on structure; **graph loses warm-vs-warm latency 3–10×** (the honest "pays
  latency to buy correctness + flatness" tradeoff). Pre-registered before measuring.

## 6. The honest ceiling + the open decision
The big scattered-N target is real (`util/str` is called by **79 distinct functions** in honey.sql). BUT
honeysql's symbols are plain/static → clojure-lsp catches every reference (completeness ties) and the
verification-burden is ~0 → **honeysql yields no MEASURED Tier-2 divergence**, only the Tier-1 guarantee.
honeysql is, however, a beagle-hardening goldmine (it surfaced 2 real compiler bugs).

**The load-bearing open decision (see `STATUS.md`):** the Tier-2 target.
(a) stay on honeysql for the mechanism-at-scale number, accept Tier-2 = structural only; (b) pivot Tier-2
to a dynamic/macro-ref target where clojure-lsp actually misses → a measured gap; (both).

## 7. Working discipline (these norms are load-bearing — internalize them)
This project's whole credibility rests on measurement honesty. The norms, learned the hard way this session:
- **Measure, don't assert.** A plausible tradeoff is a guess until measured.
- **Pre-register before measuring (anti-Edison).** Predictions get committed to git *before* the numbers,
  so they can't be retrofitted. The arrow points prediction → measurement.
- **Distrust the clean number.** A result you *want* to be true is the one to re-check. (We caught a
  warm-graph-vs-cold-lsp comparison that flattered the graph; the honest warm-vs-warm has the graph losing.)
- **Report the axes you LOSE.** The graph pays latency/compute; say so plainly.
- **Symmetric engineering.** Both arms get their best realistic tooling (text arm = clojure-lsp, not a
  strawman find-replace). The test for any fix: would I apply it to the arm I hope loses?
- **Classify every result:** measured-with-config / argued / external-evidence. An honest FALSE is a result.
- **Drive experiment/gate work by hand** — no fire-and-forget workflows for the measured gates.

## 8. Coordination + hard constraints
- **Two agents, one machine.** Fram (this work) and Gjoa coordinate via `~/code/agentchat/agentchat.md`
  (append-only, read-then-write). Lanes: **Gjoa owns Beagle, Fram owns Fram + the experiment.** Beagle bugs
  Fram hits are **filed in agentchat.md, not fixed** by Fram. Gjoa will not touch `~/code/fram`.
- **NEVER touch port 7977 or `~/.local/state/lodestar/claims.log`** (the live lodestar coordinator). All
  experiment runs use isolated `/tmp` logs + non-7977 ports.
- **Don't depend on chartroom/resolve.clj as stable** — it's the code under experiment.
- **Work on `main`** (no feature branches without a real reason); commit straight to main; pushing public
  main needs Tom's explicit go-ahead. Repos are at `github.com/Autonymy/{fram,beagle}`.
- **Backward-compat is never a decider** (only correctness + the desired thesis design).

## 9. Where to read more
- This dir: `STATUS.md`, the four `*-RESULTS.md` / `*-MEASUREMENT.md` / `CURVE-*` receipts, `reproduce-s2.sh`.
- `private-docs/ADDRESSING_THESIS.md` — the talk's full spine.
- `private-docs/CONCURRENT_AUTHORING_EXPERIMENT.md` — the earlier (2-arm, git-vs-fram, concurrent) protocol;
  note it predates the current 2-arm graph-vs-lsp framing — read for the reconstruction-cost discipline.
- `docs/WHY_FRAM_EXISTS.md` — why the atom is a claim-object, not a datom (the "why not Datomic/RDF/X").
- `experiments/THESIS.md` — coordination is the thesis, query-speed is not (guards against re-testing graphify).
- `experiments/rename-identity/RESULTS.md` — the READ-side proof (identity-not-spelling), companion to S2's write-side.
- `~/code/agentchat/agentchat.md` — live coordination with the Gjoa agent.
