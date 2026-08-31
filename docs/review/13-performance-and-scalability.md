# 13. Performance & Scalability Assessment

Deliverables 15 and 16 — review steps 15 and 13.

## 13.1 The measured baseline

The only measurement in the project is recorded in
[docs/02 ADR-2](../02-technology-decisions.md): `scp hydrate --project medium --tag m3` against a
seeded fixture of **50 sessions / 503 entries**, on Windows 11 with Temurin 21, launched through the
`installDist` `.bat` script:

> **884–950 ms** over three runs — inside the 1 s budget, past the 800 ms revisit threshold.

The ADR attributes part of that to `cmd.exe` and launcher-script overhead and classifies it as a watch
item. Both the honesty and the caveat are fair. What follows decomposes it.

### Where the time goes

| Stage | ADR-2 budget | Realistic | Basis |
|---|---:|---:|---|
| JVM cold start (class loading, JIT) | 150–300 ms | **500–700 ms** | Dominant. Kotlin stdlib + serialization + SQLDelight + Clikt + logback on a cold classpath |
| Launcher script (`cmd.exe` → `java`) | not budgeted | 50–150 ms | Windows-specific, and it is in the measurement |
| Config load + validation | part of 100 ms | 5–15 ms | One small YAML file |
| SQLite open + PRAGMAs + schema check | part of 100 ms | 20–50 ms | Native library load dominates |
| Hydration queries | 300 ms | **<20 ms** | Six indexed queries over 503 rows. ADR-2's own estimate says this |
| Ranking (~500 candidates) | included above | **<5 ms** | ~10 float ops per item |
| JSON serialisation | 100 ms | 10–30 ms | A token-capped payload is small |

**The conclusion the number supports: the application is fast and the runtime is slow.** Actual work
is well under 100 ms; roughly 85% of wall time is JVM and launcher startup. That reframes the problem
usefully — no amount of query optimisation moves this number.

**F-57 (P2) — the measurement conflates the two surfaces.** It was taken on the CLI, where cold start
is paid per invocation. The MCP server pays it **once per client session**, and every subsequent tool
call runs on a warm JVM. ADR-2 says this, and it is the more important half of the finding:

| Surface | Cold start | Effective per-call latency |
|---|---|---|
| MCP server | once per session | **~20–60 ms** (queries + ranking + serialisation, warm) |
| CLI | every invocation | ~900 ms |

For the primary product surface, hydration is roughly **an order of magnitude inside budget**. The
900 ms figure is a CLI-and-hooks problem, not a product problem — and it should be recorded that way
so it is not mistaken for a system-wide latency ceiling.

### Cold start: what to do

The revisit trigger has fired, so a response is owed. In cost order:

1. **Measure the JVM alone.** `java -cp … com.scp.cli.MainKt` directly, excluding `cmd.exe` and the
   launcher. Until that number exists, 884 ms is a measurement of the launcher plus the JVM plus the
   work, and only one third of that is actionable.
2. **AppCDS** (`-XX:ArchiveClassesAtExit` then `-XX:SharedArchiveFile`). Typically removes 30–40% of
   class-loading time for a fixed classpath. One build step, no code change, no second toolchain. ADR-2
   already names it as the cheap first step — agreed.
3. **`-XX:TieredStopAtLevel=1`** for the CLI. Short-lived JVMs never benefit from C2; skipping it
   reduces JIT overhead.
4. **Trim the CLI classpath.** It pulls logback and serialization for a process that typically prints
   one line.
5. **GraalVM native-image** only if 1–4 leave the CLI above ~300 ms. It adds a second build pipeline
   and reflection configuration for kotlinx.serialization — real cost, and the ADR is right to hold it
   in reserve.

Target: **CLI under 300 ms**, which makes git and build hooks ([10](10-hook-specification.md)) viable.
That is the concrete goal, and it is a hook-enablement requirement (**F-44**) more than a UX one.

---

## 13.2 Query and index analysis

Indexes are present and match the access patterns — this is done properly:

