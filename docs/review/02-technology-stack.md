# 2. Technology Stack Analysis

Deliverable 3. Every pinned choice, whether it holds up under review, and what it costs.

The stack is defined once in `gradle/libs.versions.toml` with no hardcoded versions in module build
files, and each choice has an ADR in [docs/02](../02-technology-decisions.md) carrying rationale,
consequences, **and a revisit trigger**. That last field is unusual and valuable — most ADR sets
record what was decided but not what would change the answer.

## 2.1 Verdicts

| Layer | Choice | ADR | Verdict |
|---|---|---|---|
| Language | Kotlin 2.3.21, JVM 21, explicit API strict | 1 | **Right.** Explicit API mode is the highest-value low-cost decision here |
| Build | Gradle KTS multi-module + version catalog | 1 | **Right.** Module graph is the architecture enforcement mechanism |
| Runtime | JVM, no native-image | 2 | **Right, but the revisit trigger has fired** — see §2.3 |
| Storage | SQLite via `io.github.willena:sqlite-jdbc` 3.49.1.0 | 3 | **Right**, with a supply-chain caveat — see §2.4 |
| DB access | SQLDelight 2.3.2, `.sq` schema + typed queries | 5 | **Right.** Compile-time query verification is worth more than an ORM here |
| Concurrency | WAL + `BEGIN IMMEDIATE` + busy retry | 4 | **Right.** Correct at the level it claims |
| Search | FTS5 external-content + triggers | 8 | **Right.** See §2.5 for the tokenizer gap |
| IDs | UUID v4 as TEXT | 6 | **Right.** Buys future sync at negligible cost |
| Time | ISO 8601 UTC TEXT | 7 | **Right.** Lexicographic = chronological is the whole trick |
| Serialization | kotlinx.serialization | 10 | **Right** |
| Validation | Konform 0.11.1 | 10 | **Right**, but living in the wrong module (F-01) |
| Config | kaml 0.83.0, fail-fast | 11 | **Right.** Silent defaults for *invalid* values would be the worst failure mode |
| DI | manual constructor wiring | 12 | **Right in principle, duplicated in practice** (F-03) |
| MCP | `io.modelcontextprotocol:kotlin-sdk-server` 0.14.0, stdio | 9 | **Right**, under-used — see [06-mcp-compatibility](06-mcp-compatibility.md) |
| CLI | Clikt 5.0.3 | — | **Right** |
| Logging | kotlin-logging + Logback, JSONL, never stdout | 13 | **Right.** The stdout prohibition is load-bearing |
| Test | JUnit 5 + kotlin-test, MockK declared | 14 | **Right**, coverage gaps in §2.7 |
| Lint | ktlint + detekt on every module | 14 | **Right** |

Nothing in the stack is a mistake. What follows are the four places where the choice is right but the
consequences deserve more attention than they currently get.

## 2.2 Explicit API mode is doing real work

`kotlin { explicitApi() }` appears in all nine module build files. This forces every public
declaration to state visibility and return type, which prevents Kotlin platform types (`String!`)
from leaking across module boundaries. In a codebase where `modules/model` is a pure contract layer
consumed by six other modules, that is the difference between a contract and a suggestion.

The cost is visible in the source — `public` on nearly every declaration, `internal` used
deliberately (`Briefs.kt`, `SkillLogging.kt`) — and it is a cost worth paying. Keep it.

## 2.3 The JVM cold-start decision has fired its own revisit trigger

ADR-2 accepted JVM cold start with a documented budget and set an explicit revisit trigger: *"A
profiled hydration run exceeding 800 ms end-to-end on a medium project."*

The ADR then records the Phase 5 measurement: **884–950 ms** across three runs on a 50-session /
503-entry fixture. The document notes this is inside the 1 s budget but past the 800 ms threshold,
attributes part of it to `cmd.exe` + launcher-script overhead, and classifies it as a watch item.

That is intellectually honest and I want to credit it explicitly — projects usually move the
threshold rather than record the breach. But the ADR's own decision rule says the trigger fired, and
the recommended response is not "watch":

- The **MCP server path is genuinely unaffected**, as the ADR says. It is a long-lived process; cold
  start is paid once per client session, not per tool call. For the primary product surface, this is
  a non-issue and the ADR is correct to say so.
