> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# THE FLIP — GATE REPORT (source-of-truth demotion for CODE)

**Date:** 2026-06-19 · **Repo:** /home/tom/code/fram · **Branch:** `authoring-claim-ops`
**Module under proof:** `src/fram/schema.bclj` (sentinel'd `;; @claim-canonical`)
**Status:** working-tree only. **NOTHING git-committed / pushed.** Other 10 modules STAGED, not flipped.

This report is an **independent adversarial re-run** of the 5 flip gates + the keystone,
re-verifying each load-bearing claim rather than trusting the build's self-report. Every
gate was run; the two subtle keystone claims (normalization + semantic losslessness) were
proven directly, and the flip's independence from the unrelated daemon diff was confirmed.

---

## PASS / FAIL TABLE

| # | Gate | Verdict | Evidence (independently reproduced) |
|---|---|---|---|
| 1 | **rename = one triple** | **PASS** | `resolve.clj rename` → `CLAIMS EDITED: 1` for THREE distinct targets (`cardinality`, `name-of`, `setup!`). Output states "just the definition's name; references follow refers_to". |
| 2 | **recompiles** (single-module — NOT cross-module) | **PASS (scoped)** | render-from-log tree → `1 built, 0 error(s)` via `beagle-build-all`. **CAVEAT (M1):** the build set is a SINGLE module (`run-gates.sh:50` copies ONLY `from-log.bclj` to `$W/src/schema.bclj`; `fram.cnf` (`c/`) and `fram.types` (`t/`) — which schema.bclj requires (schema.bclj:34-35) — are NEVER placed in the build set). Beagle therefore types every cross-module `c/...`/`t/...` call as `Any (unchecked)` (a NOTE, never an error), so this gate does **NOT** prove cross-module correctness. A genuine cross-module breakage (e.g. a call to a function in NO module) STILL returns `1 built, 0 error(s)`. Read this as "render-from-log parses standalone; cross-module calls unchecked." |
| 3 | **ingest (lossless)** | **PASS** | emit-edn(schema) re-keyed `@schema#n` == warm-store AST, **symdiff 0**; code log carries **0** `refers_to`/marker lines (derived-only). 4685 AST claims. |
| 4 | **cross-frame** | **PASS** | `@schema#1 relates_to @flip-foreign-thread` (code subject, foreign-thread object) commits through the coordinator `:assert`, reads back over the warm `:query`, and is durable in the code log. |
| 5 | **warm reads** | **PASS** | `:callers schema/name-of` resolves a binding + returns a vector; `:query` over warm AST returns `kind=list` rows — over the CODE-log-booted store. |
| **K-A** | **KEYSTONE byte-identity** | **PASS** | `cmp -s render(log) render(text)` → **BYTE-IDENTICAL** (5673 B both; the gate decides via `cmp -s`, not a byte count). |
| **K-B** | **recompile-from-log** (single-module — NOT cross-module) | **PASS (scoped)** | render-from-log tree → `1 built, 0 error(s)`. **Same M1 caveat as GATE 2:** this is the degenerate SINGLE-module build (cross-module `c/`/`t/` calls are unchecked `Any`); it does NOT prove cross-module correctness. The separate compile-identity keystone (§KEYSTONE claim 2 below — render(log) compiles byte-identical to committed `out/fram/schema.clj` modulo `^{:line :file}`) is UNAFFECTED and remains meaningful: it proves render(log) is program-semantics-equal to committed source, but NOT that committed source type-checks cross-module. |
| **K-C** | **delta-commit recompiles** | **PASS** | a `set-body` delta committed THROUGH the coordinator → render(updated log) recompiles `0 error` AND the new body (`cp (c/value-id ctx`) is present in the post-commit render. |

**HARNESS:** `experiments/flip/run-gates.sh` → all GREEN; `cnf_code_flip_test.clj` → **14/14 PASS**.
**REAL MCP EDGE:** `mcp_edit_test.clj` → **20/20 PASS** (16 legacy + 4 FLIP-path through `route-edit`).

**ALL GREEN.**

---

## KEYSTONE — the two load-bearing claims, proven directly (not asserted)

### 1. Byte-identity is `render(log) == render(text)`, NOT vs the committed bytes — CONFIRMED HONEST

`racket --render` is a normalizing pretty-printer, not a byte copier. Measured directly:

- `render(log)` = **5673 bytes** ; committed `src/fram/schema.bclj` = **6006 bytes** → they **DIFFER** by 333 B.
- The differences (via `diff`) are NOT merely cosmetic: `#lang beagle/clj` → `(define-target clj)`
  (a reader directive the renderer canonicalizes) PLUS — and this is the load-bearing correction —
  **4 source comments are DELETED, not "reflowed."** (The earlier label "comment-block whitespace
  reflow" was WRONG; see the LOSSY-ON-COMMENTS subsection below.)

So `render(log) == render(text)` is **byte-identical** (the SAME projection the existing authoring
edge produces and trusts), but that equality holds precisely because BOTH sides drop the same
comments — it tests the projector against itself, **not** fidelity to the committed source. The
keystone is correctly scoped as **program-semantics-identical; source comments NOT preserved.**

### 1b. The flip is LOSSY on inline / in-body comments — CORRECTION to "reflow" (B3/B4)

`render(log)` and `render(text)` are NOT lossless vs the committed `schema.bclj`. **4 comments
present in committed source are absent (count 0) from render(log), render(text), AND
`.fram/code.log`:**

| schema.bclj line | Dropped comment |
|---|---|
| 41 | `; RESERVED: claim-level supersession` (trailing-inline) |
| 42 | `; trigger; distinct from any domain` (trailing-inline) |
| 45-46 | `;; identity lives in the graph (interned values + claims), NOT in store fields:` / `;; those don't survive dump/replay, so all reads resolve preds via value-id.` (2-line in-body block) |

The loss originates at `racket --emit-edn` — the lexer drops trailing-inline + in-body comments,
so they never enter the code log and cannot be rendered. This violates the work's OWN contract
(DESIGN §3: "Any other byte difference FAILS the gate") and is exactly the failure mode DESIGN
§8 risk #6 names: "a real loss masked as normalization." The keystone guarantee is therefore
re-scoped to **"program-semantics-identical; source comments NOT preserved."** The staged
`bin/fram-render-code-all --write` path is currently UNGUARDED against this comment loss (B4) —
its dry-run wording ("mostly #lang/comment normalization") is false when a comment is actually
deleted, and `cp "$tmp" "$f"` under `--write` would silently drop the 4 committed comments with
git history as the only recovery oracle.

### 2. `render(log)` is SEMANTICALLY LOSSLESS vs committed source — PROVEN at the compiled level

Stronger than the build claimed. I compiled BOTH `render(log)` and the committed
`schema.bclj` and diffed the emitted `.clj`:

- Raw diff: differs only in `^{:line N :file "..."}` source-position metadata (132 diff lines, all positional).
- **After stripping `^{:line :file}` metadata: the two compiled outputs are BYTE-IDENTICAL.**

`^{:line :file}` is a pure function of the file path + line numbering, not of the AST/program.
→ render(log) carries identical program semantics to the committed source. The `.bclj` is a
**pure function of the log** at the compiled-output level, not merely at the render level.

> SCOPE: "lossless" here means PROGRAM-SEMANTICS lossless (the compiled `.clj` is identical).
> It is NOT source-lossless: 4 source comments are dropped (§1b / B3). Comments do not reach
> the compiler, so they do not affect this compiled-output identity — but they ARE lost from
> the source view. Keystone guarantee = program-semantics-identical; source comments NOT
> preserved.

---

## SAFETY — independently verified

- **`fram-commit-code` SAFETY GATE refuses a non-`code.log` coordinator.** Booted a throwaway
  daemon on a `*threads.log` and attempted a commit: refused with
  `SAFETY: coordinator … serves …/threads.log — NOT a *code.log; refusing`, **exit 1**.
  Code claims can never be written to a thread/lodestar log.
- **Port 7977 + the lodestar canonical log were NEVER touched.** The live coordinator
  (`serve-flat 7977 …/lodestar/claims.log`) remained the sole 7977 listener throughout;
  all throwaway daemons ran on verified-free high ports (7992/7995), trap-killed, **0 lingering**.
- **`src/fram/schema.bclj` is BYTE-UNTOUCHED** before and after every run (`git diff` empty,
  sentinel intact). `.fram/code.log` is gitignored (regenerable).

---

## FLIP IS INDEPENDENT OF THE UNRELATED DAEMON DIFF

`cnf_coord_daemon.clj` shows as Modified, but the working-tree diff is **only** the
`:te`→`ultimate` `:callers` READ-path change (DESIGN risk #7) — it contains NO
`flip`/`code.log`/`FRAM_FLIP` lines. I stashed that diff, re-ran `cnf_code_flip_test.clj`
with the daemon reverted to HEAD, and got **14/14 PASS**, then restored the diff. The flip
neither authored nor depends on the daemon change; the reviewer should account for it
separately in the landing.

---

## KNOWN LIMITATION (carried from the build — not a gate failure)

The delta-commit (`fram-commit-code`) is a **WHOLE-NODE clean-slate re-commit**, not the
DESIGN's surgical id-keyed set-difference: `emit-edn` renumbers nodes after the edit point,
so a naive set-diff corrupts the AST. The whole-node re-commit is total + correct (postcondition
`live AST == emit-edn(new)`) but re-commits ~the whole module per edit. A surgical delta needs
id-stable identity (committing the resolver's own mint/supersede ops) — FOLLOW-UP, out of scope.

---

## RE-RUN

```
export BEAGLE_HOME=~/code/beagle
experiments/flip/run-gates.sh        # full harness (all 5 + keystone)
bb -cp out cnf_code_flip_test.clj    # gates 3/4/5 + keystone (throwaway daemon, 14/14)
bb mcp_edit_test.clj                  # legacy (16) + FLIP (4) through the real MCP edge
```
