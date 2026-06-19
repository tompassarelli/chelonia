> ⛔ **ARCHIVED — INVALID FOR THE THESIS.** This tests warm-index-vs-text-grep on a graph DERIVED FROM TEXT (the graphify result), NOT the thesis (canonical graph as source of truth). Diagnostic value only. **DO NOT cite as evidence.** See `experiments/ARCHIVE-INVALID/INVALID.md` and `experiments/THESIS.md`.

# Owned-Resolution Audit — fram/beagle vs its foundational thesis

**Date:** 2026-06-19
**Repo:** /home/tom/code/fram (branch `authoring-claim-ops`)
**Method:** read real code; every claim is file:line-backed and re-verified against the live tree.

## The thesis under audit

> "We OWN name resolution, so that when a reference resolves, the binding IS a claim in the
> graph BY CONSTRUCTION — created by the resolver at resolution time — NOT reconstructed
> afterward by parsing source or emitted text."

**Failure mode:** the graph is secretly a DERIVED INDEX — text is the real source of truth and
the graph is a cache the system rebuilds by parsing text. If that's what's happening, fram is
"a worse version of a text-parsing code-graph tool" and Beagle bought nothing.

- **CANONICAL** = resolution produces the `refers_to` claim; the graph IS the truth; text is a downstream VIEW.
- **DERIVED INDEX** = text is the truth; the graph is reconstructed by parsing/re-analyzing text after the fact.

---

## HEADLINE VERDICT

**The act of resolution is canonical; the substrate it resolves over is a derived index.**

`refers_to` is *always* minted by the resolver at resolution time — it is never recovered from
text, and text structurally *cannot* carry it (the projector strips it). That half of the thesis
is genuinely, provably true at the genesis layer. **But the AST nodes the resolver walks have no
canonical home:** there is **zero** code in the canonical claim log (`claims.log` contains 0
`kind`/`fN`/`v`/`refers_to` claims), so the entire code corpus is manufactured from `.bclj` text
on demand and persisted by *overwriting `.bclj` text*. So `refers_to` is "a claim by construction"
computed over "an AST reconstructed from text" — canonical genesis sitting on a derived-index
foundation. **The flip (claim log canonical, `.bclj` generated, `route-edit` commits the AST delta)
is the single fix that makes the thesis true end-to-end.**

---

## Canonical vs Derived — verdict table

| Layer / operation | Verdict | Why (file:line) |
|---|---|---|
| `refers_to` **genesis** (the binding becoming a claim) | **CANONICAL** | `bind!` mints it at walk time from the scope-computed target; `resolve.clj:178`, `resolve.clj:231-237` |
| Text carrying `refers_to` | **CANONICAL (impossible to be otherwise)** | projector explicitly strips `refers_to` from emitted text; `resolve.clj:839` |
| Warm-daemon **read** path (`:callers`, `:query`) | **CANONICAL** | reads live materialized `refers_to` straight off the cnf store, no `.bclj` touched; `cnf_coord_daemon.clj:609`, `:736`, `:718-721` |
| THREAD/domain graph (title/owner/body/...) | **CANONICAL** | folded from the real flat `claims.log`; `src/fram/rt.clj:155-164`, log census = these preds only |
| **Code-corpus AST as a stored artifact** | **DERIVED INDEX** | `claims.log` has **0** `kind/fN/v/refers_to` claims; corpus manufactured from `.bclj` per call; `resolve.clj:41-51` |
| MCP **authoring** path (`route-edit`) | **DERIVED INDEX** | emit-edn -> verb -> render -> **overwrite `.bclj`** (`io/copy`), AST delta NOT committed; `fram_mcp.clj:104-176`, esp. `:122`,`:143`,`:156`,`:169` |
| resolve.clj **CLI verbs** (rename/set-body/upsert) | **DERIVED INDEX** | rebuild a fresh store from EDN-text each run, re-emit; `resolve.clj:41-51`, `:567-601`, `:829-863` |
| concurrent-authoring **graph arm** | **DERIVED INDEX** | every op = `project()`/`render()` text round-trip; coordinator sees only 4 proxy claims; `graph_arm.sh:78-90`, disclosure `:370-381` |
| MCP/CLI **ingest** (`load-state`) | **CANONICAL-BUT-COLD** | re-folds the canonical LOG per request (not text), but doesn't read the warm daemon; `fram_mcp.clj:42-45` |
| `refers_to` durability | **DERIVED (by design)** | barred from the flat log; in-memory-only, re-materialized every boot; `cnf_coord_daemon.clj:44-50` |

