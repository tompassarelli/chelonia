> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# THE FLIP — source-of-truth demotion for CODE (design)

**Date:** 2026-06-19 · **Repo:** /home/tom/code/fram (branch `authoring-claim-ops`)
**Status:** DESIGN ONLY — no code committed. Working-tree changes + a CHANGES manifest;
the human main-loop reviews and lands.
**Scope:** prove the WHOLE mechanism end-to-end on ONE already-sentinel'd module,
`src/fram/schema.bclj`, behind the 5 flip gates + the keystone roundtrip-identity gate.
STAGE (document, do not flip) broader adoption of the other 10 modules.

This file is read by a human main-loop. Every file:line below was re-verified against the
live tree.

---

## KNOWN BLOCKERS — gate FRAM_FLIP default-on (do NOT enable until closed + re-reviewed)

> The flip code is staged OFF by default (`FRAM_FLIP` unset + no `FRAM_CODE_PORT`). The
> legacy `io/copy` path is byte-for-byte unchanged. The blockers below are **DOCUMENTED, not
> code-fixed** — they require re-review before `FRAM_FLIP` is enabled by default anywhere
> persistent. Each operates on REAL source the moment the flag is set.

- **B1 — render failure TRUNCATES the live source `.bclj` to 0 bytes (write path).**
  `fram_mcp.clj:136-138` renders with `(apply sh {:out (io/file target-bclj) :err :string}
  … "fram-render-code" module …)` where `target-bclj` = the live `src/fram/<m>.bclj`
  (line 218-219). No `--out`, no temp file, no atomic rename. `babashka.process/sh` with
  `:out (io/file …)` TRUNCATES the target at subprocess spawn regardless of exit code: a
  child that emits nothing and exits 1 leaves the file `""` (reproduced: 24 B → 0 B). On any
  `fram-render-code` `die` (6 die paths) the source file is DESTROYED; recovery is
  `git checkout`. The legacy path does it correctly (renders to a temp at `fram_mcp.clj:194-203`,
  then `io/copy` over `tf` at :229) — the flip path regressed away from that exact-safe
  pattern. *Close before default-on:* render to a temp / pass `--out`, check
  `(zero? (:exit render))`, then atomically copy over `target-bclj` only on success; add a
  failure-injection gate asserting the source is byte-identical before/after a forced render
  failure.

- **B2 — non-atomic torn delta-commit can leave the code log un-renderable (DURABLE
  inconsistency).** `bin/fram-commit-code:189` runs ALL retracts first, `:195` then asserts;
  each `commit-op!` (:125-132) is an independent coordinator round-trip that `die`s
  (System/exit 1) on a non-conflict reject / socket failure / daemon death. The wire has NO
  batch/tx op (`cnf_coord_daemon.clj:718` `case (:op req)` → default `{:error "unknown op"}`
  at :771). A mid-sequence abort AFTER the retract phase leaves a node MISSING required claims
  → render fails with `hash-ref: no value found for key "placement"` (exact crash documented
  in CHANGES.md). The torn commit can LOSE required claims, not only gain extras. **This
  corrects §8 risk #2, which wrongly says the worst case is "only GAINS" (extra multi edges)
  — see the inline correction there.** `fram_mcp.clj:134`'s "FLIP REJECTED … no .bclj
  written" message also reads as if the commit were atomic/rolled back when the log is in
  fact half-mutated. *Close before default-on:* add an all-or-nothing batch/tx daemon op
  (the kernel already has begin-tx!/append-tx! with one fsync at tx-commit — wire a
  `:tx-batch`), OR wrap the doseq with compensating re-asserts on assert-phase failure; add a
  partial-commit failure-injection gate.

- **B3/B4 — the flip is LOSSY on inline/in-body comments; `--write` is unguarded.**
  render(log)/render(text) DELETE 4 comments present in committed `schema.bclj`
  (schema.bclj:41, :42, :45-46; see §3). The keystone guarantee is re-scoped to
  **program-semantics-identical; source comments NOT preserved.** `bin/fram-render-code-all
  --write` (`:47 cp "$tmp" "$f"`) has NO comment-diff check despite its header claiming a
  KEYSTONE byte-identity gate. *Close before default-on:* add a comment-preservation check
  that FAILS adoption when render(log) drops any committed comment; make `--write` REFUSE when
  the diff exceeds the enumerated normalization; fix the dry-run wording.

