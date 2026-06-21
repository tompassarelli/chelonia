# #51 — Tier-3 agent-eval head-to-head — SCOPED PLAN + DEFER (with receipt)

**Status: deferred-with-receipt.** Not autonomously-overnight-feasible (needs a sandbox + a second
driven agent + graded task specs). Documented so it's a real plan, not a silent skip.

## Goal
An off-the-shelf agent (Claude Code) drives BOTH Fram and zerolang to author the same app(s); grade
on (a) correctness (builds + passes the task's tests), (b) efficiency (edit count / tokens / wall),
(c) behaviour under concurrency (K agents on one project). This is the CODESTRUCT-style question —
does a structured, identity-addressed action space make the *agent* better, not just the substrate
faster — applied head-to-head.

## Design (mirrors zerolang's own `evals/`, which runs Claude Code in a Vercel sandbox graded by
`expectedStdout` + `requiredSourcePatterns` + a duration cap)
1. Task specs: N small app tasks ("CLI that sums args", "fizzbuzz to 100", "tiny http handler"),
   each with an oracle (expected stdout + required behaviour).
2. Two arms, same agent, same tasks:
   - **zero arm:** agent uses `zero query`/`zero patch` (its native loop).
   - **Fram arm:** agent uses the Fram MCP surface (`add-def`/`set-body`/`rename` + `query`/`callers`)
     — already exists (`bin/fram-mcp`); add `insert-after` to reach commute (the §0.2 gap noted in
     [[authoring-surface-prior-art]]).
3. Grade: correctness (oracle pass), edit-count, wall, tokens; + a concurrency variant (K agents).
4. Harness: a sandbox + the Claude Code SDK (headless), per-task isolation, deterministic grading.

## Why deferred (honest)
- Needs infra not available autonomously here: a sandbox/agent-harness + headless agent driving +
  reliable grading. zerolang ships one (Vercel sandbox); Fram has none yet. Building it is a focused
  multi-day effort, not an overnight task.
- The **load-bearing wins are already measured mechanistically**: construction-path O(N) vs
  O(N²)-shaped (4.2-7.5×, `CONSTRUCTION-SCALING.md`), commute vs merge-queue under load
  (`../propagation/CONTINUOUS-ARRIVAL-RESULTS.md`), and the external CODESTRUCT result (named-AST
  action spaces beat free-form diffs for LLMs, arXiv:2604.05407). The agent-eval would *corroborate*
  the agent-side benefit, not establish the substrate result.

## Recommendation
Run in a dedicated session once a small agent-harness exists (reuse zerolang's evals/ structure as
the template). Lower priority than shipping the talk + the measured mechanistic wins.
