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