- **B5 — the rollback step-2 byte-identity premise is FALSE.** §7 step 2 claims render is
  byte-identical to committed so `git diff src/fram/schema.bclj` is empty. Render is NOT
  byte-identical to committed (5673 vs 6006 B; drops 4 comments). See the inline correction
  at §7 step 2. *Close before default-on:* corrected in §7 (this doc); the irreversible
  window is git-history-only recovery for the dropped comments.

---

## 0. What is true today (the slippage the flip fixes)

Read first: `experiments/owned-resolution-audit/REPORT.md`. Its headline verdict:

> The act of resolution is canonical; the substrate it resolves over is a derived index.

`refers_to` genesis is canonical (`resolve.clj:178 bind!`). But there are **zero** code
claims in any canonical log (`claims.log` has 0 `kind/v/fN/refers_to`), so the AST corpus
is manufactured from `.bclj` text on demand. The authoring edge —
`fram_mcp.clj:104-176 route-edit` — projects `.bclj` → emit-edn → applies the verb (a
resolve.clj subprocess) → renders → recompiles → **`io/copy` OVERWRITES the source
`.bclj`** (`:167-169`) and **never commits the AST claim delta through the coordinator**.
The `.bclj` text is the durable artifact; the claim graph is a regenerated cache. THE FLIP
inverts this: the **claim log becomes canonical for code**, the **`.bclj` becomes a pure
generated function of the log**, and **an edit commits the claim delta** (then re-renders).

---

## 1. Substrate map (verified seams)

| Seam | File:line | Role in the flip |
|---|---|---|
| Authoring edge (the gap) | `fram_mcp.clj:104-176` (esp. `:142-143` verb, `:167-169` overwrite) | replaced by the delta-commit path |
| Resolver / authoring verbs | `chartroom/src/resolve.clj` — `resolve-edn!` (`:567`), `resolve-warm-store!` (`:688`), `extract-file!` (`:829`), `out-path` honors `$RESOLVE_OUT` (`:863`), mint (`mint-datum!` `:769`), `rename` (`:1014`), `set-body` (`:1169`), `upsert-form` (`:1128`) | INGEST (emit-edn shape ↔ mint), RENDER-FROM-LOG (extract-file!) |
| Daemon wire | `cnf_coord_daemon.clj` — `client` (`:893`), `:assert`/`:retract` (`:720-721`) → `do-assert`/`do-retract` (`:350`,`:364`) → `commit!`/`retract!` (`cnf_coord.clj:118`,`:159`), `:status` (`:740`), `apply-commit-delta!` (`:301`), `callers-of-in-store` (`:616`), `:query` (`:728`), warm materialize (`materialize-refers-whole!` `:463`, `ensure-refers!` `:571`) | COMMIT-THE-DELTA, warm reads |
| Flat-log ingest | `migrate-flat->co` (`:932`) — names entity by its `:l` subject STRING via `s/name!` (`:955`); `@`-prefixed object ⇒ link; skips only `schema-preds` (`:40`) | INGEST: a CODE flat log folds into the warm store with `@mod#int` names automatically |
| Names ↔ modules | `corpus-from-store!` (`resolve.clj:620`) groups by `name` claim matching `@([^#]+)#\d+`; daemon `module-of-name` (`:71-73`) | the corpus the resolver walks comes straight from the log |
| Cardinality | `out/fram/kernel.clj:4` `single-valued` — `kind`/`v`/`fN` are **NOT** in the list ⇒ multi-valued; `single?` (`kernel.clj:28`) | governs OCC + supersede decomposition (§4) |
| `ent!` auto-create | `cnf_coord.clj:67` — any unseen `@schema#N` subject/target auto-creates an entity with a `name` claim | minted nodes land with the right name with no extra op |
| Sentinel + guard | `src/fram/schema.bclj:2` `;; @claim-canonical`; the claim-canonical PreToolUse guard refuses text edits to sentinel'd files | GENERATED-MARKER already present on schema.bclj |
| Project / build | `racket $RT --emit-edn <f>` / `--render <resolved.edn>`; `$BEAGLE/bin/beagle-build-all <dir> --out <o>` (verdict line `N built, M error(s)`) | INGEST input, RENDER output, recompile gate |

Confirmed present: `out/` built; `$BEAGLE/bin/beagle-build-all`; `$BEAGLE/beagle-lib/private/claims-roundtrip.rkt`.
Free verified high ports for throwaway daemons: 7991-7994, 7996, 7999 (probe again at run time).

---

## 2. The canonical CODE log (NEW, per-repo, separate)

