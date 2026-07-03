# Session Resolution & Concurrency

Deterministically answers: *"which Session row does this write belong to?"* — including when two AI tools hit the same project in the same second.

## 1. Rules

Given a write for project `P` from tool `T` (e.g. `claude-code`, `antigravity`):

1. **Explicit wins.** If the caller supplies `session_id`, validate it exists and belongs to `P` (error if not — never silently fall through), then use it. This works even on a closed session (a tool may append a late note).
2. **Reuse only your own.** If exactly one `open` session exists for `P` **and** its `tool_name == T`, reuse it.
3. **Otherwise create.** Zero open sessions, more than one open session, or a single open session belonging to a *different* tool → create a new session for `T`. Two tools' work must never silently merge into one session — that's a correctness bug, not a convenience.
4. **`update_context` closes.** After a successful `update_context`, the session it operated on is closed (`status='closed'`, `end_time=now`) unless the caller passed `keep_open=true`.

> Rule 2's tool-name condition is a user-approved refinement of the original spec ([ADR-16](02-technology-decisions.md#adr-16--session-resolution-tool_name-must-match-to-reuse-user-approved-refinement)): the original rule 2 reused the single open session unconditionally, which would let Antigravity's work land inside a session labeled Claude Code — exactly the merge rule 3 exists to prevent.

## 2. Pseudocode

Lives in `modules/core` as pure policy; the atomic execution wrapper is the `TransactionRunner` port.

```kotlin
fun resolveSession(projectId: String, toolName: String, explicitSessionId: String?): Session {
    if (explicitSessionId != null) {
        val s = sessions.findById(explicitSessionId)
            ?: fail("session $explicitSessionId not found")
        require(s.projectId == projectId) { "session belongs to a different project" }
        return s
    }

    // Atomic check-then-act: BEGIN IMMEDIATE takes the write lock up front,
    // so no second process can run this block between the SELECT and the INSERT.
    return transactionRunner.inWriteTransaction {
        val open = sessions.findOpen(projectId)                  // uses idx_session_project_status
        val mine = open.singleOrNull()?.takeIf { it.toolName == toolName }
        mine ?: sessions.create(
            Session(
                id = idGenerator.newId(),                        // UUID v4
                projectId = projectId,
                toolName = toolName,
                startTime = clock.now(),
                status = OPEN,
            ),
        )
    }
}
```

Decision table:

| Open sessions for P | tool_name match | Result |
|---|---|---|
| 0 | — | create new |
| 1 | yes | **reuse** |
| 1 | no | create new (never merge) |
| ≥2 | — | create new (never guess) |

## 3. Why this is race-safe

Three layers, each covering a different scope:

| Mechanism | Scope | What it prevents |
|---|---|---|
| WAL mode | cross-process | readers and the writer proceeding concurrently without corruption |
| `BEGIN IMMEDIATE` transaction | cross-process | two processes both passing the "exactly one open?" check and both appending to the same session — the write lock is taken *before* the SELECT, so resolve-and-create is atomic |
| JVM `Mutex` per DB file + retry-with-backoff (max 3) on `SQLITE_BUSY` | in-process / cross-process | in-process: coroutine write interleaving; cross-process: the second writer's `BEGIN IMMEDIATE` gets `SQLITE_BUSY` after `busy_timeout` (5 s) and retries with backoff before surfacing an error |

SQLite allows exactly one writer at a time even in WAL — that is not a limitation here, it's the serialization point the algorithm leans on.

## 4. Sequence diagram — session resolution flow

```mermaid
sequenceDiagram
    participant T as AI tool (MCP client)
    participant M as mcp-server (update_context)
    participant C as core: resolveSession
    participant D as SQLite (WAL)

    T->>M: update_context(project, entries, session_id?)
    M->>M: deserialize + Konform validate
    M->>C: resolve(projectId, toolName, sessionId?)
    alt session_id supplied
        C->>D: SELECT session WHERE id = ?
        D-->>C: row (validate project ownership)
    else no session_id
        C->>D: BEGIN IMMEDIATE
        C->>D: SELECT * FROM session WHERE project_id=? AND status='open'
        alt exactly 1 open AND tool_name matches
            D-->>C: reuse that session
        else 0, ≥2, or different tool
            C->>D: INSERT new session (UUID, open, now)
        end
        C->>D: ... entry writes in same transaction ...
        C->>D: COMMIT
    end
    C->>D: close session (status='closed', end_time) unless keep_open
    C-->>M: session
    M-->>T: result {sessionId, entriesWritten, markdownPath}
```

## 5. Sequence diagram — concurrent-write scenario

Two tools, same project, same second. Claude Code has an open session; Antigravity arrives concurrently. Expected outcome (and the Phase 5 integration-test assertion): **two session rows, both tools' entries fully persisted, nothing merged, nothing lost.**

```mermaid
sequenceDiagram
    participant A as Claude Code process
    participant B as Antigravity process
    participant D as SQLite file (WAL)

    par A writes
        A->>D: BEGIN IMMEDIATE  (write lock acquired)
        A->>D: SELECT open sessions → [S1 (claude-code)]
        Note over A,D: tool matches → reuse S1
        A->>D: INSERT context entries into S1
        A->>D: COMMIT (lock released)
    and B writes (same second)
        B->>D: BEGIN IMMEDIATE
        Note over B,D: lock held by A → busy_timeout wait,<br/>then retry w/ backoff (max 3) on SQLITE_BUSY
        B->>D: BEGIN IMMEDIATE (succeeds after A commits)
        B->>D: SELECT open sessions → [S1 (claude-code)]
        Note over B,D: tool mismatch → never merge
        B->>D: INSERT new session S2 (antigravity)
        B->>D: INSERT context entries into S2
        B->>D: COMMIT
    end

    Note over A,B: Result: S1 has A's entries, S2 has B's entries.<br/>No data loss, no cross-tool merge, FKs intact.
```

Readers (a third tool running `hydrate_context` mid-write) are unaffected throughout: WAL readers see the last committed snapshot and never block on the writer.

## 6. Abandoned sessions

A tool that crashes without calling `update_context` leaves an open session. That is deliberate — SCP never guesses that work is finished. `scp doctor` reports open sessions older than 24 h; the human (or the tool's next `update_context` with an explicit `session_id`) closes them. Future auto-archival is a separate feature with explicit semantics, per the spec's note on compaction.
