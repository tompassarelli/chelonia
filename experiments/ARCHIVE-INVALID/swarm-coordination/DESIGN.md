> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# CLAIM 3 — Multi-agent swarm coordination (DESIGN ONLY — the hardest tier-2)

> **STATUS: FRONTIER. DESIGN, not a result.** This document defines an experiment;
> it does **not** report one. There is no harness, no numbers, no "demonstrated" here
> — by design. The claim it characterizes is the **hardest and least proven** in the
> whole project, and is **not provable by ~2026-06-25**. Read the *"Why this is the
> hardest claim, and why it is not proven"* section before anything else; it is the
> honest frame the rest hangs from.

---

## The claim (precise scope)

**HYPOTHESIS (not a result):** Graph coordination of N contending agents *would beat*
file/worktree-based swarm coordination — on coordination cost, lost work, and convergence
under contention, *not* on wall-clock speed. Stated as a target to be tested and
falsified, never as something shown here. (Quoted alone, this sentence is a hypothesis;
the "beats" is the thing the experiment in this doc is designed to put at risk.)

This is a **coordination property**: "the swarm is greater than the sum of its agents"
because the *shared substrate* (one sole-writer claim graph) makes contention cheap to
detect, cheap to reconcile, and impossible to lose silently. That is a different and
**strictly harder** claim than:

- **Claim 1 (single-agent read/edit speed)** — one agent, the read-side identity win
  (`../rename-identity/`, the tier-1 result). Already shown.
- **The pairwise concurrent-authoring result** (`../concurrent-authoring/`) — **K=4 ops
  against one frozen base, serialized through one orchestrator**, measuring correctness
  (lost updates / conflicts / stale refs / recompile). That experiment is real and
  measured, BUT it is **not a swarm**: the four ops are stepped by a single harness
  against a pinned base, not N processes contending in parallel for the same nodes over
  time. Claim 3 is the genuine N-agent, real-contention extension of that result.

The whole point of Claim 3 is the gap between "K serialized ops with correct outcomes"
(shown) and "N **independently-running** agents whose *coordination overhead itself*
stays low as N and contention grow" (**unproven**).

---

## Why this is the hardest claim, and why it is NOT proven (read first)

Three things make this the frontier, in descending order of difficulty:

1. **You need real contention, not staged contention.** A fair swarm result requires N
   agents that *actually collide* — pick overlapping work, race on the same definitions,
   build on each other's half-finished edits, go stale and have to re-decide. The
   `concurrent-authoring` experiment deliberately *avoids* this (one frozen base per op,
   no moving base — that is its fairness guarantee). Manufacturing **genuine, fair**
   contention without rigging the outcome is the central unsolved design problem here.

2. **You need a fair, competent swarm baseline — and the honest baseline is GOOD.** A
   competent multi-agent file setup is **not** "everyone edits one working copy." It is
   **git worktrees per agent + branches + serialized merge/rebase + a PR/merge queue +
   CI-gate-after-merge** (detailed below). That baseline is genuinely strong: it
   isolates agents (worktrees), surfaces conflicts loudly (git), serializes integration
   (merge queue), and catches semantic breakage (CI). To beat it the graph must win on a
   *coordination* axis the file baseline structurally cannot match — and we must let the
   file baseline play its strongest hand or the result is a strawman.

3. **"Greater than the sum" is a systems property, not a micro-benchmark.** It only
   shows up at N > 2 with sustained, evolving contention over many rounds. A two-process
   race (which we *can* run today — see the grounded primitive below) proves the
   *mechanism* (OCC serializes, no lost update) but **not the property** (the swarm
   coordinates more cheaply than worktrees+merge-queue *as a whole*). The property
   requires a multi-round, multi-agent run with a faithful baseline harness on both
   sides. That harness does not exist, and building+validating it fairly is more than a
   week of work.

