# Chartroom — benchmark results

**Verdict: code-is-claims is VALIDATED on the leverage axis.** On the live gjoa
corpus the Fram-backed claim graph answers two questions the incumbent
(`beagle callers` / bare-symbol match) structurally cannot, and Fram's Datalog
computes the real call-graph transitive closure correctly. The bet cleared its
kill lines decisively. Next turtle is the source-of-truth round-trip gate.

Run: `bb -cp ~/code/fram/out:src src/chartroom.clj build/gjoa-full.claims`
Corpus: gjoa `src` + `tools` + `tests` — **97 files, 193,179 claims**
(29 EDN-unparseable leaf literals skipped — see caveats), **369 defns, 424
resolved internal call-edges**.

---

## Benchmark A — scope-correct caller precision

**Question:** "who calls *this* `red`?" when `red` is a separate `defn` in two
modules. A call binds the defn in its own module (module-local lexical scope);
the bare-symbol incumbent ignores scope and merges both.

| metric | value |
|---|---|
| collision names (defn names defined in ≥2 files) | 19 |
| scored targets | 18 |
| **graph precision** | **1.00 on every target** |
| incumbent precision | 0.33 – 0.67 |
| **mean precision delta (graph − incumbent)** | **+0.389** |
| kill line | delta < +0.20 → **cleared (≈2×)** |

Worst incumbent cases (graph is 1.00 throughout):

```
red / green / bold / dim / yellow   preflight.bjs   incumbent P=0.33   (1 of 3 callers in-scope)
c                                   preflight.bjs   incumbent P=0.42   (5 of 12 in-scope)
fetchVersions                       bump.bjs        incumbent P=0.50   (1 of 2 in-scope)
c                                   status.bjs      incumbent P=0.58   (7 of 12 in-scope)
```

**Corroborated against the real tool.** `beagle callers red ~/code/gjoa/tools`
returns call sites from **both** `preflight.bjs` and `status.bjs` in one list;
ground truth confirms `red` is a distinct `(defn red …)` in each file. The
incumbent cannot separate them; the graph resolves each to its own module.

The +0.389 mean is held down by 2-file collisions (precision floor 0.5 for a
balanced 2-way split). On higher-arity collisions the gap widens — the graph is
*perfectly* precise on all 18 targets while the incumbent is wrong 33–67% of the
time. This is a clear win, short only of an optimistic 0.50 target bar.

---

## Benchmark B — transitive leverage (keystones)

**Question:** rank every defn by **blast radius** — how many functions
transitively depend on it. A flat one-hop caller list cannot compute this; a
graph closure does in one fixpoint. (This is Lodestar's `leverage`, for code.)

| metric | value | kill line | result |
|---|---|---|---|
| Spearman ρ (one-hop rank vs transitive rank) | **0.564** | ρ ≥ 0.80 | ✅ closure reorders |
| defns with blast/direct ≥ 3× | **59** | < 5 | ✅ |
| keystone hidden by one-hop | **yes** | — | ✅ |

```
TOP by transitive blast radius (transitive callers):
  blast=30  direct=8   tab-by-id            tabs/helpers.bjs
  blast=25  direct=3   pin-tab-id           tabs/helpers.bjs   ← top-5 transitive, NOT top-10 direct
  blast=24  direct=2   read-pinned-id       tabs/helpers.bjs
  blast=23  direct=14  tree-data!           tabs/helpers.bjs
  blast=20  direct=1   migrate-legacy-log!  tabs/log.bjs       ← direct=1, transitively unblocks 20
```

`migrate-legacy-log!` is the thesis in one line: a one-hop tool ranks it dead
last (one caller); the closure shows changing it touches 20 functions.

---

## Engine — Fram Datalog on real code

The resolved call graph (424 edges) loaded into a Fram store; the proven
transitive-closure rule computed **857 reaches-pairs in 2.3 s**, an exact
**MATCH** to an independent in-process closure. Fram's domain-agnostic Datalog —
written for life threads — computed code leverage unchanged.

Caveat / next perf turtle: the v1 fixpoint is naive (cold-recompute); 2.3 s at
424 edges flags **semi-naive evaluation** as the engine upgrade needed before
graphs an order of magnitude larger.

---

## Turtle #3 — the source-of-truth gate (the graph IS the program)

Turtle #2 proved the graph is a queryable *index*. Turtle #3 proves it can be a
*source of truth*: a beagle program round-trips through its claim graph losslessly,
so you could author the graph and treat text as a regenerable view — exactly how
Fram already treats a thread's markdown.

The key move: turtle-#2's AST claims are a lossy **query** projection (overlays
drop types/params; reconstruction needs an AST unparser). Losslessness lives one
layer down, at the **reader datum tree**, where type annotations (`:- Any`) are
just tokens. So the gate is proven there. Two projections, both derived from the
same source — verbose-but-lossless for truth, compact-but-lossy for queries.

