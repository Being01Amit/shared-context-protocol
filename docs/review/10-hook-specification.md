# 10. Hook Specification

Deliverable 12 — review step 11. Fourteen automatic hooks that keep SCP fed without relying on an
agent remembering.

## 10.0 Why hooks are the highest-value forward design in this review

Everything in SCP depends on `update_context` being called. Nothing calls it automatically. The
failure mode is **silence**: an agent finishes two hours of work, forgets to save, and the context is
gone with no error, no warning, and nothing for `doctor` to detect. That is **F-36**
([05](05-multi-agent-collaboration.md)), and it is a design gap rather than a bug — the system has no
mechanism that could notice.

Hooks close it. They turn capture from an act of agent discipline into a property of the environment.

## 10.1 Where hooks actually run

SCP cannot implement most of these itself, and being clear about this is the difference between a
useful spec and a wish list.

| Layer | Owner | Mechanism | Hooks |
|---|---|---|---|
| **MCP lifecycle** | SCP server | SDK session callbacks — *SCP fully controls these* | H13, H14 |
| **Agent client** | the AI tool | Client hook config (e.g. Claude Code `settings.json` hooks) | H1, H2, H3, H11, H12 |
| **Version control** | git | `.git/hooks/*` | H7, H8 |
| **Build/test** | build tool | Gradle task finalizers, test listeners | H4, H5, H6 |
| **CI/forge** | CI system | Workflow steps, webhooks | H9 |
| **OS** | shell / service | Signal handlers, shutdown scripts | H10 |

**So what does SCP ship?** Three things:

1. **The two hooks it owns outright** (H13, H14) — implemented in `apps/mcp-server`.
2. **A hook toolkit**: ready-made `.git/hooks` scripts, a Gradle plugin or init script, a client hook
   config snippet, and an installer (`scp hooks install --client claude-code --git --gradle`) that
   places them.
3. **A CLI fast enough to be called from a hook** — which today it is not. See §10.2.

### 10.2 The blocking constraint: CLI cold start

**F-44 (P1) — the CLI is too slow to be hooked at high frequency.** ADR-2 records a measured
884–950 ms for `scp hydrate` on a medium project, dominated by JVM cold start. Every CLI invocation
pays it.

Hook frequencies, and what that costs:

| Hook | Frequency | Cost at ~900 ms |
|---|---|---|
| H3 on file modified | tens per minute | **Unusable** — would add minutes of latency per hour |
| H7 on commit | several per hour | ~1 s per commit — acceptable |
| H4/H5/H6 on build/test | several per hour | acceptable, amortised against a build |
| H1/H11/H13/H14 session boundaries | a few per day | irrelevant |

Three mitigations, in order of preference:

1. **Prefer MCP over CLI.** An agent-side hook should call the SCP *tool* through the already-running
   MCP server — no new process, no cold start. This works for every client-layer hook (H1, H2, H3,
   H11, H12) and is by far the best answer.
2. **Batch and defer.** H3 should never write per file. Accumulate in a local buffer and flush at the
   next natural boundary (H7 commit or H11 session end). This is the design below.
3. **Make the CLI fast** for the git and build layers, which genuinely need a process: AppCDS first,
   native-image only if that is not enough ([13](13-performance-and-scalability.md) §Cold start).

**Rule for every hook below: a hook must never block the developer.** Fire-and-forget, fail silently
to a log, and never fail the build, the commit, or the agent's turn because SCP was unavailable. A
memory layer that breaks `git commit` will be uninstalled within a day.

---

## 10.3 The hooks

### H1 · `onProjectOpen`

- **Trigger.** Agent client opens a workspace / starts a session in a directory.
- **Layer.** Agent client (config snippet SCP ships).
- **Purpose.** Ensure the project exists and the agent starts with context — the automatic half of
  [P1 `resume`](09-mcp-prompt-library.md).
- **Data collected.** Workspace root, client identity and version, git remote and branch if present.
- **Memory updates.** Auto-provision the project if the root is unknown ([07](07-mcp-integration-blueprint.md)
  §7.2). No session is created — opening a workspace is not doing work.
- **Context updates.** Inject the hydration payload, or a compact banner: *"SCP has context for this
  project: 3 open decisions, 2 open todos, last session 4 h ago by antigravity. Run `/scp:resume` for
  the full picture."*
- **Notes.** Prefer the banner over full injection by default. Silently consuming 12 k tokens of every
  session's budget is the fastest way to get a memory tool disabled. Make full injection opt-in.