---

## Q1 — WHEN does `refers_to` become a claim? **CANONICAL.**

`refers_to` is created **exclusively** by the resolver at resolution time, computing the binding
node's identity from the lexical scope walk. It is never recovered from text.

- **The sole genesis point:** `chartroom/src/resolve.clj:178`
  `(defn bind! [L target] (c/claim! ctx L REFERS target tx) (swap! n-resolved inc))`.
  `REFERS` is the `refers_to` predicate value-id. The claim is minted here, given the binding node
  the *walk* computed — not read from any text.
- **The identity is scope-correct, computed live:** `resolve.clj:231-237` — `walk` finds the nearest
  enclosing frame (`local (some #(get % nm) scope)`) and calls `(bind! node local)`. Shadowing-aware
  identity produced during the walk = the defining property of canonical genesis.
- **Every producer is inside the walk:** cross-module `bind-xmod!` (`resolve.clj:179-189`), types
  (`walk-type`, `resolve.clj:191-195`), comment mentions (`cbind!`). There is **no** codepath that
  parses a `refers_to` out of text.
- **Text structurally cannot carry it — DECISIVE:** the projector back to EDN/text **excludes**
  `refers_to`: `resolve.clj:839` —
  `(#{"supersedes" "refers_to" "keep_spelling" ...} ps) nil  ; internal edges`.
  The rendered name is built *from* `refers_to` (`resolve.clj:840-849`, render via `(binding-name D)`
  where `D` is the refers-target), never the source of it. Text is a downstream view.
- **The daemon uses the same genesis:** `cnf_coord_daemon.clj:463-468` materializes by calling
  `resolve/resolve-warm-store!` -> `run-resolution!` -> `walk` -> `bind!`. No text re-parse on the warm path.

**Verdict: CANONICAL — no slippage at the genesis layer.** The only nuance (a Q2/Q3 concern, not Q1):
the resolver's *input* on the CLI path is structural EDN text projected from `.bclj`, and the daemon
re-runs the full walk to (re)materialize `refers_to` rather than incrementally maintaining it. `refers_to`
is never recovered *from* text, but its *(re)genesis* is a from-scratch walk over a corpus structure
that was itself reconstructed from text. That is where derived-index pressure lives — see Q2.

---

## Q2 — Is the GRAPH the source of truth, or is TEXT? **MIXED, bifurcated by domain.**

- **THREAD/domain graph: CANONICAL.** The flat `claims.log` IS the source of truth.
  `src/fram/rt.clj:132-134` (log path), `:155-164` (`read-log` folds the flat log into assertions),
  `fram_mcp.clj:42-45` (`load-state` folds it per request). Live log census confirms it contains only
  domain predicates: `title/source/owner/body/committed/relates_to/updated_at/proposed_by/lead/
  created_by/created_at/part_of/depends_on/estimate_hours/driver/do_on/outcome`. The graph IS a fold
  of the log.

