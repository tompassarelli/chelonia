# Attention FEED SCHEMA (for @framescope)

The contract between the **attention extractor** (`bin/fram-attention`, fram-lease
owns) and **framescope's graph view** (you own) for the fleet light-show: tail
each agent's tool-use stream, map the file it just touched to a code-graph module
node, and light that node up — "agent X is attending node Y right now". This is
the live, per-agent counterpart to the code-as-claims render
(`CODE-RENDER-SCHEMA.md`): that doc renders the *program*; this one overlays
*who is looking where*.

The crucial property: an attention event is a **claim** in a dedicated attention
daemon, so **nothing on your wire changes**. You point the existing bridge at the
attention port (`/graph?port=7980`, `/live?port=7980`) and the same snapshot /
commit-relay machinery serves it. The only new work is on the overlay (§6).

---

## 1. The live-feed contract (no bridge change)

- **Daemon**: a DEDICATED attention daemon on its OWN port and log — recommend
  **:7980**, log `.fram/attention.log`. It is deliberately NOT the code daemon
  (:7979) or the fleet coordinator (:7978): attention is high-frequency,
  last-write-wins churn (every file an agent opens supersedes the previous), and
  we do NOT want that decay traffic polluting the code AST log or the fleet
  planning log. Stand it up exactly like the code daemon —
  `bin/fram-daemon 7980 .fram/attention.log` (it speaks the identical wire).
- **Read path** (unchanged): `/graph?port=7980` → your bridge runs `ALL-TRIPLES`
  → `{nodes,edges}`. Attention triples flow through the *same* `snapshot`
  builder: `@attention:<uuid>` becomes a node, `attending → @<module>` becomes an
  edge (it is `@`-prefixed + whitespace-free, so your existing `ref-obj?` already
  classifies it as an EDGE), and `attending_at` / `attending_tool` land as attrs.
- **Live path** (unchanged): `/live?port=7980` pushes one
  `{type:"commit", op, l, p, r, version, ref}` per assert/retract. An `attending`
  assert is a node lighting up; the matching `attending` **retract** (op
  `"retract"`) is the previous node going dark. **This is the light-show** — edges
  flicker between modules as the fleet moves.

Nothing on the wire changes. What changes is **classification + overlay** in
`graph-domain.js` / the render layer (§6).

---

## 2. Claim vocabulary (what you'll see on an attention node)

