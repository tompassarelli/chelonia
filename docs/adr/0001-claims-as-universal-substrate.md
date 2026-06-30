# ADR 0001 — Claims are the universal substrate; SQL is optional interop

**Status:** Accepted — 2026-06-18
**Supersedes the recurring "where do the boundaries go?" debate.** If you are about
to re-open the project-boundary or "is eddy real?" question, read this first.

## Context

Fram is a generic claim engine (append-only `subject predicate object` log →
in-memory graph + stratified Datalog + sole-writer coordinator). Over the last
~100 commits the code lane converged (repair + reasoning, one resolver), and the
recurring strategic churn has been *boundaries*: should chartroom be its own
project, should every app "use chartroom", is eddy a real project, should apps be
backed by SQL or by claims. This ADR settles the frame so it stops being
re-litigated each session.

## Decision

### 1. One engine, one substrate: **claims**
Fram is *the* engine. The thesis is uniform across domains: **data + logic +
structure live as claims**, so each is *reasoned* (blast radius, Datalog) and
*repaired* (graph edits) the same way. Text and SQL are projections/printouts,
never the source of truth.

| Domain | Claims customer | Status |
|---|---|---|
| Code | **chartroom** (beagle source → claims; rename/delete/who-calls/blast) | shipped, folded into fram |
| Work / thought | **tern** (threads/clock as claims) | shipped, in daily use |
| **Apps** | **eddy** (app data/logic/structure as claims) | **target state — the open build** |

### 2. The four projects (and the one module)
`fram` (engine + the `chartroom` module), `beagle` (language/compiler), `eddy`
(app compiler), `tern` (work substrate). **chartroom is a module inside fram**
(`fram/chartroom`, "beagle source code-intelligence"), folded 2026-06-18 — licensed
by lockstep cadence + cross-repo friction relief, *not* dependency-direction. The
seam is enforced structurally: `core_code_blind_test` (fram-core stays blind to
beagle-*as-subject*; authored-in-beagle is fine) + `chartroom_seam_test` (chartroom
rents only fram's public `{cnf,datalog}` surface).

### 3. SQL is demoted, not banished
For **greenfield**, fram claims are the backend — **not SQL**. Persisting to SQL
and rebuilding a graph to ask relational questions reintroduces the reconstruction
tax the engine exists to kill (the CodeQL/Glean problem, in the app domain). SQL
(`emit-sql`/`emit-server`) remains a legitimate **optional** emit target for
interop (existing SQL systems) and scale/ops — never the default, never the point.

### 4. eddy's aligned form is a **claim-backed app compiler**
Eddy today emits a SQL/REST backend and emits its entity-graph as claims only as a
*static projection for reasoning* (`emit-claims-ir`). Its aligned form is the
inverse: app **data lives in fram claims at runtime**. eddy-on-fram-claims *is* the
apps pillar — not a bridge or a hedge. The gap is one bounded build: a claim-backed
persistence runtime (a fram adapter / emit target) parallel to `emit-sql`, wiring
the client `gen-store` CRUD seam (`add/update/remove/load`) to claim operations and
queries to `by-lp`/Datalog. eddy stays its own repo (cleanly decoupled — core
imports fram in zero files); this build is what earns it its first real consumer.

### 5. Count stable interfaces, not folders
The mature system has **two** load-bearing interfaces, independent of repo count:
(1) the fram-core library API; (2) the AST/IR → claims CNF triple format (chartroom
and eddy *produce*, fram Datalog *consumes*). Repo count and the chartroom fold are
orthogonal to those two.

## Non-goals / parked
- **"Every app's logic as Datalog" is a tax**, rejected. Claim-native logic wins
  only for recursive/relational derivation (leverage/reaches); it is net-negative
  for flat filters (measured: `leverage_probe`, ~2.3× more code + a rotting predicate
  schema). ready/blocked-as-Datalog is a **decided non-goal**.
- The tern **leverage retrofit is parked** — adopt it only as part of a wholesale
  move of tern's projection layer onto the reified store, never standalone.

## Next move
**eddy-on-claims**, greenfield: back the smallest eddy demo with a fram claim store
instead of SQL. Falsifiable question: is it less ceremony than eddy-on-SQL, and does
reasoning (blast/Datalog over live app data) fall out for free? No tern bridge,
no new API — greenfield removes the preconditions.