- **CODE-INTELLIGENCE graph (the thesis's whole point): DERIVED INDEX.**
  - **There is no canonical log of code claims.** `grep -cE ':p "(kind|fN|v|refers_to)"' claims.log` = **0**.
    Zero AST/code claims anywhere in the canonical artifact. There is nothing for the code graph to fold.
  - **The AST corpus is manufactured from `.bclj` text on demand.** `resolve.clj:41-51` (`load-edn`)
    slurps an EDN file (a `racket --emit-edn` projection of one `.bclj`) and `claim!`s the `kind/v/fN`
    triples into a **fresh** `c/new-store` per invocation — an ephemeral store that is never the canonical log.
  - **The daemon warm store is built ONLY from the flat log**, which has no AST claims:
    `cnf_coord_daemon.clj:922-957` (`migrate-flat->co`) / `:959-966` (`boot-flat!`). So the warm store has
    no `@mod#N` AST nodes unless something else loads them — and on the authoring path that "something"
    is the `.bclj` re-projection.
  - **`refers_to` is in-memory-only and explicitly barred from the flat log:** `cnf_coord_daemon.clj:44-50`
    — `resolve-preds` "must (a) never reach the flat log ... (b) never leak into the :query warm cache."
    It is rebuilt by re-resolving every boot — the defining signature of a derived index.

**Verdict for the half of the graph the language exists to own: DERIVED INDEX.** `refers_to` is in the
store because RESOLUTION put it there (canonical at *that* layer, `cnf_coord_daemon.clj:463-468`), but
resolution runs over an AST that was just re-parsed from `.bclj`. **Text is still secretly the source of
truth for code.**

---

## Q3 — What ROUND-TRIPS THROUGH TEXT? **The entire write path + the experiment graph arm.**

**Canonical contrast (no text round-trip) — the warm read side, the thesis working:**
- `cnf_coord_daemon.clj:606-625` `callers-of-in-store` walks live `refers_to` straight off the cnf store
  (`c/by-p resolve/ctx resolve/REFERS`, `:609`) and renders names from claim markers.
- `cnf_coord_daemon.clj:736-743` `:callers` serves this with NO `.bclj` read.
- `cnf_coord_daemon.clj:718-721` `:query` runs `fram.query` over in-memory warm claims, never parsing text.

**Round-trip #1 (the big one) — MCP authoring `route-edit`, `fram_mcp.clj:104-176`:**
1. `:119-124` shell `racket --emit-edn` over EVERY `.bclj` to PARSE text into AST-claim EDN.
2. `:142-143` run `resolve.clj` as a SUBPROCESS over those EDN files.
3. `:150-159` shell `racket --render` to RE-EMIT `.bclj` text.
4. `:163-165` recompile.
5. `:167-169` `io/copy` **OVERWRITES the source `.bclj`** — and it NEVER commits the AST claim delta
   through the coordinator. The rendered `.bclj` IS the persisted state. Claims-direct was possible: the
   daemon already holds the AST as claims (the `:callers`/`:query` path proves it).

**Round-trip #2 — resolve.clj CLI verbs (rename/delete/upsert/set-body):** EDN-in/EDN-out, store rebuilt
from text per run — `resolve.clj:41-51` (`load-edn` rebuilds a FRESH store), `:567-601` (`resolve-edn!`
does the whole rebuild every call), `:829-863` (`extract-file!` writes `resolved-*.edn` back out). The
daemon-resident `resolve-warm-store!`/`resolve-modules!` is the claims-direct alternative that exists but
these verbs don't use.

**Round-trip #3 (experiment-invalidating) — concurrent-authoring graph arm:** every op is a text
round-trip — `experiments/concurrent-authoring/graph_arm.sh:78-90` (`project()=racket --emit-edn`,
`render()=racket --render`); per-op project -> verb -> render -> recompile -> commit. The coordinator only
ever sees single-triple OCC PROXY asserts, NOT the AST delta — **self-disclosed** at
`graph_arm.sh:370-381`: *"route-edit does not yet commit the AST claim delta through the coordinator —
'the flip' ... `:callers` over THIS coordinator has no `@kernel#N` nodes to resolve."*

**Round-trip #4 — MCP/CLI ingest re-folds per request:** `fram_mcp.clj:42-45` (`load-state`) folds the log
on every call. **Least severe: it folds the canonical LOG, not `.bclj` text** — canonical-but-cold, not
text-derived. Still avoidable (reads could go through the warm daemon like `:query`/`:callers`).

**Round-trip #5 — daemon flat-log re-migration on external edit:** `cnf_coord_daemon.clj:977-985`
`maybe-reload!` re-runs `migrate-flat->co` on mtime change. Again folds the canonical LOG, not text —
canonical-but-cold, listed for completeness.

**Verdict: the read graph is canonical; the WRITE graph is a derived index whose ground truth is the
regenerated `.bclj` text.** route-edit and the CLI verbs deliberately treat `.bclj` as the durable artifact
and DO NOT commit the AST delta (`fram_mcp.clj:76-79`, disclosed as "the flip" at `graph_arm.sh:372-381`).

---

## Q4 — EXPERIMENT VALIDITY (reasoning-cost graph arm). **CANNOT-CERTIFY-YET — design-only, with concrete invalidating gaps the build MUST close.**

### The experiment is not built.
`experiments/reasoning-cost/` contains ONLY `METHODOLOGY.md` + `tasks.edn` — no `gq`/`gquery` CLI, no
runner, no `reproduce.sh` anywhere in the repo (verified: `find` for `gq*`/`reproduce.sh` returns only the
*rename-identity* and *concurrent-authoring* siblings). `git status`: `?? experiments/reasoning-cost/`
(untracked, design-only). So Q4 is answered against design intent + the substrate the design names.

### Intent is CANONICAL for tasks (a)/(b), and the substrate backs it.
The graph-arm primitive is "`gquery <op> {...}` = ONE daemon socket round-trip" over the live warm graph
(`tasks.edn:35`, `:9`; `METHODOLOGY.md:107-117`). I traced that the daemon's `:callers` op genuinely serves
RESOLUTION-PRODUCED warm claims, not re-parsed text: `:callers` (`cnf_coord_daemon.clj:736`) ->
`callers-of-in-store` (`:606`) -> `(c/by-p resolve/ctx resolve/REFERS)` (`:609`) reads live `refers_to`
materialized by `resolve/resolve-warm-store!` (`:463-468`), and the warm store is folded from a CLAIM LOG
(`migrate-flat->co` -> `read-log`, `:922-957`), never parsed from `.bclj`. **For (a) direct-callers and
(b) ultimate-through-spelling, a `gquery :callers` IS one query against resolution-produced warm
`refers_to` = CANONICAL.**

### But the graph arm is NOT VALID AS WRITTEN, for three concrete reasons:

1. **Tasks (c) and (d) are routed through a path that cannot serve them today.** The design routes (c)
   reaches-closure and (d) type-refs through the daemon `:query` op (`tasks.edn:147,150`). Two blockers:
   (i) the `calls-defn` edge relation that reaches/blast needs is built ONLY inside the resolver's
   callgraph mode in an EPHEMERAL fresh store (`resolve.clj`, callgraph mode), NOT in the warm store (which
   materializes only `refers_to` + render markers — `resolve-preds`, `cnf_coord_daemon.clj:50`). (ii)
   `refers_to` is DELIBERATELY FILTERED OUT of the `:query` warm view (`cnf_coord_daemon.clj:50`, and the
   read projection at `:174` `when-not (or (schema-preds ...) (resolve-preds ...))`), so a generic `:query`
   can't even read `refers_to`, let alone `calls-defn`. As specified, `gquery :query` for (c)/(d) returns
   empty/wrong.

2. **The design offers a TEXT-RE-PARSE FALLBACK inside the graph substrate.** `tasks.edn:40` lists
   `:resolver-callgraph "bb -cp out chartroom/src/resolve.clj callgraph <srcs>"` **under `:substrates :graph`**,
   and task (c) says "OR a single callgraph emit" (`tasks.edn:151`). That CLI re-parses `.bclj` (via
   `racket --emit-edn`) and re-runs `resolve.clj` fresh per invocation. **If the graph arm scores (c)/(d) by
   shelling that CLI, it is RE-DERIVING FROM TEXT PER QUERY -> derived-index -> it tests "two ways of parsing
   text" -> INVALID.** That cold path is the correct GROUND-TRUTH oracle (computed once), but must NOT be the
   per-query retrieval.

3. **The sibling establishes a dangerous precedent the build could copy.** In
   `experiments/concurrent-authoring/graph_arm.sh` the daemon flat log is seeded with only 4 synthetic
   OCC-proxy claims (`graph_arm.sh:192-197`), carrying NO corpus code; all real graph work goes through
   `project()=racket --emit-edn` + verb + `render()` (`:78-90`, `:243`,`:266`,`:277`). If reasoning-cost
   copies that shape, `gquery` would hit a daemon whose warm store has no corpus claims, and every answer
   would come from the per-op `.bclj` re-parse = **INVALID**.

### **Q4 VERDICT: `graph_arm_valid = false` (CANNOT-CERTIFY-YET).**
Canonical *by intent* for (a)/(b) — the daemon already serves those off resolution-produced `refers_to`.
But (c)/(d) cannot be served by the warm store as it stands, and the design dangles a text-re-parse
fallback (`tasks.edn:40,151`) that, if used, makes the arm test text-parsing rather than the thesis.

### Required fix (the build MUST satisfy these or the arm is invalid):
1. **Populate the daemon with the corpus AS CLAIMS, ONCE, at setup — not per query.** The TEMP-LOG the
   daemon boots from must contain the projected AST of all corpus modules, so the warm store holds real
   code and `resolve-warm-store!` materializes `refers_to` over it. The `.bclj`->EDN->claim-log projection is
   a legitimate ONE-TIME setup cost ("graph pre-pays", `METHODOLOGY.md:38-44`). Do NOT copy the sibling's
   synthetic-proxy + per-op re-parse pattern.
2. **Every graph-arm retrieval MUST be a daemon socket round-trip (`gquery`) and nothing else.** The wrapper
   must be structurally INCAPABLE of shelling `bb ... resolve.clj callgraph` or `racket --emit-edn`. REMOVE
   `:resolver-callgraph` (`tasks.edn:40`) from the graph SUBSTRATE; keep it only as the once-computed oracle.
3. **Make the warm store actually answer (c)/(d), or drop them from the graph arm.** Either extend
   `resolve-warm-store!` to materialize a `calls-defn` relation + add `:reaches`/`:blast`/`:type-refs` daemon
   ops (analogous to `:callers` reading `refers_to`), OR restrict the headline to (a)/(b). Routing (c)/(d) to
   the cold callgraph CLI to "make them work" is the exact invalidating move.
4. **Add a boot guard that fails loud if the arm is secretly derived.** `reproduce.sh` must assert, before
   any query, that `{:op :status}` reports a nonzero corpus claim count and `{:op :callers ...}` returns a
   nonempty set for a known target — proving the warm store holds resolution-produced `refers_to` over the
   corpus, not an empty/proxy store with a text-reparse fallback doing the real work.

---

## POST-AUDIT ADDENDUM (2026-06-19)

*The Q4 verdict above stands as written for the state of the tree it audited (design-only: at
audit time `experiments/reasoning-cost/` held only `METHODOLOGY.md` + `tasks.edn`). This addendum
reconciles it with the experiment that was **built afterward** — the original text is not retracted;
it is superseded by the build's evidence.*

**The reasoning-cost graph arm was built after this report and satisfies all 4 of the required
fixes above, point-for-point:**

1. **Corpus projected ONCE at setup, not per query.** `experiments/reasoning-cost/project_corpus.clj`
   translates the per-module `racket --emit-edn` dumps into ONE flat coordinator log the daemon boots
   from, using the resolver's OWN canonical edge/literal rule (`resolve.clj load-edn:50`: integer
   object => `@<module>#<id>` node-ref edge; else literal). The daemon's warm store therefore holds the
   SAME AST the resolver would, and `resolve-warm-store!` materializes `refers_to` over real code. This
   is the legitimate one-time "graph pre-pays" cost — NOT the sibling's synthetic-proxy + per-op
   re-parse pattern.
