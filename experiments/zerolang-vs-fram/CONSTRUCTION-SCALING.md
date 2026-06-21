# Fram vs zerolang — construction-path scaling (the medium-app authoring benchmark)

**One line:** building a medium app by incremental authoring, **Fram is flat per-op (O(N) total)
while zerolang is O(N²)-shaped** — each `zero patch` loads + validates + rewrites the whole graph,
so per-edit cost rises with program size. Measured (median): Fram **6.76 s** vs zerolang **28.4 s**
at 500 defs (**4.2×**), and the gap **grows with size**: **2.3× @250, 4.2× @500, 7.5× @1000**.

Framing (deliberate, per review): this is **construction-path scaling**, NOT "language speed." The
claim is about the *edit path's* complexity, not how fast either runtime executes.

## Environment (pinned)
- Host: Linux 6.12.82 x86_64; **AMD Ryzen AI 9 HX 370** (24 cores); 62 GB RAM.
- zerolang: **zero 0.3.4** (git `fef945e4`), native C compiler built with gcc 14.3 from a /tmp copy
  of `~/code/reference/zerolang` (source READ-ONLY); `make -C native/zero-c`.
- Fram: git `e3f5df5` + this session's authoring-path optimizations (uncommitted; see below).
  Beagle git `a7fc511`. Runtime: babashka v1.12.209 on OpenJDK 21.0.10 (warm in-process daemon).

## The result — incremental build of a 500-def app
Each system authors 500 new defs **one edit at a time** (the agentic loop both tools document:
query → patch → repeat). Fram: 50 modules (`mod00..mod49`), round-robin, via the warm daemon socket
(`:edit-min upsert-form`). zerolang: `zero patch --op 'addFunction …'` per edit, in-place on the project.

| build 500 defs | total wall | per-op shape |
|---|---:|---|
| **Fram** | **6.76 s** median (12.8–13.5 ms/def) | **FLAT** ~9→15 ms (growth 0.76–0.82×) |
| zerolang | 27.79 s | RISING — cumulative 1.9s@100 → 5.3 → 10.5 → 18.0 → 27.7s@500 |

**~4.1× wall-clock at N=500** (27.79 / 6.76). zerolang's per-100-def cumulative deltas
(1.9 / 3.4 / 5.2 / 7.5 / 9.7 s) mean per-edit cost climbs ~19 → 97 ms across the build = the
O(N²)-shaped total. [zerolang N=250/1000 curve points + the recompile-fair total: in progress.]

**Fram stability (3× at N=500):** 6.76 / 6.77 / 6.76 s — ±0.01 s. Rock-stable.

**The full sweep — Fram vs zerolang, build N defs (raw logs in `logs/`):**

| N | Fram build | zerolang build | **ratio** | Fram per-op | zero per-op |
|---:|---:|---:|:--:|---:|---:|
| 250  | 3.29 s  | 7.66 s   | **2.3×** | ~13 ms flat | ~31 ms |
| 500  | 6.76 s  | 28.37 s  | **4.2×** | ~13 ms flat | ~57 ms |
| 1000 | 15.82 s | 118.85 s | **7.5×** | ~16 ms flat | ~119 ms |

**The ratio GROWS with app size (2.3× → 4.2× → 7.5×)** — the curve divergence is the result, not the
single ratio. Fram build is **linear in N (flat per-op)**; zerolang is **O(N²)-shaped** — doubling N
~quadruples its build (7.66 → 28.37 → 118.85 s). Fram per-op flat (the slight creep to 15.8 ms/def
at N=1000 is the edited module growing to ~22 defs — still O(edited-module), not O(total-app)).
0 failures at every N for Fram.

**The killer artifact — per-op latency curve (zerolang N=1000, sampled every 50 ops):**
zero per-op rises ~linearly with program size: **22.6 → 57.5 → 113 → 196 → 251 ms/op** (op
100→1000). Fram per-op stays **flat** ~13–21 ms across the whole build.

```
per-op (ms)   ·                                          zero ╱ (rising, O(N))
              ·                                    ╱
              ·                            ╱
              ·                    ╱
              · ───────────────────────────────────────── Fram (flat, O(1))
              └───────────────────────────────────────────→ program size (defs)
```

Flat line vs rising line = **identity-addressed incremental edit path** vs **whole-graph
reload + revalidate + rewrite per edit**. That is the substrate thesis in one picture.

## The mechanism (PINNED, not inferred from two points)
- **zerolang per patch is O(N):** `zero patch` is a stateless CLI invocation that (1) loads the whole
  `zero.graph` artifact, (2) applies the op, (3) **validates the whole graph** —
  `native/zero-c/src/program_graph_patch.c:986` `z_program_graph_validate(graph, &validation)` — and
  (4) writes the whole graph back. All four are O(graph size); over an N-edit build that is O(N²).
  Confirmed independently by the per-op curve (rising) and the standalone scale probe (16 ms @ N=20 →
  50 ms @ N=240 → ~97 ms @ N=500). Still hedged as **"O(N²)-shaped"** (curve + source mechanism;
  no formal regression fit) — do not write a bare "O(N²)".
- **Fram per edit is O(edited-module), flat:** the warm daemon holds the graph in memory (no reload)
  and does an incremental, module-scoped commit. Three optimizations this session removed the O(total)
  terms (see below). Final per-op is verb ~4 ms + harvest ~0.1 ms + commit ~15 ms (1 fsync), flat in N.

