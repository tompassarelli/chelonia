> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# GIT SUBSUMPTION — characterization (GROUNDED versioning + a FRONTIER bet, honestly scoped)

**Date:** 2026-06-19 · **Repo:** /home/tom/code/fram (branch `authoring-claim-ops`)
**Status:** CHARACTERIZATION + ONE GROUNDED DEMO. One half is GROUNDED and now
**demonstrated** off the real code log (§3.3 — `demo-versioning.sh` was built and run, real
output captured; the edit-delta is committed to a **/tmp copy** of `.fram/code.log` — the
canonical log is byte-unchanged, and since it carries 0 retracts today, the *interesting*
supersession is exercised on the copy); the other half is a FRONTIER HYPOTHESIS
(characterized, **not** proven, and not provable by ~2026-06-25). The split is the whole
point of this doc.
**Companion claim:** CLAIM 3 (swarm coordination) —
`experiments/swarm-coordination/DESIGN.md`. The lighter-coordination bet here IS the
read-side of the coordination primitive that claim exercises.

This file is read by a human main-loop. Every mechanism claim is grounded in
`file:line` against the live tree. The rigor that already caught the rename overclaim
tonight (text's `sed` wins the standalone single-file case) must catch any overclaim
here too — so the unproven half is fenced off explicitly.

---

## 0. The claim, in one breath

> **For claim-native code**, the immutable claim log subsumes git's versioning
> *for that code* — time-travel ("what was the code at T") and diff ("what changed
> between T1 and T2") fall out of the log with **no git machinery** — **and** the
> same log offers a coordination primitive (*repoint-at-heads*) that **might** be
> dramatically lighter than git's checkout-and-merge. **That lighter coordination is
> the bet I have NOT proven.**

Two parts, kept rigorously distinct for the rest of this doc:

| Part | Status | Where |
|---|---|---|
| **A. For-free versioning** (time-travel + diff from the log alone) | GROUNDED — Datomic-shaped, demonstrable on the real `.fram/code.log` | §3 |
| **B. Repoint-at-heads coordination** (the lighter-than-merge bet) | FRONTIER — testable hypothesis, NOT a result | §4 |

---

## 1. WHAT THIS IS NOT (read this before anything else)

This is the section that survives Q&A. Each line is a disclaimer the rest of the
doc must not quietly walk back.

- **NOT "replace git."** Git is not removed, not wrapped, not deprecated. The claim is
  *subsume the versioning of one slice of the tree* (claim-native code), not the repo.
- **NOT for assets.** Binaries, images, lockfiles, vendored deps, generated artifacts,
  `.gitignore`d state — **stay in git**. The claim log has no story for opaque blobs and
  is not trying to grow one. Git remains the substrate for everything not claim-native.
- **NOT for configs / non-Beagle source.** Anything that is not ingested as claims
  (today: only `.bclj`/Beagle modules under the flip; see `cnf_code_flip_test.clj`,
  `experiments/flip/DESIGN.md`) is versioned by git exactly as before. A repo is a
  *mix*: a claim-native island inside a git sea.
- **NOT "the log is a better merge engine."** §4 (repoint-at-heads) is a *hypothesis
  about coordination cost*, not a demonstrated merge algorithm. We have NOT shown it
  resolves a single real concurrent edit conflict more cheaply than `git merge`. Treat
  every §4 number as a target to falsify, not a measurement.
- **NOT "branches/PRs/review go away."** Even in the best case, the log subsumes the
  *storage + history + diff* of claim-native code; the human review surface (what git's
  PR is *for*) is orthogonal and out of scope here.
- **NOT proven that it scales.** The grounded demo (§3) runs on ONE ingested module
  (`schema`, 4685 claims). Whole-repo, multi-module, multi-agent is unmeasured.
- **NOT a distributed-VCS replacement.** No remote/push/pull/clone story. The log is
  per-repo and local (`/home/tom/code/fram/.fram/code.log`). Federation is not claimed.

If a reader can only remember one sentence: **git survives; we are subsuming the
versioning of the claim-native slice, and the lighter-coordination part is a bet, not a
result.**

---

## 2. Substrate map (verified seams)

The log is an immutable, append-only transaction log — Datomic-shaped. Everything in §3
is a fold over it; nothing in §3 needs git.

| Seam | File:line | Role |
|---|---|---|
| Canonical CODE log (per-repo) | `/home/tom/code/fram/.fram/code.log` — 4685 flat lines, monotonic `:tx 1..4685`, all `:op "assert"` (verified: 0 retracts in the fresh ingest) | the immutable tx log under test |
| Flat line shape | `{:tx N :op "assert"|"retract" :l <subj> :p <pred> :r <obj> :ts ... :by "coord"}` — written by the daemon's `append-flat!` (`cnf_coord_daemon.clj:151-157`) | one transaction = one (or a few) appended lines |
| The fold (state @ a version) | `out/fram/fold.clj` — `max-tx` (`:28-30`), `keyed-latest` (`:49-53`, latest-by-`:tx` per `(l,p[,r])` key), `fold` (`:55-59`) | folding the log up to tx T **= the code at version T** |
| Single vs multi cardinality | `out/fram/kernel.clj` `single-valued` / `single?` — `kind`/`v`/`fN` are multi-valued; supersession is per-`(l,p,r)` for multi, per-`(l,p)` for single (`fold.clj:46-47 key-of`) | makes the latest-wins fold correct under edits |
| Immutability / replay identity | `cnf_replay_test.clj` proves `replay(dump-log!(store)) ≡ store`; `cnf_coord.clj:185-223 replay`, `dump-log!` (`:229-242`) — torn/uncommitted txs dropped (`committed-records :191-200`) | the log is the source of truth; folding it is deterministic |
| Delta commit (the writer) | `commit!`/`retract!` (`cnf_coord.clj:118`,`:159`) via daemon `do-assert`/`do-retract` (`cnf_coord_daemon.clj:350`,`:364`) → `append-flat!`; a `set-body` edit becomes a retract+assert delta (`cnf_code_flip_test.clj:220-273` KEYSTONE-C) | how new versions are minted (and where retract lines first appear) |
| Diff primitive | `live-triples` (`cnf_coord.clj:244-248`) = the set of live `(l,p,r)` of a store; **diff(T1,T2) = set-difference of two folds** | the "what changed" answer, from the log alone |
| Daemon version/status | `:version` and `:status` ops (`cnf_coord_daemon.clj` ~`:740`); `:version` == flat fold's `max-tx` (seeded `:959-962`) | the version handle an agent reads (the H in §4) |
| The flip (why code is in the log at all) | `cnf_code_flip_test.clj` (KEYSTONE: `render(log) == render(text)` byte-identical, delta-commit recompiles); `experiments/flip/DESIGN.md` | precondition for ANY of this — code is claim-native only post-flip |

**Honesty note on the current log's shape.** `.fram/code.log` today is a *single clean
ingest* (tx 1..4685, all asserts, 0 retracts). So time-travel over it right now is
"every prefix is a valid earlier state" — real, but trivial. The *interesting*
diff (a retract+assert delta) only appears **after** a `set-body`/rename is committed
through the coordinator. The grounded demo (§3.3) therefore COMMITS a delta (over a /tmp
copy) and time-travels across it, so the demonstration exercises supersession — the run
confirms the retract at tx 4686 drops `@schema#5 v "clj"` from the T2 fold — not just
prefix-truncation.

---

## 3. GROUNDED — for-free versioning (Datomic-shaped, demonstrable)

**Thesis:** time-travel and diff are folds over the immutable log. No git object store,
no `git checkout`, no `git diff`, no working-tree mutation. This is the **safe** half:
it is a property of the substrate that already exists, and the demo in §3.3 was **run** on
the real `.fram/code.log` (output captured below).

### 3.1 Time-travel: "what was the code at version T"

The code at version T is `fold(filter(log, :tx <= T))` rendered. Mechanically:
1. read the flat log (`read-records`-equivalent; the daemon already folds it on boot,
   `cnf_coord_daemon.clj:932 migrate-flat->co`),
2. keep lines with `:tx <= T`,
3. `fold/fold` them → the live claim set at T (`out/fram/fold.clj:55`),
4. (optionally) render that claim set back to `.bclj` via the flip's render-from-log
   path (`bin/fram-render-code`, exercised in `cnf_code_flip_test.clj:198,260`).

There is no git in that pipeline. The version handle T is the same integer the daemon
reports as `:version`.

### 3.2 Diff: "what changed between T1 and T2"

`diff(T1, T2) = live(fold(log≤T2)) \ live(fold(log≤T1))` (added/changed claims) and the
reverse set-difference (removed/superseded claims), where `live(...)` is `live-triples`
(`cnf_coord.clj:244-248`). Because identity is the node id (`@module#int`), a changed
function body shows up as the specific `(l, p, r)` claims that flipped — **structural,
node-level diff**, not a line-text diff. This is the read-side advantage flagged in the
recent commit `db02df9` ("references carry identity, not spelling").

### 3.3 The grounded demo — BUILT AND RUN (real output, 2026-06-19)

`experiments/git-subsumption/demo-versioning.sh` exists and runs. It is fully read-only
w.r.t. the canonical log: it **copies** `.fram/code.log` to a `/tmp` working dir
(trap-cleaned), appends a **real delta in the daemon's exact wire shape**
(`{:tx :op "retract"/"assert" :l :p :r :ts :by}` — the line `append-flat!` writes,
`cnf_coord_daemon.clj:151-154`), then time-travels and diffs **purely by folding the log**
(`out/fram/fold.clj` in-process via `bb -cp out`). No daemon is started and **no git is
invoked** — both deliberate, so the run is hermetic and the substrate property is shown
naked. (The heavier render-from-log + recompile path is already proven separately by
`cnf_code_flip_test.clj` KEYSTONE-C; this demo isolates the *versioning* property.)

The committed delta is a non-trivial, 3-node edit (a renumber-shaped change, not a
one-liner): retract a leaf (`@schema#5 v "clj"`), assert its new value (`"cljx"`), add a
new node (`@schema#9001`), and reparent it under `@schema#1`. **Verbatim captured output:**

```
== CLAIM 4 GROUNDED: for-free versioning off the immutable claim log ==
canonical code log : /home/tom/code/fram/.fram/code.log  (READ-ONLY here)
working copy       : /tmp/fram-versioning-demo.XXXXXX/code.log
T1 (current head)  : tx=4685

committed a 3-node delta to the working copy -> new head tx=4690
  (retract @schema#5 v; assert @schema#5 v=cljx; add @schema#9001; reparent under @schema#1)

-- TIME-TRAVEL: fold(log | :tx<=T) --
  state @ T1 (tx 4685 ): 4685 live claims
  state @ T2 (tx 4690 ): 4688 live claims

-- DIFF: set-difference of the two folds (T1 -> T2) --
  ADDED   ( 4 ):
    + ["@schema#1" "f99" "@schema#9001"]
    + ["@schema#5" "v" "cljx"]
    + ["@schema#9001" "kind" "symbol"]
    + ["@schema#9001" "v" "extra"]
  REMOVED ( 1 ):
    - ["@schema#5" "v" "clj"]

-- delta-H : the nodes whose HEAD moved (the repoint set) --
  |delta-H| = 3 of 1383 nodes -> ("@schema#1" "@schema#5" "@schema#9001")

-- no-git assertion --
  git invocations in this demo: 0 (the answers came from out/fram/fold.clj alone)
  the canonical log is byte-unchanged:
    sha256(/home/tom/code/fram/.fram/code.log) = 181cbe59eb5ce0d5e5eb3cb089c76a04da0789be36abfb50a8c3bf588bfb022c
    (we only ever appended to the COPY at /tmp/fram-versioning-demo.XXXXXX/code.log)
```

What this output establishes, grounded:
- **Time-travel works:** `fold(log | :tx<=4685)` = 4685 live claims (the original code);
  `fold(log | :tx<=4690)` = 4688 (the edited code). Each is a pure fold of a log prefix —
  "the code at T" with no checkout. The retract at tx 4686 correctly *removes* `@schema#5 v
  "clj"` from the T2 view (supersession via `keyed-latest`, `fold.clj:49-53`), so this is
  real supersession, not just prefix-truncation.
- **Diff works:** the set-difference of the two folds is exactly the 5 `(l,p,r)` claims the
  edit touched — added/removed at node granularity. No line-text diff, no `git diff`.
- **No git, log untouched:** zero git invocations; the canonical `code.log` sha256 is
  byte-identical before and after the run (re-verified by the harness, PASS).

**Falsifier (what would sink the grounded half):** if folding a log prefix did NOT yield a
valid earlier code state (e.g. supersession lost a retract, so the T2 view still carried
`@schema#5 v "clj"`), or if the diff were not a clean node-level set-difference, the
"for-free versioning" claim would be wrong. The run above shows neither failure. (The
*stronger* claim — that each folded state re-renders to compiling `.bclj` from the log
alone — is the render-from-log path, proven separately in `cnf_code_flip_test.clj`
KEYSTONE-B/C; this demo deliberately does not re-litigate it.)

**Comparison framing (fair, not strawman).** Git also does time-travel
(`git show T:path`) and diff (`git diff T1 T2`) for free, and does it for *any* file type.
The grounded half does NOT claim to beat git at versioning. It claims **equivalence on the
claim-native slice without git's machinery**, with one genuine asymmetry: the claim-log
diff is **semantic/structural** (node identity), where git's is **textual** (line hunks).
That asymmetry is real but modest — it is NOT the crown jewel. The crown jewel is §4, and
§4 is unproven.

---

## 4. FRONTIER — repoint-at-heads coordination (the unproven bet)

**This entire section is a HYPOTHESIS.** Nothing here is measured. It is written so a
later experiment can falsify it cleanly. Do not cite any number in §4 as a result.

### 4.1 The primitive

In a graph world, an agent's "checkout" is a **set of claim heads** `H = { node →
head-tx }` for the nodes it cares about. When the canonical log advances (another agent
committed), the stale agent does not re-checkout a tree and re-merge. It receives a
**repoint message**: *"these specific nodes have new heads; here they are."*

The minimal repoint diff from head-set `H1` to `H2` is:

```
ΔH = { node | head_{H2}(node) ≠ head_{H1}(node) }   plus the new (l,p,r) claims at those nodes
```

i.e. exactly the §3.2 set-difference, scoped to the nodes the agent holds. The substrate
to compute it *is being built*: the daemon serves a `:version` (the global H handle) and
the scoped re-resolve added in commit `62a9407` ("scoped (module-granular) re-resolve")
is the mechanism intended to compute *which modules/nodes moved* without re-folding the
world. What `62a9407` actually established is **correctness, not the cost win**: its gate
checks scoped-re-resolve == whole-corpus (sym-diff 0) on a small module set, with only
*single-instance* skipped-work evidence (~16-17 forms walked vs 20). That it *stays*
cheaper than a whole-corpus re-fold at scale — the thing H4 needs — is **unproven**
(see §4.4 FALSE-case (1)); calling it "proven" here would contradict that.

**Grounded input (NOT the proof of the bet):** the §3.3 demo measured `ΔH` for one real
3-node edit: `|ΔH| = 3 of 1383 nodes` — the repoint payload was the 5 `(l,p,r)` claims at
those 3 nodes. That is the *shape* of a repoint message, computed off the log. It does NOT
prove H4 (§4.2): one edit on one module, no contending peer, no comparison against a real
`git merge`. It shows the primitive is computable and small for this edit; whether it stays
small and stays *correct* under N-agent contention is the unproven part.

### 4.2 The bet, stated as a falsifiable hypothesis

> **H4:** To bring a stale agent from H1 to H2, the bytes/work it must receive scale with
> **the nodes that actually changed** (|ΔH|), not with the size of the working tree or the
> number of files touched — and that update requires **no working-tree checkout and no
> three-way merge**. Therefore, under contention, graph repoint is asymptotically and
> practically lighter than git's checkout+merge ceremony.

### 4.3 Git's ceremony, conceptually (the thing we'd be lighter than)

A competent file-based agent, to absorb a peer's commit, does some of:
`git fetch` → `git merge`/`git rebase` (three-way merge: base, ours, theirs) →
re-read working tree → re-index → potentially re-resolve conflicts → re-run resolution
over touched files. The unit of work is **files/hunks and a tree checkout**, even when the
*semantic* change is one function body. (This is the SAME asymmetry the rename experiment
surfaced: text tools work at the spelling level; the graph works at the identity level.)

### 4.4 What would make H4 TRUE vs FALSE (the experiment to run, NOT tonight)

- **TRUE-ish** if: repoint payload size ≈ |ΔH| × (claims per node), independent of repo
  size; an agent applies it with no checkout/merge step; and stale-decision incidents
  drop because the agent learns *exactly* which nodes it holds are now stale (vs git's
  "your branch is N commits behind, good luck").
- **FALSE** if: computing ΔH costs a whole-corpus re-fold/re-resolve (then it's not lighter
  — the scoped re-resolve of `62a9407` is the hedge against this, and is itself only
  partially proven); OR if real edits routinely touch so many nodes that |ΔH| ≈ |tree|;
  OR if the merge git does is doing real semantic conflict work the repoint silently drops
  (a correctness hole masquerading as a speed win).

### 4.5 Why this is the hardest, least-proven part

1. It is a **coordination property** ("greater than the sum"), not a single-agent speed
   number — you cannot prove it with one agent and a stopwatch (that's claim 1's lane).
2. It needs **real contention** (N agents actually colliding on one log) and a **fair
   file baseline** (worktrees + serialized merge), which is exactly CLAIM 3's apparatus —
   so H4 cannot be settled before claim 3's harness exists.
3. The honest danger is **hiding a correctness gap as a speed win** (4.4 FALSE case):
   git's merge sometimes does necessary semantic work; a lighter repoint that skips it is
   only "lighter" if it's also *correct*. Proving lighter-AND-correct is the real bar.

**Verdict for the wakeup:** §3 is real and worth demonstrating; §4 is the prize and is
**unproven** — flag it FRONTIER, fence it from §3, and route its proof through CLAIM 3's
contention harness, not a solo benchmark.

---

## 5. The clean split (one-screen summary)

| | GROUNDED (§3) | FRONTIER (§4) |
|---|---|---|
| Claim | time-travel + diff from the log, no git | repoint-at-heads lighter than checkout+merge |
| Status | DEMONSTRATED off the real `.fram/code.log` (§3.3, run, output captured) — *delta committed to a /tmp COPY; canonical log byte-unchanged; render-recompile NOT re-run here (proven separately, KEYSTONE-B/C)* | testable hypothesis, NOT a result |
| Substrate | fold (`fold.clj:55`), `live-triples` (`cnf_coord.clj:244`), replay identity (`cnf_replay_test`) | scoped re-resolve (`62a9407`), daemon `:version`, ΔH = scoped §3.2 diff |
| vs git | equivalence on the claim-native slice, minus git's machinery; diff is semantic not textual | *bet*: ΔH-sized update, no checkout, no 3-way merge |
| Can sink it | renders not reproducible from log alone; diff not a clean node-set-difference | ΔH needs whole-corpus re-fold; \|ΔH\|≈\|tree\|; repoint drops real merge work |
| Provable by 2026-06-25 | yes (the §3.3 demo) | no — needs CLAIM 3's contention + fair baseline |

---

## 6. Safety (binding on any future demo under this dir)

- NEVER touch port **7977** or `~/.local/state/lodestar/claims.log`.
- The shipped demo (`demo-versioning.sh`) honors this: it starts **no daemon**, opens
  **no port**, and works over a **/tmp COPY** of the code log (trap-cleaned), so it cannot
  touch 7977 or the lodestar log even by accident. Any *heavier* future demo that DOES boot
  a daemon must use **verified-free high ports** (re-probe at run time,
  `cnf_code_flip_test.clj:67-70` pattern), with **trap-kill** (`:77-78`) and a
  **`:status :log` sanity assertion** before trusting any result (`:82-86`).
- Read-only to engine code. This experiment writes only docs + the demo script under
  `experiments/git-subsumption/`. Do NOT `git commit`/`push`.