| Index | Serves |
|---|---|
| `idx_session_project_status` | `findOpenByProject` — session resolution's hot query, on every write |
| `idx_session_start_time` | `listRecent`, `listChronological` |
| `idx_context_entry_session` | `findBySession` |
| `idx_context_entry_timestamp` | recency ordering |
| `idx_context_entry_type` | `findRecentByType` — hydration's bug/prompt fetch |
| `idx_decision_project_status`, `idx_todo_project_status` | open-item queries |
| `idx_file_project_updated` | `findRecentlyModified` |
| `idx_context_entry_tag_tag` | tag filtering in search |

**F-58 (P2) — `findRecentByType` has no covering composite index.** The query filters
`s.project_id = ? AND ce.type = ?` and orders by `ce.timestamp DESC`. There are separate indexes on
`type` and on `timestamp`, and SQLite will use one and sort or scan for the rest. A composite
`(session_id, type, timestamp DESC)` — or, better, denormalising `project_id` onto `context_entry` and
indexing `(project_id, type, timestamp DESC)` — makes hydration's two hottest reads index-only.

Irrelevant at 500 entries. It is the first thing that matters at 50,000, and it is worth measuring
before assuming.

### N+1 patterns

Three, of increasing severity:

| Location | Pattern | Cost |
|---|---|---|
| `ListProjectsUseCase.kt:21` | `countByProject` per project | N+1, small N |
| `TimelineUseCase.kt:28` | `findBySession` per session | N+1, **and each call runs its own tag batch query** |
| `UpdateContextUseCase.kt:77` | full session re-read per update | O(n²) across a `keepOpen` session (**F-05**) |

`timeline` on a 50-session project is 1 + 50 + 50 = 101 queries. On a 500-session project it is 1001,
and each returns unbounded rows. Both are fixable with a single grouped query — the codebase already
demonstrates the technique in `SqlContextEntryRepository.withTags`, which batch-loads tags with one
`IN` query specifically to avoid this. The pattern was understood; it was applied in one place and not
the others.

**F-20 (repeated, P2)** — `tagsForEntries` binds one parameter per id. `listChronological` passes
every entry in the project, so past SQLite's variable limit it *throws* rather than degrading. Chunk it.

### Search

FTS5 external content with bm25 — correct, and it scales well: search cost tracks matched documents,
not table size. Structured filters compose in the same query, so there is no fetch-then-filter waste.

Two performance-relevant gaps carried from [02](02-technology-stack.md): no prefix index (F-16), and
English-only stemming (F-15). And the correctness one that also wastes work — **F-10**: bm25 is
computed by SQLite, carried through the adapter, and discarded by the ranking function. The database
does relevance work whose result is thrown away.

---

## 13.3 Memory and disk

**Memory.** Every query materialises to a `List` — `executeAsList()` throughout, no streaming, no
cursors. Fine for bounded reads; `timeline` and `listChronological` are unbounded and load the entire
project history into heap plus its JSON serialisation. A large project can OOM a default-heap JVM on
`timeline`, and nothing bounds it.

**Disk.** Compact and predictable:

