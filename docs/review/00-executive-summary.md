# 0. Executive Summary

Deliverable 1. A complete technical review of the Shared Context Protocol, performed against commit
`b79ccec` plus the uncommitted at-rest-encryption work. Every source file in `modules/` and `apps/`
was read; the live MCP server was queried to confirm the stdio surface responds.

---

## The verdict

**SCP is a genuinely well-engineered local-first context store that is not yet a multi-agent
protocol.**

The half that is built is built properly. Hexagonal architecture with the dependency rule enforced by
the Gradle module graph rather than by convention. A zero-I/O domain layer with injectable time and
identity. One shared ranking function used everywhere ranking happens. Secret redaction placed so it
cannot be forgotten. Real cross-process concurrency safety, demonstrated by a real two-client test.
Sixteen ADRs with rationale, consequences, *and revisit triggers* — including one that honestly
records a measurement breaching its own threshold rather than quietly moving the threshold.

The half that is missing is not architectural. It is **completion**: repository methods that exist and
are never called, entry types that are stored and never returned, a relevance score that is computed
and discarded, and a threat model that the design has not yet been pointed at.

**Overall score: 6.3 / 10.** Roughly seven focused engineer-days from delivering its stated promise;
roughly thirty from being ready for other people to depend on.

---

## What SCP is

A single-file SQLite database with a ranked, token-budgeted read API, exposed over MCP stdio and a
CLI. That framing is not a criticism — it is the correct shape for the problem. The value sits in
three places, and all three are well executed: the ranking function, the session-resolution policy,
and the discipline of bounded output.

Nine Gradle modules, ~3,050 lines of Kotlin plus 286 lines of SQL. Eight MCP tools, nine CLI commands.
No daemon, no network listener, no background thread. Each AI tool launches its own server process;
agents coordinate through the file, never with each other.

---

## The five findings that matter most

### 1 · Nothing can ever change state after it is created — P0

`TodoRepository.updateStatus`, `DecisionRepository.updateStatus`, and
`ProjectRepository.updateDescription` are implemented and have **zero callers** outside tests
(verified by grep across `core`, `skills`, and `apps`). No delete exists at all.

So a todo can never be marked done — every todo ever created appears in every future hydration. A
decision can never be accepted or superseded — a decision reversed months ago presents identically to
current policy, which misleads rather than merely omitting. A project description is write-once, and
it is the first thing hydration emits.

For a system built to track "todos, implementation progress, task execution state", this is not a
missing feature. It is *the* feature. The enum values, `CHECK` constraints, indexes, and repository
methods all already exist — this is wiring, not design, which is strong evidence it was planned and
simply not finished. → [F-24](03-module-analysis.md)

### 2 · Hydration never returns most of what agents store — P0

**Implementation status: fixed, this finding is stale.** As of the current code, `hydrate_context`
already fetches every `ContextType` — `BUG`/`PROMPT` keep their own dedicated sections, and
`ContextEntryRepository.findRecent` feeds a `recentEntries` catch-all for the other fourteen types,
filtered only to avoid duplicating the two dedicated sections. This was verified directly against
`HydrateContextUseCase.kt`, not carried forward from an earlier pass of this document, and is pinned by
an existing test (`` `non-bug non-prompt entries are hydrated, not silently dropped` ``) plus
`docs/05-hydration-ranking.md` §5. Likely landed as part of the same commit that added this review.

What was still true — `omittedCount` didn't count the post-rank top-N caps on `recentEntries`
(`TOP_ENTRIES`) or `relevantPrompts` (`TOP_PROMPTS`), so entries beyond those caps were dropped without
signaling — has since been fixed too: `Budget.recordOmitted` now accounts for cap-based drops
alongside budget-fill truncation. The SQL `CANDIDATE_LIMIT` bound (entries beyond it are never fetched
at all) remains a known, deliberately deferred gap — see the comment on `CANDIDATE_LIMIT` in
`HydrateContextUseCase.kt`.