Subject id is `@attention:<agent-uuid>` — one attention node per agent (the
agent's "cursor"). Predicates:

| pred | edge/attr | meaning |
|------|-----------|---------|
| `attending` | **edge** | the code-graph node the agent is touching NOW. Object is a module id `@<mod>` (matches a `#root` module node from the code graph) or a synthetic `@<basename>` for an unmapped file. **Single-valued** by intent — exactly one live `attending` edge per agent. |
| `attending_at` | attr | ISO-8601 timestamp of the touch (`2026-06-22T10:34:07.983Z`), or a fallback (stream mtime) when the event carried none. |
| `attending_tool` | attr | the tool that produced the touch: `Read` `Edit` `Write` `MultiEdit` `NotebookEdit` `Grep`. Lets the overlay distinguish a *read* glance from a *write* focus. |
| `attending_file` | attr | the raw source path that produced the touch (`src/fram/schema.bclj`). Tooltip detail only — the `attending` target node is the authoritative location; this is the unmapped path for hover. Optional on the wire: omitted when no path was extracted, so fall back to the target node label. |

**Edge vs attr rule (you already have it):** an object that is an `@`-prefixed,
whitespace-free id is an EDGE; everything else is a literal attr. Holds verbatim:
`attending → @schema` is an edge; `attending_at → "2026-…Z"` is an attr.

The `attending` target is a **node id you already render** when framescope is
federated across the code daemon (the `@<mod>#root` / module node from
`CODE-RENDER-SCHEMA.md`). In the federated `/graph` union the attention edge
connects the agent's `@attention:<uuid>` node to the live program's module node —
the cross-graph "who is editing what" line, the persistent-edge form of the
light signal. (Bridge `working_on` already does a tail-based, single-freshest
approximation; this feed is the daemon-backed, decaying, multi-event version.)

---

## 3. Node typing — the attention type

One new type, keyed on the id prefix (exactly how `bridge.snapshot`'s `type-of`
already keys fleet nodes — `@attention:` falls out of the existing
`subs body 0 (index-of ":")` rule as type `"attention"` with zero code change;
you only need a color + label for it):

```
attention — the @attention:<uuid> node (an agent's cursor). type-of already
            yields "attention" from the @attention: prefix.
```

The `attending` edge's TARGET is a normal code node (`module`/`def`/…), typed by
`CODE-RENDER-SCHEMA.md` §3. No new target types.

---

## 4. Edge typing

| render type | source pred | notes |
|-------------|-------------|-------|
| **attending** (focus) | `attending` | agent cursor → module. Highlight the target node. Style by `attending_tool` (write = solid/bright, read/grep = thin/dim) if you want a glance-vs-edit distinction. |

There is exactly one live `attending` edge per agent at a time (§5). Render it as
a directed, transient edge — it is meant to flicker.

---

## 5. Decay / TTL semantics (the "now" property)

Attention is **single-valued per agent: latest file wins**. The extractor models
this as **last-write-wins (retract-then-assert)**: when agent X touches a new
node, the extractor first `:retract`s X's previous `attending` edge, then
`:assert`s the new one. So at any instant the graph holds *one* `attending` edge
per active agent — the graph naturally shows "where each agent is looking now",
and you get a clean dark→light transition over `/live` (a `retract` commit
followed by an `assert` commit).

**Staleness / TTL.** An agent that stops touching files leaves a stale `attending`
edge. Two complementary retirement paths (the extractor implements the supersede
path; the TTL sweep is documented here as the recommended operational layer, not
yet a daemon thread):

1. **Supersede (implemented):** the next touch retracts the prior edge. Fast
   movers self-clean.
2. **TTL sweep (recommended):** a periodic sweeper retracts any `attending` whose
   `attending_at` is older than e.g. **30s**, so an idle agent fades. This is a
   trivial cron-style loop over `/graph?port=7980`: for each `@attention:<uuid>`,
   if `now - attending_at > TTL`, `:retract` the `attending` edge (and optionally
   the attrs). It can live in the extractor (`follow` could own a parallel sweep)
   or as a separate `bin/fram-attention sweep` — kept out of v1 to keep the
   write path simple. Until it runs, the overlay should fade an edge client-side
   when its `attending_at` ages past the TTL (belt-and-suspenders; see §6).

The TTL number is a UI/ops knob, not a wire contract — 30s is a sensible default
for "is this agent still here".

---

## 6. What the framescope overlay should do

1. **Highlight on `attending`.** When an `attending` edge points at module Y,
   visually emphasize Y (halo / pulse / raised z) and draw the agent→Y edge.
   Color the edge by the agent (reuse `colorForType` on the `@attention:<uuid>`)
   so multiple agents on the same module are distinguishable.
2. **Glance vs focus.** Use `attending_tool`: `Edit`/`Write`/`MultiEdit`/
   `NotebookEdit` = a strong, solid highlight (active editing); `Read`/`Grep` =
   a soft, thin highlight (looking).
3. **Fade on supersede / retract.** On a `retract` commit for `attending` (or
   when the same agent's `assert` lands on a different node), fade the OLD target
   back to baseline and light the new one — the cursor moves.
4. **Client-side TTL fallback.** Even before the server-side sweep exists, fade an
   `attending` edge whose `attending_at` is older than the TTL (≈30s) so an idle
   agent doesn't stay lit forever. (When the sweep ships, the edge will also
   actually disappear from `/graph`.)
5. **Hover.** Show `attending_tool` + `attending_at` + `attending_file` (the raw
   path, now carried as a 4th attr) in the tooltip. Fall back to the target node
   label when `attending_file` is absent.

The agent node already exists in the federated fleet graph as `@agent:<uuid>`;
`@attention:<uuid>` is a SEPARATE cursor node. If you'd rather hang the edge off
the existing `@agent:<uuid>` node, that's a one-line change to the extractor's
subject — flagged as the open coordination question (§8).

---

## 7. Example claim lines (the EDN the extractor asserts)

Three `:assert`s land per touch (edge + two attrs), each over the same wire the
bridge uses (`{:op :assert :te … :p … :r … :base <version>}`, OCC-retried):

```
@attention:13b34cc5653c  attending       @bridge
@attention:13b34cc5653c  attending_at     "2026-06-22T10:39:38.740Z"
@attention:13b34cc5653c  attending_tool   "Edit"
@attention:13b34cc5653c  attending_file   "web/bridge.js"
```

When that agent then opens `web/graph.js`, the extractor retracts the old edge and
asserts the new one (last-write-wins) — the cursor moves `@bridge → @graph`:

```
;; retract @attention:13b34cc5653c attending @bridge   (op "retract")
@attention:13b34cc5653c  attending       @graph
@attention:13b34cc5653c  attending_at     "2026-06-22T10:41:02.119Z"
@attention:13b34cc5653c  attending_tool   "Read"
@attention:13b34cc5653c  attending_file   "web/graph.js"
```

A second agent attending the real `schema` module (authoritative file→module
match, not a basename fallback):

```
@attention:29f60f7680b2  attending       @schema
@attention:29f60f7680b2  attending_at     "2026-06-22T10:42:15.300Z"
@attention:29f60f7680b2  attending_tool   "Edit"
@attention:29f60f7680b2  attending_file   "src/fram/schema.bclj"
```

## 8. Example /live JSON frames (what your WS receives)

These are the existing `live-ws` frames — UNCHANGED shape, just new preds:

```json
{"type":"commit","op":"assert","l":"@attention:13b34cc5653c","p":"attending","r":"@graph","version":5012,"ref":true}
{"type":"commit","op":"assert","l":"@attention:13b34cc5653c","p":"attending_tool","r":"Read","version":5013,"ref":false}
{"type":"commit","op":"assert","l":"@attention:13b34cc5653c","p":"attending_file","r":"web/graph.js","version":5014,"ref":false}
{"type":"commit","op":"retract","l":"@attention:13b34cc5653c","p":"attending","r":"@bridge","version":5011,"ref":true}
```

`ref:true` (an `@`-prefixed object) ⇒ an EDGE commit — light/dim the target node.
`op:"retract"` on `attending` ⇒ the previous focus going dark.

---

## 9. Coordination questions — RESOLVED 2026-06-22 (@framescope ↔ @fram-engine)

- **Cursor node vs agent node — RESOLVED: keep v1.** Stay with the dedicated
  `@attention:<uuid>` cursor node so attention churn never mutates the
  `@agent:<uuid>` fleet node; the overlay maps cursor↔agent by the shared uuid
  suffix for isolate-on-click. No extractor change.
- **Raw `file` on the wire — RESOLVED: shipped.** `attending_file` now lands as a
  4th attr (one extra `:assert` per touch in `emit-attention!`), carrying the raw
  source path for the beam tooltip. Optional — omitted when no path was
  extracted; fall back to the target node label.

Still open / on request to @fram-engine (low priority):

- **Richer file→module map.** Today only modules with a committed `#root file`
  claim resolve authoritatively; everything else uses the
  `basename-without-extension` fallback (`web/graph.js → @graph`). More files
  landing on real `#root` modules makes beams hit the actual program graph — but
  the fallback renders fine, so this is deferred (it's a code-daemon ingest
  concern, not an extractor change).
- **Server-side TTL sweeper.** If you want stale `attending` edges to actually
  vanish from `/graph` rather than just fade client-side (§5).
