# 6. MCP Compatibility Report

Deliverable 8 — review step 7. Can SCP be an MCP server? It already is one. The real question is
*how much of MCP it should use*, and the answer is: considerably more than it does.

## 6.0 The headline finding

**F-37 — SCP uses roughly 15% of the MCP SDK it already depends on, and every gap below can be closed
without a dependency bump.**

Verified by extracting `io.modelcontextprotocol:kotlin-sdk-server-jvm:0.14.0` and
`kotlin-sdk-core-jvm:0.14.0` from the Gradle cache and reading the public API. The pinned version
ships, unused:

| Available in the pinned SDK | Used by SCP |
|---|---|
| `Server.addTool` (with `Tool.annotations`, `Tool.outputSchema`, `Tool.title`, `Tool.icons`) | tools only — no annotations, no output schema, no title |
| `Server.addResource`, `addResources` | **no** |
| `Server.addResourceTemplate` (RFC 6570 URI templates + `ResourceTemplateMatcher`) | **no** |
| `Server.addPrompt`, `addPrompts` | **no** |
| `sendResourceUpdated`, `sendResourceListChanged` (per-session and per-connection) | **no** |
| `CallToolResult.structuredContent` | **no** — results are JSON strings inside `TextContent` |
| `StdioServerTransport` | ✅ yes |
| `SseServerTransport` | **no** |
| `StreamableHttpServerTransport` (+ `EventStore` for resumability) | **no** |
| `WebSocketMcpServerTransport` | **no** |
| `DnsRebindingProtectionConfig`, `HostValidationKt` | **no** (needed only for HTTP) |
| Sampling (`SamplingMessage`, `SamplingValidationKt`) | **no** |
| Elicitation (`ElicitRequest`, form and URL params) | **no** |
| Roots (`ListRootsRequest`, `RootsListChangedNotification`) | **no** |
| Completions (`CompleteRequest`) | **no** |
| Logging notifications (`LoggingMessageNotification`) | **no** — logs go to files only |
| Progress (`ProgressNotification`) | **no** |
| `ServerSessionRegistry`, `SessionContext`, `TransportManager` | **no** |

The `ServerCapabilities` type the SDK offers is:

```kotlin
public data class ServerCapabilities(
    val tools: Tools? = null,          // SCP: Tools(listChanged = false)
    val resources: Resources? = null,  // SCP: null
    val prompts: Prompts? = null,      // SCP: null
    val logging: JsonObject? = null,   // SCP: null
    val completions: JsonObject? = null,
    val tasks: Tasks? = null,
    val experimental: JsonObject? = null,
    val extensions: Map<String, JsonObject>? = null,
)
```

SCP declares exactly one field (`apps/mcp-server/src/main/kotlin/com/scp/server/Main.kt`):

```kotlin
ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)))
```

That is a legitimate v1 scope decision, and ADR-9's reasoning for stdio-only is sound. But the cost of
the additional capabilities is much lower than the project appears to assume — no new dependency, no
new transport, no redesign. Most of them are a `Server.addResource { }` call away.

## 6.1 Capability-by-capability assessment

### Tools — implemented, under-specified

Eight tools, all with hand-built JSON Schema input schemas, all validated twice (shape then
constraints). The `callTool` pipeline maps domain failures to `isError` results rather than protocol
errors, which is correct MCP behaviour. The foundation is solid.

**F-38 (P1) — no tool annotations.** `ToolAnnotations(title, readOnlyHint, destructiveHint,
idempotentHint, openWorldHint)` exists in the pinned SDK and no tool sets it. Clients use these hints
to decide auto-approval, confirmation prompts, and how much to trust a tool. Without them, a client
must treat `hydrate_context` — a pure read — with the same suspicion as `update_context`, which writes.

For SCP specifically this matters more than usual: it is a *memory* server, so the read tools will be
called constantly and automatically. Annotating them correctly is what makes that acceptable.

Recommended annotations for all eight:

