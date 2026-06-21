# Resolver-woven handler port — scope / facet-map (turnkey once beagle-1 lands G-A)

The last #11 metric-mover: port the daemon's resolver-woven handlers (the remaining
hand-Clojure floor in `cnf_coord_daemon.clj`) to Beagle. Blocked ONLY on **G-A**
(cross-module `binding`), which beagle-1 is closing at root (`(binding [resolve/*x* v])`
matching Clojure). defmacro works. This doc makes the port turnkey the moment G-A lands.

## The 3 cross-module binding-sites (the only G-A dependants)
1. **`with-resolve-read [store & body]`** (a defmacro) — binds 15 resolve dynvars
   (ctx/Vp/KIND/REFERS/FIXED/QUAL/CTOR/ACC/file->ents/srcs/file-modframe/file-typeframe/
   file-accessors/global-exports/global-type-exports/global-accessor-exports) + calls
   `resolve/corpus-from-store!`, then runs body. Once G-A lands it stays a Beagle defmacro
   (defmacro is supported) OR becomes a thunk-fn — macro is cleaner (the body reads the binds).
2. **`do-edit-min`** verb-binds — `resolve/*reject!*`/`*capture-only?*`/`*resolve-walk?*`/
   `*corpus-scope*`/`*corpus-cache*` around `resolve/run-verb-warm!`.
3. **`:render` handler** (inline in `handle`) — same 15 as (1) + `resolve/tx` (begin-tx!),
   then `resolve/corpus-from-store!` + `resolve/extract-file!`.

## Handler inventory + port shape
| handler | binds | resolve calls | store churn (G-C) | port shape |
|---|---|---|---|---|
| `with-resolve-read` | site 1 (15) | corpus-from-store! | — | Beagle defmacro after G-A |
| `snapshot-exports!` | via (1) | module-export-set | — | straightforward |
| `classify-affected` | via (1) | import-graph/module-export-set/module-has-macro? | — | straightforward |
| `target-node` | via (1) | ultimate/def-binding | — | straightforward |
| `callers-of-in-store` | via (1) | by-p/ultimate/binding-name/pred-val/sym-val/name->module/refers-target | — | straightforward |
| `parent-slot-index` | — | ord-pos? | reads @st :claims | read-only store scan + ord-pos? |
| `refers-keyset` | via (1) | + node-path (cd) | — | straightforward |
| `refers-keyset-resp` | — | resolve-warm-store! (clone) | `(atom @st)` clone + strip | clone + strip-accessor |
| `materialize-refers-whole!` | — | resolve-warm-store! (cb) | strip + restore-seq-space! | needs G-C accessors |
| `materialize-refers-scoped!` | via (1) for classify | resolve-modules! (cb) | module-node-ids(cd) + strip + restore | needs G-C accessors |
| `ensure-refers!` | — | (dispatches whole/scoped) | — | cond -> if/vector-cond |
| `ensure-corpus-groups!` / `invalidate-` | via (1) | file->ents | — | straightforward (cd/corpus-groups) |
| `persist-bound-for-rename!` | — | (calls ensure-refers!/target-node) | — | uses cd/do-assert! |
| `do-edit-min` | site 2 | run-verb-warm! | clone + harvest | the big one; cd helpers ready |
| `strip-resolve-claims!` | — | — | **reduce-kv over 5 idx + multi-field update** | **G-C: cnf accessor** |
| `restore-seq-space!` | — | — | swap! st assoc (3 fields) | small cnf accessor or inline |

## G-C — store-internal churn = a cnf typed-accessor refactor (MINE, not beagle-1)
`strip-resolve-claims!` reaches into `t/Store` `:claims/:tx-of/:objects/:superseded` +
all 5 `:idx-by-*`. Move it into `fram.cnf` as a typed
`strip-claims-by-pred! [ctx pred-set subj-keep?] -> Int` (count stripped), the recipe's
"store-internal reads live in cnf" lever (cf. records-since/claim-l-p-r). `restore-seq-space!`
is a 3-field `swap! assoc` — inline or a tiny `cnf/restore-seq-space!`.

## Port sequence (once beagle-1 pings G-A landed)
1. Add `fram.cnf/strip-claims-by-pred!` (+ maybe restore-seq-space!) — typed, build, unit-test.
2. Port the handlers into Beagle (fold into `fram.coord-daemon` or a sibling `fram.coord-resolver`),
   binding `resolve/*` directly (G-A) + calling the cnf accessors for store churn.
3. Wire into the shim via re-export aliases (the cut-3 pattern) — delete the local Clojure handlers.
4. Verify: `:render`/`:callers`/`:refers-ensure`/`:refers-keyset`/`:edit-min` over the socket on
   the code corpus + cnf_edit_min_correctness/rename/commute + the cut-3 wired test. Adversarial audit. Commit.

Result: the daemon's resolver layer becomes Beagle too — only the host floor (socket/mTLS/-main/
boot/migrate + the cnf strip seam) remains hand-Clojure. That's the #11 endgame.