---

### H2 · `onTaskCompleted` ⛔ *(F-24, F-31)*

- **Trigger.** Agent reports a task done; a todo is checked off.
- **Layer.** Agent client.
- **Purpose.** Close the work item and record the outcome, so `openTodos` reflects reality.
- **Data collected.** Task id, outcome, files changed, duration, agent identity.
- **Memory updates.** Set `todo.status = done`, `owner = <agent>`; add a `TASK` entry describing what
  was done.
- **Context updates.** Drop the item from working context; surface any todo it unblocked.
- **Blocked by.** Todo status cannot be changed through any interface (**F-24**), and there is no
  claim/ownership mechanism (**F-31**). This hook is unimplementable until both land — and it is the
  hook that keeps the open-work list honest, which makes those two findings load-bearing rather than
  cosmetic.

---

### H3 · `onFileModified`

- **Trigger.** A file is written by the agent.
- **Layer.** Agent client.
- **Purpose.** Maintain the tracked-file map without an explicit call.
- **Data collected.** Path, change kind, content hash, and — importantly — *nothing else*. Not
  content, not diffs.
- **Memory updates.** **Buffer only.** Accumulate `{path, hash}` in memory and flush a batched
  `update_context(files = [...])` at the next boundary (H7 or H11).
- **Context updates.** None per file.
- **Notes.**
  - Never write per file (§10.2).
  - **Never store file contents.** SCP's redaction is regex-based and best-effort; piping raw file
    contents through it at high frequency is the single easiest way to persist a secret it does not
    match.
  - Respect `.gitignore` and skip build output — otherwise the file map fills with `build/` noise.
  - `file.hash` exists in the schema and nothing computes it
    ([04](04-memory-architecture.md)); this hook is what makes it meaningful, and once populated it
    enables staleness detection: *"the summary for `StripeAdapter.kt` was written when the hash was
    `abc123`; it is now `def456` — treat the summary as stale."* That is a genuinely valuable
    capability sitting one hook away.

---

### H4 · `onBuildSuccess`

- **Trigger.** Build completes successfully.
- **Layer.** Build tool (Gradle finalizer or init script).
- **Purpose.** Mark a known-good state — the anchor an agent can safely return to.
- **Data collected.** Build id, duration, git SHA, artefact versions.
- **Memory updates.** Low-priority `COMMIT`-typed entry, or preferably **nothing at all** in the
  steady state.
- **Context updates.** None.
- **Notes.** **Recommend this hook be off by default.** A successful build is the normal case;
  recording every one fills the database with entries carrying no information and dilutes ranking.
  Record only the *transition* from failing to passing, which is genuinely informative.

---

### H5 · `onBuildFailure`

- **Trigger.** Build fails.
- **Layer.** Build tool.
- **Purpose.** Capture the failure so the next agent does not rediscover it.
- **Data collected.** Error type, failing module, **first ~2 KB of the error output** (not the whole
  log), git SHA, whether it is a repeat of the previous failure.
- **Memory updates.** `BUG` entry, priority 4, tags `[build, <module>]`. Deduplicate: if the same
  signature is already recorded and unresolved, do not create a second entry.
- **Context updates.** Surface immediately to the current agent — this is one of the few hooks whose
  value is *immediate* rather than for a future session.
- **Notes.** Truncate hard. Build logs are enormous and mostly noise; the first error is the signal.
  Redaction applies but do not lean on it — build output frequently contains environment dumps.

---

### H6 · `onTestFailure`

- **Trigger.** One or more tests fail.
- **Layer.** Build tool (JUnit listener / Gradle test task).
- **Purpose.** Record which tests fail and whether they are flaky.
- **Data collected.** Test names, assertion messages, failure counts, whether the same test failed in
  the previous run.
- **Memory updates.** `TESTING` entry, priority by count. **Flakiness detection is the high-value part**:
  a test that fails intermittently across runs is a distinct and much more useful finding than a test
  that fails consistently, and only accumulated history can tell them apart. This is a genuine
  advantage of a persistent context layer over a CI dashboard.
- **Context updates.** Surface to the current agent.
- **Notes.** Batch per run, one entry per run, not one per test. A 40-test failure is one event.

---

### H7 · `onCommit`

- **Trigger.** `post-commit` git hook.
- **Layer.** Git (script SCP ships).
- **Purpose.** The primary natural checkpoint — the best moment to flush buffered state.
- **Data collected.** SHA, message, author, changed paths and stats, branch.
- **Memory updates.** `COMMIT` entry with the message; flush the H3 file buffer as `files`; if the
  message references a todo or issue, note the link (a real link once relations exist — **F-30**).