| Tool | readOnly | destructive | idempotent | openWorld |
|---|:---:|:---:|:---:|:---:|
| `hydrate_context` | ✅ | ❌ | ✅ | ❌ |
| `search_context` | ✅ | ❌ | ✅ | ❌ |
| `project_summary` | ✅ | ❌ | ✅ | ❌ |
| `timeline` | ✅ | ❌ | ✅ | ❌ |
| `list_projects` | ✅ | ❌ | ✅ | ❌ |
| `create_project` | ❌ | ❌ | ❌ | ❌ |
| `update_context` | ❌ | ❌ | ❌ | ❌ |
| `save_note` | ❌ | ❌ | ❌ | ❌ |

`openWorldHint = false` throughout is itself a selling point — it tells the client this server touches
only local state, which is exactly SCP's positioning.

**F-39 (P1) — no `outputSchema`, no `structuredContent`.** Every tool returns
`CallToolResult(content = listOf(TextContent(json.encodeToString(result))))`. The result is a JSON
document serialised into a string and handed to the model as text. `CallToolResult.structuredContent:
JsonObject?` and `Tool.outputSchema: ToolSchema?` both exist in the pinned SDK.

Consequences of the current approach:

- The client cannot validate or parse the result structurally; the model re-parses JSON from prose.
- `HydrationPayload` — a rich, deeply-structured object with eight sections and truncation metadata —
  arrives as an opaque blob. The agent must be told, in prose somewhere, what its shape is.
- Token cost is higher (escaped JSON inside a string) for identical information.

For a server whose *entire output* is structured data, this is the single highest-value MCP upgrade
available. The DTOs are already `@Serializable`; emitting `structuredContent` alongside the text is
close to a one-line change per tool, and the output schemas can be derived from the same DTOs.

**F-40 (P2) — no pagination.** `timeline` is unbounded (§[03](03-module-analysis.md) F-20),
`search_context` caps at 500, `list_projects` returns everything. MCP's list operations support
cursors; tool results can carry their own continuation tokens. As a project's history grows, `timeline`
will exceed what any client will accept in one response, and there is no next-page mechanism.

**F-28 (repeated, P2) — hardcoded version.** `Implementation(name = "scp", version = "1.0.0")`.

### Resources — absent, and the most natural fit

Not declared, not implemented. This is the biggest missed opportunity in the MCP surface, because SCP
is *already* a resource server that only speaks in tools.

MCP resources are for **content the client can read and attach to context directly**, addressed by URI,
listable, subscribable. That is a precise description of what SCP stores. Tools are for *actions*;
SCP's five read tools are not actions, they are resource reads dressed as function calls.

Concretely, the following should be resources:

| URI | Content | Why a resource |
|---|---|---|
| `scp://projects` | Project list | Listable, stable, cacheable |
| `scp://project/{name}` | Project summary | The client can attach it without a tool round trip |
| `scp://project/{name}/hydration` | The hydration payload | The canonical "here is the state" document |
| `scp://project/{name}/decisions` | Open decisions | Small, high-value, changes rarely |
| `scp://project/{name}/todos` | Open work | The thing a user most wants pinned |
| `scp://project/{name}/session/{id}` | One session's Markdown mirror | **Already exists as a file on disk** |
| `scp://project/{name}/file/{path}` | Tracked-file summary | Naturally addressed by path |

The session mirror case is almost comical: `FileMarkdownStore` already renders exactly the document an
MCP resource would serve, writes it to disk, and returns its path in `UpdateContextResult.markdownPath`
— where no client can do anything with it, because a path is not a resource URI.

`addResourceTemplate` with RFC 6570 templates (`scp://project/{name}/session/{id}`) is in the pinned
SDK, along with `ResourceTemplateMatcher`. The work is a URI router over existing use-cases.

**Why this matters beyond elegance:** resources are how a client *pins* context. A user who wants "the
open decisions always visible" can attach a resource; they cannot attach a tool call. For a memory
product, that is the difference between memory the user opts into once and memory the agent must
remember to fetch.

