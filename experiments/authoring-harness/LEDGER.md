# Ledger — canonical measured log (append-only; this is your memory)

## Corpus index (Beagle-reachable AS IT STANDS — no porting allowed; STEP 2)
Reachability checked 2026-06-20 by `git ls-files '*.bclj'` + dir scan. NO porting in this loop.
- [x] **greet** (N=1) — `experiments/authoring-harness/greet.bclj`, committed, behavioral oracle. REACHABLE. MEASURED (S-GREET below).
- [x] **fram-own-source** — `src/fram/*.bclj` REACHABLE, no port. COVERED: query N=1..4 (clean intra-module curve, size constant) + fold N=2 (cross-module confirmation). See S-QUERY-CURVE. Other modules would add same-shape small-N points (diminishing returns).
- [x] ~~hiccup.util (S3)~~ — port was EPHEMERAL (/tmp, never committed); GONE. Re-creating = PORTING = forbidden in-loop. Not reachable.
- [x] ~~honeysql util/str (curve)~~ — carve was EPHEMERAL (/tmp/s5-honeysql, never committed); GONE + had the multi-arity-fn beagle bug. Re-creating = PORTING = forbidden. Not reachable.
- [x] ~~datahike~~ — raw Clojure, never Beagle. NOT Beagle-reachable for arm-G (it was the clojure-lsp falsifier corpus only). Not a cost-curve scenario.

CONSEQUENCE (stated, per spec — thin corpus is a limitation, not a confound): the intended higher-N
corpus (hiccup/honeysql) is gone and re-creating it is porting (outer-loop decision, Tom's). The only
non-porting higher-N source is Fram's own `src/fram/*.bclj`; if it doesn't yield a CLEAN single-module
scenario, the curve has one clean point (greet N=1) and the loop halts with that as the stated limit.

---

## RESULT — S-GREET (N=1), rename `base`->`greeting` — measured-with-config
3 runs, isolated /tmp log + port 7993, warm-vs-warm. Pre-registration: P1. Behavioral oracle: greet
"world" == "hello world" — BOTH arms PASS (correctness held).

| layer | arm-G (graph) | arm-LSP (clojure-lsp) | notes |
|---|---|---|---|
| **rename op** (clean cross-arm) | edit **328-347ms** (~334 median) | rename **112-118ms** (~114 median) | **graph LOSES ~2.9x** at the floor (predicted, P1; rule-6 ok) |
| render | 1867-2017ms | n/a (text is its own source) | arm-G-only: project the view from the graph |
| recompile | 5081-5260ms (beagle typed build) | 26-27ms (clj dynamic load) | NOT apples-to-apples (typed emit+check vs dynamic load); confounded, not the cross-arm axis |
| setup (one-time) | ingest 1571-1674ms | beagle-build of the .clj source (one-time) | not part of per-rename cost |
| graph delta | log 539->544 (+1 assert/+1 retract = **O(1)**) | edits 2 sites (def + ref) | the substrate op is O(1); lsp edits scale with refs |

**Per-layer attribution (rule 3):** the arm-G rename-op loss is NOT a substrate cost. The daemon op core
is sub-ms (prior CURVE-RESULTS); the ~334ms is bb-CLI JVM/babashka startup + socket round-trip to the
daemon = EXECUTION layer, currently UNATTRIBUTED-split (bb-startup vs round-trip not yet isolated). The
graph-algorithm cost (the actual re-point) is O(1) and sub-ms. So "graph loses the rename op ~2.9x" is an
EXECUTION-layer statement (CLI client startup), not a substrate statement.
**Tier-1 observed live:** arm-G render correctly left `base` in the DOC COMMENT untouched while re-pointing
the code reference — the no-false-hit-in-comments property, by construction.
**Classification:** measured-with-config. Matches P1. The graph loses the wall-time column at the floor, as
required.

---

## RESULT — S-QUERY-CURVE: cost-vs-N inside one module (module size CONSTANT) — measured-with-config
Pre-registration: P2. Target = `src/fram/query.bclj` (real Fram source, no port). 4 intra-module private
targets, all cross-module=0, recompile-clean oracle (beagle-build-all 0 errors == every ref re-pointed).
2 runs each. + `fold/key-of` (different module) as cross-module confirmation. + greet (N=1) for the size axis.

| scenario | N | log size (claims) | arm-LSP rename | arm-G edit | log Δ (storage) |
|---|---|---|---|---|---|
| query-N1 (lit-errors) | 1 | 10612 | ~206ms | ~663ms | +3 |
| query-N2 (strata-of)  | 2 | 10612 | ~207ms | ~707ms | +4 |
| query-N3 (max-results)| 3 | 10612 | ~208ms | ~678ms | +5 |
| query-N4 (vars-of)    | 4 | 10612 | ~205ms | ~668ms | +6 |
| fold-N2 (key-of)      | 2 | 3098  | ~135ms | ~397ms | +3 |
| greet (base)          | 1 | 539   | ~114ms | ~334ms | +5 |

**FINDINGS (the genuinely-open prediction, now measured):**
1. **Both arms are FLAT in N.** Within query (size fixed), arm-LSP rename = ~205-208ms and arm-G edit =
   ~663-707ms across N=1..4 — neither grows with the reference count. **The pre-registered latency
   DIVERGENCE does NOT appear** at reachable N. clojure-lsp's rename is flat (cached analysis + cheap edits);
   arm-G's edit is flat (O(1) re-point). The latency axis is a **CONSTANT ~3.2x gap, not a divergence** (P2's
   "near-flat lsp -> constant gap" branch confirmed, not the divergence branch).
