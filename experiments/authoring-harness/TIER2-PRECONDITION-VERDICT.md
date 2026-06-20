# Tier-2 precondition — VERDICT (measured in-code, 2026-06-20)

**Question (advisor 2026-06-20):** a measured Tier-2 gap needs clojure-lsp to MISS a reference AND Fram's
`refers_to` to CATCH it. *Does Fram's walk cover any reference class lsp misses?* Verified directly from the
resolver source — a 3-probe, read-only investigation (workflow `wa1so0ko3`): (1) Fram's walk mechanism, (2)
clojure-lsp/clj-kondo coverage, (3) candidate dynamic-ref classes with local corpora.

## VERDICT: NO. Fram's current graph shares clojure-lsp's blind spots exactly. **(b) is a BUILD, not a pick.**

### 1. Fram's `refers_to` is a purely LEXICAL-SURFACE walk
- **materialization_source = lexical-surface-AST.** The AST claims feeding the resolver come from
  `read-beagle-syntax` → `syntax->datum` (the parsed reader datum); there is **no macroexpand step** anywhere
  in the projection or resolver (`claims-roundtrip.rkt:502-505`, `parse.rkt:282`).
- **sees_macros = NO.** `walk` dispatches only on node kind `"symbol"`/`"list"` (`resolve.clj:313-344`); a
  symbol introduced only by macroexpansion never gets an edge. The defmacro path merely widens the dirty set,
  never expands ("dormant in fram, which has zero defmacro" — `resolve.clj:773`).
- **sees_keyword_dispatch = NO.** Keywords mint inert leaf nodes (`resolve.clj:868`); `walk` has no keyword
  arm → no edge. Zero machinery for multimethod / re-frame / integrant / malli / spec keyword registries. The
  sole edge-writer `bind!` (`resolve.clj:260`) always targets a *binding node* (var/param/type/accessor),
  never a keyword- or string-named handler.
- **Edges it DOES carry:** local-binding, module-local def, cross-module `:refer/:as/:rename`, type refs,
  `->Name`/`map->Name` ctors, record field-accessors, comment-identifier mentions, + the durable `bound_to`
  identity edge. **All are symbol / var / type references over surface syntax** — i.e. exactly clj-kondo's domain.

### 2. clojure-lsp (clj-kondo) — complete on static vars, misses 4 classes
Verified empirically against installed clj-kondo 2026.01.19 / clojure-lsp 2025.11.28 (probe `:analysis` EDN):
- **COMPLETE:** static `def`/`defn` var refs (incl. the honeysql `util/str` 79-caller case → completeness
  **TIE**), locals, ns-aliases.
