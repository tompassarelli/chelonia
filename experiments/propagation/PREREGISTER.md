# Propagation-latency — PRE-REGISTRATION (#44 / PROMPT 2)

Pre-registered BEFORE any measurement. The arrow points prediction → measurement.
The two ways this number dies in a PL room are fenced off here: a poll-loop git
baseline, and a re-resolve (not corpus-from-store) metric.

## Objective
Propagation latency = wall-time from agent **A committing** a code change to agent
**B's read** reflecting it. Graph (Fram daemon's warm `co` store) vs text + git,
symmetric tooling.

## Metric (killer #1 — fenced)
**commit-to-visible against the MAINTAINED store (corpus-from-store), NOT a re-resolve
walk.** The earlier ~3 s "propagation" figure was the re-resolve cost — not repeated.
Both arms carry the SAME two timestamps:
- `t0` = A's commit call returns.
- `t1` = B's read first reflects A's change.
- `latency = t1 − t0`.
- Graph arm: B's read = a warm daemon query served off `co` (corpus-from-store /
  `:render` / `:callers`). `t1` = first warm read that includes A's claim.
- Git arm: B's read = B's working tree after fetch+merge. `t1` = first B-side read
  that sees A's change.

## Baseline symmetry (killer #2 — fenced)
The git arm gets git's BEST realistic propagation: a shared **bare** repo,
**push-notification-driven** fetch (a `post-receive` hook triggers B's fetch), real
merge tooling. **No poll loop** — a poll interval hands git an artificial floor that
is a poll artifact, not git's physics. Beat push-hook git or the number is worthless.

## Pre-registered predictions (committed before running)
- **P1 — graph propagation is FLAT in agent count.** Eager store update on commit +
  commuting structural writes (post-#36, top-level) ⇒ B's warm read sees A's claim in
  ~constant time independent of N.
- **P2 — git propagation is floored and climbs with overlap.** Floor =
  commit+push+(hook)+fetch+merge; the merge term rises with the OVERLAP rate (concurrent
  edits touching the same files cost more / conflict).
- **P3 — write-side: graph lands concurrent writes with NO barrier** (append-only, no
  cardinality axiom on the relation); git hits a merge conflict on overlap. (The
  no-coordination-barrier win.)
- **P4 — read-side does NOT vanish.** When concurrent writes touch the SAME logical
  thing, the graph converts the conflict into a read-time **path-selection** obligation.
  Its cost + correctness are measured and reported **separately, never as free**. The
  no-silent-misorder bar holds.

## Decomposition (report separately; do not conflate)
- **write-side**: do concurrent writes land without a barrier? graph yes-by-construction;
  git merge-conflict on overlap.
- **read-side**: cost + correctness of resolving same-thing concurrent writes
  (path-selection). NOT free.

## Corpus + sweep
Real code edits touching ordered structures (**top-level**, post-#36 where commute
holds — not claim-level toys). Sweep **agent count N** and **overlap rate**. Attribute
every result to a layer (eager store / structural commute / both).

## Falsifiers / honest-null
- If graph propagation climbs with N → P1 is false; find the layer.
- If push-hook git propagation is already flat/cheap and the graph isn't faster → that
  is an **honest null**, reported as a result, not buried.
- A graph-wins-every-column table is the tell to distrust; include the columns where
  the graph loses (read-side path-selection cost).

## Scope note (from #43)
#36 gives same-gap concurrent inserts **no-clobber + no-dup + a deterministic order per
execution** (commit-order tie), NOT delivery-order-independent convergence. This harness
measures TOP-LEVEL edits where that guarantee is exactly what the write-side win needs;
it does not depend on strong order-convergence.