2. **arm-G's loss is EXECUTION, not substrate (rule-3 attribution).** arm-G edit wall-time tracks LOG/MODULE
   SIZE, not N: 539 claims->334ms, 3098->397ms, 10612->668ms (3 modules, monotonic). The growth is the
   daemon's module-scoped resolve over a bigger warm store + bb-CLI JVM/babashka startup + socket round-trip
   — all N-independent EXECUTION layers. The graph-algorithm core (the re-point) is O(1) and sub-ms; the
   essential op is 2 claims regardless of N. So "graph loses ~3x" is an execution statement, never a substrate one.
3. **Substrate STORAGE is O(N) per rename** (log Δ = N + small const): the warm re-resolve re-writes
   `refers_to` for the N references (a derived-index update). Distinct from the O(1) essential op and the
   flat wall-time — cheap in time, linear in log growth. The one axis that is genuinely O(N) in the graph.
4. **recompile dominates end-to-end and is N-independent.** arm-G beagle typed build ~5s (module-bound, flat
   in N); not apples-to-apples with arm-LSP dynamic load. The rename op is a small slice of total wall-time —
   itself a finding: the substrate op is cheap; the build dominates both arms' real cost.
5. **Completeness held everywhere** (every oracle/recompile green). Expected: clean static symbols, the
   settled "no analyzer miss." No FALSE, no tie-that-should-have-diverged.
**Classification:** measured-with-config. Matches P2's near-flat branch; falsifier (arm-G edit scaling with N)
NOT triggered — edit is flat, O(1) confirmed.

---

## SUMMARY (HALT — curves flattened + clean reachable corpus covered)
**Why halt:** (a) the cost-vs-N curve FLATTENED — both arms flat in N over the reachable range (N=1..4), so
more same-shape points are diminishing returns; (b) the clean Beagle-reachable corpus without porting is
covered (greet + query N=1..4 + fold N=2); the higher-N corpus (honeysql ~79) is ephemeral/gone and
re-creating it is PORTING = outer-loop decision (Tom's), not mine.

**What was measured:** rename reconstruction cost, both arms, per-layer, across N=1..4 (3 modules of 3
different log sizes). Cost curves: **both arms FLAT in N**; arm-G a **constant ~3x slower** on the rename op,
attributed to EXECUTION (CLI startup + daemon round-trip + module-scoped resolve), NOT the graph algorithm
(O(1), sub-ms, 2 essential claims). arm-G edit wall-time tracks LOG SIZE (monotonic over 539/3098/10612
claims), not N. Substrate STORAGE is O(N) per rename (re-resolve re-writes refers_to). recompile (typed
beagle build, ~5s) dominates end-to-end for arm-G and is N-independent.

**Curve shapes:** flat / flat (in N). Constant multiplicative gap on the rename op. Linear (O(N)) only on the
log-storage axis. The thesis-relevant *divergence* (lsp cost growing with N while the graph stays flat) was
NOT observed — because clojure-lsp's rename is itself ~O(1) in N at this scale.

**Every FALSE/tie:** none. All renames correct on both arms (completeness held — consistent with the settled
"no analyzer miss"). The pre-registered divergence is an honest NULL at reachable N.

**Per-layer attribution pattern:** arm-G's measured loss lives entirely in execution layers (bb-CLI JVM
startup, socket round-trip, daemon module-scoped resolve that scales with log size) + the confounded
typed-recompile. The graph-algorithm layer is O(1) and never the bottleneck. This is the substrate-vs-execution
decomposition working: the graph's cost is its tooling/runtime, not its representation.

