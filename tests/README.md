# tests/ — dev tests, receipts, and experiment harnesses

Moved here from the repo root 2026-06-21 to declutter (was ~57 `.clj` files at root).
These are the CNF/coordinator dev scripts: `*_test.clj` (tests), `*_receipt.clj` (banked
experiment evidence), and `*_experiment.clj` / `*_sim.clj` / `*_ksweep.clj` (experiment harnesses).

## How to run (IMPORTANT: from the repo ROOT)

```
bb -cp out tests/<file>.clj            # e.g. bb -cp out tests/cnf_edit_min_smoke.clj
FRAM_LOG=/path/to/code.log bb -cp out tests/<file>.clj   # for the ones that need a log
```

Run from the **repo root** (`~/code/fram`), not from inside `tests/`. Each script does
`(load-file "cnf_coord_daemon.clj")` / `(load-file "cnf_coord.clj")` by a path **relative to the
current working directory** — those two coordinator files intentionally stay at the repo root (the
bins `bin/fram-daemon` / `fram-edit-code` / `fram-render-code` also `load-file` them by relative
path). Running from root makes the relative load resolve correctly. (Some in-file usage comments
still show the old pre-move `bb -cp out cnf_<file>.clj` path — prefix `tests/`.)

## What stays at the root (do NOT move)

- `cnf_coord.clj` — base coordinator; load-file'd by `bin/fram-daemon` + the daemon + tests.
- `cnf_coord_daemon.clj` — the warm code-graph daemon; load-file'd by `bin/fram-edit-code`,
  `bin/fram-render-code`, and ~31 tests. (Production code, not a test.)