2. **Every graph-arm retrieval is a daemon socket round-trip, and nothing else.** `gq` opens a TCP
   socket, writes one EDN request line, reads one EDN reply line (`gq:3-7,31-38`). It is structurally
   incapable of shelling `racket --emit-edn` / `bb resolve.clj callgraph` — it has no grep/read/
   file-search/re-projection verb at all (`gq:44-66`). `wc -l $GQ_LOG` == graph-arm op count by
   construction. `:resolver-callgraph` is used ONLY as the once-computed ground-truth oracle, never as a
   per-query retrieval.
3. **(c)/(d) are honestly hypothetical and visually segregated — they are NOT routed through a
   text-re-parse fallback.** The build VERIFIES that `{:op :reaches}` and `{:op :type-refs}` both return
   `{:error "unknown op"}` (`reproduce.sh:158-165`, `run_graph_arm.sh:134-138`, `tasks.edn:39,108,132`).
   Their graph cost (1) is explicitly labeled HYPOTHETICAL — "what the principled `:reaches`/`:type-refs`
   WOULD cost once the daemon materializes `calls-defn` and adds those ops" — and is segregated from the
   LIVE a/b rows rather than served by shelling the cold callgraph CLI. The invalidating move the audit
   warned about (gap 2 / required-fix 3) was specifically avoided.
