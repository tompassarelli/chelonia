> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# Concurrent authoring through the graph beats file-based editing on correctness/convergence under concurrency

Many agents authoring **one** codebase **concurrently** can go through a live claim graph or
through files. Through the graph, every write is **serialized** through a sole-writer
coordinator (OCC `base_version`), **recompile-gated** (a write that breaks the build is
*rejected, never committed*), and references carry **identity, not spelling** so a reference
to a renamed thing re-points by construction. File-based editing under concurrency hits real
failure modes a competent engineer genuinely meets — merge conflicts, lost updates (in a
no-VCS sub-mode), and the load-bearing one: a **merge that every merge tool calls clean but
that recompilation rejects**, because a cross-module reference went stale across the gap and
no *merge/textual* tool can see it. This file demonstrates it, and `reproduce.sh` lets you
watch a compiler — not us — render the verdict on **both** arms.

This is **tier-2** — the write/concurrency side, and the result the whole project exists to
show. Tier-1 (`../rename-identity/`) proved the read/edit side (references carry identity, not
spelling). Tier-2 puts that under concurrency, against a real file-based baseline.

---

## Read this first: what this is NOT

- **Not "git is bad at merging."** Git's 3-way merge is *correct* for what it is. Disjoint
  edits to different files merge perfectly, and we **concede it** — op 4 below is the explicit
  concession: it auto-merges clean *and* compiles, with zero coordination cost. The claim is
  narrower: a *textually-clean* merge across disjoint files is **not necessarily a compiling
  merge** when a reference goes stale across the gap.
- **NOT "the file arm silently ships broken code."** This is the most important correction.
  The stale-reference merge is **not silent or undetectable to the file arm's own toolchain.**
  Its *merge tooling* (git 3-way + grep + clojure-lsp-on-the-lowered-`.clj`) is **blind** to
  the stale ref and reports the merge **green**. But the file arm's **compiler — run as CI, the
  exact same oracle this experiment uses — is NOT blind**: it catches the stale ref **loudly**
  as `10 built, 1 error(s)`. A competent file-based team **runs CI after a merge**, so the
  honest comparison is:
  - **FILE:** CI catches the broken merge → the merge is **rejected, never shipped** → it costs
    **manual rework** (re-resolve the stale reference by hand).
  - **GRAPH:** identity re-resolves the reference at render time → the broken tree is **never
    produced** → **zero rework** (correct-by-construction).
  The graph's win is **no broken intermediate ever exists**, not "the file arm ships
  undetectably-broken code." The discriminator's precise name is a **merge-tool-blind stale
  reference, caught only by recompilation** — never "silent miscompile."
- **Not the tier-1 erased-reference blind spot riding again.** Tier-1's kill was a tool reading
  the *lowered* `.clj`, where the type annotation is gone. A tier-2 **write-side** agent edits
  **source**, where every annotation is a visible token. We **verified** that the strongest
  source rename (`sed -E 's/\bClaim\b/Datum/g'` over all `.bclj`) catches all **73** `Claim`
  tokens — *including every annotation* — and recompiles `11 built, 0 error(s)`. **The file arm
  WINS the stand-alone rename.** The graph does **not** beat it there. The discriminator is a
  **stale cross-module reference under concurrency**, where the failing token *is* textually
  present in the source but no *merge/textual* tool can connect it to the concurrent rename in
  another file — only the compiler can.
- **Not "the graph is faster."** Serialization through a sole writer has a real cost. We
  **measure and report it (M6)**: the graph's reconcile step is ~14× slower than git's merge on
  the disjoint op. The win is **correctness / convergence under concurrency**, not speed; a
  skeptic attacking throughput is right, and is conceded.
- **Not a strawman baseline.** The file arm gets the *strongest real* workflow: multi-file
  find/replace on `.bclj` **source** (not clojure-lsp on `.clj`), real concurrent git branches
  off one common ancestor, a real `git merge` 3-way, a **symmetric compile-after-merge CI gate**
  (the same oracle the graph gets), plus `clojure-lsp` (installed, `2025.11.28`) and `grep` as
  supplementary "even these say green" notes. A skeptic attacking the baseline as a strawman
  must **lose**.
- **Not a claim the graph "understands" types.** Type-checking is delegated to the Beagle
  compiler (the oracle). The graph transports identity and serializes writes; the *compiler*
  decides correctness. We never assert correctness ourselves.

## The phenomenon (the headline)

