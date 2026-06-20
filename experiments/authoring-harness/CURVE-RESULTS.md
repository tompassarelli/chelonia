# Reconstruction-cost curve — RESULTS (measured; populated as N points land)

Pre-registration: `CURVE-PREREGISTRATION.md` (predictions committed before these numbers). Two arms:
arm-G (Beagle-graph, fram rename verb) vs arm-LSP (raw Clojure + real clojure-lsp rename). Measured-
with-config, hand-driven (not yet real agents). Reporting EVERY column, including where the graph loses.

## N=1 — greet, rename `base` -> `base2` (the floor)
| axis | arm-G (graph) | arm-LSP (clojure-lsp) | read |
|---|---|---|---|
| **completeness** | 2/2 sites (def + ref), by construction | 2/2 (def + ref) | **TIE at the floor, as predicted** |
| **collision-safety** | immune by construction | safe (semantic) | TIE (both semantic) |
| **verification needed** | 0 (correct by construction) | must trust/verify analysis (here trivially complete) | graph wins epistemically; immaterial at N=1 |
| **edit latency, WARM vs WARM** | ~280 ms `:edit-min` op (daemon-side sub-ms; bb-CLI + round-trip dominate); ~926 ms full edit+render | **~90 ms** (clojure-lsp = GraalVM native binary + cached analysis) | **graph LOSES 3-10x at the floor, reported straight** |
| **cold start (both from scratch)** | 2816 ms daemon cold-start + op ≈ 3742 ms | 1167 ms first run (uncached analysis) | graph loses ~3x cold too |
| **recompile (shared verification)** | 4044 ms | 4044 ms (same module) | **shared, cancels in the comparison** |

**Reading (honest):**
- The **floor tie holds** — at N=1 both arms are complete + collision-safe; nothing separates them on
  the structural axes. Exactly the pre-registered prediction.
- **Graph loses on latency, measured warm-vs-warm (no deferral, no cold-CLI cheat).** clojure-lsp is a
  GraalVM **native binary**: warm (cached analysis) it renames in **~90 ms**. arm-G's `:edit-min` op
  alone is **~280 ms** (the daemon op is sub-ms; the bb-CLI startup + round-trip dominate), and the full
  edit+render pipeline is ~926 ms. So **the graph loses 3-10x on warm-vs-warm latency at the floor** —
  exactly where its fixed overhead is most exposed. This is the latency the graph *pays* to buy
  correctness-by-construction + flatness; reporting it at full honesty is what makes that tradeoff
  credible. The latency lives in the bb-CLI + render layer (the graph op itself is sub-ms) — partly
  tooling (a native/persistent client would close some of it), partly fundamental (render-recompile vs
  in-memory edit). An earlier draft compared warm-graph to cold-lsp-CLI and read as "comparable"; that
  flattered the graph and is corrected here.
- **Recompile (4044 ms) is shared** — both arms recompile to verify; it grows with module size (not N),
  equally, so it cancels in the arm-vs-arm comparison.
- **Method validated:** both arms measurable across the full vector at the floor. The thesis-relevant
  divergence (graph edit flat / lsp's edit+verification growing with N) is not visible at N=1 by
  construction — it shows at N≈12 (carve) and N≈79 (full honey.sql), the next points.

## Pre-registered for N≈12 (committed before running it)
- **Latency:** arm-G `:edit-min` ~constant (~280 ms, O(1) verb) + render growing with module size;
  arm-LSP warm ~constant-ish + small N-growth (native, cached). **Graph likely still loses raw latency.**
- **Completeness — expect a TIE, not a graph win.** honeysql's `util/str` is a plain function called
  plainly (all static-visible); clojure-lsp will almost certainly catch all ~12 references. So the
  completeness column stays a TIE at N≈12 — the graph's edge there remains the **Tier-1 structural
  guarantee** (can't-miss by construction), NOT a *measured* miss by lsp.
- **The Tier-2 completeness GAP needs a genuinely dynamic / macro / reflective reference to materialize**,
  and `util/str` won't trigger one. **If honeysql contains no dynamic ref anywhere, that is a real LIMIT
  on what this experiment can show for Tier 2** (completeness stays a structural guarantee, never a
  measured number) — stated now, not discovered at N≈79. Tier 1 (can't-miss by construction) holds regardless.
- **Falsifier:** if arm-G `:edit-min` is NOT ~flat (scales with N) at N≈79, the constant-cost claim is
  unsupported — reported straight.