- **STRUCTURALLY MISSES:** (a) macroexpansion-generated refs (sees the macro *call*, not its expansion);
  (b) multimethod keyword dispatch (dispatch values aren't vars; keyword is a bare token, no edge to handler);
  (c) keyword-keyed registries — re-frame / integrant / malli / spec (handler-naming keyword and invocation
  keyword are unlinked tokens); (d) reflective / string-built — `resolve`/`intern`/`ns-resolve`/`(symbol (str
  …))` (var name is runtime data). Root cause: a static source-form analyzer with no user-macro execution, no
  registry model, no value-flow.

### 3. The cross-reference — the load-bearing table
For EVERY candidate reference class lsp misses, a lexical walk (= Fram's current graph) **ALSO misses it**:

| class | lsp misses | lexical-walk (Fram now) misses | local corpus |
|---|---|---|---|
| multimethod keyword dispatch | yes | **yes** | datahike store/connector/writer; cljs compiler `emit*` |
| aero reader-tag / class dispatch | yes | **yes** | biff config; datahike `print-method` |
| malli registry keys | yes | **yes** | datahike `api/types` ↔ `specification` |
| clojure.spec `::keys` | yes | **yes** | cljs `spec.alpha` (engine) |
| namespaced context-map keys (integrant-shaped) | yes | **yes** | biff `impl/*` |
| macro-generated def names | yes | **yes** | datahike `emit-api`; electric `e/defn` |
| reflective / string-built | yes | **yes** (everyone) | datahike / biff / cljs |

⇒ **No class where lsp misses but Fram catches today. No free measured gap.** (No re-frame Clojure source
exists locally; no integrant `init-key` either — biff uses keyword-keyed context maps. datahike is the
richest local corpus for multimethods + macro-generated API + malli + reflective.)

## What this means (honest)
- Fram's **Tier-1 structural guarantee is real, but its SCOPE = exactly the reference classes a lexical walk
  sees = the classes clojure-lsp already handles with full completeness.** On those classes the graph's win is
  "**by-construction vs must-verify**," NOT "catches something lsp misses."
- The Tier-2 "**graph catches references text misses**" claim is **NOT available as-built.** It requires
  materializing edges *beyond* the lexical walk. The discipline worked: we measured before asserting and found
  the advantage narrower than the loose framing implied — exactly the ceiling the advisor predicted.

## The reframe that matters: ANALYZER vs SUBSTRATE
The finding sharpens, not deflates, the thesis — by separating two questions the loose Tier-2 framing conflated:
- **ANALYZER question** — "can you *compute* a dynamic edge from source?" Here **graph == lsp** (both lexical;
  neither computes a keyword-dispatch or macro-generated edge). This is *not* the addressing thesis.
- **SUBSTRATE / addressing question** — "once an edge *exists*, can you record it as durable IDENTITY and
  propagate a rename through it uniformly?" Here the **graph wins by construction**: a dispatch/registry edge is
  just another `refers_to`/`bound_to` claim; **text has no slot** for a non-lexical edge and must re-derive from
  spelling on every rename. This *is* the addressing thesis.

So the cleanest Tier-2 demonstration **isolates the substrate**: reify ONE edge **no static analyzer can
derive** — a *runtime-computed* dispatch, NOT a static-keyword one (see the correction below) — rename, show
propagation. The graph can; text can't, not because lsp is weak but because text has **no durable slot** for an
edge that didn't come from spelling. **Name the hand-assertion as the point:** of course the edge was authored;
the claim is that text has nowhere to keep it.

## (B) is the anchor, (A) is demoted to Tier-1 — the correction (advisor 2026-06-20 #2)
The first draft of this doc leaned (A) for the demo and named (B) as an "even-here" extension. **That inverts
it, and re-commits the two failure modes this project exists to avoid:**
- **(A) "uniform substrate vs per-macro hooks" is NOT a measured Tier-2 — the symmetric-engineering rule kills
  it.** The fair text baseline for a *hook-able* pattern (a static-keyword defmethod, a re-frame id) **includes
  the clj-kondo hook** — "best realistic tooling for the text arm" is our own rule. Grant the hook and
  completeness stops separating; the only graph advantage left is "one mechanism vs a hook per macro" =
  uniformity/ergonomics = the **honeysql category we already rejected**, wearing a Tier-2 label. (A) survives
  only as **Tier-1 ergonomics color**, not a measured miss. Delete "measured" from anything (A) touches.
- **"propagation lsp structurally can't" was asserted, not measured.** Whether clojure-lsp renames *through* a
  clj-kondo hook-asserted keyword edge is an **empirical fact not yet run.** We owe lsp the scrutiny we gave
  the resolver before stating what it structurally can't do.

**So the lean flips: (B) — a RUNTIME-COMPUTED dispatch — is the Tier-2 anchor.** It is **hook-proof by
construction**: no hook can compute a dispatch value that only exists at runtime, so (B) **sidesteps** the
unmeasured "lsp-through-hook" fact instead of betting against it. And (B) is **not contrived** — open dispatch
on a computed value is idiomatic multimethod usage (the reason multimethods exist) and is exactly the
*reference-that-isn't-spelling* the thesis is about. (A)'s "just write the hook" is the fatal vulnerability,
not (B)'s setup.

## Build (the measured Tier-2 milestone) — option 1, on a COMPUTED-dispatch method
- **Target:** not "a datahike multimethod" but one whose **dispatch value is computed at runtime**, not a
  literal keyword (a static-keyword defmethod is hook-recoverable → drops straight back into (A)). Candidate:
  datahike's backend-dispatch (`ready-store` / `create-writer` / `-connect*`, dispatched on a config value
  loaded at runtime) — **verify the dispatch is genuinely runtime, not a code literal**, before committing it.
- **Option 1 (hand-assert, ~hours, purest isolation):** reify ONE `bound_to` claim linking the runtime
  use-site to its handler, rename, show graph propagation; arm-LSP can't (no static link, no slot). State it
  straight: we reify an edge no static analyzer can derive, assert it once as a claim, rename, propagation is
  uniform — **name the hand-assertion as the point** (hide it and a sharp listener hears "you hand-fed the
  graph"; the point is text has nowhere to keep the edge even when you possess it).
- Options 2 (keyword-dispatch materialization pass, ~1–2 days) and 3 (expand-then-index, larger) are
  *generalizations of the analyzer*, separate from this measured milestone.

## Bottom line for the proposal
**Wednesday is unaffected; the default stands.** Ship on the named plan — Tier-1 + N=1 + the latency tradeoff +
the named Tier-2 milestone = honest and complete; you do **not** need the miss banked for the proposal. The fix
is to the plan's **content, not the timeline:** name **(B) runtime-computed dispatch** as the Tier-2 milestone,
name **(A)** as Tier-1 ergonomics, and **delete "measured" from anything (A) touches.** Which framing anchors
the *talk* is a call to escalate; the target + build option are settled above.

## Build note (2026-06-20) — target confirmed, demo design sharpened, mechanism looks pure-hand-assert
Started the build. Three findings, banked before the run (measure, don't assert):

1. **Target confirmed RUNTIME, not literal.** datahike dispatches `-connect*` / `create-writer` /
   `create-database` / `delete-database` on **`backend-dispatch`** (a config-derived value), and `ready-store`
   on a config value — the method is selected from a config loaded at runtime, not a literal keyword at the
   call site (connector.cljc:246, writer.cljc:197/205, store.cljc:53). This is the hook-proof dispatch.

2. **Demo-design sharpening (HONESTY — heads off a NEW confound).** "Runtime-computed dispatch" has a trap: if
   the dispatch value is computed from EXTERNAL runtime input (e.g. `(keyword (read-line))`), the value lives
   in runtime DATA and **neither** text nor graph can rewrite it → "everyone misses" (the reflective-string
   class, no graph win). The graph wins only in the **author-assertable code-use-site band**: the use-site is a
   CODE node (e.g. a config/route-table entry keyed by the handler's dispatch keyword) whose *link* to the
   handler is runtime-mediated by the multimethod, NOT a use-site whose *value* is external-runtime. There the
   edge is a real reference, not spelling-derivable (lsp/clj-kondo can't model the dispatch), author-asserted.
   Demoing the external-input variant would falsely show a tie; the code-use-site variant is the honest win.

3. **Mechanism looks like PURE hand-assert (option 1, no renderer change) — pending a live run.** The renderer
   keys "render the binding's CURRENT name" on `(= ps "v")` + a `refers-target`, **NOT** on `kind=symbol`
   (resolve.clj:953; `refers-target` prefers the durable `bound_to`, :149-152). A keyword node carries a `"v"`
   claim, so a HAND-ASSERTED `bound_to` on the use-site keyword node should make it render the target def's
   current name — i.e. renaming the handler def re-renders the use-site keyword, with no resolver/renderer code
   change. The no-capture invariant (:984) and `renders-as-tracked-name?` (:991) still apply. **To VERIFY, not
   assert:** stand up an isolated demo (own /tmp log, non-7977 port), assert the edge, rename, render, confirm
   the use-site re-points; then arm-LSP (clojure-lsp leaves the code-use-site keyword stale).

**Next:** build + run the above on an isolated log/port.

## CORRECTION (advisor #3, 2026-06-20) — the multimethod squeeze + work-order inverted
The build note above re-committed two sins; recording the correction in full (the trail matters).

1. **"hook-proof by construction" defended the WRONG axis.** Runtime-computed dispatch stops a static tool
   from *computing* the config→defmethod edge. It says nothing about whether a static tool can *rename the
   keyword*. For ANY working plain-keyword multimethod the dispatch value is the **same literal keyword** at
   the defmethod and at the config use-site — that is the only way value-dispatch matches (`(defmethod
   -connect* :self ...)` fires only if `backend-dispatch` yields literal `:self`, which forces literal `:self`
   into the config; datahike confirms: `:self` is a literal at connector.cljc:248 + writer.cljc:162/199 AND at
   config.cljc:67 `(def self-writer {:backend :self})`). Same spelling at both sites ⇒ the rename is
   **spelling-coupled** ⇒ clojure-lsp's keyword rename plausibly catches both *without ever knowing the
   dispatch is runtime-mediated*. Runtime mediation is irrelevant to the rename.
2. **The squeeze (kills multimethods as the vehicle).** The only way to break spelling-coupling is to compute
   the use-site value (`{:backend (choose-backend)}`). But then (a) the graph's lexical walk can't derive the
   edge either (the reflective class both tools concededly miss), AND (b) "rename-and-propagate" stops being
   coherent — no literal at the use-site to carry the rename; changing the defmethod alone just breaks
   dispatch. So a multimethod's cross-link is necessarily a shared literal keyword wherever rename is
   meaningful, and a shared literal keyword is exactly what keyword-rename tooling targets. **Multimethods
   structurally cannot isolate "a reference carried by something other than spelling"** — the property the demo
   needs. (My finding-#2 "graph wins in the author-assertable band" asserted the win; same offense as
   "propagation lsp structurally can't." Retracted pending measurement.)
3. **Work-order was inverted (anti-Edison on my own plan).** I scheduled the arm-LSP comparison LAST, after
   standing up the graph demo. But the lsp result is the **gate** that decides whether there is a miss to demo
   at all. So: **run the lsp falsifier FIRST**, on the real datahike `:self`, via the LSP server's
   rename-at-position (the CLI `rename` is symbol-only: `--from <FQNS>`). Three pre-registered outcomes:
   - **lsp renames both** (defmethods + config) → TIE → honeysql-land; the substrate story is **Tier-1
     ergonomics, not a measured miss**. An honest result, same as honeysql.
   - **lsp refuses the unqualified keyword** → a real miss, but the honest cause is "**lsp won't rename a bare
     ambiguous keyword**," NOT "runtime dispatch is hook-proof" — rename the claim to match the measurement
     (then it is Tom's call whether that weaker property anchors the talk).
   - **lsp partially fires** → measure exactly which positions and report that.

### RESULT (MEASURED 2026-06-20 — clojure-lsp 2025.11.28 server, `textDocument/rename`, with positive controls)
Driver: `/tmp/lsp_rename_probe2.py` (LSP server, rename-at-position; the CLI `rename` is symbol-only). Ran on
the real datahike source (isolated copy at `/tmp/lsp-dh`).

| target | kind | result |
|---|---|---|
| `backend-dispatch` (connector.cljc:243) | **var** (control) | ✅ renamed — 2 edits (def + `#'`ref :246) |
| `::tx-data` (norm.clj:131) | **namespaced keyword** (control) | ✅ renamed — 2 edits (`s/def` + use in `::norm-map` :133); auto-ns resolved to `:datahike.norm.norm/tx-data2` |
| `:self` at `(defmethod -connect* :self ...)` | **unqualified keyword** | ❌ ERROR `"Can't rename - only namespaced keywords can be renamed."` |
| `:self` at `(def self-writer {:backend :self})` | **unqualified keyword** | ❌ same error |

**Outcome = advisor's case #2 (lsp refuses the unqualified keyword), bulletproofed by the two positive
controls.** clojure-lsp renames vars and NAMESPACED keywords with full cross-ref completeness; it
**structurally refuses unqualified keywords** by a hard guard. The honest cause is **qualification, not
runtime dispatch** — runtime mediation never enters; the guard fires on the element type alone.

**Honest claim, renamed to match the measurement:** the best text tool will not rename datahike's unqualified
`:self` backend keyword at all; a developer is on their own with an unsafe global find/replace (which is *why*
lsp refuses — bare `:self` is ambiguous).

**Symmetric-engineering counter (must be stated — it weakens this):** the strongest realistic text setup
NAMESPACES the keyword (`:datahike/self`), and clojure-lsp then renames it completely (Control B proves it).
So the specific gap **closes under idiomatic text usage** → this is a **convention-contingent property, NOT a
structural impossibility**. The same place honeysql landed, in a new costume.

**The one residual that namespacing does NOT close (candidate framing, graph side still UNMEASURED):**
namespacing *changes the keyword's value* (a data/wire-contract change); the graph can attach identity to a
keyword **without changing its spelling**. So for unqualified keywords you can't retro-namespace (legacy data
contracts), text has no safe rename and the graph could — BUT only with hand-asserted identity edges (keywords
are inert leaves today, :868), and the graph must then ALSO avoid over-renaming unrelated `:self` (the exact
ambiguity lsp cited). That is the substrate argument again, and it is **not yet measured** — do not assert it.

**Rhetorical asset (honest, no overclaim):** clojure-lsp's own error — *"only namespaced keywords can be
renamed"* — is the incumbent conceding that **without identity, a name cannot be safely refactored.** That is
the addressing thesis stated by the text tool itself. It illustrates Tier-1; it is not a benchmarked Tier-2 win.

**Where this leaves Tier-2 (escalation, per advisor #2):** no clean structural Tier-2 miss is available here
either — namespacing closes it on the text side. Options, Tom/advisor's call:
- **(i)** Talk stands on **Tier-1 + the substrate/addressing argument**, using the measured lsp refusal as a
  concrete *illustration* (the incumbent's own "needs a namespace = needs identity" admission). Honest, no
  contested graph-beats-lsp claim. *My lean.*
- **(ii)** Pursue the residual (un-retro-namespaceable unqualified keyword) as a measured graph-win — requires
  building the graph arm with hand-asserted identity AND honestly handling over-rename; weaker + contestable.
- **Do NOT build the graph arm until (i)/(ii) is chosen** — building the favorable arm before the claim is
  settled is the anti-Edison failure again.

## FINAL VERDICT (2026-06-20, advisor decision relayed) — (i) chosen, (ii) DEAD, analyzer-Tier-2 closed
**Decision: (i).** The talk stands on **Tier-1 (the structural guarantee) + the substrate/addressing
argument**, with the measured clojure-lsp refusal as a concrete *illustration* (the incumbent's own "only
namespaced keywords can be renamed" = no-identity ⇒ no-safe-rename). **(ii) is DEAD, not deferred** — do not
build the graph arm to chase it.

**Why (ii) collapses — the value-is-spelling dilemma.** A Clojure keyword's spelling IS its value; there is no
display-name separate from identity, so a rename changes the value by exactly as much as namespacing does.
Case-split the residual:
- **`:self` is a wire contract** → NO rename is safe for anyone (graph or text): any rename changes what's on
  the wire. The graph only buys keeping `:self` on the wire and skinning a friendly name over it — that is
  *aliasing* (graphify-flavored comprehension color), with no text analog, NOT the rename op arm-LSP performs.
- **`:self` is internal** → the idiomatic fix is to namespace it, and clojure-lsp renames it completely (that
  IS Control B).
Both horns land in the same place: **no graph-renames-where-lsp-cannot.** The residual is the honeysql null in
a third costume.

**The generalization (the real payload) — analyzer-based Tier-2 is structurally closed.** Two independent
targets converge, and with the resolver read it generalizes. CONFIRMED by direct read (not asserted):
`refers_to` is a **surface lexical walk over UNEXPANDED forms** — the beagle projection is `read-beagle-syntax`
→ `syntax->datum` → `datum->claims` (claims-roundtrip.rkt:502-505; `syntax->datum` = surface reader datum,
zero macroexpand), `parse.rkt` has no expand/macroexpand/namespace-require, and `resolve.clj`'s walk has none
either (the only "expand" is a comment, :770). So the graph sees **exactly the reference classes lsp sees and
no others** — symbols, keywords (qualified or not), and is blind to post-expansion refs *equally with lsp*
(both surface analyzers). **There is no reference class the graph computes that lsp lacks.** Every
analyzer-based Tier-2 (a measured miss) is therefore closed across all three classes: symbol (honeysql),
keyword (datahike), macro (unexpanded-surface, confirmed). The miss-hunt ends here, cleanly.

**SCOPE — hold this precisely (implementation, NOT theorem).** "Closed by direct read" is true of THIS
codebase as it stands: the projection sources from the surface datum, so the graph happens to see exactly
text's analyzer classes. A graph that materialized *post-expansion* refs WOULD see a class lsp misses — that
is not closed in general. The honest, bulletproof claim is **"the graph AS BUILT sees the analyzer classes
text sees, so the substrate win is Tier-1,"** NOT "a graph can never compute more." Pin it to the
implementation and it survives any Q&A; let it drift to the general claim and a sharp listener reopens it.

**What the substrate advantage actually is: entirely Tier-1 structural** (demonstrated, not benchmarked) — O(1)
re-point on rename, no false-hits in strings/comments by construction, and a durable slot for an edge text has
nowhere to keep. Real, but not a measured-miss.

**The only measured empirical content left** is the **cost curves under a real refactor with full per-layer
attribution** (MCP vs render vs daemon vs graph-algorithm) — a different axis from the miss now ruled out
twice, and the place the attribution discipline actually bites. *Loop-spec implication (for when we return to
the three locks):* a loop built to hunt a Tier-2 rename miss would hunt forever; the loop **measures cost
curves and demonstrates the Tier-1 guarantee at scale — it does not search for a win.**
