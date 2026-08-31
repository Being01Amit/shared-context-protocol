# 14. Missing Features, Roadmap, Scorecard & Future Vision

Deliverables 17, 18, 19 and 20 — review steps 16, 17, and 19, plus the closing vision.

---

## Part A — Code quality (review step 16)

Assessed against the principles the brief names, with evidence rather than adjectives.

| Principle | Grade | Evidence |
|---|:---:|---|
| **SOLID** | A− | SRP: one class one job throughout. DIP: exemplary — `core` names ports, never implementations, enforced by the build. ISP: eleven focused ports rather than one god-repository. OCP: `RankingWeights.semantic` and the `embedding` column are real extension points. LSP: not stressed (little inheritance). Deduction: `HydrateContextUseCase` does candidate fetching, ranking, budgeting, and rendering |
| **DRY** | C+ | Ranking is defined exactly once and reused — the important case, done right. But the two composition roots are ~95% identical (**F-03**), and they already had to be edited in lockstep once |
| **KISS** | A | No DI container, no ORM, no event bus, no abstraction without a second implementation. `Scoring` is one function. `SessionResolver` is 40 lines. Manual constructor wiring. Consistently the simpler choice |
| **YAGNI** | B+ | Reserved extension points are cheap and justified (`embedding` column, `semantic` weight, `modules/api` slot). Deductions: `modules/skills` is a layer with no behaviour (**F-02**); `autoSaveIntervalSeconds` is loaded, validated, and never read; MockK is declared and unused |
| **Clean Architecture** | A | Dependency rule enforced by the Gradle module graph, not convention. Domain has zero I/O. Time and identity injected. The one deduction is MCP DTOs living in the contracts layer (**F-01**) |
| **DDD** | B | Entities, value objects, and a ubiquitous language present. No aggregates with enforced invariants, no domain events, and an anaemic model — logic lives in use-cases, not entities. Appropriate at this scale; noted rather than criticised |
| **Hexagonal** | A | Textbook. Ports in `model`, adapters in their own modules, composition at the edge |
| **Modularity** | A− | Nine modules, acyclic, shallow, one justified adapter-to-adapter edge. Deduction: `skills` |
| **Extensibility** | B+ | Concrete reservations, and the roadmap names the extension point for each future feature. Deduction: no relations in the schema (**F-30**) makes graph-shaped features expensive |
| **Testability** | A | `Clock`/`IdGenerator` ports, pure functions, hand-written fakes, real-SQLite integration tests. The design makes testing easy — and the tests confirm it |
| **Maintainability** | B+ | Comments explain *why*; ADRs with revisit triggers; schema is its own documentation. Deductions: zero tests on `apps/mcp-server` (509 lines, the whole external contract), no CI (**F-18**) |

**Overall: A−.** This is materially better-engineered than most projects at this stage. The
architecture is not aspirational — it is enforced. The gaps are consistently in *completion* (unused
ports, untested surfaces, no CI), not in design.

---

## Part B — Missing features (step 17)

### B.1 Missing capabilities that block the stated product

| Feature | Impact | Finding |
|---|---|---|
| **Entity lifecycle** — close todos, accept/supersede decisions, edit project | **The single largest gap.** Todos accumulate forever; superseded decisions read as current | F-24 |
| **Hydration of all entry types** | 14 of 16 `ContextType`s never reach the resume path | F-07 |
| **Relevance in search ranking** | bm25 computed and discarded | F-10 |
| **Work claiming** | No way to prevent duplicate work across agents | F-31 |
| **Delete / soft delete** | A wrong or poisoned entry is permanent | S5 |
| **Change notification** | Every agent's view is a stale snapshot | F-32 |

### B.2 Missing infrastructure

CI pipeline (**F-18**) · MCP-server tests · benchmark harness · `LICENSE` · `CHANGELOG` ·
`CONTRIBUTING` · backup and export commands · dependency verification (**S9**) · release/distribution
mechanism (users must build from source).

### B.3 The forward roadmap

Grouped by horizon. Items already reserved in [docs/07](../07-roadmap.md) are marked ✓reserved.

**Near — completes the v1 promise**

