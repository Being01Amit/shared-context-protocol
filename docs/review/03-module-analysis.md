# 3. Module Analysis

Deliverable 4 (review step 4), plus deliverable 19 (documentation review, step 18) in §3.11.

## 3.0 Size and shape

| Module | Main LOC | Files | Test LOC | Test ratio | Verdict |
|---|---:|---:|---:|---:|---|
| `modules/model` | 934 | 13 | 0 | 0.00 | Contracts only; largely acceptable, see §3.1 |
| `modules/core` | 808 | 13 | 802 | 0.99 | **Best-covered module. Correct priority** |
| `modules/database` | 552 + 286 `.sq` | 5 | 541 | 0.65 | Solid |
| `modules/search` | 81 | 1 | 100 | 1.23 | Tight and well-scoped |
| `modules/markdown` | 127 | 2 | 110 | 0.87 | Good |
| `modules/config` | 169 | 3 | 106 | 0.63 | Good |
| `modules/skills` | 116 | 7 | 0 | 0.00 | Untested pass-through layer (F-02) |
| `apps/mcp-server` | 509 | 3 | **0** | 0.00 | **The entire external contract, untested** |
| `apps/cli` | 465 | 4 | 145 | 0.31 | Concurrency test only; commands untested |

Total ~3050 lines of production Kotlin plus 286 lines of SQL. This is a small, dense codebase — which
is a compliment. The whole system fits in a reader's head, and the module count (9) is proportionate
rather than aspirational.

The distribution tells a clear story: **testing effort tracked complexity correctly in the middle of
the stack and stopped at both ends.** `core` at a 0.99 test ratio is where the logic is and where the
tests are. `apps/mcp-server` at 0.00 is where the contract with every AI client is.

---

## 3.1 `modules/model` — contracts and ports

**Purpose.** The shared vocabulary. Domain entities, status enums, the `ContextType` taxonomy, the
hydration payload shape, ranking weights, all port interfaces, MCP boundary DTOs, and Konform
validations.

**Public API.** `Project`, `Session`, `ContextEntry`, `TrackedFile`, `Decision`, `Todo`;
`SessionStatus`/`DecisionStatus`/`TodoStatus` with `dbValue` round-tripping; `ContextType` (16
members); `HydrationPayload` + seven `*Brief` types; `RankingWeights` + `RankableItem` +
`HydrationQuery`; `ScpException` hierarchy; eleven port interfaces across `port/`; ten input DTOs and
ten result DTOs under `mcp/`; `McpValidations`.

**Dependencies.** kotlinx.serialization, kotlinx.datetime, konform. No project dependencies — correct.

**Internal quality.** High. Enum-to-DB mapping is explicit (`dbValue` + `fromDb`) rather than relying
on `.name`, which means renaming a Kotlin enum constant cannot silently corrupt stored data — except
for `ContextType`, which *is* stored via `EnumColumnAdapter` on `.name`. That asymmetry is deliberate
and documented in the `ContextType` KDoc, but it means renaming a `ContextType` member is a breaking
data change while renaming a `TodoStatus` member is not. Worth a comment on `ContextType` saying so.

`RankableItem.Companion` encodes the mapping table from [docs/05](../05-hydration-ranking.md) §1 in
code (decisions rank as max-priority DECISION items, todos as priority-4 TASK items), so the doc and
the implementation cannot drift. Good.

**Improvements.**

- **F-01 (repeated):** `mcp/` DTOs and `konform` do not belong in the contracts layer that the pure
  domain depends on. Split `model` into `model` (entities + ports) and `model-mcp` (boundary DTOs +
  validations), or move the DTOs to the delivery layer and give `core` its own command types.
- `McpValidations` at 200+ lines of declarative constraints in one object is fine now; if it grows,
  split per tool.
- Zero tests. Defensible — the module is data classes and declarations. But `McpValidations` contains
  real logic (limits, patterns) that is only exercised indirectly through use-case tests. A small
  table-driven test asserting each validation's boundary values would be cheap insurance on the
  project's outermost guard rail.

**Complexity:** low. **Scalability:** N/A. **Maintainability:** high.

---

## 3.2 `modules/core` — the domain