- **Context updates.** None.
- **Notes.**
  - **Must be `post-commit`, never `pre-commit`.** A pre-commit hook that fails blocks the commit; a
    memory layer must never do that.
  - Must not fail the hook script on SCP error — `|| true` and log.
  - Commit messages are the highest-quality context SCP can capture automatically: they are
    human-authored, deliberately summarised, and already exist. This is the best cost/value ratio of
    any hook here.

---

### H8 · `onMerge`

- **Trigger.** `post-merge` git hook.
- **Layer.** Git.
- **Purpose.** Record integration events and, critically, that another line of work has arrived.
- **Data collected.** Merged branch, commit range, conflicts encountered, resulting SHA.
- **Memory updates.** `COMMIT` entry summarising the merge; if conflicts occurred, a `LEARNING` entry
  recording where and how they were resolved — conflict resolutions are exactly the reasoning that is
  lost between sessions.
- **Context updates.** Mark any file summary for a merged-in file as potentially stale.
- **Notes.** A merge is a strong signal that an agent's cached understanding is out of date. Once
  resource subscriptions exist ([06](06-mcp-compatibility.md)), this should fire a
  `resources/updated`.

---

### H9 · `onPullRequest`

- **Trigger.** PR opened, updated, or merged.
- **Layer.** CI / forge webhook.
- **Purpose.** Record review outcomes and the decisions made in review — a rich source that is
  currently lost entirely.
- **Data collected.** PR number, title, description, review comments, approvals, merge state.
- **Memory updates.** `FEATURE` or `REFACTOR` entry; **any decision made in review recorded as a
  decision with its reason** — review threads are where reasoning is densest and where it most
  reliably disappears.
- **Context updates.** None.
- **Notes.** Requires network access to the forge, which sits against SCP's zero-network principle. It
  must therefore be strictly opt-in, clearly documented as the one hook that talks to a remote, and
  ideally run in CI (where network is expected) rather than on the developer's machine.
  **Treat PR content as untrusted** — on a public repository, PR descriptions and comments are
  attacker-controlled text that this hook would write directly into shared agent memory. That is the
  clearest memory-poisoning path in the entire design; see
  [12-security-assessment](12-security-assessment.md).

---

### H10 · `onShutdown`

- **Trigger.** SIGTERM / SIGINT / OS shutdown.
- **Layer.** OS + JVM shutdown hook.
- **Purpose.** Do not lose buffered state; leave the database clean.
- **Data collected.** Whatever is buffered and unflushed.
- **Memory updates.** Best-effort flush of the H3 buffer; mark any session open for this process with
  `disconnected_at`.
- **Context updates.** None.
- **Notes.** Must be fast and must not hang — a shutdown hook that blocks for seconds is worse than
  losing a buffer. Time-box it (~500 ms) and give up. The database is already crash-safe (WAL +
  transactions), so the only thing at risk is unflushed in-memory buffer, and that is by definition
  the least valuable state.

---

### H11 · `onSessionEnd`

- **Trigger.** Agent session ends normally.
- **Layer.** Agent client.
- **Purpose.** **The most important hook in this document.** Persist the session's work automatically.
- **Data collected.** Everything the session produced: what was built, decided, learned, left open;
  files touched; token usage.
- **Memory updates.** A full `update_context` — entries, decisions, todos, files, summary — closing
  the session.
- **Context updates.** None (the session is over).
- **Notes.**
  - This is the automatic form of [P9 `save`](09-mcp-prompt-library.md), and it is the hook that makes
    every other capability real. Without it, capture depends on discipline and eventually fails.
  - **The agent must generate the content**, not the hook. A mechanical dump of the transcript is
    worse than nothing: it is verbose, unranked, and it poisons hydration with noise. The hook should
    invoke the agent with the P9 instruction and let it summarise.
  - Must degrade: if the agent cannot summarise, write a minimal session record with the file list
    rather than nothing.
  - Should skip trivial sessions. A session that read three files and answered a question should not
    produce an entry.

---

### H12 · `onAgentSwitch`