| Feature | Notes |
|---|---|
| Entity lifecycle + status transitions | F-24. Repositories and enums already exist |
| Full-type hydration | F-07. `findRecent` already exists and is unused |
| Relevance-aware search ranking | F-10. Extend `RankingWeights` |
| Work claiming with leases | F-31. `todo.owner` and `IN_PROGRESS` already exist |
| MCP resources + prompts | [06](06-mcp-compatibility.md), [09](09-mcp-prompt-library.md). No new dependency |
| Roots → project auto-resolution | [07](07-mcp-integration-blueprint.md). Delivers connect-once |
| Backup / export / soft delete | S5 |
| Session hooks (H11, H13, H14) | [10](10-hook-specification.md). Makes capture automatic |

**Medium — makes it a multi-agent protocol**

| Feature | Notes |
|---|---|
| **Entry relations** (`entry_relation(from, to, kind)`) | F-30. Highest-value schema addition: makes `SUPERSEDED` mean something, links bugs to fixes, todos to decisions |
| Semantic search / embeddings | ✓reserved (`embedding` column, `semantic` weight). Local ONNX inference; hybrid keyword + vector over FTS-preselected candidates |
| Resource subscriptions + notifications | F-32 |
| Shared-server mode (Streamable HTTP) | [07](07-mcp-integration-blueprint.md) §7.4. Requires all five hardening controls |
| Git integration | ✓reserved (`file.hash`, `COMMIT` type). Commit summaries, staleness detection |
| Provenance + trust-aware ranking | S1. Security-critical |
| Audit trail | S4 |
| Summarisation / compaction | ✓reserved as deliberately deferred. Needed by year two |
| Full observability | Metrics, `doctor --mcp`, structured telemetry |

**Long — enterprise and distribution**

Cross-machine sync (✓reserved: UUID PKs + UTC timestamps) · multi-user with real authz · knowledge
graph over relations · plugin system for custom types, rankers, and adapters · KMP native CLI
(✓reserved) · team/server deployment · analytics · versioned memory with time-travel queries.

**A caution on the long list.** SCP's advantages are that it is local, structured, bounded, and
universal. Cross-machine sync, multi-user, and team deployment each erode "local" and add
proportionally more security surface than feature value ([12](12-security-assessment.md) shows the
severity of most findings jumping a full grade the moment the deployment is shared). Test every
long-horizon item against those four properties before committing to it. A worse product that syncs is
still worse.

---

## Part C — Refactoring roadmap (deliverable 18)

Ordered by value per unit of effort. Effort is engineer-days for someone who knows this codebase.

### P0 — correctness and security, before anything else

| # | Change | Effort | Finding |
|---|---|---:|---|
| 1 | **Fix `SessionResolver`** — `open.filter { it.toolName == toolName }.singleOrNull()` + 3-agent test | 0.5 | F-11 |
| 2 | **Entity lifecycle** — `update_todo_status`, `update_decision_status`, `update_project`, `close_session`; MCP + CLI; **add `version` + `updated_at` while doing it** | 2–3 | F-24, F-45, F-60 |
| 3 | **Hydrate all entry types** — general `findRecent` section using the existing type multipliers | 0.5 | F-07 |
| 4 | **Relevance in search ranking** — normalised bm25 term in `RankingWeights` | 1 | F-10 |
| 5 | **Fence stored content on read** — data delimiters + untrusted-data preamble on every read path | 1 | S1 |
| 6 | **Extract the shared runtime** from the two composition roots | 1 | F-03 |
| 7 | **CI pipeline** — Windows + Linux matrix, `build` + `verifySqlDelightMigration` + dependency verification | 0.5 | F-18, S9 |

**Subtotal ≈ 7 days.** This is the set that takes SCP from "works for the demo" to "delivers its
stated promise". Items 1–4 are all small and all block the core product claim.

### P1 — the multi-agent story

