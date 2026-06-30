# Chartroom

**Code as claims, on [Fram](https://github.com/tompassarelli/fram).** Chartroom
projects a beagle source tree into the same reified claim graph that Tern
uses for life and work, then *derives* code intelligence — call graphs, scope-correct caller
resolution, transitive leverage — as queries over that graph instead of as
bespoke passes over text.

The bet is the same one Tern makes, pointed at source: **a flat,
text-and-grep view of a codebase rots and can't compute relational questions;
the graph is always current and answers them for free.** The question Chartroom
exists to settle is whether that actually buys anything over the cold-parse,
one-hop tools we already have — or not.

## The pipeline

```
*.bjs ──beagle-claims──▶ CNF triples ──load──▶ Fram claim store ──Datalog──▶ leverage / callers
  (AST as claims)         [s "p" o]        (interned graph)      (transitive closure)
```

1. **`beagle-claims`** (in [beagle](https://github.com/tompassarelli/beagle), `bin/beagle-claims`) reflects a
   file's AST into newline-separated EDN claim triples `[subj "pred" obj]` —
   `form-kind`, `name`, `calls`, and a uniform **`child`** containment edge.
   It's a cross-cutting *analysis* projection, not a compile target: it claims
   `.bjs` / `.bclj` / `.bnix` alike, ignoring each file's `#lang`.
2. **`bin/emit-corpus`** runs that over a source tree → `build/<name>.claims`.
3. **`src/chartroom.clj`** folds the triples into a Fram store, derives the
   **namespace-correct** function call graph (a call binds the defn in its own
   module — the scope a bare-symbol match ignores), and runs the benchmarks.

## Prerequisites

Chartroom is the glue layer over three sibling projects; clone them next to this
repo (the `~/code/<name>` layout the commands below assume) and have
[Babashka](https://github.com/babashka/babashka) (`bb`) on `PATH`:

- **[fram](https://github.com/tompassarelli/fram)** — the claim store + Datalog
  engine. Build its classpath dir (`fram/out`); the runner loads `-cp ~/code/fram/out`.
- **[beagle](https://github.com/tompassarelli/beagle)** — provides `bin/beagle-claims`
  and `bin/beagle-roundtrip` (the AST→claims projectors). `bin/*` here resolve it
  via `$BEAGLE` (default `$HOME/code/beagle`); override if you check it out elsewhere.
- **[gjoa](https://github.com/tompassarelli/gjoa)** — the live corpus the benchmarks
  in RESULTS.md run against. Any beagle source tree works; gjoa is just the one measured.

## Run it

```sh
bin/emit-corpus  ~/code/gjoa/src ~/code/gjoa/tools ~/code/gjoa/tests  build/gjoa.claims
bb -cp ~/code/fram/out:src  src/chartroom.clj  build/gjoa.claims
```

## What it proves (and doesn't)

See **[RESULTS.md](RESULTS.md)** for the measured verdict. In short: on the live
gjoa corpus the graph answers two questions the incumbent cannot — **scope-correct
callers** (perfect precision where bare-symbol match is 33–67% wrong) and
**transitive blast radius** (the keystone a one-hop tool structurally can't
surface) — and Fram's Datalog computes the real call-graph closure correctly.

Two projections, two jobs (both derived from the same source):
- **Query projection** (`beagle-claims`) — compact AST claims with semantic
  overlays (`calls`/`name`/`child`). Great for leverage queries; lossy (drops
  types/params). ~18 triples/form.
- **Truth projection** (`beagle-roundtrip`) — verbose reader-datum claims that
  round-trip the program **losslessly** (types survive as tokens, comments as
  resolved references). The graph as a *source of truth*; text as a regenerable
  view. ~238 triples/form.

## Status

Built and validated in stages: projection → leverage benchmark → lossless
round-trip → graph-native rename → a shadow-correct lexical resolver →
rename-correct comments. Headline gates hold at **1100/1100 forms, 97/97 files**.
Measured results are in **[RESULTS.md](RESULTS.md)**; the stage-by-stage build
log is in **[docs/build-log.md](docs/build-log.md)**.
