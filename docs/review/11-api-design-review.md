# 11. API Design Review & Interoperability

Deliverable 13, covering the API critique and review step 12 (interoperability).

## Part A — API design

SCP has four public surfaces. Two are external contracts (MCP tools, CLI), one is internal but
architecturally load-bearing (ports), one is the data contract (schema).

### A.1 The MCP tool API

| Tool | Args | Returns | Read/write |
|---|---|---|---|
| `create_project` | name, description | projectId, name | write |
| `update_context` | projectName, toolName, sessionId?, summary, keepOpen, tokenUsage?, entries[], decisions[], todos[], files[] | sessionId, flags, counts, markdownPath | write |
| `hydrate_context` | projectName, tags[], tokenLimit? | HydrationPayload (8 sections + truncation meta) | read |
| `search_context` | query, projectName?, type?, tag?, from?, to?, limit? | items[], totalShown, limitApplied | read |
| `project_summary` | projectName | stats, decisions, todos, sessions | read |
| `timeline` | projectName, limit? | sessions[] with entries | read |
| `list_projects` | — | projects[] | read |
| `save_note` | projectName, toolName, title, content, type, tags[], priority | entryId, sessionId, created | write |

**What is well designed:**

- **Verb-noun naming, consistent and predictable.** An agent can guess `hydrate_context` from
  `update_context`.
- **Coarse-grained writes.** `update_context` takes entries, decisions, todos, and files in one atomic
  call. This is exactly right for an LLM caller: one round trip, one transaction, one decision point.
  A fine-grained CRUD API would be worse in every dimension that matters here.
- **Explicit, honest metadata on reads.** `omittedCount`, `truncationNotice`, `estimatedTokens`,
  `limitApplied`, `totalShown`, `truncated` — the API tells the caller what it did not return. Rare
  and correct.
- **Every input bounded.** `McpValidations` caps every string, list, and number.
- **Optional-with-documented-defaults** rather than required-everything.

**What needs work:**

**F-45 (P1) — the API is write-only for state.** There is no `update_todo_status`,
`update_decision_status`, `update_project`, `close_session`, or any delete. Eight tools, six of which
read and two of which append. See [03 §3.10](03-module-analysis.md) (F-24). This is the largest API
gap and it is not a design question — the repository methods exist unused.

Minimum additions to make the API complete:

| Tool | Why |
|---|---|
| `update_todo_status(todoId, status, owner?)` | Todos can never close |
| `update_decision_status(decisionId, status, supersededBy?)` | Superseded decisions read as current |
| `update_project(projectName, description)` | Description is write-once and is hydration's first section |
| `close_session(sessionId, summary?)` | Abandoned sessions are unreachable (F-34) |
| `claim_work(todoId, agentId, ttl)` / `release_work` | The missing coordination primitive (F-31) |

**F-46 (P2) — `projectName` as the identifier is fragile.** Every tool takes a project *name*, not an
id, and `create_project` returns an id that no other tool accepts. Names are user-typed and
collision-prone; two clients writing "payments-service" and "payments_service" silently create two
projects. Accept either — `projectId` when supplied, `projectName` otherwise — and prefer root-based
resolution ([07](07-mcp-integration-blueprint.md) §7.2). Names remain the human affordance; ids become
the machine one.

**F-47 (P2) — `toolName` on every write call.** It is repeated on `update_context` and `save_note`,
must be spelled consistently for session reuse to work (ADR-16 matches on exact string), and is
unverified. Derive it from `clientInfo` at initialize (**F-42**, [10](10-hook-specification.md) H13)
and make the parameter an override.

**F-48 (P2) — `timeline` is unbounded and unpaginated.** `limit` caps *sessions*, and each session
carries all its entries. A mature project returns megabytes. Add a token budget like
`hydrate_context`, or cursor pagination, or both.

**F-49 (P2) — no idempotency.** Calling `update_context` twice with the same payload writes
everything twice; a network hiccup, a retry, or a confused agent silently duplicates the session's
content. An optional client-supplied `requestId`, deduplicated for a short window, would make writes
safely retryable. This matters more once hooks fire writes automatically.

