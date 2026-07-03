# SCP Technology Decisions

ADR-lite format: **Decision / Rationale / Consequences / Revisit trigger**. These choices are pinned; substitutions must be flagged and recorded here.

---

## ADR-1 — Kotlin on the JVM, Gradle multi-module

**Decision.** Kotlin with explicit API mode (`-Xexplicit-api=strict`), Gradle Kotlin DSL, version catalog (`gradle/libs.versions.toml`) — no hardcoded dependency versions in module build files.

**Rationale.** Explicit API mode forces every public declaration to state visibility and return type, which prevents platform types (`String!`) leaking across module boundaries — the Kotlin equivalent of "strict mode, no `any`". Gradle modules make the Clean Architecture boundary (core has zero I/O deps) a compile-time property.

**Consequences.** Every module's public surface is deliberate. Adding a dependency to `core` requires editing its build file, which is exactly the friction we want.

**Revisit trigger.** None expected; language choice is foundational.

---

## ADR-2 — JVM cold-start accepted; no native-image in v1

**Decision.** Option (a) from the spec: accept JVM cold start and budget the remaining window. **Do not** build GraalVM native-image tooling in Phase 1–5.

**Rationale & budget math.** The 1 s hydration budget for a medium project (~500 entries, ~50 sessions):

| Stage | Budget |
|---|---|
| JVM cold start (JIT, class loading) | ~150–300 ms |
| Config load + SQLite connection + PRAGMAs | ~100 ms |
| Hydration queries (indexed, WAL) + ranking 500 candidates | ~300 ms (measured expectation: <20 ms — indexed SQLite point/range queries are sub-millisecond; scoring is 500 × ~10 float ops) |
| JSON serialization of a token-capped payload | ~100 ms |
| **Headroom** | **≥200 ms** |

Native-image would add a second build pipeline, reflection config for kotlinx.serialization, and platform-specific binaries — real cost against a problem we have not measured.

**Consequences.** Hydration comfortably fits 1 s on any modern machine. A cold-start regression can only come from classpath bloat, so apps keep dependencies lean.

**Revisit trigger.** A profiled hydration run (`scp hydrate --profile`, Phase 5) exceeding 800 ms end-to-end on a medium project. Then: GraalVM native-image for `apps/cli` and `apps/mcp-server` (ADR to be written at that point).

---

## ADR-3 — SQLite via `org.xerial:sqlite-jdbc`

**Decision.** `org.xerial:sqlite-jdbc` as the only database driver.

**Rationale.** The standard, actively maintained JDBC driver for SQLite on the JVM; bundles native libs for Windows/macOS/Linux; supports `PRAGMA` control and FTS5 out of the box. No server process — matches local-first.

**Consequences.** Single-file database at `storage/database/scp.db`; backup = copy file (while no writer active, or via SQLite backup API later).