Original finding text, preserved for history: `hydrate_context` fetches entries of exactly two types:
`BUG` and `PROMPT`. `ContextType` has sixteen members. Entries typed `ARCHITECTURE`, `FEATURE`, `TASK`,
`LEARNING`, `REFACTOR`, `SECURITY` and eight others are validated, redacted, persisted, and indexed —
and never appear on the resume path. → [F-07](01-architecture-review.md), [F-08](01-architecture-review.md)

### 3 · Prompt injection and memory poisoning are entirely unaddressed — P0

Stored content is returned verbatim into another agent's context with no provenance, no trust label,
and no data/instruction separation.

Ordinary prompt injection is bounded by a session. SCP removes that bound: a poisoned entry is
persistent (and cannot be deleted — finding 1), authoritative (it arrives labelled as this project's
decisions), cross-agent (it reaches every future agent, possibly one with broader permissions), and
**ranked into prominence** — an attacker writing `type: DECISION, priority: 5` gets the maximum type
multiplier and maximum priority, because the scoring inputs are caller-controlled.

The attack needs nothing unusual: an agent summarises a GitHub issue or a dependency README containing
instructions, records what it read as a project note, redaction matches nothing (it is prose, not
key-shaped), and a week later a different agent receives it as a project decision.

This is the defining threat for a shared-memory layer and the risk grows with each MCP capability
added — resources attach content with *less* framing than tools, and prompts inject it as conversation
messages, the strongest possible framing as instruction. → [S1](12-security-assessment.md)

### 4 · There is no way to divide work between agents — P1

No claims, no leases, no locks, no assignment. `Todo.owner` is accepted at the boundary and never
written by any code path; `TodoStatus.IN_PROGRESS` exists and nothing can set it.

Two agents hydrate, both see the same open todo, both implement it. SCP's contribution is to have
faithfully recorded both. For a *multi-agent* protocol, work claiming is not an advanced feature —
it is the minimum coordination primitive.

Compounding it: `SessionResolver` uses `open.singleOrNull()`, so once **any** two agents hold open
sessions, no agent can reuse its own — including one that already has an open session. Every
`save_note` then creates a new session, and hydration's five-session window fills with fragments
exactly when multi-agent activity makes it most valuable. That one is a one-line fix.
→ [F-31](05-multi-agent-collaboration.md), [F-11](01-architecture-review.md)

### 5 · Search computes a relevance score and throws it away — P0

`SqlSearchIndex` computes `bm25()`, carries it into `SearchHit.ftsRank` — and
`SearchContextUseCase` then ranks purely by recency, priority, tag overlap, and type. `ftsRank` is
never read again.

A search for a specific error message can rank a two-day-old decision that happens to share a word
above a three-week-old bug entry that is a verbatim match. SQLite does the relevance work and the
application discards the answer. `RankingWeights` already demonstrates the extension pattern
(`semantic` defaults to 0.0 for exactly this purpose). → [F-10](01-architecture-review.md)

---

## What is done well

Worth stating plainly, because the findings above are exceptions rather than the rule.

- **The architecture is enforced, not aspirational.** `modules/core` declares one dependency, so an
  accidental adapter import is a compile error.
- **One ranking function**, pure, shared, tested, matching a written spec.
- **Redaction is architecturally placed** — before the first repository call, so "no secret at rest" is
  structural rather than remembered per call site.
- **Concurrency safety is real and proven.** WAL + `BEGIN IMMEDIATE` + busy retry, with a test using
  two independently-built stacks against the same file at the same instant.
- **Bounded output as a stated, implemented principle.** Rarer than it should be.
- **`scp doctor`** diagnoses *and repairs* — WAL, foreign keys, FTS/table agreement with an automatic
  rebuild, stale sessions. Shipping this in v1 is a mark of maturity.
- **stdout is guarded before anything else initialises**, because on a stdio transport one stray
  `println` corrupts the protocol.
- **The strategic bet is correct.** Being an MCP server means universality is inherited rather than
  built per client.

---

## The MCP finding

**SCP uses roughly 15% of the MCP SDK it already depends on.** Verified by extracting
`kotlin-sdk-server-jvm:0.14.0` from the Gradle cache and reading its API.

Available in the pinned version and unused: resources, resource templates, prompts, subscriptions and
change notifications, tool annotations (`readOnlyHint`/`destructiveHint`/`idempotentHint`),
`outputSchema` and `structuredContent`, sampling, elicitation, roots, completions, and three additional
transports (Streamable HTTP, SSE, WebSocket).

