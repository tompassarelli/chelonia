# ⛔ INVALID FOR THE THESIS — read before touching anything in this directory

**These results test `warm-index-vs-text-grep` on a system where the graph was DERIVED FROM TEXT.
They do NOT test the thesis (graph as canonical source of truth). They are retained ONLY as the
diagnostic that revealed the graph was not canonical. DO NOT cite any of these as evidence that the
comprehension / refactor / coordination thesis holds. Any future reasoning that relies on these as
confirmatory is INVALID.**

## Why every result in here is the wrong layer

The thesis is: *the graph is the canonical source of truth (not derived from text), and that
uniquely enables (a) structure that cannot go stale and (b) edits as transactions on structure —
i.e. multi-agent coordination.*

But on the night these were produced, **the graph was built FROM `.bclj` text** (the canonical log
held zero code claims; edits sourced from text; even the "flip" was *log alongside text*). So every
comparison reduces to **a warm pre-built index vs raw grep** — which is the **graphify** result that
read-only inferred-index tools (clojure-lsp, Glean, …) already prove. It is *not* this project's
thesis.

- `reasoning-cost/` — "1 graph query vs 2 text ops." Warm-index-vs-grep. A warm cache over text gives
  the same query speed. Proves nothing unique to a canonical graph.
- `concurrent-authoring/` — the refactor-safety win leans on the recompile-gate ("rebuild and
  check"), which can be bolted onto a *text* workflow too. Substrate-independent.
- `owned-resolution-audit/` — the one genuinely useful artifact, but **diagnostic, not
  confirmatory**: it is *how we found out* the graph isn't canonical. Read it as a bug report, not a win.
- `flip/` — gates were "green" but the flip is *log alongside text* (edit still sources from text;
  the gate validates the text-derived tree). Not a real flip.
- `swarm-coordination/`, `git-subsumption/` — frontier design only; no results; the actual thesis
  test (coordination) was never runnable because the flip isn't real.
- `REVIEW.md` — the adversarial review that surfaced all of the above. Useful as the record of the
  miss; not evidence of any win.

## The rule going forward

Query speed is NOT the thesis. The unique value of a canonical graph — the thing a warm cache over
text CANNOT give — is exactly **staleness-immunity** (the structure IS the truth, not a copy that
drifts) and **transactional structural edits** (which enable coordination). The ONLY experiments
that can prove the thesis are coordination experiments, and they require the flip to be REAL first
(code claims in the log; edits sourced from the graph; the system runs with **no `.bclj` present**).

See `experiments/THESIS.md` for the authoritative frame. Until the flip is real, **no experiment
tests the thesis** — anything claiming otherwise is testing graphify.