Under genuine concurrency (K=4 ops, one frozen base, no agent sees another's edit), the two
arms diverge on **correctness / rework**, not speed. Three interaction classes, all re-derived
by a real merge / real coordinator:

| interaction class | ops | file arm | graph arm |
|---|---|---|---|
| **divergent same-region rename** (`Claim→Datum` vs `Claim→Fact`) | 1+3 | real git **CONFLICT in 8 files** → manual resolution (the conflict is **VISIBLE** — conceded) | loser's rename verb **ATOMIC-REJECTs** on fresh projection (`no binding named Claim`, `exit 5`, *no claims mutated*) — no half-merged conflicted tree; **agent C must still re-decide** its rename |
| **stale cross-module reference** (B adds `(Vec k/Claim)` while A renames `Claim`) | 1+2 | merge tooling **auto-merges CLEAN** (git: 0 conflicts; grep: `k/Claim` present; LSP blind) — then the file arm's **CI/compiler REJECTS it LOUDLY** (`10 built, 1 error(s)`) → **manual rework** | B's `(Vec k/Claim)` carries `refers_to <kernel Claim node>`; A's rename re-points it to `(Vec k/Datum)` **by identity** at render time — the broken tree is never produced; compiles clean |
| **truly disjoint** (`set-body var?` in `datalog`) | 4 | merges clean, **compiles** — **file arm WINS** (no coordination cost) | commits clean — a tie, and the graph paid serialization latency the file arm did not |

The graph avoids the first two **by construction**: identity (B's reference points at A's
renamed *node*, not its old spelling) + serialization (A and C are ordered, never clobbered) +
the recompile-gate (a write that would break the build cannot commit). The file arm's CI
*catches* the broken merge — it does not ship it — but catching it costs rework the graph
never pays.

## The demonstration (Beagle — because it has a recompile oracle)

`reproduce.sh` runs **both arms over the same `scenario.edn`** on the real toolchain (racket
projector/renderer, `bb` `resolve.clj` claim-verbs, a real sole-writer coordinator over TCP
sockets, `beagle-build-all` as the oracle) and prints a side-by-side. **Measured** (the
numbers below are the per-run output; reproducibility is reported honestly under *Reproduce*):

| metric | GRAPH (arm A) | FILE (arm B) |
|---|---|---|
| **M2** manual merge conflicts (git) | **0** | **8 files** (ops 1+3 divergent rename) |
| **M3** writes rejected fail-closed | **1 GOOD** (op3 verb-reject, *no claims mutated*) | n/a (merge tooling has no gate) |
| **M4** final recompile / CI (THE ORACLE) | **`11 built, 0 error(s)`** | **`10 built, 1 error(s)`** |
| **M5** stale cross-module refs | **0** (re-resolved by identity) | **1** (`k/Claim`; git+grep green, LSP blind, **compiler red**) |
| **M6** reconcile latency | coordinator-commit **~0.20s** | git-merge **~0.014s** (file arm faster — **CONCEDED**) |
| **M1** lost update (LWW, no-VCS sub-mode) | **0** | **1** (measured; explicitly **weaker** sub-mode — see below) |

**On M1 — measured, and honestly scoped.** M1 is the **last-writer-wins shared-filesystem
sub-mode** with **no version control**: A renames `Claim→Datum`, then C (working from a stale
base buffer) saves `Claim→Fact` over the same files, silently overwriting A. The harness
**measures** it (counts `Datum` tokens before/after C's save; they drop 73→0 → A's update was
lost). This is the **weakest** file-based sub-mode — *a competent engineer using git never hits
it*, because git **surfaces** the same divergence as the M2 conflict. We therefore report M1
**separately** and do **not** charge it against the git baseline. The git baseline's
divergent-rename outcome is the **visible M2 conflict**, not a lost update.

Supporting facts, all measured by the harness:

- **Stand-alone rename (the conceded file-arm win):** `sed` on `.bclj` source caught all **73**
  `Claim` tokens and recompiled `11 built, 0 error(s)`. The graph does not beat it in isolation.
- **The file arm's merge-tool-blind stale reference (caught by CI, not silent):** the merged
  `schema.bclj` is green on every *merge/textual* tool (git: 0 conflicts; grep: `k/Claim`
  present, 1 match; clojure-lsp: **59** source `:- …Claim` annotations **erase to 0** in the
  emitted `.clj`, so the LSP has nothing to flag). The **same** tree under the file arm's own
  **CI/compiler**: `schema.bclj:109: call to k/thread-ids: arg 1 expected (Vec Datum), got
  (Vec k/Claim)` → `10 built, 1 error(s)`. The merge is **rejected**, never shipped, and must
  be **reworked by hand**.
- **The graph's re-resolve:** B's `(Vec k/Claim)` reference, carrying `refers_to
  <kernel-Claim-node>`, re-rendered to `(Vec k/Datum)` after A's rename — **identity, not
  spelling** — so the regenerated tree recompiled clean and the broken tree was never produced.
- **Serialization, proven over real sockets:** 8 concurrent racers on one `(subject,
  single-valued predicate)` at the same stale base → **wins=1, conflicts=7** (exactly the
  `cnf_flip_test` guarantee). The loser reconciles; it is never a lost update.
- **Fail-closed, verified not asserted:** a **deterministically**-broken edit (revert every
  `k/Datum`→`k/Claim` in `schema.bclj`, which always breaks the cross-module ref) gate-FAILed
  (`10 built, 1 error(s)`) and the coordinator version stayed **unchanged** (before=7, after=7)
  — a gate-FAIL never reaches the coordinator. The harness **asserts the broken tree fails**
  before relying on it; if it ever compiled clean that would be a harness defect (fatal), not a
  check to skip.

## The oracle is the verification — not "trust me"

Correctness here is checked by **the Beagle compiler**, parsed for **the exact build count AND
exactly 0 errors** — never `str/includes? "0 error"` (which would pass `built, 10 error(s)` — a
real `route-edit` bug we do **not** inherit), and never just the error integer (which would let
a degenerate `1 built, 0 error(s)` partial tree pass — closed here by gating on the build
**count**: the graph arm must be exactly **`11 built`**, the file arm exactly **`10 built`**, so
a tree where render silently dropped modules cannot masquerade as the thesis). The graph arm's
final tree recompiles `11 built, 0 error(s)`. The file arm's final tree — produced by the
*strongest real* file workflow, green on every *merge* tool — recompiles `10 built, 1 error(s)`
**under its own CI**. So `clean-recompile` genuinely **discriminates**: the compiler says the
graph's tree is right and the file arm's merge-tool-clean merge is wrong. `reproduce.sh` runs
both so you watch the oracle fire on each.

## Scope — what this does and does NOT show (incl. the honest serialization cost)

**Shows:** under genuine concurrency (K=4, one frozen base), the graph arm converges to a
compiling tree with **0 lost updates, 0 manual conflicts, 0 stale references**; the file arm —
using the strongest real toolchain *including a symmetric CI gate* — produces a **visible
manual conflict** on the divergent-rename pair and a **merge-tool-clean merge its own CI
rejects** on the stale-reference pair (costing manual rework). Verified by the compiler.

**Concedes (honestly):**
- The **stand-alone** rename — the file arm's source find/replace compiles clean (`11 built, 0
  error(s)`). The graph does not beat it in isolation.
- The **disjoint** edit (op 4) — file-based merges clean *and* compiles with **no serialization
  cost**. The file arm wins; the graph paid the latency in M6.
- The divergent-rename **branch-merge conflict is VISIBLE** — git surfaces it loudly; that is a
  file-arm strength.
- The stale-reference merge is **caught loudly by the file arm's CI** (the same compiler) —
  it is **not** shipped undetectably. The graph's advantage there is **zero rework**
  (correct-by-construction), not "the file arm ships broken code."
- **The serialization cost is real and reported (M6):** the graph's reconcile step (~0.20s
  coordinator round-trip) is ~14× slower than git's merge (~0.014s). The win is bought on the
  correctness / rework axis, not the speed axis. We never claim the speed axis.

**Does NOT show / out of scope (disclosed faithfulness gaps — the result is scoped to them):**
- **The OCC step is a single-triple proxy.** A `rename` supersedes a *subtree* of claims, which
  is inexpressible on the coordinator's single-`(te,p,r)` wire (the documented `route-edit` gap —
  "the flip" — where `route-edit` overwrites the `.bclj` text but does not yet commit the claim
  delta through the coordinator). Each op therefore commits a single-triple proxy assert through
  the **real** coordinator under `base_version` to exercise genuine OCC over independent sockets.
  **Proven:** serialization with no lost updates at single-triple granularity (8 racers → 1 win,
  7 conflict). **Not proven:** OCC-gating the rename *subtree* itself.
- **The divergent-rename pair's convergence is verb-rejection, not OCC.** When C renames `Claim`
  after A's rename, C's verb re-projects current source, finds no `Claim` binding, and
  **atomic-rejects** (exit 5) — it does **not** idempotent-no-op. The no-lost-update guarantee
  there comes from *verb-rejection-over-fresh-projection + coordinator serialization*, **not**
  from `base_version` OCC on the rename. **And the atomic reject still requires agent C to
  reconcile** — C's intended `Claim→Fact` rename did not happen, the binding it targeted no
  longer exists, so C must re-decide. The win over git is that the tree is **never left in a
  broken / conflict-markered intermediate**, *not* that no human/agent follow-up is needed.
- **Warm `:callers` over *this* coordinator returns no AST nodes** — its store holds only the OCC
  proxies (the same "the flip" gap), not the projected corpus AST, so `:callers` prints
  `{:error "no such binding"}` (a labeled diagnostic, expected). The load-bearing freshness claim
  here is the **render-time identity re-resolve** (op2 `(Vec k/Claim)→(Vec k/Datum)`), which *is*
  demonstrated and compiler-verified; warm `:callers` over a corpus-AST coordinator is proven
  elsewhere (the S3 daemon-wiring commits + tier-1) and is not re-asserted off the proxy log.
- **The M5=0 re-point is mechanically produced by re-running A's rename verb a SECOND time over
  B's re-projected tree** (`reproduce.sh` "replay A's rename over it"), where the resolver
  re-resolves the reference by identity at render time — **not** by A's single op1 rename
  propagating to B's reference, and **not** by identity transported through the concurrent
  coordinator (which carries only the single-triple OCC proxy above). The headline "identity, not
  spelling" holds, but it is the render-time resolver — invoked by a second serial application of
  the rename verb — that does the re-point, not the OCC wire.
- The graph does not "understand" types (the compiler does); M6 is a measurement, not a target;
  reference counts are illustrative (the *phenomenon* is the result, not its magnitude).

## Reproduce

```
bash reproduce.sh
```

Needs `racket`, `babashka` (`bb`), `git`, and Beagle (`BEAGLE=~/code/beagle`); `clojure-lsp` is a
supplementary baseline note (the oracle runs without it).

**Reproducibility (reported honestly).** Earlier revisions of this harness were intermittently
flaky: `project()`/`render()` ran racket with `2>/dev/null`, so a transient subprocess failure
under load left an empty/partial `.bclj` tree and the run silently degraded to a misleading
verdict (a 0/0 race, a near-empty tree passing as the thesis). This is **fixed**: racket stderr
is captured and checked, every emitted file must be non-empty or the run **aborts non-zero**;
the daemon client **hard-fails** (not nil/0) if the coordinator is unreachable; the oracle gate
asserts the **build count** (11 / 10), not just the error count; and the OCC race aborts rather
than report a 0/0 "result". With those fixes the discriminating numbers above reproduced
**identically on a clean 5-run serial loop (5/5 PASS) and a 3-way concurrent-invocation batch
(3/3 PASS, each invocation auto-claiming a distinct port 9100/9101/9102), plus standalone
verification runs — 11/11 PASS this session, only M6 latency micro-varying (~0.19-0.20s)**.
The fixes were validated against the exact failure conditions the adversarial review hit
(competing JVM coordinators from sibling experiments, load avg > 1, parallel `reproduce.sh`
invocations). If you observe a `FATAL`/non-zero exit, that is the harness **refusing to emit a
degraded verdict** (a transient subprocess/daemon lapse), not the thesis failing — re-run; it
**never** prints `THESIS DEMONSTRATED` over a degenerate or partial tree (the build-count gate
forbids it).

**Non-destructive and leak-safe.** It operates on `mktemp` copies of `src/fram` and a temp
coordinator log on a **verified-free** high port, confirms the coordinator `:status` returns the
expected temp log before trusting any result, and `trap`-kills its daemon **by process group**
(via `setsid`) on **every** exit path including interrupt and FATAL. At startup it **sweeps**
any stale `t2-repro` daemon (whose log lives under `/tmp/t2-repro-*`) and reaps orphaned temp
dirs, so a prior leak self-heals. It **never** touches port 7977 or
`~/.local/state/lodestar/claims.log` (the live lodestar coordinator). (The sibling
`graph_arm.sh` / `file_arm.sh` are exploratory scripts, not part of this reproduction; this
leak-safety statement is scoped to `reproduce.sh`.)

You will watch the file arm's divergent-rename conflict (visible), its stale-reference pair merge
clean on every *merge* tool then get **rejected by its own CI** (`10 built, 1 error(s)`), the
stand-alone source rename compile clean (the conceded file-arm win), and the graph arm converge
to `11 built, 0 error(s)` with the divergent rename atomic-rejected (C re-decides) and the stale
reference re-resolved by identity — the verification firing in front of you.
