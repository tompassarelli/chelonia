> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# Tier-2 experiment SPEC — concurrent authoring through the claim graph

> **Status:** SPEC + scenario only (no harness code yet). This file defines the two
> arms, the concrete K-op scenario, the metrics, the oracle wiring, and the fairness
> guardrails. The harness (`run.sh` / `reproduce.sh`) is built against *this* spec.
>
> **Revision note.** This spec has been hardened against two adversarial critiques
> (fairness + faithfulness). The biggest change: **the original "silent miscompile via an
> *erased* reference" headline was a strawman for the write side and has been removed.**
> A write-side agent edits **source**, where every type annotation is a visible token — a
> multi-file find/replace of `Claim→Datum` on `.bclj` source catches **all 73 tokens** and
> recompiles `11 built, 0 error(s)` (measured). Tier-1's read-side blindness (a tool reading
> the *lowered* `.clj`) does **not** transfer. The thesis is re-scoped to the results the
> corpus actually supports, all **measured**.

## The thesis (the owed result)

**Many agents authoring ONE codebase CONCURRENTLY through the live claim graph beats
file-based editing — on correctness/convergence under concurrency, not on speed.**
Tier-1 (`../rename-identity/`) proved the *read/edit* side: references carry identity, not
spelling, so a cross-module rename re-points every reference. Tier-2 is the
*write/concurrency* side and is the result the whole project exists to show.

Through the graph, every write is:

1. **Serialized** through a sole-writer coordinator. The sole writer is a single JVM
   monitor (`locking dlock`, `locking (:lock co)`) — that *is* the architecture, not a
   cheat. The property that makes a *concurrent pair* safe is **OCC `base_version` across
   lock release/reacquire over independent socket connections** (proven race-safe:
   `cnf_flip_test.clj` — **8 separate socket clients → exactly 1 `:ok`, 7 `:reject
   :conflict`**). The win is OCC, *not* "a mutex made races disappear."
2. **Recompile-gated / fail-closed**: a write whose regenerated tree does not compile
   `0 error(s)` is **rejected, never committed** (`fram_mcp.clj` `route-edit` builds the
   regenerated tree and commits only on PASS, mutating nothing on FAIL). **The harness does
   NOT inherit route-edit's pass predicate** — see "the gate-predicate bug" below.
3. **Scope-correct intelligence that stays fresh between edits** at edit-scope cost —
   `refers_to` identity + `:callers` re-resolved module-granularly (S3.3) on each commit, so
   a reference to a renamed thing resolves to the *current* identity.

File-based editing has failure modes a competent engineer/agent genuinely hits **under
concurrency**:

- **Merge conflicts** on divergent edits to the same region — they need *manual*
  resolution (a throughput/coordination cost; the graph does not pay it). **Conceded as
  visible:** git *surfaces* a conflict, which is a real file-arm strength.
- **Lost updates** in the last-writer-wins working-copy mode — one edit silently
  overwritten.