**Purpose.** All business logic, with zero I/O. Eight use-cases, the shared scoring function, secret
redaction, session-resolution policy, token estimation.

**Public API.** `Scoring.scoreEntry`, `Redaction.redact`/`compile`, `SessionResolver.resolve`,
`TokenEstimator`/`CharsPerTokenEstimator`, and eight `*UseCase` classes.

**Dependencies.** `modules/model` only, enforced by `build.gradle.kts`. This is the load-bearing
constraint of the whole architecture.

**Internal quality.** The three pure components are excellent:

- `Scoring` — one function, four normalised terms, no I/O, a reserved `semantic` addend with an
  explaining comment. Directly testable and directly tested (`ScoringTest.kt`).
- `Redaction` — nine built-in patterns covering AWS keys, GitHub tokens/PATs, Anthropic and generic
  `sk-` keys, JWTs, bearer tokens, PEM private-key blocks, and `.env`-style assignments with a
  `keepFirstGroup` mode that preserves the variable name. Config patterns *extend* rather than
  replace, which is the right default.
- `SessionResolver` — 40 lines encoding a three-branch policy, with the invariant ("must be invoked
  inside `inWriteTransaction`") stated in the KDoc.

**Improvements.**

- **F-07 (P0):** hydration ignores 14 of 16 `ContextType`s. `ContextEntryRepository.findRecent` exists
  and is unused — the fix is a few lines.
- **F-08 (P1):** `omittedCount` under-reports SQL-`LIMIT` drops.
- **F-09 (P2):** dead guard at `HydrateContextUseCase.kt:131`.
- **F-10 (P0):** `SearchContextUseCase` discards `ftsRank`.
- **F-11 (P1):** `SessionResolver.kt:39` `singleOrNull()` degrades at ≥2 open sessions.
- **F-05 (P1):** `UpdateContextUseCase` re-reads the full session inside the write transaction.
- `HydrateContextUseCase` is 180 lines holding candidate fetching, ranking, the `Budget` inner class,
  six render functions, and section assembly. It is the most complex class in the codebase and it is
  where three of the six findings live. Splitting `Budget` and the renderers into their own file would
  make the section-assembly logic — the part that implements a documented spec — readable on one
  screen.
- `TimelineUseCase` and `ListProjectsUseCase` are N+1 (§3.3, F-21/F-22).

**Complexity:** moderate, concentrated in one class. **Scalability:** ranking is O(n) over bounded
candidate sets — fine. **Maintainability:** high, aided by the 0.99 test ratio.

---

## 3.3 `modules/database` — persistence adapter

**Purpose.** SQLDelight schema, generated typed queries, six repository implementations, the
transaction runner, the driver factory, FTS maintenance.

**Public API.** `DriverFactory.open(path, key?) : DatabaseHandle`, six `Sql*Repository` classes,
`SqliteTransactionRunner`, `FtsAdmin.rebuild`.

**Schema.** Seven tables plus one FTS5 virtual table, 286 lines of `.sq`. Indexes are present and
purposeful: `(project_id, status)` composites on `session`, `decision`, `todo` — exactly matching the
`findOpenByProject` hot queries; `timestamp` and `type` on `context_entry`; `(project_id, updated_at)`
on `file`; `tag` on the tag join table. `CHECK` constraints enforce status domains and priority range
at the storage layer, so a bug in Kotlin cannot write an invalid status. Foreign keys are declared
*and* enabled (`PRAGMA foreign_keys = ON`, verified by `doctor`) — the ADR-4 note that SQLite does not
default to this is exactly the trap most projects fall into.

**Internal quality.** High. `SqlContextEntryRepository.insert` wraps entry + tags in `db.transaction`
with a comment noting nested transactions join the enclosing one, so it is correct standalone and
inside `inWriteTransaction`. `withTags` batch-loads tags via one `IN` query rather than per-entry
lookups — the N+1 that most repository layers ship with is explicitly avoided here.

**Improvements.**

- **F-04 (P2):** `user_version` set outside the schema-creation statement.
- **F-12 (P2):** backoff sleeps hold the JVM lock.
- **F-20 (P2) — `tagsForEntries` uses `IN :entryIds` with one bind parameter per id.** SQLite's
  default `SQLITE_MAX_VARIABLE_NUMBER` is 32766 on modern builds, so this is safe for realistic
  batches, but `listChronological` (used by `timeline`) passes *every entry in the project*. A project
  with 40k entries throws a parameter-count error rather than degrading. Chunk the id list.
- No `.sqm` migration exists yet, so `verifyMigrations` currently verifies nothing. That is correct
  for v1 and becomes important at the first schema change — the snapshot is committed and the
  machinery is wired, which is the hard part.
- **Missing DELETE paths entirely.** There is no delete query for any entity. See F-24.

**Complexity:** low per class. **Scalability:** see [13](13-performance-and-scalability.md).
**Maintainability:** high — the `.sq` files *are* the schema documentation, so drift is impossible.

---

## 3.4 `modules/search` — FTS5 adapter

**Purpose.** Compose full-text `MATCH` with structured filters in one query; expose index count and
rebuild for `doctor`.

**Public API.** `SqlSearchIndex(database, driver) : SearchIndex`.

81 lines, one class, one responsibility, a 1.23 test ratio. The `toFtsQuery` sanitiser — quote every
whitespace token, escape embedded quotes — is the right shape: it makes FTS5 syntax errors from user
input structurally impossible.

**Improvements.** F-15 (English-only stemmer), F-16 (no prefix search, and the sanitiser blocks it).
The `SearchHit.ftsRank` it faithfully produces is discarded by its caller (F-10) — the defect is in
`core`, but this module is where the evidence sits.

**Complexity:** low. **Scalability:** bounded by `LIMIT`. **Maintainability:** high.

---

## 3.5 `modules/markdown` — human-readable mirror

**Purpose.** Render one `.md` per session at `storage/markdown/{project}/{date}-{id8}.md`.

**Public API.** `FileMarkdownStore(root) : MarkdownStore`, `NoOpMarkdownStore : MarkdownStore`.

**Internal quality.** Better than it needs to be. Writes are atomic (temp file + `ATOMIC_MOVE` with a
graceful fallback when the filesystem refuses), so a crash never leaves a half-written mirror.
Directory names are sanitised for Windows (`<>:"/\|?*`, control characters, trailing dots and spaces)
with a blank-name fallback. The renderer groups entries into fixed sections and emits `_(none)_` for
empty ones, so the output shape is stable and diff-friendly.

**Improvements.**

- **F-23 (P2) — the mirror is write-only.** `MarkdownStore` has `write` and nothing else. The port's
  own doc comment in [docs/01 §3](../01-architecture.md) describes it as "Write/read one `.md` per
  session" — read was never implemented. That matters for the stated principle that Markdown is a
  human-editable surface: a human who edits a mirror file has their edit silently overwritten on the
  next `update_context` (F-05 rewrites the whole file). Either make the mirror explicitly read-only
  output (document it, and say so in the file header), or implement round-tripping. Silently
  discarding user edits to a file the project advertises as human-readable is the worst of the three.
- `NoOpMarkdownStore.write` returns `""`, and `UpdateContextResult.markdownPath` carries it to every
  client with no signal that mirroring is off (F-14).

**Complexity:** low. **Maintainability:** high.

---

## 3.6 `modules/config` — configuration and file permissions

**Purpose.** Load and validate `config.yaml`; enforce least-privilege permissions on storage.

**Public API.** `ConfigLoader.load/parse/validated`, `ScpConfig`, `ConfigException`,
`SecureFiles.secureDirectory/restrict/prepareStorage`.

**Internal quality.** `validated()` accumulates *all* violations and reports them together, each
naming its key — far better than failing on the first. Every numeric bound, every weight, the log
level, and each custom regex are checked. Absent keys take documented defaults; present-but-invalid
values abort startup. ADR-11's rationale is right: a silently-defaulted ranking weight produces wrong
output with no error, which is the worst failure mode for a memory system.

`SecureFiles` is well-reasoned — the KDoc explains *why* directory permissions are the dominant
control (POSIX traverse permission on ancestors gates everything beneath, including files sqlite
creates natively that the application never sees at creation time) and that every operation is a
no-op on Windows, where the user profile ACL already applies.

**Improvements.**

- **F-26 (P2) — custom redaction regexes compile without a complexity guard.** `ConfigLoader`
  validates that each `secretRedactionPatterns` entry *compiles*, not that it terminates. A
  catastrophically backtracking pattern applied to a 100 KB entry body hangs the write path. Local
  config, so the threat model is self-inflicted, but a length cap on patterns plus a documented
  warning costs nothing.
- `SecureFiles` lives in `config` but is a filesystem-security concern used by `apps/*` and unrelated
  to configuration. Minor placement smell.
- `autoSaveIntervalSeconds` is loaded, validated, and **never read by anything**. Either implement it
  or remove it — a config key that does nothing is a promise the system does not keep.

**Complexity:** low. **Maintainability:** high.

---

## 3.7 `modules/skills` — orchestration

**Purpose.** Thin, logged entry points per operation.

**Public API.** Eight classes, each with one `execute` that delegates and wraps in `logged { }`.

116 lines across 7 files, zero tests. See **F-02** — this is a layer with no behaviour. `SkillLogging`
itself is good: it logs skill, project, duration, and outcome on both success and failure, and it
lives here rather than in the delivery layer so CLI and MCP callers are instrumented identically.
That single decision is the module's whole justification.

**Improvements.** Either commit to skills as composition units (see
[08-skills-specification](08-skills-specification.md), where multi-use-case skills are specified and
this module becomes their home), or collapse the layer and apply `logged { }` at the composition
root. The current state — a module that exists for a future that has not been decided — is the one
outcome to avoid.

---

## 3.8 `apps/mcp-server` — MCP delivery

**Purpose.** Composition root plus the MCP tool surface.

**Internal quality.** `McpTools.kt` is well-organised: one shared `callTool` inline function
implements deserialize → validate → execute → serialize with a three-tier catch
(`SerializationException` → "Invalid arguments", `ScpException` → domain message, `Exception` → logged
"Internal error"), and small `prop`/`arrayProp` helpers keep the JSON-Schema construction readable.
Domain failures become `isError` results rather than protocol crashes — correct MCP behaviour.

`Main.kt`'s stdout guarding is discussed in [01 §1.4](01-architecture-review.md) and is exemplary.

**Improvements.**

- **Zero tests** on 509 lines that constitute the entire contract with every AI client.
- **F-27 (P2) — `list_projects` bypasses the shared pipeline.** It hand-rolls a try/catch that
  catches only `Exception` and reports every failure as "Internal error", so an `ScpException` from
  this one tool is presented differently from the other seven. It takes no arguments, so the shared
  helper needs a no-input variant — worth adding rather than duplicating the error handling.
- **F-28 (P2) — server version hardcoded.** `Implementation(name = "scp", version = "1.0.0")` is a
  literal. Wire it from the Gradle project version so clients see the truth.
- Capability, schema, and annotation gaps: see [06-mcp-compatibility](06-mcp-compatibility.md).

---

## 3.9 `apps/cli` — CLI delivery

**Purpose.** Human and script surface; the only home of `doctor` and `init`.

**Internal quality.** `ScpCommand` centralises open → run → translate-domain-errors-to-`CliktError`,
so no command handles plumbing. `DoctorCommand` is genuinely useful: it verifies WAL, foreign keys,
FTS/table row-count agreement (**and auto-repairs via rebuild, then re-verifies**), and warns on open
sessions older than 24 h. Building a self-diagnosis command in v1 is a mark of maturity.

**Improvements.**

- **F-29 (P1) — CLI and MCP surfaces are not equivalent.** MCP exposes `save_note`; the CLI does not.
  For a project whose premise is "every client sees the same context", two delivery surfaces with
  different capability sets is a design inconsistency, and it will grow every time a tool is added to
  one and not the other. A shared operation registry (one list, two renderers) prevents the drift
  structurally.
- Tested only by the concurrency integration test; no command-level tests, none for `doctor`.
- `UpdateCommand --json` reads an arbitrary file path and deserializes it with no size bound.
  Local-trust context, but it is the one CLI input that is not length-capped the way
  `McpValidations` caps everything else.

---

## 3.10 Cross-cutting: the missing lifecycle

The single largest functional gap does not belong to one module, which is why it is easy to miss when
reading any of them.

**F-24 (P0) — nothing can ever change state after creation.**

| Port method | Implemented | Called by any use-case, tool, or command |
|---|---|---|
| `TodoRepository.updateStatus` | yes | **no** |
| `DecisionRepository.updateStatus` | yes | **no** |
| `ProjectRepository.updateDescription` | yes | **no** |
| any delete | not implemented | n/a |
| any entry edit | not implemented | n/a |

Verified by grepping every caller outside tests: zero hits in `modules/core`, `modules/skills`, and
`apps/`.

The consequences compound:

- **A todo can never be marked done.** `TodoStatus` defines `DONE` and `DROPPED`; nothing can reach
  them. `findOpenByProject` selects `open`/`in_progress`, so *every todo ever created* appears in
  every hydration and every project summary, forever.
- **A decision can never be accepted or superseded.** `DecisionStatus` defines `ACCEPTED`,
  `SUPERSEDED`, `REJECTED`; all four decisions land as `OPEN`. `findOpenByProject` returns all of
  them, so hydration shows superseded decisions as current — actively misleading for the next agent,
  which is worse than omission.
- **A project description is write-once.** It is set by `create_project` and is the first thing
  `hydrate_context` emits as `projectSummary`. It cannot be corrected or evolved.
- **Nothing can be deleted or corrected.** A wrong or poisoned entry is permanent (see
  [12-security-assessment](12-security-assessment.md)), and there is no GDPR-style erasure path.

For a system built to track "todos, implementation progress, task execution state", write-only
storage is not a missing feature — it is the feature. Everything else works; agents can record state
transitions only as *new* entries whose text describes a change that the data model already has a
column for.

*Fix (P0, roughly a day):* add `update_todo_status`, `update_decision_status`, and
`update_project_description` use-cases, MCP tools, and CLI commands. The repositories, the enum
values, the `CHECK` constraints, and the indexes all already exist. This is wiring, not design — and
the presence of the unused ports says it was planned and simply not finished.

---

## 3.11 Documentation review (deliverable 19 / step 18)

**What exists is unusually good.** Seven documents totalling ~1080 lines: architecture with Mermaid
component and dependency graphs, 16 ADRs with revisit triggers, a schema document that *is* the `.sq`
content rather than a parallel description, session resolution with pseudocode and two sequence
diagrams, hydration ranking with the scoring formula and budget algorithm, a setup guide with real
client-registration JSON, and a roadmap that names the extension point reserved for each future
feature. Code comments explain *why* (the sqlite-jdbc autocommit constraint, the ktlint exclusion
patterns, the top-level-logger hazard) rather than restating *what*.

**Gaps, in priority order:**

| Gap | Impact |
|---|---|
| **No API reference for the 8 MCP tools** — schemas exist only as Kotlin `buildJsonObject` calls | An integrator must read Kotlin to learn the interface |
| **ADR-3 is factually stale** (says xerial; tree uses the willena fork) — F-13 | A reader following the ADRs is wrong about the classpath |
| **No CONTRIBUTING / developer guide** | No documented way to add a tool, a `ContextType`, or a migration |
| **No troubleshooting guide** | `doctor` output is undocumented; the AF_UNIX note is buried in the setup guide |
| **No integration guide beyond Claude Code** | Antigravity, Cursor, Gemini CLI, Codex are named in the vision and absent from the docs — see [11-api-design-review](11-api-design-review.md) |
| **No skills or hooks guide** | Delivered by [08](08-skills-specification.md) and [10](10-hook-specification.md) |
| **No LICENSE, no CHANGELOG** | Blocks open-source distribution outright |
| **README status table stops at Phase 5** | No statement of what is *not* implemented (F-24 is invisible to a reader) |

*Recommended additions*, in order: `docs/08-mcp-api-reference.md` (generated from the tool schemas so
it cannot drift), ADR-17 for the driver swap, `CONTRIBUTING.md`, `docs/09-troubleshooting.md`,
`LICENSE`, `CHANGELOG.md`.

Next: [memory architecture](04-memory-architecture.md).