### Prompts — absent, and the fix for the discipline problem

Not declared, not implemented. `Server.addPrompt`, `Prompt`, `PromptArgument`, `PromptMessage`, and
`GetPromptResult` are all in the pinned SDK.

MCP prompts are user-initiated templates that clients surface as slash commands or menu entries. This
directly addresses **F-36** ([05](05-multi-agent-collaboration.md)): the handoff protocol currently
depends on an agent *remembering* to hydrate and update. A prompt makes it a user-visible affordance —
the user types `/scp:resume` and the client injects a message that includes the hydration payload and
tells the model how to use it.

The full catalogue is specified in [09-mcp-prompt-library](09-mcp-prompt-library.md). The key insight
for this document: **prompts are where SCP's opinion about how to use the context belongs.** Today the
tool descriptions carry that burden in a sentence each, and the agent's interpretation is unconstrained.

### Notifications — absent, and required for concurrent agents

`ServerCapabilities.Resources(listChanged, subscribe)` supports both. The SDK exposes
`sendResourceUpdated(notification)` and `sendResourceListChanged()` on both `ServerSession` and
`ClientConnection`, plus per-session variants on `Server`.

Without notifications, every agent's view is a snapshot from its last hydration (**F-32**). With
resources plus `subscribe`, an agent that has subscribed to `scp://project/payments-service/todos` is
told when another agent changes them.

**One design constraint deserves emphasis:** notifications only work if the server process is
long-lived and observes the change. SCP's model is one process per client, coordinating through the
file. Process A cannot currently know that process B wrote — SQLite gives no cross-process change
feed. Options, in increasing order of cost:

1. **Poll on a timer** — each server polls `project.updated_at` every N seconds and fires
   `resources/updated` on change. Crude, trivial, and sufficient. `project.updated_at` is already
   maintained on every write (`projects.touch`), so the hook exists.
2. **File-watch the WAL or a sentinel file** — `WatchService` on `storage/`; lower latency, more
   platform variance.
3. **A shared broker process** — correct and a significant architectural change; see
   [07-mcp-integration-blueprint](07-mcp-integration-blueprint.md) §Shared-server mode.

Start with (1). It is a scheduled coroutine and a comparison.

### Sampling — available, and one genuinely good use for it

Not declared. Sampling lets the *server* ask the *client* to run an LLM completion — the client keeps
control of model choice, cost, and user approval.

Most servers should not use this. SCP has one strong case: **session summarisation**. When
`update_context` closes a session with no `summary`, or when compaction rolls up old sessions
(roadmap item 6), the server needs a summary and has no model. Sampling lets it ask the client for
one, using the client's model, with the client's consent — and keeps SCP's zero-network promise
intact, because the request goes back over the same stdio pipe.

This is a genuinely elegant fit and worth prototyping. Two cautions: it makes the server dependent on
a client capability that not all clients implement, so it must degrade gracefully; and sampling over
stored content is a prompt-injection amplifier — see
[12-security-assessment](12-security-assessment.md).

### Roots — available, and the fix for a real workflow problem

Not declared. Roots are the client telling the server which filesystem directories are in scope.

SCP currently resolves its workspace from `-Dscp.home` or the process working directory, and projects
are matched **by name string**. So the agent must know the project name, and two different
repositories can be pointed at the same SCP project by typo, or one repository can accumulate two
projects because two clients passed different names.

With roots, the server learns the client's actual workspace directory and can **map a root to a
project automatically**. `hydrate_context` with no `projectName` becomes possible: use the root. This
removes the most common friction in the whole workflow — the agent guessing the project name — and it
removes the `create_project`-first requirement for the common case.

*Recommendation:* add a `project.root_path` column, populate it from the client's roots on first
contact, and resolve project-by-root before falling back to project-by-name.

### Elicitation — available, and the answer to a design tension

Not declared. `ElicitRequest` (with form and URL variants) lets the server ask the *user* a structured
question mid-operation.