| Data | Size |
|---|---|
| Context entry | ~200 B–5 KB (dominated by `content`, capped at 100 KB) |
| FTS index | ~30–50% of indexed text (external content stores text once — the pattern's main win) |
| Markdown mirror | roughly duplicates entry text on disk |
| 10k entries, ~1 KB each | ~10 MB DB + ~5 MB FTS + ~10 MB Markdown ≈ **25 MB** |

Growth is linear and unbounded — nothing expires, nothing compacts. At a realistic capture rate this
is years away from mattering, which is why the roadmap defers compaction, and that is the right call.

**F-59 (P2) — no WAL checkpoint or vacuum management.** WAL grows until checkpointed. SQLite
auto-checkpoints at ~1000 pages by default, but with many short-lived processes (the CLI, and one
server per client) checkpoint timing is unpredictable, and a `-wal` file can outgrow the database.
Nothing runs `wal_checkpoint(TRUNCATE)` or `VACUUM`, and `doctor` does not report WAL size. Add both —
a `doctor` warning and an explicit `scp compact`.

---

## 13.4 Caching

**There is none, anywhere.** No query cache, no prepared-statement reuse beyond what SQLDelight does
internally, no memoisation of hydration payloads, no config cache across CLI invocations.

For the CLI this is inherent — a fresh process has nothing to cache. For the **MCP server**, which is
long-lived and where repeated `hydrate_context` calls with identical arguments are the expected access
pattern, it is a genuine gap. Two cheap wins:

- **Hydration payload cache** keyed by `(projectId, tags, tokenLimit)`, invalidated by
  `project.updated_at`. That column is already maintained on every write (`projects.touch`), so the
  invalidation key exists.
- **Compiled redaction patterns** are compiled once at startup via `Redaction.compile` — already done
  correctly.

Measure first. At ~20–60 ms warm, hydration may simply not need a cache, and an unnecessary cache is a
correctness risk for no gain.

---

## 13.5 Scalability: 2 → 50 agents

The question is not throughput. SQLite handles thousands of writes per second and agents write a few
times per hour. The question is **whether the coordination model holds**.

| Agents | Writes/hour (est.) | Contention | Verdict |
|---|---:|---|---|
| **2** | ~20 | Negligible | ✅ Works. Proven by `ConcurrentUpdateIntegrationTest` |
| **5** | ~50 | Rare | ⚠️ Works mechanically; **session fragmentation begins** (F-11/F-33) |
| **10** | ~100 | Occasional | ⚠️ Fragmentation is now the dominant problem |
| **20** | ~200 | Frequent | ❌ Hydration quality collapses; duplicate work is routine |
| **50** | ~500 | Constant | ❌ Not viable without coordination primitives |

**Crucially, the failure mode is not performance.** SQLite at 500 writes/hour is idle. Each write is a
short transaction, WAL keeps readers unblocked, `busy_timeout` is 5 s and backoff retries three times.
At 50 agents, contention is still measured in milliseconds. **The storage layer scales to 50 agents
without modification.**

What breaks is the *semantics*:

1. **Session fragmentation (F-11/F-33).** `open.singleOrNull()` means that once two agents hold open
   sessions, no agent can reuse its own. Every `save_note` creates a session. At 20 agents,
   `listRecent(5)` in hydration returns five one-note fragments instead of five work periods. The
   resume payload becomes useless exactly when multi-agent coordination makes it most valuable.
   *One-line fix; see [01](01-architecture-review.md) F-11.*
2. **Duplicate work (F-31).** No claims, no leases, no ownership. At 20 agents reading the same open
   todos, duplicated effort is the expected outcome, not the exception.
3. **No change awareness (F-32).** Every agent's view is a snapshot from its last hydration. At 50
   agents the average staleness of every agent's context approaches the mean session length.
4. **Hydration noise.** Every agent's entries compete for one token budget. At 20 agents the recency
   term saturates — everything is recent — and ranking degenerates toward priority and type alone.
5. **Todo list growth (F-24).** Nothing can be closed, so the open list is cumulative across all
   agents forever.

**Assessment: SCP's *storage* scales to 50 agents today; its *protocol* scales to about 3.** The
findings are all in [05](05-multi-agent-collaboration.md), and none requires a storage redesign.

### The transport ceiling

One process per client means N JVMs, N SQLite connections, N cold starts, and no cross-process change
feed. At 20+ agents on one machine that is real overhead and it is the concrete argument for the
shared-server mode in [07](07-mcp-integration-blueprint.md) §7.4 — one process, one connection pool,
an in-process notification bus, and real subscriptions instead of polling.

---

## 13.6 Locking, transactions, consistency

| Property | Mechanism | Assessment |
|---|---|---|
| Write serialisation | `BEGIN IMMEDIATE` + JVM lock per DB path | ✅ Correct |
| Cross-process atomicity | `BEGIN IMMEDIATE` before the SELECT | ✅ Correct — makes check-then-act atomic |
| Reader isolation | WAL snapshot | ✅ Correct, and tested |
| Busy handling | 5 s `busy_timeout` + 3 retries (50/150/400 ms) | ✅ Adequate |
| Lost updates | Impossible — insert-only | ✅ **Because nothing updates** |
| Deadlock | Impossible — one lock, no ordering | ✅ |
| Durability | WAL + `synchronous = NORMAL` | ✅ Correct with WAL |

**F-60 (P1) — the consistency story depends on immutability, and immutability is about to end.** Every
guarantee above holds because rows are never updated. When F-24 adds status transitions and two agents
both set `todo.status`, SCP has a lost-update problem and **no mechanism**: no version column, no
`updated_at` on todos, no compare-and-set, no documented conflict policy.

*Fix, to be done in the same change as F-24, not after:* add `version INTEGER` and `updated_at` to any
entity that becomes mutable; make transitions `UPDATE … WHERE id = ? AND version = ?`; return a
conflict error the caller can act on. Retrofitting this later is a migration; doing it alongside is
free.

**F-12 (repeated, P2)** — retry backoff sleeps while holding the JVM lock, blocking other in-process
writers. Irrelevant today; matters if the server ever serves concurrent tool calls in one process.

---

## 13.7 Benchmark plan

There is no benchmark harness — one manual `Measure-Command` run, recorded in an ADR. Given that ADR-2
sets a numeric budget with a numeric revisit trigger, the budget should be enforced by something other
than memory.

**Recommended: a `benchmarks` source set with seeded fixtures at three scales.**

| Fixture | Sessions | Entries | Represents |
|---|---:|---:|---|
| `small` | 5 | 50 | A new project |
| `medium` | 50 | 500 | ADR-2's baseline — keep it for comparability |
| `large` | 500 | 10,000 | Two years of active use |
| `huge` | 2,000 | 100,000 | Stress; finds the F-20 parameter limit and the `timeline` OOM |

**Metrics to track, per fixture:**

| Metric | Why | Target |
|---|---|---|
| `hydrate` end-to-end, CLI | ADR-2's stated budget | < 1 s (`medium`) |
| `hydrate` warm, in-process | The real product latency | < 100 ms (`medium`) |
| **JVM cold start alone** | Isolates the actual problem | measure, then < 300 ms |
| `search` p50/p99 | Query scaling | < 50 ms warm |
| `update_context`, 10 entries | Write path | < 100 ms warm |
| `timeline` full | The unbounded path — expect failure at `huge` | document the ceiling |
| Peak heap on `timeline` | OOM risk | bounded |
| Concurrent writers 2/5/10/20 | Contention + fragmentation | zero loss; **assert session count** |
| DB + WAL + Markdown size | Growth | linear |

**Two things this harness should do that a benchmark usually does not:**

- **Assert semantic properties, not just timing.** The 10- and 20-writer runs should assert *how many
  sessions were created*. That turns F-11/F-33 from an argument into a failing test, and it is the
  cheapest way to keep the multi-agent story honest as the code changes.
- **Run in CI on the `medium` fixture** and fail on regression past the ADR-2 budget. Once CI exists
  (**F-18**), the revisit trigger enforces itself instead of depending on someone re-running a manual
  command.

---

## 13.8 Summary

**Performance: good, with one honest caveat and one real constraint.**

- The application is fast: sub-100 ms of actual work for a medium project.
- The MCP server — the primary surface — is roughly 10× inside its budget once warm.
- The CLI is slow because the JVM is slow to start, and that is a solvable, well-understood problem
  with a cheap first step (AppCDS) already named in the ADR.
- The CLI number is a **hook-enablement blocker** (F-44) more than a user-experience problem.

**Scalability: storage scales far past the protocol.**

- SQLite, WAL, and the transaction design handle 50 agents without modification.
- The coordination model degrades from about 3 agents — session fragmentation, duplicate work, no
  change awareness.
- Every limiting finding is additive to fix. None requires a storage redesign.

**The three highest-value performance and scalability changes**, in order:

1. **Fix `SessionResolver` (F-11).** One line. Unblocks the entire multi-agent scaling story.
2. **Add the benchmark harness with semantic assertions.** Makes ADR-2's budget and the concurrency
   claims self-enforcing.
3. **AppCDS for the CLI.** Unblocks hooks, which unblock automatic capture, which is what makes SCP
   work at all.

Next: [roadmap & production readiness](14-roadmap-and-production-readiness.md).
