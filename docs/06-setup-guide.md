# SCP Setup Guide

## Prerequisites

- **JDK 21+** (Temurin recommended). Verify: `java -version`.
- No Gradle install needed — the repo ships the wrapper (`gradlew` / `gradlew.bat`).

## Build

```powershell
.\gradlew.bat build          # everything: compile, tests, ktlint, detekt
.\gradlew.bat installDist    # runnable distributions for both apps
```

Distributions land in:

- `apps/cli/build/install/scp/bin/scp[.bat]`
- `apps/mcp-server/build/install/scp-mcp-server/bin/scp-mcp-server[.bat]`

> **Windows note:** if any JVM step fails with `Unable to establish loopback connection`,
> your machine blocks AF_UNIX sockets under `%APPDATA%`-adjacent temp dirs (JDK NIO
> selectors create socket files in the temp dir). Fix: set `TMP`/`TEMP` to a directory
> outside `AppData` (e.g. `C:\Users\<you>\.jvmtmp`) for JVM processes.

## Initialize a workspace

SCP stores everything relative to its working directory (override with `-Dscp.home=<dir>`):

```powershell
cd <your-scp-workspace>
scp init                                  # storage dirs + default config.yaml + database
scp create-project --name my-app --description "Payments service rewrite"
scp list-projects
```

## Register the MCP server with an AI tool

Any MCP-compatible client works identically — that is the point. The server speaks MCP
over stdio and exposes: `update_context`, `hydrate_context`, `search_context`,
`project_summary`, `timeline`, `list_projects`, `save_note`, `create_project`.

**Claude Code:**

```powershell
claude mcp add scp -- "<repo>/apps/mcp-server/build/install/scp-mcp-server/bin/scp-mcp-server.bat"
```

**Generic client config (JSON):**

```json
{
  "mcpServers": {
    "scp": {
      "command": "<repo>/apps/mcp-server/build/install/scp-mcp-server/bin/scp-mcp-server.bat",
      "env": { "SCP_LOG_DIR": "<workspace>/storage/logs" }
    }
  }
}
```

Set the process working directory (or `-Dscp.home`) to the SCP workspace so all tools
share the same `storage/` and `config.yaml`.

## The cross-AI workflow

```text
Claude Code   → work for 2 hours → update_context   → SCP stores + writes markdown mirror
...later...
Antigravity   → hydrate_context  → ranked, token-budgeted resume payload → continues
```

- Concurrent tools are safe: WAL mode + IMMEDIATE transactions + per-tool sessions
  (docs/04). Two tools writing in the same second each get their own session row.
- Everything is mirrored human-readably to `storage/markdown/{project}/{date}-{id8}.md`.
- Secrets (API keys, tokens, private keys, `.env` values) are redacted before storage.

## CLI reference

| Command | Purpose |
|---|---|
| `scp init` | Create storage dirs, default `config.yaml`, database schema |
| `scp create-project --name N [--description D]` | Register a project |
| `scp list-projects` | Projects with session counts |
| `scp update --project N [--tool T] [--summary S] [--keep-open] [--json payload.json]` | Store context (full payload via `--json`) |
| `scp hydrate --project N [--tag t]... [--token-limit n]` | Ranked resume payload (JSON) |
| `scp search <query> [--project N] [--type BUG] [--tag t] [--from ISO] [--to ISO] [--limit n]` | Full-text + filters |
| `scp summary --project N` | Statistics, decisions, open todos |
| `scp timeline --project N [--limit n]` | Full chronological history |
| `scp doctor` | WAL/FK/FTS/config/stale-session health checks |

## Configuration

See the commented [config.yaml](../config.yaml). Ranking weights and the token budget are
documented in [docs/05-hydration-ranking.md](05-hydration-ranking.md).

## Logs

Structured JSON, one file per day: `storage/logs/scp-YYYY-MM-DD.jsonl` (never stdout —
stdout is the MCP transport).
