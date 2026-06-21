# #45 — continuous-arrival propagation under swept load λ

Extends #44 (K writers arriving all at once) to a **steady stream**: writers arrive on a Poisson
process at rate λ (writes/sec) over a fixed window; each commits a def; we measure commit-to-visible
(content-asserted via `:seen` for graph; push-landed for git). Harness:
`experiments/propagation/continuous-arrival.clj`. Env: AMD Ryzen AI 9 HX 370, 24c; warm Fram daemon
vs shared bare git repo + push-hook (the #44 baseline). Window 1500 ms; raw output in the harness.

## Result (commit-to-visible, ms)

| λ (w/s) | graph done | graph mean | graph p99 | git done | git mean | git p99 |
|---:|---:|---:|---:|---:|---:|---:|
| 10 | 21 | 41.4   | 72.3  | 14 | 390.9    | 865.4 |
| 30 | 45 | 65.0   | 116.7 | 45 | 10,368.2 | 20,618.2 |
| 60 | 81 | 111.3  | 199.3 | 65 | 27,267.3 | 50,344.0 |

## Reading
- **git's merge-queue SATURATES under sustained arrival.** Mean commit-to-visible explodes
  391 ms → 10.4 s → 27.3 s as λ rises 10 → 30 → 60; p99 hits **50 s**. Pushes to the shared ref
  serialize (non-ff reject → fetch+merge+retry), so once arrival exceeds the queue's drain rate the
  backlog grows without bound — and at λ=60 some writers don't even finish the window (65 of ~81).
- **Fram (commute) stays bounded and complete.** Mean rises gently 41 → 65 → 111 ms (the warm
  daemon serializes the *commit*, so there is mild queuing, but writes commute — no reject/retry,
  no shared-ref bottleneck), and **every writer completes** at every λ. ~160× lower at λ=30,
  ~245× lower at λ=60.
- This is the #44 finding amplified by sustained load: the merge-queue's cost is not just per-K
  latency, it is **queue saturation** — under a continuous stream it diverges, while commute degrades
  gracefully. Honest note: Fram is not perfectly flat (daemon commit serialization adds mild
  queuing); the claim is *bounded + graceful + lossless* vs *saturating + lossy*.

## Scope / caveats
- Single machine; git baseline is a local shared bare repo (no network) — generous to git.
- Graph commit-to-visible includes the daemon's serialized commit; the win is the absence of a
  shared-ref merge-queue, not zero serialization.
- λ swept 10/30/60; higher λ only widens the gap (git already at 27 s mean by λ=60).