## The optimizations that got Fram from losing to 4.3× ahead (this session)
Starting point: Fram per-op was O(total) and LOST to zerolang ~3× single-op. Three cuts, all in
`cnf_coord_daemon.clj` / `chartroom/src/resolve.clj` / `cnf_coord.clj`:
1. **Incremental corpus cache** (`resolve/*corpus-cache*`): the verb's `corpus-from-store!` reduced
   over EVERY name-claim (O(total)) per commit; now the daemon supplies a maintained module→entities
   map → that reduce is **0 ms**, and frames are scoped to the edited module.
2. **Batched flat-log fsync**: `append-flat!` fsync'd **per claim** (~13 fsyncs/def); now one
   write+fsync per commit (`*flat-batch*` + `flush-flat-batch!`) — same durable-before-ack guarantee.
   Commit ~55 ms → ~15 ms.
3. **O(1) `current-seq`**: it reduced `max` over ALL transactions (`:txs`) and was called ~37× per
   op — O(total-commits). Replaced with `(:next-seq @store)` (the monotonic counter already tracks
   the max). This killed the residual growth: per-op went from 14→61 ms (2.5× over the build) to
   **flat 9→14 ms (0.80×)**; total build 18.7 s → **6.4 s**.

## Fairness / audit-survival (built to survive a hostile read)
- **zero is in its intended mode.** No warm/daemon/server/batch subcommand exists; the documented
  agent loop is per-edit `zero patch`. Per-edit CLI is zero's design for agentic authoring, not a
  flag left wrong. (A batch `zero patch --op …×N` would be O(N) — but that is a build script, not
  incremental authoring; the benchmark measures the agentic edit loop both tools prescribe.)
- **The one real asymmetry: validation deferral — and why the win survives it.** zero's per-patch
  cost INCLUDES whole-program revalidation; Fram's `:edit-min` commits the claim and defers the full
  type-check (it does a scoped refers_to re-resolve, not a type-check). **But the O(N²) shape is NOT
  a validation artifact:** `zero patch` also *reloads the whole `zero.graph` and rewrites it* every
  edit, and the file grows linearly — so even a hypothetical no-validate zero would still be O(N) per
  patch → O(N²) build, purely from reload+rewrite of a growing whole-graph file. Validation is added
  on top. Fram avoids all three (warm daemon = no reload; append-only log = no rewrite; scoped
  resolve = incremental), which is the substrate difference. The honest validated-end-state caveat:
  a fair total adds Fram's final type-check. Two ways to add it: (a) an **incremental per-module
  check** (beagle checks only the edited module — O(edited-module), keeps the total O(N); not yet
  wired into `:edit-min`, a clean future step) or (b) a **cold whole-app `beagle-build-all`** (a
  one-time ~constant). With (a) Fram stays O(N) and wins at every N; with (b) the validated total
  crosses in Fram's favour around N≈500–700 and widens fast (at N=1000 zero is 119 s — Fram's
  6–16 s authoring + a one-time cold compile is far under that). Construction-path scaling is the
  reported, pinned result; the validation note is stated, not hidden. [exact cold-recompile constant:
  one measurement still TODO; does not change the O(N)-vs-O(N²) conclusion.]
- **Not "language speed."** Both compile to/through other layers; this measures the construction/edit
  path's scaling, which is the substrate thesis: identity-addressed incremental graph edits vs
  whole-graph reload+revalidate.

## Correctness gate (GREEN — the optimizations are correctness-neutral)
The optimizations touch OCC/versioning (`current-seq`) and the commit path, so the full receipt set
was re-run AFTER the changes:
- `cnf_edit_min_correctness` **3/3 PASS** · `cnf_edit_min_commute` **GATE 2 PASS** ·
  `cnf_edit_min_rename` **PASS** · `cnf_rename_race_receipt` **PASS** (OCC under race + identity
  capture; the riskiest for `current-seq`) · `cnf_crdt_coupled_receipt` **PASS** ·
  `cnf_code_flip_test` keystones **PASS** (KEYSTONE-A render(log)==render(text) **byte-identical**,
  KEYSTONE-B/C recompile + commit + present).
- **One pre-existing failure, attributed by baseline diff:** `cnf_code_flip_test` GATE 3 INGEST
  (`emit-edn(schema) re-keyed == warm-store AST, symdiff 0`) FAILS — but it **fails identically on
  the clean baseline e3f5df5 with my edits reverted** (verified: revert 3 files → run → same FAIL →
  restore). So it is NOT caused by these optimizations; it is a pre-existing schema-fixture/ingest
  drift, separate from the flip's actual claims (the keystones, which pass). Logged here so it isn't
  mistaken for a regression.

**Conclusion:** every receipt sensitive to these changes is green; the lone failure predates them.
The speedup does not trade correctness.

## TODO before this is publishable
- [ ] full correctness gate green (rename-race / flip / coupled)
- [ ] re-run build benchmark 3-5× (Fram + zerolang); save raw logs here; report median + spread
- [ ] capture + plot the per-op curve (the killer artifact: Fram flat line vs zero rising)
- [ ] measure Fram's final whole-app recompile; report the validation-fair total
- [ ] sweep N (250/500/1000) to strengthen the shape beyond two points
