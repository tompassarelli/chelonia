# Ledger — canonical measured log (append-only; this is your memory)

## Corpus index (Beagle-reachable AS IT STANDS — no porting allowed; STEP 2)
Reachability checked 2026-06-20 by `git ls-files '*.bclj'` + dir scan. NO porting in this loop.
- [x] **greet** (N=1) — `experiments/authoring-harness/greet.bclj`, committed, behavioral oracle. REACHABLE. MEASURED (S-GREET below).
- [~] **fram-own-source** (N>1 candidates) — `src/fram/*.bclj` (cnf/datalog/export/fold/import/kernel/main/query/schema/tools/types) are committed, compiling, REAL Beagle. Reachable WITHOUT porting. Needs: a clean single-module def with intra-module refs + arm-LSP clj buildable standalone. PROBE before use.
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