- The **CLI path pays it per invocation**, and the CLI is what a human uses interactively and what
  hooks ([10-hook-specification](10-hook-specification.md)) would invoke on every commit, build, and
  file change. A hook firing `scp save-note` on every file save at ~900 ms each is not viable.

*Recommendation:* before native-image, take the cheap wins the ADR itself names — AppCDS
(`-XX:SharedArchiveFile`) typically removes 30–40% of class-loading time for a fixed classpath, and
`-XX:TieredStopAtLevel=1 -Xshare:auto` helps short-lived JVMs. Measure the CLI path specifically,
excluding launcher overhead, so the number reflects the JVM rather than `cmd.exe`. See
[13-performance-and-scalability](13-performance-and-scalability.md) §Cold start.

## 2.4 The driver swap deserves an ADR of its own

ADR-3 says the decision is `org.xerial:sqlite-jdbc`. The working tree says otherwise:

```toml
# Encryption-capable drop-in fork of org.xerial:sqlite-jdbc (SQLite3MultipleCiphers /
# SQLCipher). Same version line as xerial; adds transparent at-rest DB encryption.
sqlite-jdbc = { module = "io.github.willena:sqlite-jdbc", version.ref = "sqlite-jdbc" }
```

with a matching exclusion in `modules/database/build.gradle.kts` so only one jar provides
`org.sqlite.*` — a correct and well-commented piece of dependency hygiene, since two jars shipping the
same packages makes class loading non-deterministic.

**F-13 (P1) — the driver change is undocumented as a decision.** `io.github.willena:sqlite-jdbc` is a
community fork, not the upstream driver. It bundles native SQLite3MultipleCiphers binaries for every
supported platform. Swapping the database driver for a third-party fork is exactly the kind of choice
the ADR process exists to record, and ADR-3 still names xerial as "the only database driver". A
reader following the ADRs will be wrong about what is on the classpath.

The substantive questions an ADR should answer:

- **Maintenance and patch latency.** When SQLite or xerial ships a security fix, how long until the
  fork rebases? What is the plan if the fork is abandoned?
- **Binary provenance.** The fork ships native `.so`/`.dll`/`.dylib` binaries executed in-process.
  What verifies them? (Checksum pinning via Gradle dependency verification is the standard answer and
  costs one `gradle/verification-metadata.xml`.)
- **The exit path.** Encryption is opt-in via `SCP_DB_KEY`. If the fork must be dropped, does the
  project fall back to xerial and lose encryption, or is there an alternative?

None of this says the choice is wrong — transparent whole-file encryption with no application-level
changes, so FTS5 and ranking work unmodified, is a genuinely elegant result. It says the choice is
significant and currently invisible. Write ADR-17.

**F-14 (P2) — encryption and human-readability are mutually exclusive.** With `SCP_DB_KEY` set, both
composition roots substitute `NoOpMarkdownStore`, so no Markdown mirror is written at all. The
reasoning is sound (a plaintext mirror leaks exactly what the encrypted DB protects) and it is
documented in [docs/06](../06-setup-guide.md). But design principle 4 is "Human readable — everything
mirrored to plain Markdown", and enabling principle-adjacent security silently disables it.

The middle path worth considering: encrypt the mirror too, or write it to a location the user
designates as already-protected, or make it an explicit three-way config (`mirror: plaintext |
encrypted | off`) so the user chooses rather than discovering the interaction. Also note
`UpdateContextResult.markdownPath` returns `""` in this mode, which every caller must handle and none
currently does.

## 2.5 Search: the right pattern, an English-only tokenizer

The FTS5 external-content design (ADR-8) is the correct choice and the reasoning in the ADR is
exactly right: storing text once and joining back by rowid is what lets structured filters compose
with `MATCH` in one SQL query instead of being faked inside FTS query syntax. The triggers make drift
impossible under normal operation, and `scp doctor` cross-checks row counts as a tripwire with
`rebuild` as the documented repair. This is a complete story.

Two gaps:

**F-15 (P2) — `tokenize='porter unicode61'` applies English stemming to all content.** The Porter
stemmer is English-specific. Code identifiers, non-English comments, and non-English project notes
are stemmed by English rules, which produces both false positives and misses. For a tool whose
content is largely code and technical English this is mostly fine, but it is an assumption worth
recording. `unicode61` alone, or `trigram` for substring/identifier search, are the alternatives.