Two places SCP needs this today and currently resolves by failing:

- `update_context` against a nonexistent project throws `NotFoundException("… create it with
  create_project")`. Elicitation turns that into "No project named 'payments-service'. Create it?
  [name] [description]".
- A conflicting write once F-24 lands ("this todo was closed by antigravity 5 minutes ago — mark it
  done anyway?") is exactly what elicitation is for.

### Completions — available, low value here

`CompleteRequest` supports argument autocompletion for prompt and resource arguments. Useful once
resources exist (completing project names, tags, `ContextType` values). Cheap, cosmetic, do it last.

### Logging — available, currently a deliberate no

`ServerCapabilities.logging` would let the server send log records to the client. SCP logs to
`storage/logs/*.jsonl` and never to stdout (ADR-13, load-bearing on a stdio transport).

Keep the file logs as the system of record. Adding the MCP logging capability at `warn`/`error` level
would surface real problems (a failed FTS rebuild, a busy-retry exhaustion) where the user can see
them, instead of in a file nobody opens. Low cost, real value.

### Sessions and state

MCP session lifecycle (initialize → capability negotiation → operation → shutdown) is handled by the
SDK. `Main.kt` correctly creates a session, registers an `onClose` handler, and joins until closed.

**F-41 (P2) — MCP session lifecycle and SCP session lifecycle are unconnected.** They are different
concepts with the same name, which is a documentation hazard, but there is also a missed integration:
when the MCP session closes, SCP could close its own open session for that tool. Today the `onClose`
handler logs a line and completes a `Job`. An agent that disconnects without calling `update_context`
leaves an open SCP session forever (**F-34**) — and the server *knew* the client went away.

Even without auto-closing (which [docs/04 §6](../04-session-resolution.md) rightly refuses to do
blindly), recording a `disconnected_at` on the open session would let `doctor` distinguish "crashed"
from "still running", which it currently cannot.

## 6.2 Target capability declaration

What SCP should declare once the recommendations land:

```kotlin
ServerCapabilities(
    tools     = ServerCapabilities.Tools(listChanged = false),
    resources = ServerCapabilities.Resources(listChanged = true, subscribe = true),
    prompts   = ServerCapabilities.Prompts(listChanged = false),
    logging   = ServerCapabilities.Logging,
    completions = ServerCapabilities.Completions,
)
```

`tools.listChanged = false` stays correct — the tool set is static.

## 6.3 Prioritised roadmap

| Priority | Change | Effort | Why |
|---|---|---|---|
| **P1** | Tool annotations on all 8 tools | hours | Client trust; read tools become auto-approvable |
| **P1** | `structuredContent` + `outputSchema` | 1–2 days | The output *is* structure; stop stringifying it |
| **P1** | Resources for projects, hydration, decisions, todos, session mirrors | 2–3 days | The natural shape; enables user-pinned context |
| **P2** | Prompts (see [09](09-mcp-prompt-library.md)) | 2–3 days | Makes the handoff protocol a user affordance, not agent discipline |
| **P2** | Resource subscriptions + polled `resources/updated` | 2–3 days | The only path to concurrent-agent awareness |
| **P2** | Roots → project auto-resolution | 1–2 days | Removes the "guess the project name" friction |
| **P2** | MCP logging at warn/error | hours | Surfaces failures where someone will see them |
| **P3** | Elicitation for missing-project and conflict cases | 1–2 days | Better than throwing |
| **P3** | Sampling for session summarisation | 2–3 days | Elegant; needs injection review first |
| **P3** | Completions for names/tags/types | hours | Polish |
| **P3** | Streamable HTTP transport | see [07](07-mcp-integration-blueprint.md) | Only if a shared-server mode is wanted |

**Verdict on step 7's question — can SCP become an MCP server?** It is one, correctly, today. It
should become a *fully-featured* one: a resource server with prompts and subscriptions, not just a
tool server. Everything required is in the dependency it already has.

Next: [MCP integration blueprint](07-mcp-integration-blueprint.md).