**Therefore: NOT provable by ~2026-06-25.** What IS grounded today is the *coordination
primitive* (the sole-writer OCC daemon, run live below). What is NOT is the *swarm-level
comparison*. This document keeps that line bright.

### The specific overclaim to refuse

> "N agents on the graph beat N agents on worktrees, so the graph replaces the swarm
> tooling." — **NO.** (a) Not shown; (b) the worktree+merge-queue baseline is strong and
> wins on parts of the space (genuinely disjoint work parallelizes with zero coordination
> cost, exactly as in `concurrent-authoring` op 4); (c) the graph's serialization is a
> *real throughput cost* we must report, never bury. The honest claim is **a coordination
> property on a bounded axis (lost work / convergence / stale-decision incidents), with
> the speed axis conceded**.

---

## The substrate: what the graph actually offers a swarm (grounded, file:line)

The graph arm's coordination is **not** "a mutex made races disappear." It is a
sole-writer coordinator enforcing **optimistic concurrency control (OCC) on
`base_version`** across independent socket connections, plus the property that **code
edits are mostly *commutative* claims** so most concurrent writes don't contend at all.
Every mechanism below is read out of real code.

### 1. The sole writer + OCC `base_version` (the serialization primitive)

`cnf_coord.clj` `commit!` (`cnf_coord.clj:118-153`) is the sole writer. Under
`(locking (:lock co))` it:

- computes `bv = base-version co te pid` = the max tx-seq over the **live** claims on the
  `(subject, predicate)` group (`cnf_coord.clj:62-63`);
- **rejects a stale single-valued write**: `(and single (> bv base)) ⇒ {:reject
  :conflict :version (current-seq co)}` (`cnf_coord.clj:129-131`);
- otherwise appends the tx atomically and fsyncs before acking
  (`cnf_coord.clj:152`, `append-tx!` at `cnf_coord.clj:33-38`).

The win is **OCC across lock release/reacquire over independent sockets**, not the lock.
The lock only serializes the *check-and-append*; the *contention decision* is
`base_version`, which is what makes two agents on **separate TCP connections** with the
**same stale base** resolve to exactly one winner.

The daemon (`cnf_coord_daemon.clj`) wraps this as a socket service: each request is
`(locking dlock ...)` (`cnf_coord_daemon.clj:716`), `do-assert` reads the pre-commit
version and routes through `commit!` (`cnf_coord_daemon.clj:350-362`), returning
`{:reject (:reject res) :version ...}` on conflict so the loser can re-read and retry.

**Grounded live (this session).** `experiments/concurrent-authoring/graph_arm_occ_probe.clj`
booted a throwaway daemon on a **verified-free temp port (9133) and a `mktemp` temp log**
(never 7977, never the lodestar log), fired **K=8 concurrent racers** on the same
`(subject, single-valued predicate)` at the same stale base, and got:

```
OCC-RACE K= 8  wins= 1  conflicts= 7  final-version= 2
OCC-PROBE: PASS (exactly 1 win, rest conflict — serialization holds, no lost update)
```

This is the same guarantee `cnf_flip_test.clj:91-92` asserts (8 real socket clients → 1
`:ok`, 7 `:reject :conflict`). **This is the coordination primitive, and it is real.**
What it is NOT: a swarm. It is a single-round, single-group race. It proves *the
substrate serializes contending writes without lost updates*; it says nothing yet about
whether an N-agent swarm coordinates more cheaply *overall* than worktrees + a merge
queue.

### 2. Contention is per-`(subject, single-pred)` group — most code edits don't contend

The single-valued predicate set is fixed and small (`out/fram/kernel.clj:5`): `title`,
`owner`, `lead`, `driver`, `committed`, `outcome`, … — domain/lifecycle fields. **None of
the AST predicates are single-valued.** A code edit commits AST claims with predicates
`kind`, `v`, `fN`/`segN`/`commentN`/`tail`/`child`, `style`, `placement`
(`bin/fram-commit-code` `ast-pred?`), and **AST asserts are multi-valued**, so:

