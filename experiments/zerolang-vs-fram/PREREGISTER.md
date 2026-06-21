# Fram vs zerolang — concurrent authoring head-to-head (PRE-REGISTRATION)

Predictions committed BEFORE the concurrent numbers land (evidence-discipline:
the arrow points prediction -> measurement). zerolang built from a /tmp copy of
`~/code/reference/zerolang` (READ-ONLY source); native compiler `zero 0.3.4`.

## The question
Does Fram beat zerolang on **concurrent authoring wall-time** (the thesis regime:
many agents authoring at once) and on **failure rate** (lost/rejected/retried ops)?

## Systems under test (each: K agents concurrently author K distinct defs into a SHARED project)
- **zerolang** — shared `zero.graph`; each writer reads the graphHash, applies
  `zero patch --op 'expect graphHash <H>' --op 'addFunction ...'`, installs via CAS.
  A stale hash → **GPH002 reject → retry** (re-read, re-patch). Each patch reloads +
  revalidates + rewrites the WHOLE graph (measured O(N)). = the OCC merge-queue.
- **Fram** — shared warm daemon (greet module); each writer `:edit-min upsert-form`
  via the socket; commit-to-visible content-asserted via `:seen`. Writes **commute**
  (distinct claims, no global version) → no rejects, no retries.
- (git reference arm already measured in `experiments/propagation` — the merge-queue baseline.)

Sweep K ∈ {1,2,4,8,16} and base project size N ∈ {0(small), 200(scaled)}.

## ALREADY MEASURED — single-thread per-op (the conceded column, reported straight)
Fram **LOSES** single-op authoring: both are O(N) in project size, Fram's constant +
slope are worse (zero 16->50ms vs Fram 27->147ms across N=20..240; ~3x at N=240).
Fram's per-op heaviness is the verb's module-frame build + CRDT ordering-key (`wrap-forms`,
reads all siblings), both O(module). This is a real loss; the thesis win (if any) is concurrency.

## Predictions (concurrent — NOT yet measured)
- **P1 (wall-time):** Fram total wall stays ~flat/low in K; zerolang **climbs** (serialized
  landings + each rejected attempt is a wasted O(N) re-patch). Crossover: Fram wins at modest K,
  margin grows with K. Target (Tom's goal): **2-5x+** at the scaled regime.
- **P2 (failure rate):** Fram = **0** rejects, 0 CAS-miss, 0 lost writes (commute). zerolang
  > 0 (GPH002 rejects + CAS-misses = wasted patches; naive-parallel would lose writes entirely).
- **P3 (amplification):** the Fram margin GROWS with base N — zerolang's per-attempt is O(N), so
  every wasted retry costs more at N=200 than N=0.

## Honest nulls to report if they happen
- If Fram's heavy per-op (O(module)) makes its serialized-at-the-daemon total LOSE even
  concurrently at small N, report it (and then optimize the per-op machinery, per the mandate).
- Modeling caveat: zerolang's CAS install is serialized via an in-process lock (the minimal
  honest "single shared graph" coordination); the wasted-patch count is the substrate-intrinsic
  cost (each rejected attempt did a full O(N) patch). Report wall AND wasted-patches separately.