Every capability gap can be closed **without a dependency bump**. Two stand out:

- **Resources.** SCP is already a resource server that only speaks in tools. `FileMarkdownStore`
  literally renders the document an MCP resource would serve, writes it to disk, and returns a
  filesystem path no client can use.
- **Prompts.** These fix the discipline problem — a prompt can carry the hydration payload with it, so
  `/scp:resume` works without the agent choosing to call a tool.

→ [F-37](06-mcp-compatibility.md)

---

## Scorecard

| Category | Score |
|---|:---:|
| Architecture | 8.5 |
| Maintainability | 8.5 |
| Documentation | 8.0 |
| Extensibility | 7.5 |
| Performance | 7.0 |
| Developer experience | 6.0 |
| MCP readiness | 5.5 |
| Scalability | 5.0 |
| Production readiness | 5.0 |
| Security | 4.0 |
| Multi-agent readiness | 4.0 |
| **Overall** | **6.3** |

Full justification per category in [14 Part D](14-roadmap-and-production-readiness.md).

---

## What to do first

Seven changes, ~7 engineer-days, taking SCP from "works for the demo" to "delivers its stated promise":

| # | Change | Days |
|---|---|---:|
| 1 | Fix `SessionResolver` — filter by own tool before `singleOrNull` | 0.5 |
| 2 | Entity lifecycle: close todos, supersede decisions, edit project, close session — **with `version` + `updated_at` in the same change** | 2–3 |
| 3 | Hydrate all entry types via the existing unused `findRecent` | 0.5 |
| 4 | Add a relevance term to the ranking function | 1 |
| 5 | Fence stored content as untrusted data on every read path | 1 |
| 6 | Extract the shared runtime from the two duplicated composition roots | 1 |
| 7 | CI pipeline — Windows + Linux, the gate ADR-14 already declares | 0.5 |

Items 6 and 7 deliver no user-visible value and should still be done early: without them, everything
after is implemented twice and verified by hand.

Then ~22 days of P1 work builds the multi-agent story — claiming, resources, prompts, session hooks,
MCP contract tests, provenance and audit. Full breakdown in
[14 Part C](14-roadmap-and-production-readiness.md).

---

## The strategic read

The bet is right and well-timed. MCP made universality cheap, local inference removed the last
technical argument for a cloud memory service, and multi-agent workflows became routine. Vendor memory
is vertical — Claude's memory serves Claude — and no vendor can make SCP's claim: **the context
outlives the vendor.**

Four properties are the moat, and every future feature should be tested against them: **local,
structured, bounded, universal.** Cross-machine sync, multi-user, and team deployment each erode the
first and add disproportionate security surface — note that most findings in
[12](12-security-assessment.md) jump a full severity grade the moment the deployment is shared.

**And one thing determines whether any of it works: capture must become automatic.** The ranking, the
budget, the concurrency safety, the resume payload, all twenty-three specified skills — every one of
them is worthless if `update_context` is never called. Today that depends on an agent remembering, and
the failure mode is silence: two hours of work vanishes with no error and nothing for `doctor` to
detect. That is why [10-hook-specification](10-hook-specification.md) is the most consequential
forward-looking document in this review, and why `onSessionEnd` is the highest-value thing to build
after the P0 list.

Memory that requires discipline is memory that does not happen.

---

**Full review:** [index](README.md) · [architecture](01-architecture-review.md) ·
[stack](02-technology-stack.md) · [modules](03-module-analysis.md) ·
[memory](04-memory-architecture.md) · [multi-agent](05-multi-agent-collaboration.md) ·
[MCP compatibility](06-mcp-compatibility.md) · [MCP blueprint](07-mcp-integration-blueprint.md) ·
[skills](08-skills-specification.md) · [prompts](09-mcp-prompt-library.md) ·
[hooks](10-hook-specification.md) · [API & interop](11-api-design-review.md) ·
[security](12-security-assessment.md) · [performance & scale](13-performance-and-scalability.md) ·
[roadmap & readiness](14-roadmap-and-production-readiness.md)