**F-50 (P3) — no API versioning.** Schema changes to tool inputs will break clients silently. The
server version is hardcoded (`"1.0.0"`, F-28) and there is no negotiation. Version the server from
the build and document a compatibility policy before external users exist.

Also outstanding from [06](06-mcp-compatibility.md): tool annotations (F-38), `outputSchema` /
`structuredContent` (F-39), structured error metadata (F-43).

### A.2 The CLI API

Nine commands: `init`, `create-project`, `list-projects`, `update`, `hydrate`, `search`, `summary`,
`timeline`, `doctor`.

**Good:** `ScpCommand` centralises open-run-translate so no command handles plumbing; JSON output on
`hydrate`/`summary`/`timeline` makes the CLI scriptable; `doctor` is genuinely useful and diagnoses
plus repairs.

**F-29 (repeated, P1) — surface asymmetry.** MCP has `save_note`; the CLI does not. For a project whose
premise is "every client sees the same context", two surfaces with different capability sets is a
defect that will widen with every addition. Drive both from one operation registry.

**F-51 (P2) — inconsistent output modes.** `hydrate`/`summary`/`timeline` emit JSON;
`search`/`list-projects`/`create-project` emit formatted text. Neither is wrong; the inconsistency is.
Add a global `--json/--text` with a sensible per-command default.

**F-52 (P2) — no `--project` default.** Every command requires `--project` explicitly. Resolve from
the working directory (matching the MCP roots design) or an `SCP_PROJECT` env var.

### A.3 The port API

Eleven interfaces in `modules/model/port/`. Reviewed as a design:

**Good:** one interface per aggregate; method names state intent (`findOpenByProject`,
`findRecentlyModified`) rather than exposing query mechanics; `Clock` and `IdGenerator` as
`fun interface`s make time and identity injectable; `TransactionRunner` has exactly one method and its
contract — BEGIN IMMEDIATE, JVM lock, retry — is documented on the interface where an implementer
will read it.

**F-53 (P2) — unused methods signal incomplete features rather than extensibility.**
`ContextEntryRepository.findRecent` (unused — and it is the fix for F-07),
`DecisionRepository.updateStatus`, `TodoRepository.updateStatus`,
`ProjectRepository.updateDescription`, `SearchIndex.rebuild` (used only by `doctor`). An unused port
method is either dead code or an unfinished feature; here it is consistently the latter, which is
useful evidence but should not stay indefinitely.

**F-54 (P2) — no ports return pagination state.** Every list method takes a `limit` and returns a
`List`. None returns a total or a cursor, which is exactly why `omittedCount` cannot report
SQL-`LIMIT` drops (**F-08**). Returning `Page<T>(items, totalAvailable)` from the bounded finders
fixes F-08 at the source.

### A.4 The data contract

The Markdown mirror is a public interface whether or not it is treated as one: users read it, and the
project advertises it as human-readable. Its format has no version marker, and **F-23** notes it is
write-only — a user who edits a mirror has the edit silently overwritten. Add a version line to the
header and document the read/write direction explicitly.

---

## Part B — Interoperability

### B.0 The universal path

SCP's integration story is genuinely simple and this is its main strategic asset: **it is an MCP stdio
server**, so any MCP-capable client integrates the same way.

```json
{ "mcpServers": { "scp": { "command": "/path/to/scp-mcp-server", "env": { "SCP_LOG_DIR": "…" } } } }
```

Everything below is a variation on that, plus per-client ergonomics.

**Important caveat for the whole section:** MCP client capability support moves quickly, and support
for resources and prompts in particular varies more than support for tools. Treat the capability
columns below as a starting point to verify at integration time, not as a fixed matrix. The
recommendation that follows from this is architectural: **SCP's tool surface must remain fully usable
for a client that supports tools and nothing else.** Resources and prompts are enhancement, never
requirement.

### B.1 Per-client