**Revisit trigger.** Only if a KMP non-JVM target needs the database layer (then: SQLDelight's native driver — the `.sq` files carry over unchanged, which is part of why SQLDelight was chosen).

---

## ADR-4 — WAL mode + connection PRAGMAs (required, not optional)

**Decision.** At every connection init, in this order:

```sql
PRAGMA journal_mode = WAL;    -- persistent per DB file, set anyway (idempotent)
PRAGMA foreign_keys = ON;     -- per-connection; SQLite does NOT default to this
PRAGMA busy_timeout = 5000;   -- per-connection; wait up to 5 s before SQLITE_BUSY
PRAGMA synchronous = NORMAL;  -- safe with WAL; fsync at checkpoint, not every commit
```

**Rationale.** Multiple AI tools run separate SCP processes against the same file. WAL gives readers-don't-block-writer and writer-doesn't-block-readers across processes — this is the foundation of the concurrency story, not a tuning flag. `foreign_keys=ON` must be explicit or every FK in the schema is decorative.

**Consequences.** `scp doctor` verifies `journal_mode` is `wal` and `foreign_keys` is `1` and fails loudly if not. `-wal`/`-shm` sidecar files appear next to the DB (normal).

**Revisit trigger.** None. Disabling WAL would be a correctness regression.

---

## ADR-5 — SQLDelight; `.sqm` migrations are the only schema-evolution path

**Decision.** SQLDelight `.sq` files define schema + typed queries, compiled to Kotlin at build time. Schema changes happen **only** through SQLDelight `.sqm` migration files in `modules/database`. Hand-written `ALTER TABLE` anywhere else is prohibited.

**Rationale.** Typed queries verified at compile time against the actual schema; raw SQL supported, so `CREATE VIRTUAL TABLE … USING fts5(...)` and triggers live in the same `.sq` files as everything else — one source of truth. `verifySqlDelightMigration` runs in CI to prove old → new schema migrates.

**Consequences.** The schema document ([03](03-database-schema.md)) *is* the `.sq` content, not a parallel description that can drift.

**Revisit trigger.** None expected.

---

## ADR-6 — UUID v4 TEXT primary keys (not autoincrement)

**Decision.** Every table's PK is a UUID v4 stored as lowercase TEXT (36 chars), generated via an injectable `IdGenerator` port (`java.util.UUID.randomUUID()` in production).

**Rationale.** Autoincrement IDs collide the moment two machines (or two processes that later sync) create rows independently — a future sync/merge feature would need a breaking ID migration. UUIDs cost ~28 bytes/row extra and one index-locality tradeoff; both are irrelevant at SCP's scale (thousands of rows, not billions).

**Consequences.** `context_entry` keeps its **implicit rowid** (do not use `WITHOUT ROWID`) because the FTS5 external-content table links by rowid — see ADR-8. Short display IDs (first 8 chars) used in Markdown filenames.

**Revisit trigger.** None.

---

## ADR-7 — ISO 8601 UTC TEXT timestamps

**Decision.** All timestamps stored as ISO 8601 UTC strings produced by `kotlinx.datetime.Instant.toString()` (e.g. `2026-07-03T14:07:12.345Z`).

**Rationale.** Lexicographic order == chronological order, so `ORDER BY timestamp` and date-range `BETWEEN` filters work on plain TEXT indexes. Human-readable in any SQLite browser. Timezone-unambiguous, which a future sync protocol requires.

**Consequences.** All comparisons in SQL are string comparisons — fine because the format is fixed-width UTC. `Clock` port makes time injectable in tests.

**Revisit trigger.** None.

---

## ADR-8 — FTS5 external-content table pattern

**Decision.** Full-text search via `CREATE VIRTUAL TABLE context_entry_fts USING fts5(title, content, content='context_entry', content_rowid='rowid')`, kept in sync by AFTER INSERT/UPDATE/DELETE triggers on `context_entry`.

**Rationale.** External content stores the text once (in `context_entry`), not twice. Because the FTS table joins back to the content table by rowid, structured filters (project, type, tags, date range) compose with the full-text `MATCH` in **one SQL query with joins** — instead of being faked inside FTS query syntax.

**Consequences.** Triggers are part of the schema (in `.sq`), so the index can never drift from the table under normal operation. `scp doctor` cross-checks row counts as a corruption tripwire, and `INSERT INTO context_entry_fts(context_entry_fts) VALUES('rebuild')` is the documented repair.

**Revisit trigger.** Semantic search lands (then FTS5 stays for keyword search; vectors are additive — see ADR-6 embedding column note in [architecture §6](01-architecture.md#6-extension-points-concrete-reserved-now)).

---

## ADR-9 — Official Kotlin MCP SDK, stdio transport

**Decision.** `io.modelcontextprotocol:kotlin-sdk-server` with `StdioServerTransport` as the only v1 transport. Each AI tool launches its own `mcp-server` process.

**Rationale.** Stdio launch is how MCP clients invoke local servers; no daemon, no port management, no lifecycle service. The SDK's Ktor extensions keep HTTP/SSE available later without redesign (reserved `modules/api`).

**Consequences.** Concurrency happens **between processes**, which is why ADR-4 (WAL) and the write-path design ([04](04-session-resolution.md)) carry the safety load, not in-process locks alone.

**Revisit trigger.** A client that needs a long-lived shared server (then: Ktor SSE transport, additive).

---

## ADR-10 — Validation pipeline: kotlinx.serialization → Konform

**Decision.** MCP tool input crosses the boundary in two steps: (1) kotlinx.serialization deserializes raw JSON into a `@Serializable` DTO — this enforces *shape and types*; (2) Konform validates *constraints* on the DTO (non-blank project name, priority 1–5, tag count limits, session_id is a UUID). Outputs are validated the same way before serialization. Internal (non-boundary) shapes may use sealed classes + `require()`.

**Rationale.** Konform alone cannot parse JSON; serialization alone cannot express "priority between 1 and 5". Together: no unvalidated data crosses the MCP interface, and validation errors are returned as structured MCP tool errors, not stack traces.

**Consequences.** Every MCP tool has exactly one input DTO + one Konform `Validation<T>` living in `modules/model`, shared by server and tests.

**Revisit trigger.** None.

---

## ADR-11 — Config: kaml → `@Serializable ScpConfig`, fail fast

**Decision.** `config.yaml` parsed by kaml into the `ScpConfig` data class from the spec, then validated (paths exist or are creatable, weights ≥ 0, half-life > 0, token limit > 0). Any failure aborts startup with a precise error naming the offending key. No silent defaults for *invalid* values; *absent* optional values use the documented defaults baked into the data class.

**Rationale.** A silently-defaulted ranking weight or token limit produces wrong hydration output with no error — the worst failure mode for a memory system.

**Consequences.** Defaults are code (data-class default values), documented in [05 §5](05-hydration-ranking.md#5-configuration-defaults) — one source of truth.

**Revisit trigger.** None.

---

## ADR-12 — Manual constructor DI (no DI framework)

**Decision.** Composition roots in `apps/mcp-server` and `apps/cli` construct adapters and pass them into use-case constructors. No Koin/Dagger.

**Rationale.** ~10 ports, 2 composition roots. A DI framework adds reflection/codegen and startup cost (see ADR-2 budget) for zero benefit at this object-graph size.

**Consequences.** Wiring is a plain, readable function per app. Tests inject fakes directly.

**Revisit trigger.** Object graph grows past what a screenful of wiring code can express (unlikely).

---

## ADR-13 — Logging: kotlin-logging + Logback, JSON, daily files

**Decision.** kotlin-logging (slf4j facade) with Logback; JSON encoder; rolling policy producing `storage/logs/scp-YYYY-MM-DD.jsonl`. Every MCP tool call logs `{tool, project, session, durationMs, outcome}`.

**Rationale.** When two AI tools interleave writes, a greppable structured log is the debugging surface. **Logs must go to files/stderr only — never stdout**, because stdout *is* the MCP stdio transport; a stray log line corrupts the protocol stream.

**Consequences.** Logback config ships in `apps/*` resources with stdout appenders forbidden by convention and checked in review.

**Revisit trigger.** None.

---

## ADR-14 — Testing: JUnit5 + MockK + kotlin-test; lint: ktlint + detekt

**Decision.** Unit tests for `core` (pure functions — ranking, redaction, session policy — no DB needed). Integration tests for `database`/`search` against a real temp-file SQLite in WAL mode, **including the mandated concurrent-write test** (two clients, same project, same second). ktlint for formatting **and** detekt for static analysis (the spec offered the pairing as optional; taking it — detekt catches complexity and correctness smells ktlint cannot).

**Consequences.** CI gate: `ktlintCheck`, `detekt`, `test`, `verifySqlDelightMigration`.

**Revisit trigger.** None.

---

## ADR-15 — `skills/` as a Gradle module

**Decision (deviation from spec, flagged).** The spec placed `skills/` at the repo root as loose `.kt` files (`UpdateContext.kt`, …). Loose Kotlin files outside a module cannot compile, be linted, or be tested, so skills live in `modules/skills` — same files, same thin-orchestration role, now build-enforced to depend only on `core` + `model`.

**Consequences.** Skills get the same quality gates as everything else.

---

## ADR-16 — Session resolution: tool_name must match to reuse (user-approved refinement)

**Decision (refinement of spec rule 2, approved by project owner 2026-07-03).** A single open session is reused **only if its `tool_name` matches the calling tool**; otherwise a new session is created.

**Rationale.** Spec rule 3's own rationale — "silently merging two tools' work into one session is a correctness bug" — applies equally when only one session is open. Without this rule, Antigravity's work lands in a session labeled Claude Code.

**Consequences.** See [session resolution](04-session-resolution.md) for the full algorithm and race handling.