| # | Change | Effort | Finding |
|---|---|---:|---|
| 8 | Work claiming with leases (`claim_work` / `release_work` + expiry sweep in `doctor`) | 2 | F-31 |
| 9 | MCP-server tests via an in-repo test client | 2 | [03](03-module-analysis.md) |
| 10 | Tool annotations + `outputSchema` + `structuredContent` | 1–2 | F-38, F-39 |
| 11 | Roots → project auto-resolution + auto-provision (first real `.sqm`) | 2 | [07](07-mcp-integration-blueprint.md) |
| 12 | MCP resources + templates | 2–3 | [06](06-mcp-compatibility.md) |
| 13 | MCP prompts (P1 `resume`, P9 `save` first) | 2–3 | [09](09-mcp-prompt-library.md) |
| 14 | Session hooks H13/H14/H11 | 2 | [10](10-hook-specification.md) |
| 15 | Fix N+1s (`timeline`, `list_projects`) + chunk `tagsForEntries` + bound `timeline` | 1 | F-20, F-48 |
| 16 | Backup, export, soft delete | 2 | S5 |
| 17 | Provenance columns + trust-aware ranking + priority clamping | 2 | S1 |
| 18 | Audit table | 1 | S4 |

**Subtotal ≈ 22 days.**

### P2 — quality, cost, and future-proofing

Benchmark harness with semantic assertions (2) · AppCDS for the CLI (1) · CLI/MCP surface parity +
`save-note` (1) · ADR-17 for the driver swap and doc corrections (0.5) · MCP API reference generated
from schemas (1) · integration guide covering all nine clients (2) · `LICENSE`/`CHANGELOG`/`CONTRIBUTING`
(1) · WAL checkpoint + `scp compact` + `doctor` size warning (1) · composite index on
`(project_id, type, timestamp)` (0.5) · structured error `_meta` (1) · key from OS keychain + `scp rekey`
(2) · remove dead code — `Budget` guard, unused `autoSaveIntervalSeconds`, MockK (0.5).

**Subtotal ≈ 13 days.**

### Sequencing note

Do **P0-6 (shared runtime) and P0-7 (CI) early even though they deliver no user-visible value.**
Everything after them is otherwise implemented twice and verified by hand. They are the multiplier on
the remaining ~35 days of work.

---

## Part D — Scorecard (deliverable 19 / step 19)

Each score is justified by what was found, not by impression.

| Category | Score | Justification |
|---|:---:|---|
| **Architecture** | **8.5** / 10 | Textbook hexagonal, enforced by the build rather than convention. Zero-I/O domain, injectable time and identity, one shared ranking function. Deductions: transport DTOs in the contracts layer (F-01), a behaviourless orchestration layer (F-02), duplicated composition roots (F-03) |
| **Scalability** | **5.0** / 10 | Storage scales to 50 agents unmodified. The *protocol* degrades from ~3: session fragmentation (F-11), no claiming (F-31), no change awareness (F-32), monotonically growing todo lists (F-24). All additive to fix |
| **Performance** | **7.0** / 10 | Sub-100 ms of real work; MCP server ~10× inside budget warm. Deductions: CLI cold start past ADR-2's own revisit trigger and blocking hooks (F-44/F-57), three N+1s, unbounded `timeline`, no caching, no benchmark harness |
| **Security** | **4.0** / 10 | Genuinely good hygiene — redaction placed architecturally, everything bounded, fail-closed config, parameterised SQL, least-privilege files, protected stdout. But the defining threat for a shared-context layer — prompt injection and memory poisoning (S1) — is entirely unaddressed, with no delete, no audit, and no provenance to recover with |
| **Maintainability** | **8.5** / 10 | Small, dense, well-commented; ADRs with revisit triggers; schema is its own documentation; 0.99 test ratio on `core`. Deductions: no CI (F-18), duplicated wiring (F-03), 509 untested lines in the MCP server |
| **Extensibility** | **7.5** / 10 | Concrete reservations that genuinely work (`embedding`, `semantic`, UUID PKs, `modules/api`), and the roadmap names them per feature. Deduction: no relations in the schema (F-30) makes a whole class of features expensive |
| **Developer experience** | **6.0** / 10 | Excellent `doctor`; clear errors; scriptable JSON; a working setup guide. Deductions: `create_project` required before first write, `--project` on every command, manual workspace configuration whose failure mode is a silently separate database, CLI/MCP asymmetry, no `save-note` in the CLI, no release binaries |
| **Documentation** | **8.0** / 10 | Seven strong documents, sixteen ADRs, Mermaid diagrams, comments that explain *why*. Deductions: ADR-3 factually stale (F-13), no MCP API reference, no integration guide beyond one client, no LICENSE/CHANGELOG/CONTRIBUTING, and the docs describe a lifecycle the code cannot perform |
| **MCP readiness** | **5.5** / 10 | A correct, working stdio tool server with a clean validation pipeline and proper `isError` semantics. But it uses ~15% of the SDK it already depends on: no resources, prompts, notifications, annotations, output schemas, or structured content — all available in the pinned version (F-37) |
| **Production readiness** | **5.0** / 10 | Concurrency-safe, crash-safe, self-diagnosing, encrypted at rest. Blocked by: no CI, no release artefacts, no backup/restore, no delete, no audit, untested external contract, unresolved P0 defects |
| **Multi-agent readiness** | **4.0** / 10 | Write serialisation is real, proven by a genuine two-client test, and nothing merges or corrupts. But that is *safe concurrent writing*, not collaboration: no claiming, no notification, no identity, no lifecycle, and session resolution degrades at three agents |
| **Overall** | **6.3** / 10 | A well-engineered store with an unfinished protocol |