| Client | Integration | Ergonomic addition | Notes |
|---|---|---|---|
| **Claude Code** | `claude mcp add scp -- <path>` — already documented in [docs/06](../06-setup-guide.md) | Skills ([08](08-skills-specification.md)) as `.claude/skills/`; hooks ([10](10-hook-specification.md)) via settings hooks; prompts as `/scp:*` | The reference client. Broadest capability support; hooks make H1/H3/H11/H12 real. **Verified working in this review** — the server responded to `list_projects` |
| **Cursor** | MCP server config in Cursor settings | Rules file describing when to hydrate and save | Confirm resource/prompt support at integration time; tools are the safe baseline |
| **Antigravity** | MCP server config | Depends on its extension model | Named as a primary target in the project brief and **absent from the docs entirely**. Fix this — it is the second agent in every example workflow |
| **Gemini CLI** | MCP server entry in its settings | Command aliases for resume/save | Verify capability support |
| **Codex CLI / OpenAI Codex** | MCP server config | Shell aliases | Verify capability support |
| **Continue.dev** | MCP block in `config.json` | Custom slash commands mapping to prompts | Config-file driven, straightforward |
| **VS Code (Copilot agent mode)** | Workspace MCP configuration | An extension could surface hydration in a panel | The largest install base of any target |
| **JetBrains** | AI assistant MCP configuration | Plugin could surface open todos/decisions in a tool window | Verify current support |
| **Any future MCP client** | Same JSON | — | The whole point |

### B.2 What blocks each integration today

Not client-specific — these are SCP-side, and the same three block everyone:

1. **Workspace configuration is out-of-band.** The server resolves storage from the process working
   directory or `-Dscp.home`. Every client config must set this correctly, every client sets working
   directories differently, and getting it wrong produces a *silently separate database* rather than an
   error — the worst possible failure mode for a shared-context tool. **Roots fixes this**
   ([07](07-mcp-integration-blueprint.md) §7.2) and it is the single highest-value interoperability
   change available.
2. **`create_project` must be called first.** The first `update_context` a new agent makes fails.
   Auto-provisioning removes the sharpest edge in first-run experience.
3. **Nothing tells the agent to use SCP.** Tool descriptions are one sentence each; there is no
   guidance on *when* to hydrate or save. Prompts, skills, and hooks are the three answers, in
   increasing order of automation.

### B.3 Cross-client consistency

Two risks worth naming:

**F-55 (P2) — `toolName` strings are unstandardised.** Session reuse (ADR-16) matches on exact string.
"claude-code" and "Claude Code" are different tools as far as `SessionResolver` is concerned, so
inconsistent spelling silently fragments sessions for a single agent. Publish canonical identifiers,
normalise on write (lowercase, hyphenate), and derive from `clientInfo` where possible (F-42).

**F-56 (P3) — no cross-client capability reporting.** If Cursor supports fewer capabilities than
Claude Code, the user gets a degraded experience with no explanation. `scp doctor --mcp` reporting
what the connected client negotiated would make this visible.

### B.4 Recommended integration deliverables

Concrete, in priority order:

1. **`docs/08-integration-guide.md`** — one section per client with copy-pasteable config, covering at
   minimum Claude Code, Antigravity, Cursor, Gemini CLI, Codex CLI, Continue.dev, and VS Code.
   Antigravity especially: it appears in every workflow example in the project and in none of the
   setup documentation.
2. **`scp install --client <name>`** — write the config for the named client rather than making the
   user hand-edit JSON in a location they have to find.
3. **Roots-based auto-resolution** — removes the most common misconfiguration entirely (B.2 item 1).
4. **A published `toolName` registry** — one canonical string per client.
5. **`scp doctor --mcp`** — verify the server starts, the handshake completes, tools list, and report
   negotiated capabilities.

### B.5 Assessment

**Interoperability is SCP's strongest strategic position and its weakest documented one.** The
architecture makes universal integration nearly free — being an MCP stdio server means every current
and future MCP client works with no per-client code. That is a genuinely good bet and it is already
paid for.

What is missing is entirely presentation and ergonomics: one documented client out of nine named
targets, manual workspace configuration whose failure mode is silent, and a required setup call before
first use. None of these is hard. All of them are what stands between "it works if you know how" and
"connect once and it works".

Next: [security assessment](12-security-assessment.md).
