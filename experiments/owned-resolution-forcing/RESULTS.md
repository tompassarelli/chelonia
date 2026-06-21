# Owned-resolution forcing — receipts (2026-06-21)

Reproduce: `bb experiments/owned-resolution-forcing/probe.clj` (needs `clojure-lsp` on PATH +
`~/code/reference/honeysql`; copies the corpus to /tmp, never mutates the original).

These pin the "spelling is not identity" claim against a **rented Clojure resolver** (clojure-lsp /
clj-kondo), measured on the real honeysql corpus (whole project — `src` + `test`).

## (A) clj-kondo's identity vocabulary = spelling + location, NO content-stable id
The full var-definition keyset for `honey.sql.util/str`:
```
:bucket :callstack :col :defined-by :defined-by->lint-as :doc :end-col :end-row :external?
:fixed-arities :lang :meta :name :name-col :name-end-col :name-end-row :name-row :ns :row :uri
:varargs-min-arity
```
The only identities derivable: **SPELLING** (`:ns` + `:name`) and **LOCATION** (`:row`/`:col`).
No `:id`, no `:hash`. A var-usage links to its def purely by `(:to <ns>, :name <sym>)` — the FQ
spelling. There is no durable, content-stable slot to persist.

## (B) The LOCATION corruption (the cold-open demo)
`honey.sql.util/str` is defined at `:name-row 8`. Splice a 2-line `defn` immediately above it; re-dump:
- `str` moves: **row 8 → row 10**.
- A persisted edge keyed on **row 8** now resolves to **`[newest-helper newest-helper]`** (the new
  symbol, once per `:lang`) — **a different binding**. clj-kondo reports success.
- ⇒ a persisted LOCATION edge **silently mis-points** (corruption, not staleness).

## (C) Honest reference counts for `honey.sql.util/str`
- **Dual-lang dump count** (a `.cljc` usage is counted once per `:lang`): **474**.
- **DISTINCT PHYSICAL sites** (what a text rename rewrites / what an identity edge re-points): **238**.
- Total project var-usages by lang: **7183 `:clj` + 7113 `:cljs`** (+144 reader-unconditional).

**Talk discipline:** cite **238** as the physical rewrite work (474 is the dual-lang dump count and
overstates text's cost ~2× — a `.cljc` site is one physical edit, counted twice in the dump). Use the
whole-project lint (`src` + `test`); src-only undercounts.

## The dichotomy (what survives, what doesn't)
Three ways to identify a definition without owning a substrate, and the mutation each dies on:
- **spelling** (clj-kondo's `:ns`/`:name`) — dies on **rename**.
- **location** (clj-kondo's `:row`/`:col`) — dies on **insert** (B above; silent mis-point).
- **content-hash** (zerolang `program_graph_node_id.c`, Unison) — name-exclusive hash is rename-safe
  but **dies on a type/flag/order edit** (the id recomputes, edges break); name-inclusive hash dies
  on rename.

Every *derived* identity breaks under *some* mutation. Only a **minted id decoupled from spelling,
position, AND content** survives all of it — Fram's `bound_to @mod#int` (a sequential mint-id, not a
content hash; `chartroom/src/resolve.clj:88,160-165`, persisted at `cnf_coord_daemon.clj:876-902`).
A rename touches **Δ=2 claims**; the 238 references re-point by identity, zero text rewrite. The
rented resolver must re-derive + rewrite all 238 sites every rename, because it has no slot to point
at.

Honest scope (so this receipt doesn't overclaim): it establishes that a *rented Clojure resolver*
(clj-kondo) has no content-stable id, so any persisted edge over it is spelling- or location-keyed and
breaks under mutation. An identity-addressed substrate (this one, or a node-id CRDT) is what survives;
that's the categorical line. It does **not** by itself prove a particular engine is forced.