- **Trigger.** The user switches tools, or a second agent starts on the same project.
- **Layer.** Agent client (outgoing) + SCP (incoming detection).
- **Purpose.** Make the handoff explicit rather than implicit — the moment SCP exists for.
- **Data collected.** Outgoing agent identity and state; incoming agent identity.
- **Memory updates.** Close the outgoing session with a handoff-oriented summary (*"stopping here;
  next step is X"*); create the incoming session.
- **Context updates.** Hydrate the incoming agent immediately.
- **Notes.** Detection is the hard part: SCP sees an incoming connection but cannot know the user
  "switched" versus started a parallel agent. Best available heuristic: a new client connects while
  another tool has an open session. Once resource subscriptions exist this can also *notify* the
  outgoing agent. Until then, treat H12 as H11-then-H1 with a handoff-flavoured summary.

---

### H13 · `onMcpInitialize` — **SCP implements this directly**

- **Trigger.** MCP `initialize` handshake completes.
- **Layer.** `apps/mcp-server` — the SDK gives SCP full control here.
- **Purpose.** Bind the session: identity, workspace, capabilities.
- **Data collected.** `clientInfo` (name, version), negotiated client capabilities, roots.
- **Memory updates.** Resolve or auto-provision the project from the root
  ([07](07-mcp-integration-blueprint.md) §7.2). Do **not** create an SCP session — connecting is not
  working.
- **Context updates.** Bind the default project for the connection so `projectName` becomes optional
  on every tool.
- **Notes.** This is where **F-42** is fixed: default `toolName` from `clientInfo.name` instead of
  trusting a per-call string. It is also where capability degradation is decided
  ([07](07-mcp-integration-blueprint.md) §7.6). Small, entirely within SCP's control, and it unlocks
  the connect-once experience.

---

### H14 · `onMcpDisconnect` — **SCP implements this directly**

- **Trigger.** MCP session closes (clean or dropped).
- **Layer.** `apps/mcp-server` — `session.onClose` already exists and currently only logs.
- **Purpose.** Record that the agent went away, without guessing what that means.
- **Data collected.** Session id, disconnect reason if known, whether an SCP session is still open.
- **Memory updates.** Record `disconnected_at` on any open session for this client. **Do not
  auto-close it** — [docs/04 §6](../04-session-resolution.md)'s refusal to guess that work is finished
  is correct and must be preserved.
- **Context updates.** None.
- **Notes.** This makes `doctor`'s stale-session check meaningful: today it reports "open for >24 h"
  and cannot distinguish a crashed agent from one still working. With `disconnected_at`, "open and
  disconnected 6 h ago" is actionable and "open and still connected" is not a problem at all
  (**F-34**, **F-41**). This is the smallest change in this document with the clearest payoff — the
  callback already exists and does nothing.

---

## 10.4 Summary

| # | Hook | Layer | Frequency | Value | Blocked by |
|---|---|---|---|:---:|---|
| H1 | onProjectOpen | client | low | high | — |
| H2 | onTaskCompleted | client | medium | **high** | ⛔ F-24, F-31 |
| H3 | onFileModified | client | **very high** | medium | must batch (F-44) |
| H4 | onBuildSuccess | build | medium | low | — (off by default) |
| H5 | onBuildFailure | build | low | high | — |
| H6 | onTestFailure | build | low | high | — |
| H7 | onCommit | git | medium | **high** | — |
| H8 | onMerge | git | low | medium | — |
| H9 | onPullRequest | CI | low | medium | opt-in; untrusted input |
| H10 | onShutdown | OS | low | low | — |
| H11 | onSessionEnd | client | low | **highest** | — |
| H12 | onAgentSwitch | client + SCP | low | high | detection heuristic |
| H13 | onMcpInitialize | **SCP** | low | **high** | — |
| H14 | onMcpDisconnect | **SCP** | low | medium | — |

**Implementation order:**

1. **H13, H14** — inside SCP, no external dependency, unlock connect-once and honest session state.
2. **H11** — the capture loop. Everything else is leverage on this.
3. **H7** — best automatic-context-per-unit-effort in the list.
4. **H1** — completes the resume loop.
5. **H5, H6** — failure capture, high value and low frequency.
6. **H3** (batched), **H8**, **H12**.
7. **H2** once F-24 and F-31 land.
8. **H4** (off by default), **H9** (opt-in), **H10**.

**Three principles to hold across all of them:**

- **Never block the developer.** No hook fails a build, a commit, or an agent turn.
- **Never store raw content.** Summaries and metadata, not file bodies, transcripts, or full logs.
  Redaction is a safety net, not a strategy.
- **Never inject silently.** A hook that consumes the agent's context budget without the user knowing
  will get the tool uninstalled. Banner by default, full injection on request.

Next: [API design review](11-api-design-review.md).