**The single question I could NOT answer (reserved for Tom / the outer loop):** does a latency DIVERGENCE
appear at LARGE N (≈79+ refs, honeysql scale)? At reachable N (≤4) both arms are flat and there is no
divergence. Whether clojure-lsp's rename stays flat or begins to grow at N≈79 — and whether arm-G's O(1) edit
then visibly wins on the curve — is UNMEASURED, because the only N≈79 corpus is gone and re-creating it is
porting. **This is the outer-loop decision: authorize a port for a large-N point, or accept that at
human-scale single-module N (≤~5) the curves are flat and the substrate advantage is Tier-1 (structural), not
a measured latency win.** My read (measure-only, not a strategy pivot): the loop has extracted what the
reachable corpus can show — flat curves, constant execution gap, O(1) graph op, O(N) storage — and the
remaining question is a porting/scale decision above my authority.

**HALTED 2026-06-20.** Disk state (LOOP-SPEC/PREREGISTER/LEDGER/harness.sh) is the canonical record.

---

## ADDENDUM (post-review, 2026-06-21) — pulled the footnoted O(N) thread
Review: the rename's N+2 log growth, shelved as "O(N) storage, cheap in time," is the one finding that
touches the headline ("re-points for free"); settle whether the index rewrite is NECESSARY or AVOIDABLE
(cheaper than the large-N port). Pulled it.

### S-REWRITE-NECESSITY — measured + code-grounded
**What the N extra claims ARE** (log diff, rename `vars-of`, N=4): four **`bound_to` asserts**
(`@query#292 bound_to @query#166` ...) + the 2-op name change (retract old `v` / assert new `v`). They are
**durable IDENTITY-edge materializations, NOT `refers_to` re-asserts.** The first rename of a binding lazily
PINS its N references to the binding's `@mod#int`.

**Amortization (decisive test — 3 sequential renames of the same binding):**
- #1 vars-of->vars-set:    Δ=**6** (2 + 4 `bound_to` installed)
- #2 vars-set->vars-final: Δ=**2** (O(1) — edges already exist)
- #3 vars-final->vars-x:   Δ=**2** (O(1))
The O(N) is paid **ONCE per binding**; every subsequent rename is **O(1)**.

**Mechanism (code-grounded):** `verb-rename!` (resolve.clj:1137) does ONLY the 2-op name change (:1170-1174)
after read-only collision/no-capture/import checks (:1140-1169). The N `bound_to` writes come from the
daemon's **scoped re-resolve AFTER** the verb (:1180-1181), materializing the durable identity edge for
exactly the references whose binding spelling changed and aren't yet pinned. Pinned refs are skipped → O(1).

**VERDICT (necessary or avoidable):** **necessary, not redundant.** It is the one-time conversion of
references from spelling-resolution to durable identity-pinning. Not avoidable without cost-shifting
(pre-install at ingest pays for bindings never renamed; keep spelling-resolution → abandon identity). Placed
optimally (lazy, per-binding, first rename) and **amortizes to O(1)**.

**What this BOOKS (substrate win, now honest + measured):** text rename = **O(N) writes EVERY time** (re-edit
N spellings; no durable identity to amortize into). Graph rename = **O(N) writes ONCE** then **O(1) forever**.
The amortization IS "re-point for free" — but honestly: free *after* the one-time identity installation, the
durable edge text has no slot for. **"Free re-point" as unqualified language is RETIRED; "amortized O(1) after
lazy identity installation" replaces it, measured.** Classification: measured-with-config + code-grounded.

### CALIBRATION corrections to the SUMMARY above (review was right)
- **"curves flattened" overstates.** N=1..4, 2 runs, ~40ms noise, no monotonic trend = *consistent with flat*,
  not decisive. The NULL sits in the regime the prediction already declared empty (divergence needs large N).
  Flat-flat was near-preordained; the live question (lsp climbing at N≈79 while the graph stays flat) is
  UNTOUCHED = the porting call reserved for Tom.