- Path: `/home/tom/code/fram/.fram/code.log` — NEW, per-repo, SEPARATE from
  `~/.local/state/lodestar/claims.log` (life-os) and from the repo's `data/claims.log`
  (thread graph). Creating it is safe.
- Format: the SAME flat-log line shape `migrate-flat->co` already folds
  (`{:tx N :op "assert" :l "<subj>" :p "<pred>" :r "<obj>" :ts ... :by "coord"}`), so the
  **existing daemon boots from it unchanged** via `serve-flat <PORT> <code.log>`.
- Node identity: each AST node is named `@<module>#<int>` — e.g. `@schema#5`. This is the
  exact `@mod#int` shape `corpus-from-store!`/`module-of-name` require, and
  `migrate-flat->co` mints it for free because it names every entity by its `:l` subject.
- Claims per node: `kind`/`v` leaves, `fN`/`child`/`segN`/`commentN`/`tail` structural
  edges (object = `@<module>#<int>` ref string). The wrapper carries an `@file` analogue;
  in the log we add one bookkeeping claim per module `@<module>#root` `file "<path>"` so
  RENDER-FROM-LOG knows the source path. (The `child` edges emit-edn duplicates fN are
  ingested too — harmless; resolve.clj reads fN, the renderer reads fN.)
- `refers_to` and render markers (`keep_spelling/qualifier/ctor_prefix/accessor_field/
  supersedes`) are **NOT** in the code log — they are DERIVED, materialized in-memory over
  the warm store (`resolve-preds` `:50`, barred from the flat log by `append-flat!` going
  only through `do-assert`/`do-retract`, and from reads by `claim->triple` `:174`). This is
  by design and unchanged: genesis stays at resolution time; the log carries only the AST.

### INGEST (`.bclj` → code log), lossless

A new build step (proposed `bin/fram-ingest-code`) does, for `src/fram/schema.bclj`:

1. `racket $RT --emit-edn src/fram/schema.bclj` → per-file EDN (integer ids 1,2,3…).
2. Rewrite each EDN line into a flat-log claim, renumbering the file-local integer id `n`
   to the node name `@schema#n`: a subject `n` → `:l "@schema#n"`; an integer object `o` →
   `:r "@schema#o"` (a link); a string object → `:r "<string>"` (a literal). Predicate
   passes through (`kind`/`v`/`fN`/`child`/`segN`/`commentN`/`tail`). Emit one
   `@schema#root file "src/fram/schema.bclj"` claim. Append all with monotone `:tx`.
3. (Multi-module staging) repeat per module, namespacing ids by module — IDs never
   collide because the name prefix differs.

**Lossless proof** (gate 3 — INGEST): boot the daemon on `code.log`, then prove the warm
store's AST claim set is set-equal to the emit-edn projection (modulo the documented
`@file`→`@mod#root file` normalization and the `child`-edge dup). Pattern = `cnf_migrate_test.clj`
(flat → reified → set-equal) + `roundtrip_test.clj` (in→out→in claim-identical). Concretely:
`emit-edn(schema.bclj)` re-keyed to `@schema#n` == `{kind,v,fN,...}` claims read off the warm
store via `claim->triple`. Symmetric difference 0.

---

## 3. RENDER-FROM-LOG (`.bclj` becomes a pure function of the log)

The `.bclj` is regenerated FROM the log, byte-identical to committed source.

Pipeline (proposed `bin/fram-render-code <module>`):

