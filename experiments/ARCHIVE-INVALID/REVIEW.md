> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# FINAL ADVERSARIAL REVIEW — night-of autonomous work in fram

**Date:** 2026-06-19 · **Repo:** /home/tom/code/fram · **Branch:** `authoring-claim-ops`
**Reviewer posture:** hostile, fair skeptic. "Passes my tests" != "actually holds up."
**Safety:** port 7977 (live lodestar coordinator, pid 2242005) NEVER bound/touched; the
lodestar canonical log NEVER written; all re-runs used verified-free ports + /tmp temp
logs/renders, trap-cleaned. Confirmed 7977 sole-listener before and after.

Every finding below was re-verified against real code/deliverables (file:line) and the
critical ones were empirically reproduced. The whole body of work is **git-untracked**
(`git status`: `??` on all experiments/, `bin/fram-*`, `cnf_code_flip_test.clj`; only
`cnf_coord_daemon.clj`, `fram_mcp.clj`, `mcp_edit_test.clj` are modified-tracked). So
today's posture is "working-tree only, nothing landed" — which is the right place for it.

---

## TRIAGED PUNCH-LIST (for the main loop to fix)

### BLOCKERS (must fix before the relevant deliverable lands / before flip default-on)

**B1 — Flip write path truncates the live source `.bclj` to 0 bytes on any render failure.**
`fram_mcp.clj:136-138` renders with `(apply sh {:out (io/file target-bclj) :err :string} ...
"fram-render-code" module ...)` where `target-bclj` = `tf` = the live `src/fram/<m>.bclj`
(line 218-219). No `--out`, no temp file, no atomic rename. Empirically reproduced:
`babashka.process/sh` with `:out (io/file ...)` truncates the target at subprocess spawn
regardless of exit code — a child that emits nothing and exits 1 leaves the file `""`
(tested: 24 B -> 0 B). On any `fram-render-code` `die` (6 die paths), the source file is
destroyed; recovery is `git checkout`. The **legacy path does it correctly** (renders to a
temp at `fram_mcp.clj:194-203`, then `io/copy` over `tf` at :229) — the flip path regressed
away from that exact-safe pattern. Gated behind `FRAM_FLIP=1`+`FRAM_CODE_PORT` (OFF by
default), but wired into the real MCP edit tools (add-def/set-body/rename via route-edit).
*Fix:* render to a temp (or pass `--out`), check `(zero? (:exit render))`, then atomically
copy over `target-bclj` only on success — the legacy sibling pattern. Add a failure-injection
gate asserting the source is byte-identical before/after a forced render failure.

**B2 — Non-atomic torn delta-commit leaves the canonical code log durably inconsistent.**
`bin/fram-commit-code:189` runs ALL retracts first (`doseq ... :retract`), `:195` then asserts;
each `commit-op!` (:125-132) is an independent coordinator round-trip that `die`s (System/exit
1) on a non-conflict reject / socket failure / daemon death. The wire has NO batch/tx op
(`cnf_coord_daemon.clj:718` `case (:op req)`, default `{:error "unknown op"}` at :771; the
`:tx` hits in the daemon are internal log-seq mechanics, not a client batch). Empirically: a
mid-sequence abort after the retract phase leaves a node MISSING required claims -> render
fails with `hash-ref: no value found for key "placement"` (the exact crash CHANGES.md:154
documents). **DESIGN §8 risk #2 (DESIGN.md:296-299) mis-states the failure shape** — it claims
"Worst case the code log has extra ... multi edges" (only GAINS), but retracts-first means it
can LOSE required ones. `fram_mcp.clj:134`'s "FLIP REJECTED ... no .bclj written" message
reads as if the commit were atomic/rolled back when the log is in fact half-mutated.
*Fix:* add an all-or-nothing batch/tx daemon op (the kernel already has begin-tx!/append-tx!
with one fsync at tx-commit — wire a `:tx-batch`), OR wrap the doseq with compensating
re-asserts on assert-phase failure. Correct DESIGN.md:296-299's failure shape. Add a
partial-commit failure-injection gate. Keep `FRAM_FLIP` strictly opt-in until green.

