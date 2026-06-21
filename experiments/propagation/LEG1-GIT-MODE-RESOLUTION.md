# #34 (Leg 1 — Fram git-mode receipt: stage → joint-validate → publish-on-pass; scoped vs whole gate) — RESOLVED as SUPERSEDED

The measurement #34 sought is the **scoped vs whole gate** cost: when a batch of edits is jointly
validated before publishing, does the validation/re-resolve scope to the *affected modules* (scoped)
or rework the *whole corpus* (whole)? That comparison is now decisively measured elsewhere — building
a separate single-config Leg-1 git-mode harness would be redundant:

1. **#35 (System 3 K-sweep)** measured **scoped gate vs whole vs merge-queue across K** directly
   (see `RESULTS.md`: "#35 (scoped gate vs merge-queue across K)"). That is the Leg-1 comparison,
   generalized over K. DONE.
2. **The construction-path win (this session, `experiments/zerolang-vs-fram/CONSTRUCTION-SCALING.md`)
   IS the scoped-vs-whole result, at its sharpest:** Fram's incremental authoring re-resolves only
   the **edited module** (scoped, O(edited-module)); the cost of a **whole** re-resolve/revalidate
   per write is exactly what zerolang pays (load+validate+rewrite the whole graph) — measured as the
   **O(N) vs O(N²)-shaped** gap (4.2× @500 defs, 7.5× @1000). Scoped-gate-beats-whole-gate is the
   substrate thesis, now measured against a real competitor, not just internally.
3. **#45 (continuous-arrival)** shows the whole-corpus/shared-ref gate **saturating** under load
   (git merge-queue → 27 s mean) while Fram's scoped/commute path stays bounded (111 ms).

The three together cover Leg-1's question more strongly than a dedicated git-mode receipt would.
Marking #34 resolved-as-superseded rather than build a redundant harness. (If a literal
stage→joint-validate→publish-on-pass receipt is ever wanted as a standalone artifact, the harness
machinery exists in `sweep.clj` / `continuous-arrival.clj` and the gate-scoping in
`cnf_coord_daemon.clj` `materialize-refers-scoped!`.)