**F-16 (P2) — no prefix index means no prefix search.** The virtual table declares no
`prefix='2 3'` option, so `MATCH 'payment*'` requires a full index scan. Given that `SqlSearchIndex`
quotes every token into a phrase, prefix search is not exposed anyway — the sanitiser makes `foo*`
into `"foo*"`, a literal. That is a safe default; it also means users cannot do the one thing they
most expect from a search box. Adding `prefix='2 3'` costs index size; exposing it needs an explicit
opt-in in `SearchContextInput` so the sanitiser stays safe by default.

## 2.6 Validation pipeline

Two stages, correctly separated: kotlinx.serialization enforces shape and type, Konform enforces
constraints. The ADR-10 rationale is exact — "Konform alone cannot parse JSON; serialization alone
cannot express priority between 1 and 5."

`McpValidations` is thorough: length caps on every string (`MAX_CONTENT = 100_000`), batch caps
(`MAX_BATCH = 200`), tag count and length caps, UUID pattern on `sessionId`, priority range. Nothing
unbounded crosses the boundary. This is better than most production APIs.

**F-17 (P2) — `checkValid` concatenates all violations into one string.** `McpValidations.kt`'s
`checkValid` joins errors with `"; "` into an `InvalidInputException` message, which the MCP layer
returns as `TextContent`. Structurally, MCP clients and agents do better with machine-readable error
detail (which field, which constraint). Low cost to improve when structured errors land — see
[07-mcp-integration-blueprint](07-mcp-integration-blueprint.md) §Errors.

## 2.7 Test and quality tooling

~1800 lines of test across nine files. The distribution is sensible: pure-function tests for
`Scoring`, `Redaction`, `SessionResolver`; fake-port tests for the two significant use-cases; real
temp-file SQLite integration tests for repositories, FTS sync, transactions, and encryption; a
two-client concurrency test at the app level.

**F-18 (P1) — no CI workflow exists.** ADR-14 declares the gate: `ktlintCheck`, `detekt`, `test`,
`verifySqlDelightMigration`. There is no `.github/workflows/` directory, no `.gitlab-ci.yml`, no CI
configuration of any kind in the repository. The gate is currently "the author remembers to run
`gradlew build`". `verifyMigrations.set(true)` is configured in the SQLDelight block and the schema
snapshot is committed as source — the machinery is all there and nothing runs it.

*Fix:* one workflow file, `ubuntu-latest` + `windows-latest` matrix (the project's primary platform is
Windows and the AF_UNIX temp-dir issue in [docs/06](../06-setup-guide.md) is Windows-specific, so it
must be in the matrix), JDK 21, `./gradlew build`. Half an hour of work closing the largest gap
between the project's stated standards and its enforced ones.

**F-19 (P2) — MockK is declared but never used.** `libs.mockk` is in the version catalog and in no
module's dependency list. The hand-written fakes in `FakePorts.kt` are better than mocks for this
codebase — they are deterministic and they exercise real collection behaviour. Either drop the
catalog entry or note why it is reserved.

**Coverage gaps**, ordered by risk:

| Untested | Risk |
|---|---|
| `McpTools` — tool registration, schema shape, error mapping | The entire external contract. A schema regression is invisible until a client breaks |
| Search ranking behaviour | F-10 would have been caught by one test asserting a verbatim match ranks first |
| `HydrateContextUseCase` budget edges (over-limit section 1, exact-fit, empty project) | Truncation correctness is a stated principle |
| `DoctorCommand` | The health-check tool is itself unverified |
| `TimelineUseCase` | Unbounded output path |
| Redaction false negatives | Regexes are asserted to match; nothing asserts what they miss |
| Three-agent session resolution | Exactly where F-11 lives |

## 2.8 Dependency footprint

Lean, which directly serves the ADR-2 cold-start budget. `apps/mcp-server` pulls the MCP SDK,
coroutines, serialization, logging; `apps/cli` pulls Clikt instead of the SDK. No reflection-heavy
frameworks, no DI container, no ORM, no HTTP server in the default path. This is the correct posture
for a tool that starts a fresh JVM per CLI invocation.

Versions were verified against Maven Central on 2026-07-04 per the catalog comment. At review time
(2026-08-05) that is a month old — fine, but worth a recurring check, and Dependabot/Renovate is the
zero-effort answer once CI exists (F-18).

Next: [module analysis](03-module-analysis.md).