- **The dangerous one — a textually-clean merge the compiler rejects.** Agent A renames a
  cross-module definition; agent B *concurrently* adds code in a **different** file that
  references the old definition (correct at B's base). The two edits touch **different
  files → git auto-merges with no conflict**, and **no textual signal connects B's
  reference to A's rename** (they are disjoint additions). The merge is green on git and
  grep; the **compiler** says the build is broken. This is a **stale cross-module
  reference**, not an erased one (see the honest frame).

The graph avoids all three **by construction**: identity (B's reference points at A's
renamed *node*, not its old spelling) + serialization (A and B's effects are ordered, never
clobbered) + the recompile-gate (a write that would break the build cannot commit).

## What this is NOT (read first — the honest frame)

- **Not "git is bad at merging."** Git's 3-way merge is *correct* for what it is. Disjoint
  edits to different files merge perfectly and we **concede it** (op 4 is the explicit
  concession). The claim is narrower: a *textually-clean* merge across disjoint files is
  **not necessarily a compiling merge** when a reference goes stale across the gap.
- **Not "the silent miscompile rides the tier-1 erased-reference blind spot."** **It does
  not, and we say so.** Tier-1's blindness is a tool reading the *lowered* `.clj`, where the
  annotation is gone. The tier-2 file-arm agent edits **source**. We **verified** that the
  strongest source rename (`sed -E 's/\bClaim\b/Datum/g'` across all `.bclj`) catches all
  **73** `Claim` tokens — *including every type annotation* — and recompiles
  `11 built, 0 error(s)`. **The file arm WINS the stand-alone rename.** The graph's win is
  **not** there. The real discriminator (below) is a **stale cross-module reference under
  concurrency**, where the failing token *is* textually present in the source but no textual
  tool can connect it to the concurrent rename in another file.
- **Not "the graph is faster."** Serialization through a sole writer has a real cost. We
  **measure and report it (M6)**, isolating coordinator-commit latency vs git-merge latency
  with projection/render held constant. The win is **correctness/convergence under
  concurrency**, not speed; a skeptic attacking throughput is right and is conceded.
- **Not a strawman baseline.** The file arm gets the *strongest real* approach: multi-file
  find/replace on `.bclj` **source** for the rename (not clojure-lsp on `.clj`), real
  concurrent branches, a real `git merge` / 3-way merge, plus `clojure-lsp`
  (installed: `2025.11.28`), `clj-kondo`, and `grep`. clojure-lsp appears only as a
  *supplementary* "even the LSP misses it" note, never as the primary rename. A skeptic
  attacking the baseline as a strawman must **lose**.
- **Not a claim that the graph "understands" types.** Type-checking is delegated to the
  Beagle compiler (the oracle). The graph transports identity and serializes writes; the
  *compiler* decides correctness. We never assert correctness ourselves.

### Disclosed faithfulness gaps (full strength — the result is scoped to them)

- **The OCC step is a single-triple proxy, disclosed.** A `rename Claim Datum` supersedes a
  whole *subtree* of claims (a new `v`-value claim + a `SUP` supersedes claim per binding —
  `resolve.clj:1058-1062`). The coordinator wire is **single-`(te,p,r)` ONLY** — the
  author's own note says exactly this (`fram_mcp.clj:72-73`). So the harness submits a
  **single-triple proxy `:assert`** through the **real** coordinator to exercise genuine
  `base_version` OCC over independent sockets. **Proven:** *the sole-writer coordinator
  serializes concurrent writes with no lost updates at single-triple granularity*
  (`cnf_flip_test`, real sockets). **Not proven:** *the coordinator OCC-gated the
  cross-module rename subtree* — that awaits "the flip" (`route-edit` committing the claim
  delta end-to-end). This is disclosed, not hidden; the OCC win is scoped accordingly.
- **The op-pair (1+3) convergence mechanism is verb-rejection, not coordinator OCC.** When
  C renames `Claim` after A's rename committed, C's rename verb **re-projects current
  source, finds no `Claim` binding, and HARD-FAILS** (`resolve.clj:1063-1070`,
  `System/exit 5`, *no claims mutated*) — it does **not** idempotent-no-op. The
  no-lost-update guarantee here comes from **verb-rejection-over-fresh-projection +
  coordinator serialization**, *not* from `base_version` on the rename. We attribute it to
  what actually fires (measured), and stop citing OCC as this pair's mechanism.
- **The gate-predicate bug.** `route-edit`'s gate is `(str/includes? built "0 error")`
  (`fram_mcp.clj:165`) — which **passes** `built, 10 error(s)` (it *contains* the substring
  `0 error`) and would commit a broken tree. The harness **does not** inherit it: the oracle
  parses `grep -iE 'built, .* error'` and asserts the error count is **exactly 0**, both
  arms. (Filed as a real route-edit bug.)
- **Fair base.** Both arms pin the **same frozen base snapshot per op**; only the
  reconciliation mechanism (coordinator vs git-merge) differs. The graph arm does **not**
  re-project from already-mutated source mid-sequence and call it "concurrent."

## The oracle (non-negotiable, same as tier-1): the Beagle compiler

`~/code/beagle/bin/beagle-build-all <tree-dir> --out <outdir> 2>&1 | grep -iE "built, .* error"`
— **parsed for exactly `0`**, never substring-matched.

- **Graph arm** passes iff its final tree recompiles **`N built, 0 error(s)`**, AND every
  rejected write was rejected *before* committing (the tree is never left broken, and a
  gate-FAIL leaves the coordinator version unchanged — verified).
- **File arm** is shown by the *same compiler* on its final merged tree to **FAIL**
  (`>= 1 error(s)`) on the interfering pair — a textually-clean merge that no textual tool
  flagged.

The compiler — not us — is the verification. Numbers are MEASURED by running the harness,
never asserted.

---

## The two arms (precise)

Both arms start from the **same** frozen base: a fresh `mktemp` copy of
`/home/tom/code/fram/src/fram/` (11 `.bclj` modules + `json.clj` + `rt.clj`). The
unmodified base compiles **`11 built, 0 error(s)`** (measured). Both run the **same K=4
concurrent authoring ops** (`scenario.edn`). The repo's real `src/fram` and `claims.log`
are never touched.

### Arm A — GRAPH (sole-writer coordinator + recompile-gate + identity re-resolve)

Each op, in coordinator-serialized order, runs the **strict** sequence:

1. **Project** every source module of the **frozen base** `.bclj → AST-claims EDN`
   (`racket $RT --emit-edn`), so cross-module references resolve by identity. *(Same base
   per op — not re-projected from another op's already-mutated source.)*
2. **Apply the verb as a CLAIM OP** (NOT a text splice):
   `RESOLVE_OUT=<dir> bb -cp out chartroom/src/resolve.clj <verb> ... <edn-paths>`
   - `rename <Old> <New> <module>` — supersedes **one** binding-name claim (measured:
     `CLAIMS EDITED: 1`); every `refers_to` reference re-points by identity. **Rejects
     fail-closed** if `Old` names nothing (`System/exit 5`, no claims mutated).
   - `set-body <name> <module> <file>` / `upsert-form <module> <file>`.
3. **Render** each affected module's projected EDN back to byte-stable text
   (`racket $RT --render`).
4. **RECOMPILE-GATE** the regenerated tree (the oracle), parsed for **exactly 0 errors**
   (not substring). On PASS: proceed. On any error: **reject, mutate nothing.**
5. **THEN SERIALIZE the commit through the sole-writer coordinator** under OCC
   `base_version`: read `{:op :version}` → `v`, submit a **single-triple proxy**
   `{:op :assert … :base v}` (the rename's subtree is inexpressible on the wire — disclosed
   above). The coordinator wins exactly one of any concurrent pair; the loser gets
   `{:reject :conflict :version v'}`, re-reads, retries. Strict order: **gate-PASS THEN
   commit** — a gate-FAIL never reaches the coordinator (so the coordinator version is
   unchanged, verified → M1=0 is *checked*, not asserted).
6. **Re-resolve scope-correct intelligence** module-granularly (S3.3): `refers_to` /
   `:callers` for the affected module set, so B's reference to a thing A renamed resolves to
   the **current** identity between edits — at edit-scope cost, not whole-corpus.

**Failure handling (arm A):** an op that does not survive the recompile-gate (or whose verb
rejects) is *rejected* and counted (M3); the tree is left in its last good state. The arm
"passes" iff the final tree compiles `0 error(s)` AND no broken tree was ever committed.

### Arm B — FILE (strongest real workflow: source find/replace + git 3-way merge)

K agents on a shared repo with feature branches, off **one** common ancestor:

1. `git init` the temp corpus; commit as the merge base.
2. Each op runs on its **own branch off the same base** (true concurrent authoring),
   editing `.bclj` **source text directly**:
   - **Rename** = **multi-file find/replace on `.bclj` source** (`sed -E
     's/\bClaim\b/<New>\b/g'`) — the *strongest* real baseline (catches all annotations;
     compiles clean in isolation). **clojure-lsp is NOT the primary rename** (it parse-fails
     on 4-arg `def` in `.bclj` and is blind to erased annotations on `.clj`); it appears
     only as a supplementary "even the LSP misses it" note.
   - **Body / form edits** = a real text edit (for the disjoint helper) or a real append of
     a new `defn` + its `(require …)` (for the new referencing function).
3. `git merge` / `git merge-file` the branches in order. Record conflicts requiring
   **manual** resolution (M2) and lost updates (M1). For clean auto-merges, take the merged
   tree as-is (the agent sees green; they ship).
4. **The same recompile ORACLE** on the final merged tree, parsed for exactly 0 errors (M4).

**The honest concession (arm B):** op 4 — a truly-disjoint edit — merges cleanly AND
compiles. The file arm *wins* there (no coordination overhead, fully parallel). We report
it as a tie/concession.

---

## The concrete scenario — K = 4 concurrent authoring ops over `src/fram`

`scenario.edn` is the machine-readable source of truth; this is its prose. All four ops are
authored **concurrently against the same frozen base** (no agent sees another's edit).
Every coordinate is **measured** against the live corpus.

| # | agent | op | module | what it does | interaction class |
|---|-------|-----|--------|--------------|-------------------|
| 1 | A | `rename Claim Datum` | `kernel` | cross-module rename of the core record type (`kernel.bclj:101`) | base of pairs (1+2) and (1+3) |
| 2 | B | add `schema-claim-count` (+ `require kernel`) in `schema` | `schema` | a **new fn in a file A's rename does NOT touch**, typed `(Vec k/Claim)`, calling `k/thread-ids` | **stale cross-module reference** (pairs with 1) |
| 3 | C | `rename Claim Fact` | `kernel` | a **second** agent renames the **same** binding to a **different** name | **divergent same-region rename** (pairs with 1) |
| 4 | D | `set-body var? datalog` | `datalog` | edits a private helper (`datalog.bclj:44`) in a module no other op touches, 0 `Claim` refs | **truly disjoint** — concede file-based wins |

### Why these four (mapping to the required classes, all re-derived by real merge)

- **Divergent same-region rename → conflict / lost-update (ops 1 + 3).**
  Two agents independently rename `Claim` — A to `Datum`, C to `Fact`.
  - *File arm (measured):* branch-merge → **git CONFLICT across 8 files** → manual
    resolution (M2 ≥ 1). Last-writer-wins working-copy sub-mode → **lost update** (M1 ≥ 1):
    one rename silently dropped. **Concession:** the branch-merge mode *surfaces* the
    conflict — git is not silent here; that is a real file-arm strength.
    *(Note: two **identical** renames auto-merge clean — git `ort` dedupes — so they are
    NOT a discriminator; the realistic collision is **divergent**, which we use.)*
  - *Graph arm (measured):* both submit the rename. A commits first (serialized).
    C's rename **re-projects current source, finds no `Claim` binding, and HARD-FAILS at the
    verb layer** (`REJECTED — no binding named Claim found … no claims mutated`,
    `System/exit 5`) — it does NOT silently drop or no-op. **No lost update, no manual
    conflict.** Mechanism: verb-rejection-over-fresh-projection + coordinator serialization.

- **Truly disjoint → clean (op 4).** `set-body var? datalog` — nothing else touches
  `datalog`, 0 `Claim` refs. *File arm:* merges clean, compiles → **concede the file arm
  wins** (parallel, no coordination cost). *Graph arm:* commits without conflict — a
  **tie**, and the graph paid serialization latency the file arm did not (M6).

- **Stale cross-module reference → clean merge the COMPILER rejects (ops 1 + 2).** This is
  THE discriminator.
  Op 1 (A: `Claim→Datum` in `kernel`) + op 2 (B: add `schema-claim-count` in `schema`,
  typed `(Vec k/Claim)`, calling `k/thread-ids`). `schema` has **0** `Claim` tokens, so A's
  rename never touches `schema.bclj` → the two edits are in **different files**.
  - *File arm (measured):* **git auto-merges with NO conflict** (0 conflicted files). The
    merged `schema.bclj` still says `(Vec k/Claim)`; `grep` finds `k/Claim` present and
    reports "still there"; clojure-lsp on emitted `.clj` is blind to the annotation. **Every
    textual tool says green.** The compiler says:
    `schema.bclj: call to k/thread-ids: arg 1 expected (Vec Datum), got (Vec k/Claim)` →
    **`10 built, 1 error(s)`**. A textually-clean merge that does not compile, demonstrated.
  - *Graph arm:* B's new form projects → its `(Vec k/Claim)` reference carries
    `refers_to <kernel-Claim-node-id>` — **identity, not spelling.** After A's rename
    commits (serialized), the module-granular re-resolve (step 6) re-points B's reference to
    the *current* name `Datum`; the recompile-gate then sees a consistent tree and commits
    `0 error(s)`. The interference is resolved by construction; the build is green for real.

  **Honest framing of the mechanism:** the failing token (`k/Claim`) *is* textually present
  in B's source — this is **not** an erased-reference case. The kill is that **no textual
  tool can connect B's `k/Claim` in `schema.bclj` to A's rename in `kernel.bclj`** across a
  disjoint-file auto-merge; only the compiler's cross-module type check catches it. The
  graph catches it because the reference is an *identity edge*, not a spelling, and the
  re-resolve follows it.

---

## Metrics (all MEASURED by the harness, per arm)

| id | metric | how measured | graph (hypothesis) | file (hypothesis) |
|----|--------|--------------|------------------|-----------------|
| **M1** | **Lost updates** — writes accepted then silently dropped | count ops whose effect is absent from the final tree though the op "succeeded"; verified-checked (gate-FAIL leaves coordinator version unchanged) | **0** | **≥1** (op 1 vs 3, last-writer-wins) |
| **M2** | **Merge conflicts needing manual resolution** | count `git merge` conflicts (CONFLICT markers / non-zero exit) requiring a human edit | **0** (no textual merge — writes are serialized claim ops) | **≥1** (op 1 vs 3: **8 conflicted files** measured) |
| **M3** | **Writes rejected fail-closed** | count ops the verb or recompile-gate rejected *before* commit | **≥1 GOOD** (op 3 verb-rejects on fresh projection; any non-compiling intermediate gate-rejected) — tree stays valid | n/a (no gate; bad edits land) |
| **M4** | **Final recompile status (THE ORACLE)** | `beagle-build-all <final-tree>` → `built, N error(s)`, parsed for exactly 0 | **`N built, 0 error(s)`** | **`≥1 error(s)`** (op 1+2: **`10 built, 1 error(s)`** measured) |
| **M5** | **Stale cross-module references** | count references that in the final tree point at a name that no longer exists while textual tools report "fine" | **0** (refers_to re-resolved each edit) | **≥1** (op 2's `k/Claim`; git+grep green, clojure-lsp blind) |
| **M6** | **Serialization cost (HONEST)** | isolate **coordinator-commit latency vs git-merge latency**, projection/render held constant (they are identical work both arms); ≥N trials, warm JVM; report per-op latency + throughput | **higher** — reported, conceded | **lower** on disjoint ops — the file arm's real advantage |

> **Reading the metrics:** the win is M1=M2=M5=0 and M4=`0 error(s)` for the graph vs
> M1/M2/M5 ≥1 and M4=`≥1 error(s)` for the file arm — **correctness/convergence under
> concurrency**, purchased at the cost reported in M6. We never claim the M6 axis. M6 must
> isolate the *reconciliation* step (coordinator round-trip vs git-merge), not subprocess
> startup, or it dishonestly inflates/deflates the conceded cost.

---

## Oracle wiring (how the compiler verdict is produced per arm)

Identical oracle, two final trees, **parsed for exactly 0 errors**:

```
# GRAPH ARM final tree = the regenerated .bclj tree after all serialized commits
$BUILD  $GRAPH_TREE  --out $G_OUT  2>&1 | grep -iE "built, .* error"      # expect: 0 error(s)

# FILE ARM final tree = the git-merged working tree after all branch merges
$BUILD  $FILE_TREE   --out $F_OUT  2>&1 | grep -iE "built, .* error"      # expect: >= 1 error(s)
```

where `BUILD=~/code/beagle/bin/beagle-build-all`, `BEAGLE=BEAGLE_HOME=~/code/beagle`. The
harness asserts the parsed error count is **exactly 0** (never `str/includes? "0 error"`).

**Discrimination check (the tier-1 discipline — prove the oracle actually fires):** on the
*interfering pair* (ops 1+2), the harness shows every **textual** tool reports the file arm
clean — `git merge` exit 0 (0 conflicted files), `grep -rE "k/Claim"` still matches in
`schema.bclj`, and `clojure-lsp rename` on the emitted `.clj` touches **0** of the type
annotations — while `$BUILD` on that same merged tree reports
`call to k/thread-ids: arg 1 expected (Vec Datum), got (Vec k/Claim)`. This makes M4 a
*real* discriminator: "the compiler says the file arm's merged tree is wrong, and no textual
tool could have known."

**Counter-discrimination (prove we are not strawmanning the baseline):** the harness ALSO
runs the strongest file-arm rename (`sed` on `.bclj` source) **stand-alone** and shows the
compiler reports `11 built, 0 error(s)` — the file arm WINS the isolated rename. The graph's
win appears only under **concurrency** (the stale-reference pair), which is exactly the
thesis.

---

## Scope-bounding — what tier-2 does and does NOT show

- **Shows:** under genuine concurrency (K=4, same frozen base), the graph arm converges to a
  compiling tree with **0 lost updates, 0 manual conflicts, 0 stale references**; the file
  arm — using the *strongest* real toolchain (source find/replace + git 3-way merge +
  clojure-lsp/clj-kondo/grep) — produces a **manual conflict** on the divergent-rename pair
  and a **textually-clean-but-non-compiling** merge on the stale-reference pair. Verified by
  the Beagle compiler, not by us.
- **Concedes:** (a) the **stand-alone** rename — the file arm's source find/replace compiles
  clean; the graph does not beat it in isolation. (b) The **disjoint** edit (op 4) — file
  based handles it with **no serialization cost** (M6 favors the file arm). (c) The
  divergent-rename **branch-merge** conflict is **visible** — git surfaces it; the file
  arm's only undetectable failure is the silent stale-reference merge.
- **Does NOT show / out of scope:**
  - The full production "flip" — `route-edit` committing the claim delta through the
    coordinator end-to-end. The OCC step is a **single-triple proxy** through the real
    coordinator (disclosed). Proven: serialization with no lost updates at single-triple
    granularity (`cnf_flip_test`). Not proven: OCC-gating the rename **subtree**.
  - That the graph "understands" types — type-checking is the compiler's job (the oracle).
  - Performance tuning of the serialized write path; M6 is a measurement, not a target.
  - **The tier-1 erased-reference blindness on the write side** — it does NOT apply; a
    source-editing agent sees every annotation. We do not claim it.
  - Magnitude claims (reference counts are illustrative, per tier-1 — the *phenomenon* is
    the result, not its size).

---

## Fairness guardrails (so the baseline is real, not a strawman)

1. **Same frozen base, same ops, same oracle** for both arms — only the *write/merge
   mechanism* differs. Both arms pin one base snapshot per op (no moving base).
2. **File arm gets the STRONGEST real tooling:** multi-file find/replace on `.bclj` **source**
   for the rename (the real baseline; verified to compile clean), real concurrent branches,
   real `git` 3-way merge, plus `clojure-lsp` (installed), `clj-kondo`, `grep`. clojure-lsp
   is a supplementary note, never the primary rename.
3. **Concede where file-based is correct:** the stand-alone rename (compiles clean), op 4
   (disjoint, no coordination cost), and the divergent-rename branch-merge conflict (git
   *surfaces* it — the silent stale-reference merge is the only mode the file arm cannot
   detect).
4. **Report the serialization cost (M6)** with measured numbers isolating the reconciliation
   step; never bury it.
5. **Faithful graph arm:** disclose the OCC single-triple proxy at full strength; attribute
   each pair's convergence to the mechanism that actually fires (OCC `base_version` for the
   single-triple race; **verb-rejection-over-fresh-projection** for the divergent rename);
   gate parsed for exactly 0 errors (not `str/includes?`); defeat the "you just used a lock"
   attack by naming OCC across lock release/reacquire over independent sockets.
6. **Non-destructive:** operate on `mktemp` copies of `src/fram` and temp logs only. Never
   write `src/fram`, never touch port **7977** or `~/.local/state/lodestar/claims.log` (the
   user's live coordinator). Pick a verified-free port in 9100-9990
   (`ss -ltn | grep ":<port>"` empty), confirm `{:op :status}` returns the **expected temp
   log path** before trusting any result, and `trap EXIT` to kill every daemon started.
7. **Numbers measured, reproducible from clean.** `reproduce.sh` makes the compiler render
   the verdict in front of the reader, both arms.

## Reproduce (planned)

```
bash reproduce.sh
```

Needs `racket`, `babashka`, Beagle (`BEAGLE=~/code/beagle`), `git`, and `clojure-lsp`
(supplementary; the oracle runs without it). You will watch: the file arm's
**divergent-rename pair conflict** (visible), its **stale-reference pair merge clean on
every textual tool then fail the compiler** (`10 built, 1 error(s)`), the **stand-alone
source rename compile clean** (the conceded file-arm win), and the graph arm converge to
**`0 error(s)`** with the divergent rename verb-rejected and the stale reference re-resolved
— the verification firing in front of you.
