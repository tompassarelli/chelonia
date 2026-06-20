# Reconstruction-cost curve — RESULTS (measured; populated as N points land)

Pre-registration: `CURVE-PREREGISTRATION.md` (predictions committed before these numbers). Two arms:
arm-G (Beagle-graph, fram rename verb) vs arm-LSP (raw Clojure + real clojure-lsp rename). Measured-
with-config, hand-driven (not yet real agents). Reporting EVERY column, including where the graph loses.

## N=1 — greet, rename `base` → `base2` (the floor)
| axis | arm-G (graph) | arm-LSP (clojure-lsp) | read |
|---|---|---|---|
| **completeness** | 2/2 sites (def + ref), by construction | 2/2 (def + ref) | **TIE at the floor — as predicted** |
| **collision-safety** | immune by construction | safe (semantic) | TIE (both semantic) |
| **verification needed** | 0 (correct by construction) | must trust/verify analysis (here trivially complete) | graph wins epistemically; immaterial at N=1 |
| **edit wall-time** | **926 ms** warm per-op (rename+render, 2 bb CLIs + daemon round-trip) | **1167 ms** cold CLI (JVM start + analysis + rename) | comparable; see caveat |
| **one-time setup** | **2816 ms** daemon cold-start (amortized over all ops) | none (CLI) | **arm-G slower at N=1 *including* setup (3742 vs 1167) — as predicted** |
| **recompile (shared verification)** | 4044 ms | 4044 ms (same module) | **shared cost, cancels in the comparison** |

**Reading (honest):**
- The **floor tie holds** — at N=1 both arms are complete + collision-safe; nothing separates them on
  the structural axes. Exactly the pre-registered prediction.
- **Wall-time prediction holds:** arm-G is slower at low N *including* the one-time daemon cold-start
  (3742 vs 1167 ms). Warm per-op (926 ms) is actually ~comparable to the cold lsp CLI (bb start < JVM
  start) — but that's **confounded**: clojure-lsp CLI pays JVM startup *every* invocation, while a
  persistent lsp *server* (as in an editor) would not, and arm-G's daemon is persistent. Fair per-op
  comparison is warm-vs-warm; the CLI number flatters arm-G. Flagged, not papered.
- **Recompile (4044 ms) dominates and is shared** — both arms recompile to verify; it grows with module
  size (not N), equally, so it cancels in the arm-vs-arm comparison.
- **Method validated:** both arms measurable across the full vector at the floor. The divergence the
  thesis predicts (graph flat / lsp's edit+verification growing with N) is not visible at N=1 by
  construction — it shows at N≈12 (carve) and N≈79 (full honey.sql), the next points.

**Predictions still live (to test at N≈12, ~79):** arm-G edit ~constant in N (rename verb is O(1));
arm-LSP edit grows with N (analysis + N sites); completeness gap opens only if clojure-lsp misses a
dynamic/macro ref (Tier 2, blends dynamism). Falsifier: if arm-G edit is NOT flat at N≈79, claim unsupported.