- an identical re-assert **idempotently no-ops** (`cnf_coord.clj:139-142`), and
- independent asserts are **commutative** — order doesn't matter
  (`bin/fram-commit-code` header: *"AST asserts are MULTI-valued … so an identical
  re-assert idempotently no-ops and ordering of independent asserts is commutative"*).

The consequence for a swarm is the crux of the *whole* claim: **the unit of contention
is a single node's single-valued slot, not a file.** Two agents editing **different
functions in the same module** touch **different `@mod#n` nodes** → **zero contention**
(distinct `(te,p)` groups). On the file side those same two edits are **two edits to one
file**, which a worktree+merge baseline must *merge*. This is the structural asymmetry
the experiment is built to expose — and the part that, if it holds at swarm scale, is
"greater than the sum."

> **Honesty check on §2.** This asymmetry is *conjectured to matter at scale*; it is
> **not measured** at swarm scale. At K=4 (`concurrent-authoring`) the disjoint op (op 4)
> already merges cleanly on the *file* side with **zero** coordination cost — the file
> baseline wins it. The bet is that as N grows and edits cluster in *shared modules but
> distinct nodes*, the file side pays escalating merge cost while the graph side pays
> ~none. That bet is exactly what is unproven.

### 3. Recompile-gate + identity re-resolve (the correctness layer, already grounded at K=4)

These are not new for Claim 3 — they are the `concurrent-authoring` results — but they
are part of why a swarm of graph agents can converge without a broken intermediate:

- **Fail-closed recompile gate**: a write whose regenerated tree doesn't compile is
  rejected before commit (`fram_mcp.clj` `route-edit`; the harness gates on the parsed
  build count + exactly 0 errors, not `str/includes? "0 error"`).
- **Identity, not spelling**: a reference carries `refers_to <node-id>`; after a
  concurrent rename of its target, **module-granular re-resolve** (S3.3,
  `cnf_coord_daemon.clj:540-563`, `ensure-refers!` at `:571-584`) re-points it to the
  *current* name. The `concurrent-authoring` stale-cross-module-reference pair (ops 1+2)
  shows this is the discriminator vs a merge-tool-clean-but-non-compiling file merge.

### 4. The flip: authoring is serialized through the coordinator NATIVELY

The link from "the coordinator serializes single triples" to "the coordinator serializes
*authoring*" is **the flip**, and it is real code (untracked, this branch):

`bin/fram-commit-code` commits an edited module's **AST claim delta** *through the
coordinator's single-`(te,p,r)` `:assert`/`:retract` wire*, retrying on `:conflict` at
`base_version` (`commit-op!`), so **the claim log is the source and the `.bclj` is
downstream**. It does a **whole-node clean-slate re-commit** of changed nodes
(retract-then-assert, leaves before reparent) so no stale edge survives. It has a
**SAFETY gate** that refuses to commit unless the coordinator's `:status :log` basename
contains `code.log` — *"never commit code to a thread/lodestar log."* The canonical code
log already exists on disk: `/home/tom/code/fram/.fram/code.log` (556 KB).

`cnf_code_flip_test.clj` is the in-process gate proving the flip thesis (render(log) ==
render(text) byte-identical; a `set-body` delta committed **through the coordinator**
recompiles `0 error`; ingest is lossless; cross-frame bridge claims compose).

**Why this matters for Claim 3.** Once authoring is a coordinator-serialized claim delta,
the swarm's *entire coordination surface collapses onto §1's OCC primitive*: N agents
authoring = N agents doing `:assert`/`:retract` against the sole writer, contending only
on shared `(node, single-pred)` slots, every contention resolved by `base_version` with
no lost update, every accepted write recompile-gated. **That is the architecture the
swarm claim rides on.** It is built; it is **not yet measured against a swarm baseline**.

---

## The experiment (DESIGN — what would actually have to be run)

### Shape: N agents, real contention, many rounds

- **N agents** (start N ∈ {2, 4, 8}) run as **independent processes** (not stepped by one
  orchestrator), each with its own connection to the substrate.
- Each agent runs a **work loop**: pick a task from a shared backlog → read current
  state → make an edit → attempt to integrate → on rejection/conflict, **re-read and
  re-decide** → repeat, for **R rounds** (R ≥ 10) so contention *evolves* (agents build
  on each other's committed work and go stale on the rest).
- **Contention is induced fairly**, not rigged: the backlog is designed so a known
  fraction of tasks overlap (same module, sometimes same node), the rest are disjoint.
  The *same backlog* drives both arms. The overlap fraction is a swept parameter
  (e.g. 0%, 25%, 50%, 75%) — the headline is the **shape of the cost curve vs overlap**,
  not one number.

### What is measured (coordination, NOT wall-clock)

| id | metric | definition | why it's the right axis |
|----|--------|-----------|------------------------|
| **C1** | **Lost work** | edits an agent *completed* (locally valid) that never reach the converged state AND whose loss was **silent** (no signal to the agent) | the graph's claim: OCC makes this **0** (loser always gets `:reject :conflict`, never silently dropped); file LWW/working-copy modes can lose silently |
| **C2** | **Coordination overhead** | total work spent on integration *qua integration*: conflict re-reads + retries (graph) vs merges + rebases + manual conflict resolutions + re-runs of the merge queue (file) | the "overhead" half of "greater than the sum" — does it stay flat as N grows? |
| **C3** | **Convergence** | does the swarm reach a single state where (i) every accepted edit is reflected and (ii) the tree recompiles `0 error`? after how many rounds / how much rework? | "greater than the sum" = converges in *fewer rounds / with less rework*, not just *eventually* |
| **C4** | **Stale-decision incidents** | count of times an agent acted on a view that was already superseded, AND whether the substrate **caught it** before it corrupted the converged state | graph: `base_version` reject + verb-rejection-over-fresh-projection catch it; file: a stale branch can merge clean and break CI (the `concurrent-authoring` stale-ref pair, now under sustained contention) |
| **C5** | **Manual-intervention incidents** | conflicts/breakages that require a *human* (or a re-planning round) to resolve, vs resolved automatically by the substrate | the coordination property is largely "how much does the swarm coordinate *itself*" |
| **C6 (conceded axis)** | **Throughput / latency** | per-integration latency and end-to-end wall-clock | **reported, never claimed as a win.** Serialization has a real cost (in `concurrent-authoring`, coordinator reconcile ~0.20s vs git-merge ~0.014s — file faster). The graph's case is C1–C5, *bought at* C6. |

**Wall-clock is explicitly NOT the headline.** A skeptic attacking throughput is right and
is conceded up front (mirrors `concurrent-authoring`'s M6 discipline).

### The oracle (non-negotiable, inherited from tiers 1–2)

The Beagle compiler (`~/code/beagle/bin/beagle-build-all`), parsed for the **exact build
count AND exactly 0 errors** — never `str/includes? "0 error"`, never just the error
integer (a degenerate `1 built, 0 error(s)` partial tree must not masquerade as success).
Convergence (C3) is *defined by the compiler*, not asserted by the harness.

---

## The FAIR baseline — how a competent swarm coordinates over files TODAY

This is the part a skeptic will attack hardest, so it gets the most care. **The baseline
is NOT "N agents editing one working copy."** That is the weak last-writer-wins sub-mode
(`concurrent-authoring`'s M1) which *a competent engineer using git never hits* and which
we do **not** charge against the real baseline. The real, competent multi-agent file
workflow — the one to beat — is:

1. **Git worktrees, one per agent** (`git worktree add ../agent-k <base>`). Each agent
   gets an **isolated checkout** of a shared repo off one common base. No two agents share
   a working copy → no working-copy clobber. (This is literally how this very repo's
   harness isolates its own sub-agents — see `.claude/worktrees/agent-*` and
   `.claude/worktrees/wf_*`.) **This is a real strength: disjoint work is fully parallel,
   zero coordination cost.**

2. **A branch per agent / per task** off the common ancestor. Agents commit locally,
   isolated, with full git history and the ability to surface divergence loudly.

3. **Serialized integration via a merge/PR queue.** Branches integrate to the trunk
   **one at a time** through a queue (the file world's analogue of the sole writer): each
   merge **rebases onto the current trunk** (so it integrates the latest), runs a **3-way
   merge** (git surfaces real conflicts — a genuine strength: the conflict is *visible*,
   never silent), and **runs CI (the same recompile oracle) after the merge**, rejecting
   the merge if CI fails. A modern merge-queue (GitHub merge queue / Zuul / bors / the
   Aviator-style speculative queue) is the state of the art and **must be the baseline**,
   not a hand-merge.

4. **Rebase-on-reject.** When the queue rejects an agent's branch (conflict or CI red),
   the agent **rebases onto the new trunk and re-resolves** — the file-world analogue of
   the graph's "re-read and retry on `:conflict`." This is the file baseline's
   *coordination overhead* (C2), and it is **real, competent, and sometimes cheap**.

5. **Tooling at full strength**: multi-file find/replace on **source** (not LSP on the
   lowered form), `clojure-lsp` (installed), `clj-kondo`, `grep` — all available to the
   file arm, as supplementary "even these say green" evidence where relevant.

**Where the file baseline genuinely wins (must be conceded in the result):**

- **Disjoint work** — distinct files, distinct regions → worktrees + auto-merge → **zero
  coordination cost, fully parallel**. The graph pays serialization latency here for no
  correctness benefit. (This is `concurrent-authoring` op 4, now at swarm scale.)
- **Conflicts are visible** — git *surfaces* a divergent-region conflict loudly; it does
  not silently lose it. The merge queue + CI *catches* a semantically-broken merge. The
  file baseline does **not** ship undetectably-broken code; it pays **rework** to fix it.

**Where the bet is that the graph wins (the hypothesis, unproven):**

- **Same-file, distinct-node clustering at scale.** N agents editing different
  definitions in the *same hot module*: file side = N branches all touching one file →
  escalating merge/rebase churn through the queue (each rebase re-touches the file);
  graph side = N distinct `(te,p)` groups → **no contention at all**, commits commute.
  The cost curves diverge as N and overlap grow — *if* the bet holds.
- **Stale cross-module references under sustained contention.** The `concurrent-authoring`
  stale-ref pair (merge-tool-clean, CI-red) generalizes: under many rounds the file side
  repeatedly produces merges that pass every *merge* tool and fail CI (rework each time),
  while the graph re-resolves by identity at render time (no broken intermediate, no
  rework). The hypothesis is that **the rework integral grows with contention on the file
  side and stays ~flat on the graph side.**

**The honest summary of the baseline comparison:** worktrees+merge-queue is a *good*
coordinator. The graph's structural advantages are (i) finer contention granularity
(node, not file), (ii) identity-not-spelling references (no stale-ref rework), and (iii)
no broken intermediate ever exists (correct-by-construction vs catch-and-rework). Whether
those advantages **net out ahead of** a competent merge queue, across the overlap-fraction
sweep, **at the cost of C6 serialization latency**, is the open question. It is plausible;
it is not proven; the file baseline is strong enough that it might win more of the space
than expected.

---

## Fairness guardrails (so the result can't be a strawman)

1. **Same backlog, same oracle, same base** for both arms — only the *coordination
   substrate* differs (claim graph + OCC vs worktrees + merge queue + CI).
2. **File arm gets the strongest real workflow**: per-agent worktrees, branches off one
   base, a real merge queue with rebase-on-reject and **CI-gate-after-merge** (the same
   compiler), source-level find/replace, clojure-lsp/clj-kondo/grep. A skeptic attacking
   the baseline as a strawman must **lose**.
3. **Concede where file-based wins**: disjoint work (zero coordination cost), the
   visibility of git conflicts, and the C6 throughput axis. Report them, don't bury them.
4. **Report C6 (serialization cost) with measured numbers** isolating the *integration
   step* (coordinator round-trip vs merge-queue cycle), not subprocess startup.
5. **Faithful graph arm**: the contention decision is **OCC `base_version`**, named as such
   (not "a lock made races vanish"); the gate is parsed for exactly 0 errors; convergence
   is the compiler's verdict; any single-triple-vs-subtree proxy is disclosed at full
   strength (as in `concurrent-authoring`).
6. **Non-destructive & leak-safe** (same discipline as the sibling experiments and proven
   live this session): operate on `mktemp` copies + temp logs only; pick a **verified-free
   high port** (`ss -ltn | grep ":<port>"` empty) and confirm `{:op :status}` returns the
   expected temp log before trusting any result; `trap`-kill every daemon by process group
   on every exit path. **NEVER** touch port **7977** or `~/.local/state/lodestar/claims.log`
   (the live lodestar coordinator), and never write the repo's real `src/fram`.

---

## What would make Claim 3 PROVEN (the path past 2026-06-25)

In rough order:

1. **A faithful N-agent harness on BOTH arms** — independent processes, shared backlog,
   R rounds, swept overlap fraction. (The graph side leans on the already-built flip
   (`bin/fram-commit-code`) + OCC daemon; the file side needs a real merge-queue harness,
   not a hand-merge — this is the bulk of the work.)
2. **The overlap-fraction sweep**, reporting the **cost curves** (C1–C5) vs overlap for
   each N, with C6 reported as the conceded axis. The result is the *shape* (graph stays
   flat where file climbs), not a single ratio.
3. **The contention generator validated as fair** — show the same backlog drives both
   arms, the overlap is real (agents genuinely collide), and neither arm is rigged.
4. **The disjoint-work concession measured**, not assumed — confirm the file arm wins the
   0%-overlap end with zero coordination cost (if it doesn't, the harness is rigging the
   file side and the whole result is void).

Until all four exist and reproduce from clean, Claim 3 stays **FRONTIER: a characterized,
plausible, substrate-grounded hypothesis — not a demonstrated result.**

---

## Grounding index (every mechanism claim → file:line)

- Sole writer + OCC `base_version` reject: `cnf_coord.clj:118-153` (commit), `:62-63`
  (`base-version`), `:129-131` (conflict reject), `:33-38` + `:152` (atomic fsync append).
- Daemon serialization wrapper: `cnf_coord_daemon.clj:716` (`locking dlock`), `:350-362`
  (`do-assert` → `commit!` → conflict passthrough).
- OCC proven over real sockets: `cnf_flip_test.clj:91-92` (1 win / 7 conflict); and run
  **live this session** via `experiments/concurrent-authoring/graph_arm_occ_probe.clj`
  (K=8 → wins=1, conflicts=7, on temp port 9133 + temp log).
- Single-valued predicate set (contention granularity): `out/fram/kernel.clj:5`,
  `single?` at `:27-28`. AST predicates are NOT in it ⇒ AST asserts multi-valued/commutative
  (`bin/fram-commit-code` `ast-pred?` + header note).
- Identity re-resolve (scope-correct, module-granular): `cnf_coord_daemon.clj:540-563`
  (`materialize-refers-scoped!`), `:571-584` (`ensure-refers!`).
- The flip (authoring → coordinator-serialized claim delta): `bin/fram-commit-code`
  (whole-node re-commit, OCC retry `commit-op!`, code.log SAFETY gate); gate test
  `cnf_code_flip_test.clj`; canonical code log on disk `/home/tom/code/fram/.fram/code.log`.
- The pairwise precursor (K=4, correctness measured): `../concurrent-authoring/SPEC.md`,
  `../concurrent-authoring/RESULTS.md`.