**B3 — Keystone gate is LOSSY on source comments; weakened from DESIGN's own contract;
the loss is masked as "normalization."**
DESIGN gate K is byte-identity vs the COMMITTED file (DESIGN.md:114, :221 `cmp -s render(log)
src/fram/schema.bclj`). The shipped harness instead proves `render(log) == render(text)`
(`run-gates.sh:37`, labeled KEYSTONE-A). Reproduced live (temp, no port): render(log) =
render(text) = **5673 bytes**, committed = **6006 bytes** — they DIFFER by 333 B, and the diff
is NOT only the documented `#lang -> (define-target clj)` + whitespace. **4 comments are
DELETED, not reflowed:** the inline `; RESERVED: claim-level supersession` (schema.bclj:41),
`; trigger; distinct from any domain` (:42), and the 2-line block `;; identity lives in the
graph ...` / `;; those don't survive dump/replay ...` (:45-46). Each is present in committed
(count 1) and ABSENT (count 0) from render(log), render(text), AND `.fram/code.log` — the loss
originates at `racket --emit-edn` (the lexer drops trailing-inline + in-body comments), so they
never enter the log and cannot be rendered. `render(log)==render(text)` passes precisely
because BOTH sides drop the same comments — it tests the projector against itself, not fidelity
to source. This violates the work's OWN contract: DESIGN §3 (:123-124) "Any other byte
difference FAILS the gate" and §8 risk #6 (:323) names exactly "a real loss masked as
normalization." GATE-REPORT.md:42 / CHANGES.md:136 call it "comment-block whitespace reflow" —
that label is wrong; it is deletion.
*Fix:* state plainly that the flip is LOSSY on inline/in-body comments; enumerate the 4
dropped comments; either restore `cmp -s render(log) committed` as a known-FAIL until ingest
captures comments, or re-scope the guarantee to "program-semantics-identical, source comments
NOT preserved" AND add a hard precondition blocking adoption of any module whose committed text
carries comments the renderer drops.

