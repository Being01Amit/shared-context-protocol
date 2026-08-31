# SCP Technical Review

A complete architectural review of the Shared Context Protocol, performed against commit `b79ccec`
plus the uncommitted at-rest-encryption work in the tree.

**Scope.** Every source file in `modules/` and `apps/` was read. The live MCP server was queried to
confirm the stdio surface responds. No production code was modified by this review.

**Verdict in one line.** SCP is a genuinely well-engineered local-first context *store* — clean
hexagonal architecture, compile-time-enforced boundaries, real concurrency safety — that is not yet
a multi-agent *protocol*: work-item lifecycle, agent identity, and injection defence are absent.

## Reading order

| # | Document | What it answers |
|---|---|---|
| 0 | [Executive summary](00-executive-summary.md) | What SCP is, what works, what is broken, what to do first |
| 1 | [Architecture review](01-architecture-review.md) | Layering, dependency graph, startup, lifecycle, read/write flows |
| 2 | [Technology stack analysis](02-technology-stack.md) | Every stack choice, whether it holds up, what it costs |
| 3 | [Module analysis](03-module-analysis.md) | Per-module purpose, API, complexity, improvements, doc gaps |
| 4 | [Memory architecture](04-memory-architecture.md) | Memory taxonomy, read/write/merge flows, comparison to Mem0/OpenMemory/LangGraph/MCP |
| 5 | [Multi-agent collaboration](05-multi-agent-collaboration.md) | The Claude Code → Antigravity → Gemini → Cursor handoff, end to end |
| 6 | [MCP compatibility report](06-mcp-compatibility.md) | Feature-by-feature MCP mapping; what SCP should expose |
| 7 | [MCP integration blueprint](07-mcp-integration-blueprint.md) | Server/client design, transports, auth, lifecycle, errors |
| 8 | [Skills specification](08-skills-specification.md) | 22 agent skills, fully specified |
| 9 | [MCP prompt library](09-mcp-prompt-library.md) | 14 reusable prompts with argument schemas |
| 10 | [Hook specification](10-hook-specification.md) | 13 automatic hooks with triggers and data contracts |
| 11 | [API design review](11-api-design-review.md) | Tool/port API critique + per-client interoperability |
| 12 | [Security assessment](12-security-assessment.md) | Threat model, trust boundaries, injection, poisoning, recovery |
| 13 | [Performance & scalability](13-performance-and-scalability.md) | Latency budget, indexing, 2→50 agents, locking, benchmarks |
| 14 | [Roadmap & production readiness](14-roadmap-and-production-readiness.md) | Missing features, P0/P1/P2 roadmap, scorecard, future vision |

## Conventions

- Findings are numbered `F-nn` and cited as `path/to/File.kt:line` against the tree at review time.
- Recommendations use the repo's existing ADR-lite framing and name the ADR they revise.
- Severity: **P0** correctness or security defect · **P1** blocks the stated product goal ·
  **P2** quality, cost, or future-proofing.
- This review builds on and does not restate [docs/01–07](../01-architecture.md); it links instead.