### Strengths

1. **Architecture enforced by the build.** The dependency rule is a compile error, not a review comment.
2. **One ranking function**, shared, pure, tested, with a documented spec it actually matches.
3. **Redaction placed so it cannot be forgotten** — before the first repository call, in the use-case.
4. **Real concurrency safety with a real test.** Two independent stacks, same file, same instant.
5. **Bounded output as a first-class principle.** Rare, and more rigorous than most memory systems.
6. **Intellectual honesty in the ADRs** — including recording a measurement that breached its own
   threshold rather than moving the threshold.
7. **`scp doctor`** — diagnoses *and repairs*. Building this in v1 is a mark of maturity.
8. **The strategic bet is correct.** Being an MCP server means universality is inherited, not built.

### Weaknesses

1. **Write-only lifecycle (F-24)** — the largest functional gap, and the ports for the fix already exist.
2. **Hydration omits most stored content (F-07)** — the core promise, half-delivered.
3. **Prompt injection and memory poisoning unaddressed (S1)** — the defining threat for this category.
4. **No coordination primitives (F-31)** — a multi-agent protocol with no way to divide work.
5. **Search discards relevance (F-10).**
6. **No CI (F-18)** — the declared quality gate is unenforced.
7. **Duplicated composition roots (F-03)** — a silent-divergence bug waiting to happen.
8. **The external contract is untested** — 509 lines, zero tests.

### Risks

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Composition roots diverge silently | **High** | High — CLI and MCP behave differently on the same data | P0-6 |
| Poisoned entry persists unremovably | Medium | **Critical** | P0-5, then S5 and provenance |
| Users conclude "it doesn't remember" | **High** | High — F-07 + F-24 make hydration look broken | P0-2, P0-3 |
| Agents duplicate work at 3+ agents | High | Medium | P0-1, then P1-8 |
| Forked driver abandoned | Low | High — encryption is lost | ADR-17 with an exit path |
| Regression ships unnoticed | **High** | Medium | P0-7 |

---

## Part E — Production readiness

| Dimension | Status |
|---|---|
| Functional completeness | ❌ No entity lifecycle; hydration incomplete |
| Correctness | ⚠️ Four P0 defects, all small |
| Concurrency safety | ✅ Proven |
| Crash safety | ✅ WAL, transactions, atomic file writes |
| Data durability | ✅ Durable · ❌ no backup, export, or restore |
| Security | ❌ S1 unaddressed; no delete, audit, or provenance |
| Observability | ⚠️ Good logs, excellent `doctor`; no metrics |
| Testing | ⚠️ Strong in `core`, absent on the external contract |
| CI/CD | ❌ None |
| Release | ❌ Build from source only |
| Documentation | ⚠️ Strong internally; thin for integrators |
| Licensing | ❌ No LICENSE — blocks distribution outright |