**B4 — Staged-adoption gate + `--write` overwrite are unguarded against the same comment loss.**
CHANGES.md:185 replaced DESIGN.md:241's `cmp -s render(log) committed` with `cmp -s render(log)
render(text)` — silent on comment loss (both 5673 B). `bin/fram-render-code-all:47` does
`cp "$tmp" "$f"` under `--write` with NO comment-diff check, despite its header (line 20)
claiming the write is "gated by the KEYSTONE byte-identity check below" — **no such gate exists
in the script**. The dry-run message (:49) says "mostly #lang/comment normalization" — false
when a comment is actually lost. A human acting on the tool's own message silently drops a
committed comment; git history is the sole recovery oracle.
*Fix:* restore the committed-file comparison OR add an explicit comment-preservation check that
fails adoption when render(log) drops any committed comment; make `--write` REFUSE when the
diff exceeds the enumerated normalization; fix the dry-run wording.

**B5 — DESIGN rollback step 2 makes a flatly FALSE byte-identity premise.**
DESIGN.md:264-266: "because render is byte-identical, the file is the same bytes — `git diff
src/fram/schema.bclj` is empty." Render is NOT byte-identical to committed (5673 vs 6006). If
schema.bclj is regenerated via the documented `fram-render-code-all --write`, `git diff` would
show the 333-byte / 4-comment loss; `git checkout --` is then the ONLY recovery. (Note: the
finding that pinned the window on `CHANGES.md:199 rm -rf .fram/` was partly mischaracterized —
CHANGES step 1 is working-tree-only and explicitly leaves `src/fram/*.bclj` UNTOUCHED, which is
true today since `--write` was never run and schema.bclj's git diff is empty. The false premise
lives in DESIGN.md step 2, not CHANGES:199.)
*Fix:* correct DESIGN.md:264-266 to state render(log) is byte-DIFFERENT and drops 4 comments
recoverable only from git history; state the irreversible window explicitly.

**B6 — Reasoning-cost Layer B ("the visceral evidence" / "the ONLY empirical claim") is NOT
measured agent behavior.** The 12 oplogs in `experiments/reasoning-cost/.behavioral/` are
hand-authored static artifacts. Confirmed: (a) the 6 text oplogs span exactly 128 ms
(01:23:41.296Z -> .424Z) for 6 "agent runs" — impossible for spawned LLM sub-agents; all 12
files written within ~24 s. (b) No generator anywhere — `grep` over reproduce.sh / all *.sh /
*.clj for `.behavioral`/`summary.tsv`/`spawn`/`claude -p`/`N=10`/`seq 1 10` returns NOTHING;
`reproduce.sh` has ZERO references to `.behavioral`. (c) `.behavioral/` is git-untracked.
(d) Direct contradiction of the experiment's own spec: METHODOLOGY.md:333 calls Layer B "the
ONLY empirical claim," :335 says "we **spawn** real ... sub-agents," :350-353 promises a
"wrapper-bypass detector" in reproduce.sh (NONE exists), mandates N>=10 (delivered N=2), and
:360 explicitly forbids "an authored number dressed as a measurement." (e) RESULTS.md:149-162
presents it as a results table with a behavioral-variance narrative attributed to agent
path-choice (:151, :160-161). *Mitigation that softens but does not refute:* RESULTS.md:143
discloses "this agent acted as both subjects, twice each" and :162 calls it "a small,
illustrative ... not a population study" — so "fabricated" overstates the hedge, but the
substance holds. *Fix:* either run Layer B for real (N>=10 spawned sub-agents under the
wrapper-only confinement, implement the promised bypass detector, generate `.behavioral/` from
reproduce.sh, report the distribution) OR demote Layer B to "DESIGNED, NOT RUN," strip the
results table + variance narrative, and remove "the ONLY empirical claim" framing. Note Layer A
IS real and reproducible (reproduce.sh genuinely writes graph.oplog/ground_truth.json/
.text-results; gq is structurally socket-only — only the comment at gq:6 mentions racket).

**B7 — Reproduce-section attributes Layer B artifacts to a script that does not produce them.**
RESULTS.md:252-254 (inside the `## Reproduce` section whose subject is `bash reproduce.sh`)
states the behavioral oplogs "are saved under `.behavioral/`" — but reproduce.sh never touches
`.behavioral/`. METHODOLOGY.md:350-353 goes further, asserting reproduce.sh ingests/validates
Layer B transcripts. Both are false. A reviewer running the documented command gets the full
Layer A table and no way to regenerate/audit the empirical headline. *Fix:* in the Reproduce
section, separate "regenerated by reproduce.sh" (Layer A, graph.oplog, ground_truth.json,
.text-results) from "static, captured once" (.behavioral/), and fix METHODOLOGY.md:350-353.
(Same root issue as B6; fix together.)

### MAJOR (fix before commit; not push-blocking on their own)

**M1 — GATE 2 / KEYSTONE-B "1 built, 0 error(s)" is a degenerate single-module build that does
NOT exercise cross-module correctness.** `run-gates.sh:50` copies ONLY `from-log.bclj` to
`$W/src/schema.bclj`; fram.cnf (`c/`) and fram.types (`t/`) — which schema.bclj requires
(schema.bclj:34-35) — are NEVER placed in the build set. Beagle then types the cross-module
`c/...`/`t/...` calls as `Any (unchecked)` (a NOTE, never an error). **Killer counter-test
(reproduced live):** I injected `(c/this-function-absolutely-does-not-exist-in-cnf ctx 1 2 3 4
5)` — a function in NO module — and the single-module build STILL returned `1 built, 0
error(s)` (only "call is unchecked (Any)"). So a genuine cross-module breakage passes GATE 2 /
K-B green. The separate compile-identity keystone (GATE-REPORT.md:47-57) is meaningful and
unaffected — it proves render(log) is semantically equal to committed, but NOT that committed
type-checks cross-module. Calling a one-file copy the "render-from-log tree" (GATE-REPORT.md:19,
24; CHANGES.md:121) is mild inflation. *Fix:* build the rendered schema inside the full
11-module render-from-log tree (mirror concurrent-authoring's EXPECT_BUILT=11 oracle), OR rename
the gate to "render-from-log parses standalone (cross-module calls unchecked)" and fail the gate
if any `call is unchecked (Any)` note fires for a `fram.*` namespace.

**M2 — owned-resolution-audit Q4 verdict is stale and contradicts the shipped experiment.**
REPORT.md:162 "The experiment is not built." and :205 "graph_arm_valid = false
(CANNOT-CERTIFY-YET)" — but the experiment was built afterward (file mtimes: REPORT.md 07:04,
the whole harness 07:40-08:29) and satisfies all 4 of the audit's own required fixes (corpus
projected once via project_corpus.clj; socket-only gq; :reaches/:type-refs return "unknown op"
so c/d are honestly hypothetical; fail-loud boot guard). CHANGES.md:11-13 cites REPORT.md as the
flip's canonical motivation, so a reader following that citation hits `graph_arm_valid = false`
directly contradicting RESULTS.md. *Fix:* add a dated POST-AUDIT ADDENDUM after REPORT.md:205
noting the 4 fixes landed; certify graph_arm_valid for the LIVE headline task (b)
ultimate-through-aliases, with (a) a conceded 1-vs-1 tie and (c)/(d) honestly hypothetical.
Doc-only; the code is correct. (Two reviewers independently confirmed this; severity is
genuinely doc-coherence, not a fairness/correctness defect.)

### MINOR (clean up; non-blocking)

**m1 — Title-level overclaim (claim 2).** concurrent-authoring/RESULTS.md:1 is the bare
"Concurrent authoring through the graph beats file-based editing" — the body scopes the win to
correctness/convergence (line 67) and concedes the file arm wins the disjoint edit (:74) and
the stand-alone rename (:46). Siblings (graph_arm.sh:6, reproduce.sh:8, SPEC.md:18) carry the
"on CORRECTNESS/CONVERGENCE under concurrency" qualifier; only the H1 + echo strings shipped
bare. Forbidden phrasings are CLEAN ("Not 'the graph is faster'" :50 is a negation; no "rename
faster"). *Fix:* scope the H1 to match line 67. (Note: the file arm wins 1 of 3 K=4 table
classes, not 2 — the graph wins rename + stale-ref; "stand-alone rename" is a supporting fact
outside the table.)

**m2 — Title-level overclaim (claim 4).** git-subsumption/CHARACTERIZATION.md:1 "the crown
jewel" while :201 says "it is NOT the crown jewel. The crown jewel is §4" and :208 says §4 "is a
HYPOTHESIS. Nothing here is measured." Forbidden phrasing CLEAN (:45 "NOT 'replace git'").
*Fix:* drop or qualify "the crown jewel" in the H1 to match §3/§4 GROUNDED/FRONTIER split.

**m3 — Speed-word drift (claim 1).** RESULTS.md:198-199 equates the op-count win with "~1 ms
... *exactly what 'reasons faster' means*" — a wall-clock figure contradicting line 23 "This is
NOT a wall-clock claim." METHODOLOGY.md:69 already phrases the same concept correctly (op-count,
no ms). Headline + Layer A table are wall-clock-free. *Fix:* drop "~1 ms"; phrase as "fewer
retrievals," matching the headline + METHODOLOGY.md:69.

**m4 — Speed-word drift (claim 3).** swarm DESIGN.md:223 "converges *faster/cheaper*" under
header :217 "(coordination, NOT wall-clock)". *Fix:* "converges in *fewer rounds / with less
rework*." Design-only, ships no numbers.

**m5 — concurrent-authoring M5 disclosure omits the second-verb-run mechanism.** M5=0 ("stale
ref re-resolved by identity at render time") is produced by re-running the rename verb a SECOND
time over B's re-projected tree (reproduce.sh:438-442; the script comment is honest — "replay
A's rename over it"), NOT by A's single op1 rename propagating to B's reference, and NOT by
identity transported through the concurrent coordinator (which carries only a single-triple
proxy). RESULTS.md's headline framing ("render time / not spelling") is accurate, but the
disclosure (RESULTS.md:167-188) never names the second-verb-run. *Reviewer-overreach note:* the
charge that RESULTS.md "implies the coordinator transported identity" is REFUTED — the text
consistently attributes the re-point to render/resolver, never the coordinator (:73, :76,
:118-120, :183-188). *Fix:* one sentence in the disclosure naming the second serial rename
application.

**m6 — GATE-REPORT.md:23 byte-count mislabel.** K-A PASS row says "5653 B both"; the rendered
files are 5673 BYTES (5653 is the CHAR count — 10 multi-byte UTF-8 glyphs at +2 B = +20).
Confirmed: wc -c = 5673, wc -m = 5653, cmp -s BYTE-IDENTICAL. The gate decides via `cmp -s`
(no byte count), so 5653 was hand-typed prose and never gated; line 40 + CHANGES.md:132 already
say 5673. Root cause: `bin/fram-render-code:118` `(count (:out r))` counts chars, labeled
"bytes." *Fix:* change ":23" to "5673"; optionally fix the renderer to count `.getBytes`.

**m7 — GATE 1 evidence-traceability + untested flip-path claim.** GATE-REPORT.md:18 claims
gate 1 PASSED "for THREE distinct targets (cardinality, name-of, setup!)" but run-gates.sh:45
renames only ONE (`cardinality`). Re-ran all three -> each `CLAIMS EDITED: 1` (claim true,
under-delivered harness). Separately, DESIGN.md:216 promises "On the FLIP path: a rename commits
exactly one :retract+:assert" — empirically FALSE for the actual flip path: a rename through
`bin/fram-commit-code` (dry-run, throwaway daemon on temp log, verified-free port) was "7
changed node(s): 14 assert(s), 14 retract(s)" because render-from-log spells references by name,
so re-emitting churns all spelled nodes. Gate 1 measures the resolver's warm internal op
(`resolve.clj rename` over text), NOT the flip commit path; no gate drives a rename through
fram-commit-code (only K-C drives set-body). *Fix:* add the 2 missing rename invocations to
run-gates.sh (or soften the report), and rescope DESIGN.md:216 to the true whole-node figure;
add a gate driving a rename THROUGH fram-commit-code.

**m8 — Whole-node re-commit idempotency depends on AST preds being multi-valued, which is
env-overridable and ungated.** `out/fram/kernel.clj:4` reads `FRAM_SINGLE_VALUED` once at
ns-load (env-frozen); a boot under e.g. `FRAM_SINGLE_VALUED="child ..."` makes `child`
single-valued, so a whole-node re-commit of a multi-child node collapses to one child -> corrupt
AST. Reproduced: 3 children asserted under that env -> 1 survived. No gate asserts the code
coordinator's effective cardinality; `:status` (cnf_coord_daemon.clj:740) returns only
`{:version :claims :log}` — no vocab. Default + lodestar vocab are disjoint from AST preds, so it
never fires under standard boot (hence minor). *Correction to the original finding:* `fN/segN`
are immune (ck/single? is exact-string), so the exposed surface is the bare-name preds
child/kind/v/tail/style/placement. *Fix:* add a boot/commit guard in fram-commit-code asserting
those AST preds are multi-valued and fail loud otherwise; optionally extend `:status` with the
vocab fingerprint.

**m9 — 74->71 reconciliation gap (claim 1).** RESULTS.md:136 reports the rename regenerates "71
Datum tokens" while :100/:152/:179 state 74 Claim spellings; the transition is never bridged.
Re-ran live: 71 Datum + 3 surviving Claim = 74 exactly, so the harness is CORRECT (not
green-but-wrong) — pure prose nit. *Precision:* the 3 survivors are 1 ns docstring (kernel.bclj:3,
a string literal), 1 genuine comment (cnf.bclj:5), and 1 regex false-positive (types.bclj:14
"CnfClaim" — a DIFFERENT record whose suffix the pattern matches), so the 74 baseline itself
over-counts by one. *Fix:* add a reconciling clause; do NOT use "comment mentions /
non-resolving sites" verbatim (it misattributes the CnfClaim false-positive + the docstring).

### CONFIRMED-CLEAN (positive baselines — recorded so the above is read in proportion)

- **Reasoning-cost reconstruction-work discipline holds (Layer A).** gq + ts are structurally
  one-op-per-oplog-line (gq is socket-only; ts logs one line per rg pass/read). text-min
  op-counts reproduce exactly (cal=2,a=1,b=2,c=9,d=3); ground-truth tallies b=74, d=41 reproduce
  off the live corpus with the canonical regexes. NO wall-clock in the headline or the Layer A
  table (the only timing is segregated one-time JVM boot). The metric IS reconstruction-work.
- **Owned-resolution validity holds (read side).** The graph arm genuinely queries materialized
  `refers_to` over the warm store (cnf_coord_daemon.clj:616-635 `callers-of-in-store` reads
  `c/by-p ... REFERS` + matches on `resolve/ultimate`); no `.bclj`/racket/emit-edn in the daemon
  call path. The reference is resolved by the daemon, not fed the answer. Live re-run on
  verified-free port 9477 matched the oracle. The flip eliminates the text round-trip on the read
  side for (a) callers and (b) ultimate-through-aliases.
- **Flip read/render side holds.** Ingest is derived-free (0 refers_to / 0 markers in the log).
  Decisive: deleting `src/fram/schema.bclj` from disk and rendering from log alone produced
  byte-identical output (5673 B) — render reads the CODE log only. KEYSTONE-A render(log) ==
  render(text) byte-identical reproduced. cnf_code_flip_test.clj 14/14 PASS on throwaway ports.
  (These prove SOURCE-OF-TRUTH/durability and program-semantics preservation — they do NOT
  rescue the comment-loss in B3, which is a SOURCE-fidelity gap.)
- **Forbidden-phrasing sweep clean.** No "replace git" (claim 4 says "NOT 'replace git'"); no
  "rename faster"/"graph faster" (claim 2 says "Not 'the graph is faster'"). Mandated framings
  (claim 2 = safety/correctness-not-speed; claim 4 = subsume-not-replace) are correct.

---

## PER-DELIVERABLE LAND GUIDANCE

| Deliverable | Land | Reason |
|---|---|---|
| **experiments/flip** (the flip — code + docs) | **STAGE ON-BRANCH. DO NOT push to main, DO NOT enable `FRAM_FLIP` by default.** | Two real route-edit blockers (B1 source-truncation on render failure, B2 non-atomic torn commit) operate on REAL source the moment the flag is set; the keystone gate is comment-LOSSY and masks it (B3), the staged `--write` path is unguarded (B4), and the rollback premise is false (B5). The flip's read/render/durability side is genuinely strong and the harness is honest about being working-tree-only — but the WRITE path must be made atomic + comment-loss must be surfaced/gated before any main landing or default-on. The flip code (fram_mcp.clj/cnf_coord_daemon.clj diffs, bin/fram-*, cnf_code_flip_test.clj) is fine to keep staged on `authoring-claim-ops`; it is OFF by default and the legacy io/copy path is byte-for-byte unchanged. Tighten the SAFETY-GATE substring "code.log" check to a strict basename match while in there. |
| **experiments/reasoning-cost** (claim 1) | **STAGE ON-BRANCH; fix B6/B7 before committing as evidence.** Layer A may be committed as-is. | Layer A (the op-count reconstruction-work apparatus) is rigorous and reproduces exactly — that part is push-quality. But the empirical CENTERPIECE (Layer B, billed "the visceral evidence" / "the ONLY empirical claim") is hand-authored, has no reproducer, and contradicts the experiment's own METHODOLOGY (B6); the Reproduce section + METHODOLOGY attribute it to a script that doesn't produce it (B7). Do not present `.behavioral/` as measured agent behavior. Either run it for real or demote it to DESIGNED-NOT-RUN, then commit. Plus minors m3, m9. |
| **experiments/owned-resolution-audit** (REPORT.md) | **STAGE; add the dated Q4 addendum (M2) before committing.** | Doc-coherence only — the audit's stale `graph_arm_valid = false` contradicts the shipped, correct experiment. One addendum reconciles it; no code change. |
| **experiments/concurrent-authoring** (claim 2) | **OK to commit after minors m1 (title scope) + m5 (M5 disclosure sentence).** Not push-to-main on its own; lands with the branch. | Framed correctly as safety/correctness-not-speed; forbidden phrasings clean; the M5 mechanism is real (second serial rename through the resolver) and the script comments are honest. Only a bare H1 + a one-sentence disclosure gap. Solid. |
| **experiments/swarm-coordination** (claim 3, design-only) | **OK to commit after minor m4 (speed-word at DESIGN:223).** Frontier design; ships no numbers. | Honestly scoped as design-only with the wall-clock axis quarantined to a conceded C6 column. One stray "faster" under a "NOT wall-clock" header. |
| **experiments/git-subsumption** (claim 4, frontier) | **OK to commit after minor m2 (crown-jewel title).** Frontier characterization; §4 explicitly a hypothesis. | "subsume-not-replace" framing is correct; "NOT 'replace git'" stated verbatim; §0 NOT-list + §5 GROUNDED/FRONTIER split handle honesty correctly. Only the H1 promises the unproven §4 as a delivered prize. |

**Bottom line for the main loop:** none of this should go to `main` tonight. The flip's two
write-path blockers (B1/B2) + the comment-loss keystone weakening (B3/B4/B5) are the gating
work; the reasoning-cost Layer B (B6/B7) is the gating honesty fix. Everything else is doc/title
scoping (M2, m1-m9) that should be cleaned up before the branch is committed, but is not
correctness/safety. The branch itself is the right home — keep `FRAM_FLIP` unset everywhere
persistent until B1-B5 are closed and re-reviewed.