4. **Fail-loud boot guard before any measurement.** `reproduce.sh:104-117` asserts, before measuring:
   `:status` corpus claim count is high (`>1000`, not an empty/proxy store); `:status :log` IS our temp
   log (never the live lodestar log); and `:callers kernel thread-ids-i` is NONEMPTY — proving the warm
   store holds resolution-produced `refers_to` materialized over the corpus. Any failure exits `[FATAL]`.

**Certification — `graph_arm_valid = TRUE` for the LIVE headline task (b):**

- **(b) ultimate-through-aliases — LIVE, CANONICAL, CERTIFIED VALID.** The query is keyed on the
  REFERENCE node (the `k/->Claim` ref in `import.bclj`, located once at setup, the same class of
  one-time setup as picking the (a) target — `reproduce.sh:136-150`), and the DAEMON follows
  `refers_to`->ultimate->reverse-lookup in ONE socket round-trip, resolving to `@kernel#298`. The graph
  is NOT hand-fed the answer name; it genuinely resolves the reference off resolution-produced warm
  `refers_to`, exactly as the text arm does by hand. This is the canonical thesis working end-to-end on
  the read side — the half the audit already found CANONICAL at genesis (Q1) now served live over a
  corpus-backed warm store.
- **(a) caller-set / (cal) var? locate — CONCEDED 1-vs-1 TIE.** One `gq` round-trip vs one `grep`; the
  build concedes the tie outright (`reproduce.sh:155` "TIE conceded — grep is 1 op too"). No win
  claimed.