**Verdict: not production-ready; roughly 7 focused days from being ready for its author, and ~30 from
being ready for others.** Nothing on this list is architectural. That is the important part — the
expensive decisions were made correctly, and what remains is completion.

**Gate for "ready for other people":** P0 complete · CI green on both platforms · `LICENSE` present ·
backup and export shipped · MCP contract under test · S1 fencing in place · integration guide covering
at least Claude Code, Antigravity, and Cursor.

---

## Part F — Future vision (deliverable 20)

### F.1 The thesis is right

MCP standardised how an agent reaches a tool. It did not standardise what the agent *remembers*.
Memory today is vertical: Claude remembers for Claude, Cursor for Cursor. Every vendor is building a
private silo, and the developer — who works across four tools in a day — is the transport layer
between them, paying the tax in copy-paste and re-explanation.

**SCP's bet is that memory should be horizontal, local, and owned by the developer.** That bet is
correct, and it is well-timed:

- MCP made universality cheap. A single stdio server inherits every current and future client.
- Local inference made semantic search viable offline — the last technical reason to be a cloud service
  is gone.
- Multi-agent workflows moved from novelty to routine, which is what makes the problem acute.
- Privacy expectations for code context are hardening, not softening.

### F.2 The positioning to hold

**SCP complements MCP; it does not replace it, and it should never try.** The correct framing:

> MCP is the nervous system — how agents reach tools.
> SCP is the hippocampus — where what happened is consolidated and retrieved.

And it complements vendor memory rather than competing with it. Claude's memory will always be better
*inside Claude*. SCP's claim is the one no vendor can make: **the context outlives the vendor.** The
right message is "keep using your tool's memory, and add the layer that survives switching tools" —
not "replace it".

### F.3 What SCP must protect

Four properties, in priority order. Every future feature should be tested against them, and any that
fails two should be rejected regardless of demand.

1. **Local.** Zero network on the core path. This is the differentiator no funded competitor can copy
   without abandoning its business model.
2. **Structured.** Sessions, decisions, todos, files, typed entries — this is what makes a *resume
   payload* possible instead of a similarity search. It is the second differentiator and the less
   obvious one.
3. **Bounded.** Every read has a token ceiling and signals truncation. As context windows grow, the
   discipline of returning *the right things* rather than *everything* becomes more valuable, not less.
4. **Universal.** One protocol, every client, no per-client code.

### F.4 The three-year shape

**Year 1 — finish the promise.** Complete the lifecycle, hydrate everything, add claiming and
notifications, ship resources and prompts, close the injection gap. Outcome: a developer runs four
agents across a week and none of them re-asks a question that was already answered. That is the whole
product, and it is ~30 days away.

**Year 2 — make it intelligent.** Local embeddings for hybrid retrieval; entry relations turning the
store into a graph — decisions superseding decisions, bugs linked to fixes, todos to the decisions that
motivated them; compaction that summarises without losing the thread; git integration so file
understanding is derived and staleness is detectable. Outcome: SCP answers *why* as well as *what*.

**Year 3 — make it plural.** Optional encrypted peer-to-peer sync between a developer's own machines
(never a service). Team mode as a separate, explicitly-scoped product with real authz and audit — not
an accidental consequence of adding a network transport. A plugin system so the taxonomy, rankers, and
adapters are extensible by others.

### F.5 The one thing that determines whether this works

**Capture must become automatic.** Everything in this review — the ranking, the budget, the
concurrency safety, the resume payload, all twenty-three skills — is worthless if `update_context` is
never called. Today it depends on an agent remembering, and the failure mode is silence.

That is why [10-hook-specification](10-hook-specification.md) is the most important forward-looking
document here, and why H11 (`onSessionEnd`) is the single highest-value thing to build after the P0
list. Memory that requires discipline is memory that does not happen.

Get capture automatic, finish the lifecycle, close the injection gap, and SCP is the local context
layer the multi-agent era actually needs — and the architecture underneath it is already good enough
to carry that.

---

Back to the [index](README.md), or start at the [executive summary](00-executive-summary.md).