`racket beagle-lib/private/claims-roundtrip.rkt <dir>...` (or `bin/beagle-roundtrip`):

| gate | result |
|---|---|
| `datum → claims → datum` identical, whole corpus | **1100 / 1100 forms (100.00%)** |
| `claims → idiomatic source → re-read` identical (**semantic**, not byte) | **97 / 97 files** |
| round-trip **through a persisted Fram store** (helpers.bjs) | **PASS — datum-identical program** |
| projector is a **fixpoint** (`render = render∘parse∘render`, byte-stable) | **PASS** |

The through-Fram proof is the thesis in full: `source → reader-claims → EDN → a
real fram.cnf store (3,858 claims) → re-extracted claims → datum → idiomatic
beagle text → re-read → identical program`. The reverse path (`claims → datum →
source`) — which **did not exist anywhere in beagle** — now does, un-desugaring
the reader's internal forms (`[…]→(#%brackets …)`, `{…}→(#%map …)`,
`#{…}→(#%set …)`, `#"…"→(#%regex …)`) so the regenerated text is real beagle.

**What "identical" means here — measured, not assumed.** The gate compares *datum
trees*, not bytes. Comments and whitespace are **not** preserved: the reader
discards them, and the projector emits a canonical form (verified — round-tripping
a commented file drops every comment and normalizes layout). So the honest claim
is **semantic** round-trip plus a **deterministic, idempotent formatter** (the
fixpoint gate). Byte-identity holds only on source that is already comment-free
and a fixpoint of the printer. That weaker claim is the one Turtle #4 needs — you
regenerate the file on every edit, so you never depend on byte-preservation, only
on faithful structure + a stable formatter. Both are proven. (Comments themselves —
dropped here — are captured and made rename-correct in **Turtle #6**.)

Type annotations survive because Fram **transports** them as opaque tokens (`:-`,
`Any` are just symbols) — it does not *understand* them; type-aware refactors are
a later turtle, not a property we have now. Cost of losslessness: ~238 triples/form
(vs the query projection's ~18) — the verbose/lossless vs compact/lossy trade.

---

## Turtle #4 — graph-native authoring (each claim falsified before resting on it)

Turtle #3 proved the graph is a faithful source of truth. Turtle #4 proves you
can **edit it**: a rename done as a claim mutation in a Fram store, source
projected back out. Every load-bearing word below was put to a falsifying query
first; only what survived is stated.

### The edit that is correct *because* it isn't textual

`bin/rename-demo red crimson trap test/trap.bjs` — a file deliberately seeded with
textual traps: a `red` binding + 2 references; a **different** binding `red-zone`
(of which `red` is a substring); the string literals `"red flag raised"` and
`"red"`; and a comment `;; the red path`. Graph rename `red → crimson` produces:

| construct | graph rename | naive `sed s/red/`  | careful `sed s/\bred\b/` |
|---|---|---|---|
| symbol `red` (def + 2 refs) | ✅ → `crimson` | ✅ | ✅ |
| symbol `red-zone` | ✅ untouched | ❌ `crimson-zone` | ❌ `crimson-zone` (`-` is a \b!) |
| string `"red flag raised"` | ✅ untouched | ❌ corrupted | ❌ corrupted |
| string `"red"` | ✅ untouched | ❌ corrupted | ❌ corrupted |

Even the *careful* word-boundary `sed` corrupts the substring binding (because `-`
is a regex word boundary) and both string literals (boundaries fire inside
strings). The graph touches only the 3 symbol occurrences — `red-zone` is a
distinct symbol node, and `"red"` is `kind=string` even though it is the *same
interned value* as the symbol. This is the demo that shows **why** the graph beats
the file, not just that it can imitate it. (Honest: at this turtle the comment is
*dropped*, not preserved — same as Turtle #3; the graph never sees it, so it can't
corrupt it. **Turtle #6 lifts this** — comments are now captured and rename-correct.)

### Falsification log — three queries, each one run

**1. "superseded" — is it real, or overwrite with nicer vocabulary?**
*Query (`src/supersession_check.clj`):* dump the renamed entity's claims. Result:
entity 42 holds **both** claim 45 `(v "red")` `LIVE?=false` **and** claim 283
`(v "crimson")` `LIVE?=true`; the retirement is itself a reified claim (284:
`new —supersedes→ old`). Same node, old still retrievable, live view returns only
`["crimson"]`. **Supersession is a real claim graph** — history preserved, nothing
deleted.

**2. "valid beagle" was standing in for "faithful."**
*Query (`test/faith.rkt`):* assert the mutated→projected→re-read tree equals the
original tree with **exactly** `red→crimson` applied and *nothing else*. Result:
**PASS** on the trap (6 forms) and on `preflight.bjs` (34 forms) — every other form,
type, and structure identical; and the **untouched** `status.bjs` is **datum-identical**
(36 forms, zero drift). Faithfulness holds *across the mutation*, not just "parses."

**3. "the edit text can't do" — was it the easy collision?**
The original demo (`red` in two modules) only showed *cross-module* collision.
The trap above is the harder *intra-token / string / comment* case, and it's the
one that actually proves structure beats text.

### Why the graph wins, precisely

Fram *interns* the spelling `"red"` as one value object — shared by preflight's
function, status's function, and any string. A correct rename must **not** touch
the shared value; it must re-point exactly the in-scope references. The graph can,
because each reference is a first-class claim with structural context (module,
binding, `kind=symbol`). Text has only the spelling. Edit the references once,
scope-correctly; correct source falls out; provenance preserved; no file rewrite.

---

## Adversarial sweep — "100% on this corpus" is only as strong as the corpus

The gjoa corpus never exercises exotic reader syntax, so 7 agents tried to *break*
the gate with forms it never contained (`test/torture.bjs` is the survivor set).
What it found:

- **Two real assassins, now fixed.** `#{1 2 3}` (set) and `#"a.*b"` (regex) are
  *captured* losslessly (Gate 1 passes) but the **projector** had no `#%set`/`#%regex`
  arm, so the text-is-a-view leg mangled them. Added both arms; they now round-trip,
  the corpus is still 1100/1100 + 97/97, and they live in the torture fixture as a
  regression. *This is exactly the failure the reviewer predicted: a gap invisible
  on a corpus that never contained the form.*
- **Not in beagle's surface (so not assassins, but worth knowing):** reader
  conditionals `#?`, namespaced maps `#:ns{}`, tagged literals `#inst`/`#uuid`/`#foo`,
  discard `#_`, var-quote `#'`, and char literals `\x` are all *rejected by beagle's
  reader* (only `#{` `#(` `#"` dispatch). `#(…)` desugars to a normal `(fn [%1] …)`
  and round-trips.
- **Read-time normalizations (clean round-trip, but surprising):** `010 → 10`
  (no octal), `1.5e10 → 15000000000.0`. `##Inf`/`##NaN` are rejected by the reader.
- The gate now **reports skipped/unreadable files** instead of letting a silent
  skip masquerade as a pass.

## Turtle #5 — from representing to ENFORCING

Every turtle before this proved the graph could *represent* what text represents
(query it, regenerate it, round-trip it). Turtle #5 is a different kind of result:
the graph **enforces** things text structurally **cannot see** — and refuses writes
that would violate them. That's what turns a faithful round-trip into a *refactoring
engine with guarantees*.

### The moment the graph refused (`rename.clj`)

The first enforcement was the smallest: a rename that **refuses to collide**. It's
worth stating plainly *why* it matters — falsified three ways:

- (A) `crimson` absent → **proceeds**.
- (B) `crimson` is a `defn` → **REJECTED, zero mutations**.
- (C) `crimson` is only a *string* → **proceeds**, string preserved.

Test C is the tell: the store let `crimson`-as-a-string through and rejected
`crimson`-as-a-binding, *because it checks bindings, not occurrences*. `sed` cannot
tell a binding from a string — it sees only spelling. This is the first time the
graph enforced a property text has no access to, with zero mutations on reject.

### The resolution pass — identity, not spelling (`resolve.clj`)

The deepest weakness was occurrence-per-leaf references (rename O(refs), scope a
heuristic). The steal from **Unison** (content-addressed defs, names as metadata)
and **MPS** (references store a node id, *show* the name; text is purely a view, so
byte-identity is a non-concept): a real **lexical resolver** that adds
`refers_to <binding-node>` to each reference — pointing it at the *correct* binding.

**The load-bearing falsifier was shadowing**, and it passes. Given a top-level
`(defn red …)` and a `(let [red y] …)` that shadows it inside one function:

```
in:   (defn outer [y] (let [red y] (red red)) (red y))
edit: rename top-level red -> crimson           CLAIMS EDITED: 1
out:  (defn outer [y] (let [red y] (red red)) (crimson y))
                                  ▲ shadowed inner red kept   ▲ outer red renamed
```

`refers_to` points the inner `(red red)` at the *let* binding and the outer
`(red y)` at the *top-level def* — so renaming the def touches exactly one and
leaves the shadow alone. **`sed s/\bred\b/` renames all three.** That is the
sharpest "structure beats text" result in the project: not that the graph can
imitate a rename, but that it does the rename text *cannot*.

What the resolution pass retires, all proven:

| property | before | after `refers_to` | falsified by |
|---|---|---|---|
| **rename cost** | O(refs) (supersede each) | **O(1)** — 1 claim (the binding's name) | every falsifier: "CLAIMS EDITED: 1" |
| **scope** | module heuristic | **exact, shadow-correct** | `let`/param/`{:keys}`/`for` shadowing |
| **binders** | params + `let` only | + map-destructuring + `for`/`doseq` | `{:keys [red]}` and `(doseq [red …])` shadow held |
| **cross-module** | (couldn't) | exported-def rename updates importers | `app.x` rename → `app.y` call + `:refer`; `app.z`'s own `red` untouched |
| **import forms** | `:refer` only | + `:as`, `:rename`, **re-export chains** | `x/red`→`x/crimson`; `:rename` alias *kept*; A→B→C re-export propagated |
| **types** | opaque tokens | **type-name references resolve** | rename `defrecord Color`→`Hue` updates all `:- Color`; `Int` untouched |
| **collision** | module-local guess | **exact** (id-based) | `crimson`-already-bound → REJECTED |
| **orphan-on-delete** | (didn't exist) | a query: `refers_to` → a dead node | delete-referenced REFUSED; unreferenced allowed |
| **non-corrupting** | — | resolve→project == source | **49/49 src/gjoa** projection-identity |

This unifies Turtle #2's interned `name`/`calls` overlay with Turtle #4's
reader-datum projection — the query graph and the truth graph, joined by identity.
(Module-level `def`/`defonce` are renameable too — they were silently un-renameable
until the value-frame was widened past `defn`/`fn`.)

### The final three — three *different* outcomes, not three checkmarks

These were bundled as "get it all done." They are not the same kind of thing, and
honesty requires naming which was **closed**, which was **delegated**, and which was
**bounded**.

**1. Syntax-quote — CLOSED.** Pure surface resolution. The walk now treats a
quasiquote's contents as quoted *data* (no resolution) and only escapes back to real
references inside `,x`/`,@x` (`unquote`/`unquote-splicing`). Falsifier (`test/quasi.bjs`):
rename `red`→`crimson` over `` `(red ,(red 1)) `` keeps the quoted `red` (data) and
renames only the unquoted call. Green, like every other binder/import falsifier.
(This also *fixed a latent bug*: quasiquoted symbols were previously resolved as
references.)

**2. Type checking — DELEGATED, with a named hole.** `bin/safe-rename` gates a rename
through `beagle check` and refuses if the projection introduces a new diagnostic
(`test`: clean rename ACCEPTED; rename→`let` REFUSED with "expected let bindings";
**oracle-unavailable → FAIL-CLOSED**, exit 4). But be exact about the verb: this
*delegates* type-safety to beagle's checker. **The claim graph does not understand
types** — they are still transported, not understood. A type oracle is *partial*
(beagle reports notes-not-errors and can be lenient/absent), unlike the *total*
collision guard which is always decidable from the graph. So the honest property is
**"the coordinator consults a type oracle and refuses what it *definitively* rejects;
it *admits* what the oracle cannot decide, and fails closed when the oracle is
unavailable."** That is real and useful — and it is *not* "the engine refuses
ill-typed refactors." The guarantee has a hole exactly where beagle check has one.

**3. Macro-introduced bindings — BOUNDED (scoped out), not closed.** gjoa's macros are
all *expression* macros (verified: `macros.bjs` — `pref-bool`, `attr!`, `key=`, … none
introduce bindings), so the corpus is unaffected and projection-identity holds. Surface
refactoring treats macro-introduced bindings as **opaque by design** — correct, because
they don't exist in the surface text. **Through-macro refactoring is out of scope**: it
would require resolving against the *expansion*, which does not round-trip back to
surface source by any path we've built — the unparser-shaped hole from Turtle #2 that
we deliberately refused. We did **not** build a registry to fake a green here.

- The 49/49 projection-identity remains *necessary, not sufficient* — the shadowing /
  alias / re-export / quasiquote falsifiers are the sufficient ones, and they passed.

## Turtle #6 — comments as RESOLVED references (the dropped-comment caveat, lifted)

Every turtle through #5 treated comments the way the reader does: **dropped**. The
Turtle #3/#4 caveat — "the graph never sees the comment, so it can't corrupt it" —
was a *limitation wearing a safety costume*: comments were beneath the graph's
resolution, and dropping them was dressed up as immunity. Turtle #6 removes the
costume. A comment is **another projection of the graph** that can carry **resolved
references**, so a doc comment's identifier mentions follow a rename *scope-correctly*
— while substrings and quoted strings do not. That is the rename text **cannot** do,
extended from code to prose.

### The mechanism — capture below the reader, resolve like code

The reader discards comments before any datum exists (`reader-impl.rkt`'s
`skip-whitespace-and-comments`), but **srcloc is fully preserved** on every form. So
the truth projection (`claims-roundtrip.rkt`) recovers comments from the source *text*
by position — **no reader surgery** — tokenizes each into **text** and **symbol-candidate**
segments (a maximal identifier-char run *not* flanked by `"`), and attaches them as
claims to the following form (leading) / preceding form on the same line (trailing) /
the file wrapper:

```
[<form> "comment0" <c>]  [<c> "kind" "comment"]  [<c> "placement" "leading"]
[<c> "seg0" <t0>]  [<t0> "kind" "text"   "v" ";; the "]
[<c> "seg1" <t1>]  [<t1> "kind" "symbol" "v" "red"]      ← a resolvable mention
[<c> "seg2" <t2>]  [<t2> "kind" "text"   "v" " path"]
```

The payoff: **the Turtle #5 identity machinery generalizes for free.** A symbol segment
that resolves gets `refers_to <binding>` from the *same* lexical resolver (`resolve.clj`'s
new comment pass); `extract-file!` already renders any `refers_to` node via the binding's
*current* name; `rename` already supersedes only the binding. **Zero change to the
rename/projection core** — a comment reference renames for exactly the reason a code
reference does. Concatenating a comment's segment `v`s reproduces its text — no offset
arithmetic, no second formatter.

### The load-bearing falsifier (`test/comment-falsifier.sh`)

Two modules (`test/cmt-a.bjs`, `test/cmt-b.bjs`) each define `red` with a doc comment
mentioning `red`, `red-zone`, and `"red"`. Rename `red → crimson` in module A only:

| comment construct (module A) | graph | `sed s/\bred\b/crimson/` |
|---|---|---|
| `;; the red path … keep red fast` | ✅ both → `crimson` | ✅ |
| substring `red-zone` | ✅ untouched | ❌ `crimson-zone` (`-` is a `\b`) |
| quoted `"red"` | ✅ untouched (lexer demotes to text) | ❌ corrupted |
| **module B's** `;; … mention red here` | ✅ **untouched** (different binding node) | ❌ renamed too (no scope) |

All 10 assertions pass. Same shape as the shadowing falsifier: not that the graph
imitates a rename, but that it does the rename text **cannot** — now including the
comment, which every prior turtle could only drop.

### Honest scope — named, not hidden

- **Line comments only.** Block `#| |#` and `#;` datum-comments fall through to Racket's
  base reader and are not yet captured — a documented follow-up, not a silent gap.
- **Top-level leading/trailing.** Comments *inside* a form (between tokens) are not yet
  captured; a comment binds to the following/preceding top-level form, or the file wrapper.
- **Layout reflows.** Comments round-trip by **text + placement + style**, not byte-exact
  indentation — the same contract the existing projection already lives under. The render
  is still a **fixpoint** (`render = render∘emit∘render`, comments included, verified), and
  the headline **1100 / 1100 forms, 97 / 97 files** datum gate stays green — comments live
  *outside* the datum, so they structurally cannot regress it. (`emit-edn` + `--render` also
  run clean over all 97 corpus files.)
- **Unresolved prose stays verbatim.** Only an *exact, in-scope* identifier match resolves;
  everything else renders literally and is never silently rewritten. Because the graph knows
  the identities, a retained mention of a renamed symbol is **detectable** (a token whose
  text matches no current binding) — turning staleness from a silent liability into a
  derivable lint. That flag query is the next increment; the *correctness* (resolved mentions
  rename, unresolved don't) is proven now.

This lifts the Turtle #3/#4 "comments are dropped" caveat for the truth projection:
comments are now **first-class, rename-correct, and scope-aware** — without forfeiting a
single proof. The rename-immunity property still holds (rename never reads comment text; it
follows `refers_to` edges the resolver placed), the datum gate is untouched, and the new
burden lives behind its own fixpoint.

## Other honest caveats

- **Types transported, not understood.** `:- T` survives because Fram moves it as an
  opaque token, not because anything type-checks. Type-aware refactors are #5+.
- **EDN escapes.** In the *query* path, 29 leaf literals used Racket escapes (`\e`)
  Clojure's EDN reader rejects; skipped on load (never call-graph predicates). The
  *round-trip* path's universal-safe encoder already handles them.
- **Resolution heuristic.** same-file local binding wins, else a unique global defn,
  else dropped. Correct for module-local scope; cross-module re-exports need import
  edges (not yet emitted).
- **Incumbent model.** Benchmark A's incumbent precision is corroborated against the
  real `beagle callers` tool (they agree).
