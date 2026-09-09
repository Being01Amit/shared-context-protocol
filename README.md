# Shared Context Protocol (SCP)

[![Release](https://img.shields.io/github/v/release/Being01Amit/shared-context-protocol)](https://github.com/Being01Amit/shared-context-protocol/releases/latest)
[![CI](https://github.com/Being01Amit/shared-context-protocol/actions/workflows/ci.yml/badge.svg)](https://github.com/Being01Amit/shared-context-protocol/actions/workflows/ci.yml)
[![License: MIT](https://img.shields.io/badge/license-MIT-blue.svg)](LICENSE)

**Git for AI Context** — a local-first, cross-AI persistent context and memory layer that sits behind the Model Context Protocol (MCP).

SCP is **not** an AI. It is the storage and retrieval layer that lets Claude Code, ChatGPT, Cursor, Antigravity, Gemini CLI, and any future MCP-compatible tool continue the same project without losing context:

```
Claude Code → work 2h → update_context → SCP stores everything
                     ...later...
Antigravity → hydrate_context → continues exactly where Claude stopped
```

You install SCP once, point your AI tools at it, and they share one memory. No forking, no per-tool storage.

## Requirements

- **JDK 21+** (Temurin recommended) — verify with `java -version`. The installer does not install a JVM.

## Install

**Windows (PowerShell):**

```powershell
irm https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.ps1 | iex
```

**macOS / Linux:**

```sh
curl -fsSL https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.sh | sh
```

This downloads the latest release, verifies its SHA-256 checksum, installs `scpx` and `scp-mcp-server`, and adds them to your PATH. Re-running it upgrades in place — the install path stays stable, so your MCP client config never needs updating.

| | Default install location | Override |
|---|---|---|
| Windows | `%LOCALAPPDATA%\scp` | `-InstallDir` or `SCP_INSTALL_DIR` |
| macOS / Linux | `~/.scp` | `SCP_INSTALL_DIR` |

Pin a specific version with `.\install.ps1 -Version v0.2.0` or `./install.sh v0.2.0`.

Open a new terminal afterward so the PATH change takes effect, then verify:

```sh
scpx --help
```

## Quick start

SCP stores everything relative to its working directory, so each project folder can be its own workspace:

```sh
cd my-project
scpx init
scpx create-project --name my-app --description "Payments service rewrite"
```

Then register the MCP server with your AI tool (below), and it can read and write that workspace.

## Connect an MCP client

Any MCP-compatible client works identically — that is the point. Use the server path the installer printed:

| | Path |
|---|---|
| Windows | `%LOCALAPPDATA%\scp\scp-mcp-server\bin\scp-mcp-server.bat` |
| macOS / Linux | `~/.scp/scp-mcp-server/bin/scp-mcp-server` |

**Claude Code:**

```sh
claude mcp add scp -- "<path-from-above>"
```

**Any client using the standard JSON config:**

```json
{
  "mcpServers": {
    "scp": {
      "command": "<path-from-above>",
      "env": { "SCP_LOG_DIR": "<workspace>/storage/logs" }
    }
  }
}
```

Set the server's working directory (or pass `-Dscp.home=<dir>`) to your SCP workspace, so every tool shares the same `storage/` and `config.yaml`.

## How it works

- **One session per tool.** Each tool's work gets its own session — SCP never silently merges two tools' work into one, so you can always tell who did what.
- **Ranked, budgeted hydration.** `hydrate_context` returns a **resume point first** — what the last agent did and where it stopped, plus files in flight and blocking todos. It is charged to the token budget before anything else, so it can never be truncated away. Remaining budget is filled with entries ranked by recency, priority, tag overlap, type, and semantic similarity.
- **Git-aware.** If the repo's branch or commit moved since the last session, the resume point says so.
- **Human-readable mirror.** Everything is also written as plain Markdown:

  ```
  storage/projects/{project}/
    PROJECT.md    index: resume point + every session, newest first
    LATEST.md     copy of the newest session — open this to see where work stopped
    2026-08-05T14-25-30Z-claude-code-a3f9c1d2.md
  ```

- **Concurrency-safe.** WAL mode plus immediate transactions: two tools writing at the same moment never corrupt or overwrite each other.
- **Secrets redacted.** API keys, tokens, private keys, and `.env` values are stripped before storage.

Write `--next-step` on every save — it is the first thing the next agent reads:

```sh
scpx update --project my-app --summary "what changed" --next-step "what to do next"
```

## CLI reference

| Command | Purpose |
|---|---|
| `scpx init` | Create storage dirs, default `config.yaml`, database schema |
| `scpx create-project --name N [--description D]` | Register a project |
| `scpx list-projects` | Projects with session counts |
| `scpx update --project N [--tool T] [--summary S] [--next-step S] [--keep-open] [--json payload.json]` | Store context |
| `scpx hydrate --project N [--tag t]... [--token-limit n]` | Ranked resume payload (JSON) |
| `scpx search <query> [--project N] [--type BUG] [--tag t] [--from ISO] [--to ISO] [--limit n]` | Full-text search + filters |
| `scpx summary --project N` | Statistics, decisions, open todos |
| `scpx timeline --project N [--limit n]` | Full chronological history |
| `scpx update-todo-status --project N --todo-id ID --status S` | Mark a todo OPEN/IN_PROGRESS/DONE/DROPPED |
| `scpx update-decision-status --project N --decision-id ID --status S` | Mark a decision OPEN/ACCEPTED/SUPERSEDED/REJECTED |
| `scpx update-project --project N --description D` | Edit a project's description |
| `scpx claim-todo --project N --todo-id ID --tool T` | Claim a todo (fails if another tool holds it) |
| `scpx release-todo --project N --todo-id ID --tool T` | Release a todo you claimed |
| `scpx doctor` | Resolved paths, WAL/FK/FTS/config checks, stale sessions, and silent projects |

## MCP tools

The server exposes these over stdio: `update_context`, `hydrate_context`, `search_context`, `project_summary`, `timeline`, `list_projects`, `save_note`, `create_project`, `update_todo_status`, `update_decision_status`, `update_project`, `claim_todo`, `release_todo`.

## Configuration

Ranking weights, token budget, search limits, and redaction patterns live in `config.yaml` — see the [commented default](config.yaml) and the [hydration ranking spec](docs/05-hydration-ranking.md).

**Encryption at rest (optional).** Set `SCP_DB_KEY` to a passphrase and the database is transparently encrypted on disk. The key is never written to disk, and Markdown mirrors are disabled while it is on (a plaintext copy would leak exactly what the database encrypts). There is no recovery without the passphrase — see the [setup guide](docs/06-setup-guide.md#encryption-at-rest-optional).

## Build from source

Only needed to contribute, or to run something not yet released. No Gradle install required — the repo ships the wrapper.

```sh
./gradlew build          # compile, tests, ktlint, detekt
./gradlew installDist    # runnable distributions for both apps
```

They land in `apps/cli/build/install/scpx/bin/` and `apps/mcp-server/build/install/scp-mcp-server/bin/`.

## Documentation

- [Architecture & module structure](docs/01-architecture.md)
- [Technology decisions](docs/02-technology-decisions.md) — pinned stack, JVM cold-start call, deviations flagged for approval
- [Database schema (SQLDelight)](docs/03-database-schema.md) — tables, FTS5 external-content design, PRAGMAs, indexes
- [Session resolution](docs/04-session-resolution.md) — pseudocode + sequence diagrams, concurrency handling
- [Hydration ranking](docs/05-hydration-ranking.md) — scoring formula, default weights, token budget algorithm
- [Setup guide](docs/06-setup-guide.md) — install, build, init, MCP client registration, CLI reference
- [Roadmap](docs/07-roadmap.md) — future features and the extension points already reserved for them
- [Releasing](docs/08-releasing.md) — how versioned releases are cut

## Design principles (non-negotiable)

1. **Local first** — fully offline, zero network calls on the core path.
2. **Cross-AI** — no client-specific storage; any MCP client works identically.
3. **Fast** — hydration < 1s for ~500 entries / ~50 sessions, including JVM startup.
4. **Human readable** — everything mirrored to plain Markdown under `storage/projects/`.
5. **Extensible** — concrete extension points for embeddings, sync, git integration.
6. **Concurrency-safe** — WAL mode + transactions + retry; concurrent tools never corrupt or silently merge each other's work.
7. **Bounded output** — every read path has an explicit token ceiling; truncation is always signaled, never silent.

## Security

SCP has a known, unresolved **prompt-injection / memory-poisoning risk**: context written by one agent is later read by another, so a compromised or careless agent can plant instructions that a future agent reads as trusted input. Read [SECURITY.md](SECURITY.md) before relying on SCP across agents you don't fully trust.

## License

[MIT](LICENSE) © 2026 Amit