- **"arm-G edit tracks log size" is CONFOUNDED.** It leans on 3 *different* modules (greet/fold/query) that
  differ in more than log size — the same confound I refused on the N-axis, quietly let back in. Demote to
  *consistent with* module-size dominance; not isolated.
- **recompile ~5s reattributed precisely:** the **typing tax** (beagle typecheck + emit) — the price of TYPES
  (the second variable conceded up front), NOT a graph/substrate cost. "Confounded" was vaguer than the truth.
- **render ~1.8-3.8s is deletable execution waste**, not substrate: the .bclj text projection; a
  graph->typed-AST->emit path (no intermediate text) eliminates it. Already isolated as its own layer.

### PARKED — product knobs (NOT talk work; do not build for the talk)
Execution-axis only; none touch the substrate claim. Ordered by the dependency the data implies:
1. **Typed-in-memory** (graph->typed-AST->emit, never serialize text): deletes render + re-parse, KEEPS types
   (stays the typed arm). Build FIRST — shrinks the window the others need. (graph->clj-untyped = a DIFFERENT
   arm, untyped-graph-vs-text; name it honestly if ever built.)
2. **Incremental typecheck** (recheck only the changed def's dependents): attacks the DOMINANT cost (typing
   tax) → probably highest-leverage.
3. **Optimistic recompile** (speculative, hashed, pruned): latency-HIDING not cost reduction — report
   "time-to-available-when-warm" as its OWN column, never as rename cost (that's the warm-graph-vs-cold-lsp
   flatter, caught once). Daemon warm-store pattern one layer up; only pays after knob 1.
Talk's measured content is done; keep optimization and measurement in separate buckets so a perceived-latency
win never leaks into the substrate column.

**Thread settled. Loop still HALTED.** Open question unchanged + Tom's: large-N divergence (porting), now
joined by an optional product track (knobs above).

### S-PIN-DURABILITY (2026-06-21) — do the pins survive intervening edits? (load-bearing follow-up)
The 3-rename test had nothing BETWEEN renames, so it only proved pins survive back-to-back renames. The
"O(1) forever" claim needs pins to survive the edits (body changes, re-renders, other edits) that each
trigger the daemon's scoped re-resolve. Tested: install pins, disturb with unrelated edits, re-rename, watch Δ.

Sequence (pins for one binding installed, then stressed):
- rename#1 vars-of->vars-set:      Δ=6, pins 4 refs -> `@query#166`
- intervene rename max-results:    Δ=5, pins 3 refs -> `@query#1815` (its OWN binding); **#166 NOT re-pinned**
- intervene rename strata-of:      Δ=4, pins -> `@query#402`;  #166 untouched
- intervene rename term-ok?:       Δ=4, pins -> `@query#132`;  #166 untouched
- intervene re-render query:       **Δ=0** (pure read — projection writes nothing to the log)
- rename#2 vars-set->vars-final:   **Δ=2, zero new bound_to** — O(1) AFTER all the intervening edits

**MEASURED:** pins on UNTOUCHED references are DURABLE across arbitrary re-resolve-triggering edits. The
scoped re-resolve is **pin-preserving + idempotent (skip-if-pinned)**: each edit pins only ITS OWN
newly-affected refs, never re-touching already-pinned ones. Re-render is read-only (Δ=0). The re-rename is
O(1) even after a burst of intervening edits. So "amortized O(1)" is **O(1) for the life of the binding's
references**, not the narrow "O(1) only across back-to-back renames."

**BOUNDARY (argued from node-identity architecture; the one corner not directly measured):** an edit that
REPLACES a reference's containing body (set-body/upsert-form on a def that references the binding) mints a NEW
reference node, which is unpinned and gets re-pinned on the next rename. That is correct identity semantics (a
rewritten reference IS a new identity); the cost is bounded by references-you-edited, NOT a re-pin of
untouched refs, and it does not reopen "pay N again" for the stable references. Cheap follow-up to convert to
measured: set-body a referencing defn, rename, confirm only the rewritten ref re-pins. Flagged honestly, not
asserted-as-measured.

**Net:** the O(1)-forever claim SURVIVES for untouched references (the common case) — make it loud; state the
edited-reference boundary honestly. The write-side story is now "graph slower exactly ONCE (lazy identity
install), then faster and correct," measured + durable, not "graph slower but correct."