1. Boot/attach the daemon on `.fram/code.log` (warm store holds the AST + `name` claims).
2. Materialize `refers_to` over the warm store (`ensure-refers!` → `resolve-warm-store!`),
   so references render via identity (the renderer needs `refers_to` to spell a reference
   by its binding's CURRENT name — `extract-file!:840-849`).
3. `extract-file!` (`resolve.clj:829`) projects the module's claims to a resolved EDN
   (`$RESOLVE_OUT/resolved-schema.bclj.edn`), stripping internal edges (`:839`) and
   rendering each reference's `v` from `refers_to` (`:840`).
4. `racket $RT --render resolved-schema.bclj.edn` → `.bclj` text.
5. Re-apply the `@claim-canonical` sentinel header (the leading comment block is metadata
   the renderer does not carry; it is a constant prepended by the render step — see
   normalization below).

### Keystone NEW gate — render(log) program-semantics-identical; source comments NOT preserved

> **CORRECTION (B3) — this gate as SHIPPED is NOT "byte-identical vs committed `.bclj`"; the
> flip is LOSSY on source comments.** The original design below promised
> `cmp -s render(log) src/fram/schema.bclj`. As shipped, the harness proves
> `render(log) == render(text)` (both 5673 B), NOT vs the committed file (6006 B; differ by
> 333 B). The difference is NOT only the `#lang` directive + whitespace: **4 comments present
> in committed `schema.bclj` are DELETED** — `; RESERVED: claim-level supersession`
> (schema.bclj:41), `; trigger; distinct from any domain` (:42), and the 2-line in-body block
> `;; identity lives in the graph …` / `;; those don't survive dump/replay …` (:45-46). The
> loss originates at `racket --emit-edn` (the lexer drops trailing-inline + in-body comments),
> so they never enter the code log. `render(log)==render(text)` passes only because BOTH sides
> drop the same comments — it tests the projector against itself, not fidelity to source. The
> keystone guarantee is therefore re-scoped to **"program-semantics-identical; source comments
> NOT preserved"** (the compiled `.clj` outputs are byte-identical modulo `^{:line :file}`).
> This is exactly the failure §8 risk #6 names ("a real loss masked as normalization"). A hard
> precondition must block adoption of any module whose committed text carries comments the
> renderer drops; `bin/fram-render-code-all --write` is currently UNGUARDED against this (B4).

The (now-falsified) original design intent, kept for context:

`render(.fram/code.log)` for schema → compare bytes against `src/fram/schema.bclj`.
**Documented normalization** (the "modulo" in the gate): the renderer emits the AST body;
the leading `;; @claim-canonical` sentinel comment block is NOT an AST node, so the render
step prepends it from a fixed template and the comparison is over (sentinel-header +
rendered-body) vs committed file. If beagle's lexer demotes the sentinel block to a
`comment` node (segN claims) it round-trips natively and no prepend is needed — the gate
DETERMINES which, and the normalization is whichever holds, stated explicitly in CHANGES.
~~Any other byte difference FAILS the gate~~ (CORRECTED above: the keystone gate as shipped
is `render(log)==render(text)`, which is BLIND to the 4 deleted comments — it does NOT fail
on them).

This is the gate that makes the thesis true at the PROGRAM-SEMANTICS level: the `.bclj` is
downstream; the log is the source. (It is NOT true at the source-COMMENT level — see B3.)

---

## 4. COMMIT-THE-DELTA (the route-edit replacement — the heart of the flip)

Today `route-edit` ends by `io/copy`-overwriting the `.bclj` (`fram_mcp.clj:167-169`) and
commits NOTHING. The flip changes the tail of `route-edit` so that, after the verb has
produced the resolved EDN AND the recompile gate is GREEN, it **commits the AST claim delta
through the coordinator** and THEN renders the `.bclj` from the updated log.

### 4.1 Decomposing a subtree delta into serial single-(te,p,r) ops

The wire is single-(te,p,r) with `:base` OCC (`do-assert`/`do-retract` → `commit!`
`cnf_coord.clj:118`). A verb mints/supersedes a whole subtree, so we decompose:

- **Compute the delta in claim space, not text space.** The verb already runs over the
  emit-edn projection re-keyed to `@schema#n` (the SAME corpus the daemon holds). Diff the
  POST-verb resolved AST claim set against the PRE-verb warm-store AST claim set (both
  restricted to `kind/v/fN/child/segN/commentN/tail` — NOT refers_to). This yields:
  - **adds**: claims present post, absent pre → `:assert` ops.
  - **removes**: claims present pre, absent post → `:retract` ops.
- **Cardinality matters (verified `kernel.clj:4`):** `kind`/`v`/`fN` are MULTI-valued. So:
  - a re-asserted identical `(te,p,r)` no-ops idempotently (`commit!` `:idempotent` branch)
    — safe.
  - **superseding a body fN edge is NOT automatic on assert** (multi-valued asserts append,
    they do not supersede). The delta MUST emit an explicit `:retract` of the old fN edge
    (`@schema#defn` `f5` `@schema#oldBody`) followed by an `:assert` of the new
    (`@schema#defn` `f5` `@schema#newBody`). This mirrors resolve.clj's own machine:
    `retire-claim!` (`:800`, a supersede) + mint+wire (`:1158`,`:1204`). The wire `:retract`
    is the coordinator analogue of `retire-claim!`.
- **New nodes auto-name.** A minted node `@schema#NEW` referenced as a subject/target is
  auto-created with its `name` claim by `ent!` (`cnf_coord.clj:67`) on first commit — no
  separate name op needed. (We DO assert its `kind`/`v`/`fN` claims explicitly.)
- **Ordering:** asserts of a new subtree's leaves/edges first (so targets exist), then the
  wrapper/parent fN re-point (`:retract` old + `:assert` new) last. Within OCC, ordering of
  multi-valued asserts is commutative; the single re-point is the only place base matters.
- **base_version:** read `{:op :version}` once to get `v`, submit each op at `:base v`; on
  `:reject :conflict` (only possible if another writer touched a single-valued pred on the
  same node — rare for code, but the loop is identical to `route-write` `fram_mcp.clj:62-69`)
  re-read version and RETRY the remaining ops. AST asserts are multi-valued so they do not
  conflict; the retry path exists for safety/symmetry. This is exactly what S1
  `apply-commit-delta!` (`cnf_coord_daemon.clj:301`) expects: each commit advances the warm
  cache O(group), and `mark-dirty!` (`:359`,`:372`) flags the module so the next
  `ensure-refers!` re-resolves it (scoped, S3.3).

### 4.2 The new route-edit tail (pseudocode)

```
;; ... unchanged: project src → emit-edn re-keyed @mod#n, run verb, render, RECOMPILE-GATE.
;; REPLACE fram_mcp.clj:166-176 (the io/copy PASS arm) with:
(if (str/includes? built "0 error")
  (let [pre-claims  (ast-claims-of-warm-store port module)     ; kind/v/fN... only
        post-claims (ast-claims-of-resolved-edn resolved-edn)  ; same projection
        adds    (set/difference post-claims pre-claims)
        removes (set/difference pre-claims post-claims)
        v       (coord-version port)]
    ;; serial single-(te,p,r) ops at base v, retrying on :conflict (route-write loop)
    (doseq [[te p r] removes] (coord-retract* port te p r))    ; supersede old edges
    (doseq [[te p r] adds]    (coord-assert*  port te p r))    ; mint new subtree + re-point
    ;; THEN render the .bclj FROM the now-updated log (pure function of the log)
    (render-from-log! port module target-bclj))                ; §3 pipeline
  ;; FAIL arm unchanged: mutate nothing, return the recompile diagnostic.
  ...)
```

Key inversion vs. today: the `.bclj` is written by `render-from-log!` (downstream of the
committed log), NEVER by `io/copy` of the verb's render output. If the commit succeeds but
render-from-log disagrees with the verb's own render, that is a BUG the keystone gate
catches (they must be byte-identical because both are pure functions of the same claims).

### 4.3 Why this is the same path tier-2's graph arm prototyped (and what it fixes)

`experiments/concurrent-authoring/graph_arm.sh:78-90,192-197,370-381` committed only 4
synthetic single-triple OCC **proxy** claims (`@opN-effect body …`), self-disclosed at
`:372-381` as NOT committing the AST delta — "the flip." This design commits the REAL AST
delta (4.1), so `:callers` over the coordinator now has real `@schema#N` nodes (gate 5).

---

## 5. THE GATES (5 + keystone) — run them, capture evidence

All gates run against `.fram/code.log` + a THROWAWAY daemon on a verified-free high port,
with a trap-kill and a `:status`-sanity assertion (confirm `:log` is OUR code.log before
trusting any result — mirrors `graph_arm.sh`'s STATUS_OK guard). NEVER touch port 7977 or
`~/.local/state/lodestar/claims.log`.

| # | Gate | What it proves | How (evidence) |
|---|---|---|---|
| 1 | **rename = one triple** (resolver-internal only) | `resolve.clj rename` supersedes ONE binding's `v`; references follow `refers_to` | `experiments/rename-identity/reproduce.sh` pattern; assert `CLAIMS EDITED: 1`; recompile clean; break one ref ⇒ compiler error (the discriminator). **Gate 1 ran ONE rename (`cardinality`), not three.** **CORRECTION (m7): the "one `:retract`+`:assert`" figure is the resolver's WARM-INTERNAL op, NOT the FLIP commit path.** A rename driven THROUGH `bin/fram-commit-code` is "7 changed node(s): 14 assert(s), 14 retract(s)" — render-from-log spells references by NAME, so re-emitting churns ALL spelled nodes (whole-node re-commit, CHANGES.md KEY FINDING #2). No gate drives a rename THROUGH `fram-commit-code`; the figure here is the resolver's internal op over text. |
| 2 | **recompiles** (single-module — does NOT prove cross-module; M1) | the rendered-FROM-LOG module PARSES standalone; build is `0 error` | `beagle-build-all <render-from-log dir> --out <o>`; assert verdict line ends `0 error(s)` via exact `errcount` parse, never `includes? "0 error"`. **CORRECTION (M1): GATE 2 / KEYSTONE-B is a degenerate SINGLE-module build.** `run-gates.sh:50` copies ONLY `from-log.bclj` to `$W/src/schema.bclj`; `fram.cnf` (`c/`) and `fram.types` (`t/`) — which schema.bclj requires (schema.bclj:34-35) — are NEVER in the build set. Beagle types every cross-module `c/`/`t/` call as `Any (unchecked)` (a NOTE, never an error), so a genuine cross-module breakage (e.g. a call to a function in NO module) STILL returns `1 built, 0 error(s)`. This gate does NOT prove cross-module correctness; read it as "render-from-log parses standalone; cross-module calls unchecked." The SEPARATE compile-identity keystone (render(log) compiles byte-identical to committed `out/fram/schema.clj` modulo `^{:line :file}`) is UNAFFECTED — it proves program-semantics equality, not cross-module type-checking. *Close:* build inside the full 11-module render-from-log tree (mirror concurrent-authoring's EXPECT_BUILT=11), OR fail the gate if any `call is unchecked (Any)` note fires for a `fram.*` namespace. |
| 3 | **ingest (lossless)** | `.bclj` → claims → store is lossless | `cnf_migrate_test.clj`/`roundtrip_test.clj` patterns: emit-edn(schema) re-keyed `@schema#n` == warm-store AST claims (`claim->triple`); symdiff 0 (modulo §2 normalization). |
| 4 | **cross-frame** | a bridge claim composes through the coordinator | commit (through `:assert`) a `@schema#<bindingNode> relates_to @<foreign-thread>` — code-owned subject, foreign-thread object — at base v; read it back via `:status`/`:query`; prove it lands in the code log and the warm view. Proof it composes across the code/thread frames over ONE coordinator. |
| 5 | **warm reads** | `:callers`/`:query` off the warm materialized `refers_to` | `ensure-refers!` then `{:op :callers :te "@schema#<def>"}` returns a non-empty set of `[module, rendered-name]`; `{:op :query …}` over AST claims returns rows. Already green for S3; here it runs over the CODE-log-booted store, proving the warm graph is corpus-backed (closes audit Q4 gap). |
| **K** | **KEYSTONE: roundtrip identity** | render(log) == committed `.bclj` BYTE-IDENTICAL (modulo §3 normalization); and after a claim-op edit committed through the coordinator, render(log) recompiles `0 error` | `cmp -s <render-from-log schema.bclj> src/fram/schema.bclj` → identical. Then: commit a `set-body` delta through the coordinator; `render-from-log` the module; `beagle-build-all` → `0 error`. The `.bclj` is a pure function of the log. |

A new test `cnf_code_flip_test.clj` (sibling of `cnf_flip_test.clj`, which is the THREAD
cutover — NOT the code flip) drives K + gates 3/4/5 over a throwaway daemon on
`.fram/code.log` copied to `/tmp`. Gates 1/2 reuse `rename-identity/reproduce.sh` +
`beagle-build-all`. `mcp_edit_test.clj` is extended to assert the post-edit state comes from
`render-from-log`, not `io/copy`.

---

## 6. STAGED broader adoption (document, DO NOT flip)

The mechanism is proven on `schema.bclj` only. To flip the other 10 modules later (a HUMAN
land decision), the one-command switch is:

```
# 1. INGEST all 11 modules into the code log (idempotent; re-keys per module):
bin/fram-ingest-code src/fram        # -> .fram/code.log  (@<mod>#<n> per node)
# 2. Prove byte-identity for every module BEFORE adopting:
for m in cnf datalog export fold import kernel main query schema tools types; do
  cmp -s <(bin/fram-render-code $m) src/fram/$m.bclj || echo "DIVERGES: $m"   # must be silent
done
# 3. Adopt: add the sentinel header to each module + register it claim-canonical
#    (schema.bclj already carries ;; @claim-canonical — do NOT re-adopt it).
```

This is STAGED: step 2 must be silent for ALL 11 before any non-schema module is adopted.
The flip lands per-module only when its byte-identity gate is green. Do NOT run step 3
in this experiment — it is the human's land decision.

---

## 7. ROLLBACK (the flip is reversible up to the land decision)

The flip adds NEW artifacts (`.fram/code.log`, `bin/fram-ingest-code`,
`bin/fram-render-code`, the new route-edit tail, `cnf_code_flip_test.clj`) and changes ONE
authoring tail. Rollback, in order of blast radius (cheapest first):

1. **Working-tree only (this experiment):** `git checkout -- fram_mcp.clj` reverts the
   route-edit tail to the `io/copy` behavior; `rm -rf .fram/` deletes the NEW code log;
   delete the new bin scripts + test. The `.bclj` sources are UNTOUCHED — but NOT because
   "the flip renders byte-identically" (it does NOT; see B5/B3): they are untouched because
   the experiment never ran `fram-render-code-all --write`, so no render output was ever
   copied over a source `.bclj`. (Had it run, schema.bclj would have lost 4 comments —
   see §7 step 2.) Nothing was committed to git; nothing landed. Full revert.
2. **If schema.bclj was intentionally regenerated** (the adoption step's only write):
   > **CORRECTION (B5) — the byte-identity premise of this step is FALSE.** render(log) is NOT
   > byte-identical to committed `schema.bclj` (5673 vs 6006 B; it drops 4 comments — B3). The
   > original claim below ("because render is byte-identical, the file is the same bytes —
   > `git diff` is empty") does NOT hold: if schema.bclj is regenerated via
   > `fram-render-code-all --write`, `git diff src/fram/schema.bclj` would show the 333-byte /
   > 4-comment loss, and `git checkout -- src/fram/schema.bclj` is then the ONLY recovery for
   > those comments (irreversible window: the dropped comments are recoverable ONLY from git
   > history, never from the log). NOTE: today `--write` was never run and schema.bclj's
   > `git diff` is empty (CHANGES step 1 is working-tree-only and leaves `src/fram/*.bclj`
   > UNTOUCHED), so the working tree is currently clean — but the premise that REGENERATION
   > leaves it clean is false.

   ~~because render is byte-identical, the file is the same bytes — `git diff src/fram/schema.bclj`
   is empty. If it is NOT empty, the keystone gate FAILED and the flip must not have landed~~
   (superseded by the correction above); `git checkout -- src/fram/schema.bclj` restores it.
3. **De-adopt a module:** drop the `;; @claim-canonical` sentinel + the registry line; the
   PreToolUse guard then permits text edits again and the module reverts to text-canonical
   (`schema.bclj:7-8` documents this as "a deliberate change"). The code log entry for that
   module becomes a stale derived index (harmless; can be deleted from `.fram/code.log`).
4. **Daemon:** the code daemon is a SEPARATE process on a SEPARATE log/port from the live
   coordinator (7977/lodestar log). Killing it (trap-kill) affects nothing live.

The thread graph, `data/claims.log`, the lodestar log, and port 7977 are NEVER touched by
any step.

---

## 8. IRREVERSIBILITY RISKS + containment

This is IRREVERSIBLE-ADJACENT and CI-gated. Each risk and its containment:

1. **R: Lossy ingest silently drops AST detail → render diverges → a future text edit on a
   de-adopted module loses code.**
   C: Gate 3 (lossless, symdiff 0) + Keystone (byte-identical) are HARD gates run BEFORE any
   adoption; staged step 2 requires byte-identity for ALL 11 modules before flipping any.
   The verb already fail-closes ("REJECTED … no claims mutated"); the recompile gate is
   `0 error` (exact `errcount`, never `includes?`). No adoption without green identity.

2. **R: A delta-commit that partially applies (some ops land, then a crash/conflict) leaves
   the code log internally inconsistent (orphaned fN, dangling subtree).**
   C: Order ops leaves-then-reparent (targets exist before referenced); AST asserts are
   multi-valued + idempotent (re-runnable); the single base-sensitive op (the fN re-point)
   retries on conflict (route-write loop). A failed commit leaves the verb's render UNUSED
   (we render FROM the log only after all ops land) so the `.bclj` is never written from a
   half-applied state.

   > **CORRECTION (B2) — this risk is NOT contained; the original "worst case is only GAINS"
   > is FALSE.** The implemented commit (`bin/fram-commit-code`) is a whole-node clean slate
   > that runs ALL retracts first (`:189`), THEN all asserts (`:195`), with NO batch/tx wire
   > op — each `commit-op!` (:125-132) is an independent round-trip that `die`s on a
   > non-conflict reject / socket failure / daemon death. A mid-sequence abort AFTER the
   > retract phase leaves a node MISSING required claims (it can LOSE required edges, not only
   > gain extras). The torn log is then UN-RENDERABLE — render fails with `hash-ref: no value
   > found for key "placement"` (the exact crash CHANGES.md documents). The earlier text below
   > ("Worst case … extra … multi edges — caught by the next Keystone render and repairable by
   > re-ingest") UNDERSTATES the failure shape: a torn commit can leave the code log durably
   > inconsistent, and `fram_mcp.clj:134`'s "FLIP REJECTED … no .bclj written" message reads
   > as if the commit were atomic/rolled back when the log is in fact half-mutated. This is a
   > KNOWN BLOCKER (see top of doc) — `FRAM_FLIP` must stay opt-in until an all-or-nothing
   > batch/tx op (or compensating re-asserts) lands and a partial-commit failure-injection
   > gate is green. The `.bclj` source is the recovery oracle until full adoption.

   ~~Worst case the code log has extra (superseded-by-retract-missing) multi edges — caught
   by the next Keystone render (byte diff) and repairable by re-ingest.~~ (superseded by the
   correction above)

3. **R: Writing to the WRONG log (the live lodestar log / data/claims.log / port 7977).**
   C: The code log is a NEW dedicated path `.fram/code.log`; the throwaway daemon boots
   `serve-flat <verified-free-port> <code.log copied to /tmp>`; every gate asserts `:status`
   `:log` == the expected code log BEFORE trusting results; trap-kill on exit. The brief's
   SAFETY rule (never touch 7977 / lodestar log) is enforced structurally, not by care.

4. **R: refers_to (derived) accidentally persisted to the code log → the log stops being a
   clean AST and a re-resolve doubles edges.**
   C: refers_to + render markers are in `resolve-preds` (`:50`), barred from the flat log
   (`append-flat!` is only reached by `do-assert`/`do-retract` of DOMAIN preds; the ingest
   writes only `kind/v/fN/...`) and from reads (`claim->triple:174`). The materialize step
   strips ALL refers_to before re-walking (`strip-resolve-claims!:404`) and rolls back the
   seq-space (`restore-seq-space!:452`). The flip writes NO resolve-pred to disk. A gate
   asserts the code log contains 0 `refers_to` lines.

5. **R: Adopting all 11 modules at once (a blunt irreversible switch) before identity is
   proven module-by-module.**
   C: Explicitly STAGED (§6). This experiment flips ONLY schema.bclj; the other 10 are
   document-only with a per-module byte-identity precondition. No mass switch is run here.

6. **R: The sentinel/normalization assumption (whether the leading comment block round-trips
   as an AST `comment` node or must be re-prepended) is wrong → byte diff that looks like a
   flip failure but is cosmetic, OR a real loss masked as normalization.**
   C: §3 makes the normalization EXPLICIT and the Keystone gate DETERMINES which case holds
   and states it in CHANGES; any byte difference NOT covered by the stated normalization
   FAILS the gate. Normalization is documented, narrow, and falsifiable — not a fudge.

7. **R: The uncommitted working-tree change to `cnf_coord_daemon.clj` (the `:te`→`ultimate`
   target-node diff, lines ~587-607) interacts with the flip.**
   C: That diff only affects `:callers` target resolution (a READ); it is orthogonal to the
   commit path and is already on disk. The flip design does not depend on it; gate 5 works
   with or without it. Noted so the human reviewer accounts for it in the landing.

---

## 9. What this experiment writes (for the CHANGES manifest)

NEW (proposed; not committed by this design step):
- `.fram/code.log` — the canonical CODE claim log (per-repo, generated by ingest).
- `bin/fram-ingest-code` — `.bclj` tree → code log (lossless; gate 3).
- `bin/fram-render-code` — code log → `.bclj` (pure function of the log; Keystone).
- `cnf_code_flip_test.clj` — drives Keystone + gates 3/4/5 over a throwaway daemon.

CHANGED (proposed):
- `fram_mcp.clj` route-edit tail (`:166-176`): `io/copy` overwrite → delta-commit through
  the coordinator + render-from-log.
- `mcp_edit_test.clj`: assert post-edit state comes from render-from-log, not io/copy.

UNTOUCHED (guaranteed): `src/fram/*.bclj` (schema.bclj only re-rendered byte-identically),
`data/claims.log`, `~/.local/state/lodestar/claims.log`, port 7977.

DO NOT git commit / git push. Leave changes in the working tree + this DESIGN + a CHANGES
manifest for the human main-loop to review and land.