- **(c) reaches-closure / (d) type-refs — HONESTLY HYPOTHETICAL, NOT CERTIFIED LIVE.** The graph ops do
  not exist yet (`{:error "unknown op"}`, verified). Their "1" is what the live op WOULD cost; it is a
  designed projection, segregated from the live rows, and explicitly NOT a cold-callgraph retrieval.

**Net:** the build closes Q4 required-fixes 1, 2, and 4 in full, and handles fix 3 by honestly
restricting the LIVE certified headline to (b) (with (a) a conceded tie) and quarantining (c)/(d) as
hypothetical rather than serving them off a text re-parse. `graph_arm_valid = TRUE` for the live
headline (b); the original `false (CANNOT-CERTIFY-YET)` verdict was correct for the design-only tree
it audited and is superseded here for the built experiment. (Doc-only addendum: no code changed.)

---

## Where the FLIP is the fix

The same root cause underlies Q2's code-graph slippage, Q3's write-path round-trips, and Q4's invalidating
risk: **`.bclj` text is the durable artifact for code; the claim graph is a regenerated cache.** The fix is
THE FLIP — a source-of-truth demotion:

- **Claim log becomes canonical for code.** The projected AST claims (`kind/fN/v` and the materialized
  `refers_to`) live in a durable log, exactly as the THREAD graph already does (`claims.log` -> fold).
- **`.bclj` becomes a generated VIEW.** Rendering (`resolve.clj:839-849`) already proves text is a
  downstream projection; the flip makes that *the only* role text plays.
- **`route-edit` commits the AST claim delta through the coordinator** instead of `io/copy`-overwriting the
  `.bclj` (`fram_mcp.clj:167-169`). The edit becomes a claim op; `.bclj` is regenerated *from* the
  committed claims, not the other way around.

With the flip: genesis stays canonical (it already is — Q1), the corpus the resolver walks is canonical
(no more re-parse from text — fixes Q2/Q3), and the reasoning-cost graph arm queries a real corpus-backed
warm store (fixes Q4 gaps 1 and 3). Until the flip lands, fram is canonical on reads and for threads, but a
derived index on the code-write path — i.e., on exactly the thing the language exists to own.
