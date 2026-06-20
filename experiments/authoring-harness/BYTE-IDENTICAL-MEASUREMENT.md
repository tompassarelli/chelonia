# Byte-identical projection — MEASURED (2026-06-20), deliberately deferred

**Decision:** the shipping claim is **byte-STABLE + comment-faithful**. Byte-identical is a
**measured, quantified design choice (~half a day out), parked — not wired.** This file exists so
the measurement survives the ephemeral spike: an unrecorded `/tmp` result silently decays
"measured" back to "asserted," which is the exact failure this project exists to prevent.

## The question
The talk's differentiator vs Unison is a *faithful text projection*. S2 showed the render is
byte-STABLE (deterministic, comment-preserving) but **not byte-identical** to hand-written source
(dialect header `#lang beagle/clj`→`(define-target clj)` + intra-form layout reflow). The capture
audit (workflow `printer-byte-identical-scope`) found byte-identity is a **capture-side** gap: the
reader does `read-syntax`→`syntax->datum`, discarding all layout trivia *before* claims are minted —
but the srclocs ARE retained on the syntax objects (a parser fast-path avoids flattening them),
just dropped at the `syntax->datum` boundary. So the info exists; is it sufficient, and how far out?

## Method — a velocity spike (standalone Racket; beagle untouched)
A ~40-line standalone reconstructor (NOT wired into beagle): per top-level form, `read-syntax`
keeping srclocs, walk to leaf tokens, reconstruct as **interleave(leaf-text, inter-leaf-gap)** —
the gap = source bytes between leaf spans (whitespace + delimiters + comments), the leaf = either
its re-serialized datum (the graph's `v`) or its original source lexeme. Assert reconstruction ==
original bytes over the whole `src/fram` corpus (11 modules). Core:

```racket
;; leaves: collect (start end text) over a syntax object's leaf tokens (0-based offsets into body)
(define (leaves stx)
  (define pos (syntax-position stx)) (define span (syntax-span stx))
  (define e (syntax-e stx))
  (cond
    [(and pos span (or (pair? e) (null? e) (vector? e)))    ; compound: recurse children
     (append-map leaves (filter syntax? (kids->stx e)))]
    [(and pos span)                                          ; leaf: byte-faithful = ORIGINAL lexeme
     (list (list (sub1 pos) (+ (sub1 pos) span)
                 (substring body (sub1 pos) (+ (sub1 pos) span))))]
    [else '()]))
;; reconstruct: walk offset; emit each GAP verbatim (trivia), then each LEAF
(let loop ([at 0] [ls (sort (append-map leaves forms) < #:key car)])
  (cond [(null? ls) (write-string (substring body at) out)]
        [else (write-string (substring body at (first (car ls))) out)   ; gap = ws/delims/comments
              (write-string (third (car ls)) out)                       ; leaf
              (loop (second (car ls)) (cdr ls))]))
```

## Result (total wall-clock: 1m52s to first-green-on-corpus)
- **datum-serialized leaves → 4/11 byte-identical.** All 6 diffs were ONE cause: multi-line string
  literals (`~s` canonicalizes a real newline to `\n`) — the predicted "leaf surface spelling" gap.
- **one-line fix** (leaf text = the original source lexeme, not the re-serialized value — the
  scout's `keep_spelling` fix) → **10/11 byte-identical, DIFF=0.**
- The lone failure (`kernel.bclj`) is a **spike artifact**: the standalone uses plain `read-syntax`;
  the real impl uses beagle's own reader, which handles whatever kernel uses.

## Structural insight (a free win)
Comments — leading AND in-form — round-trip **byte-identical for free** in a gap-based
representation, because they live in the inter-leaf gaps (verbatim source). The audit's "in-form
comments not captured" problem **dissolves** here — at the cost of storing them as gap-text rather
than structured comment nodes.

## What it PROVES vs does NOT
- **PROVES:** the info is sufficient and the technique is sound + cheap to validate. Byte-identity
  is an **engineering gap (~half a day to wire properly), not architectural.** The feared
  "L: re-architect `datum->claims` to be syntax-aware" is less-L than feared (srclocs are there;
  reconstruction is ~40 lines; the literal fix is one line).
- **Does NOT prove doneness.** The spike is a standalone reconstructor; it never round-trips through
  the claim graph. Finishing in beagle still needs: mint gaps+lexemes as **claims** in
  `datum->claims`; a **verbatim renderer** (`datum->verbatim`) sibling to `datum->pretty`; EDN
  claim-format + `resolve.clj` passthrough; a **byte-gate** test; **synthetic-srcloc** handling
  (macro-injected forms have no source bytes). The spike's gaps also store **delimiters as opaque
  text** — byte-identical but structurally hollow; the principled version (delimiters from
  structure, only whitespace as trivia) is **more than the spike's half-day** and re-raises the
  tension below.

## Why deferred (the design tension — the real reason, not the time)
`datum->pretty` is a **deliberately normalizing** printer: its purpose is **local-edit → local-diff**
(idempotent fixed-point). Byte-identical needs a **second, verbatim render mode** coexisting with it,
and per-gap trivia claims inflate the graph — both **fight the diff-locality the concurrency thesis
depends on.** Defaulting to byte-identical would trade against the more important claim. So: ship
byte-stable; keep byte-identical as an opt-in mode available if a reviewer demands it; frame the
deferral as a **quantified design choice** ("byte-identical is ~half a day out, deliberately deferred
because exact-whitespace preservation trades against the local-diff property concurrency needs").

Spike code: `/tmp/printer-spike/` (ephemeral, uncommitted; the method above reproduces it).
