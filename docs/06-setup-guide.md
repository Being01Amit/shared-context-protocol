# SCP Setup Guide

## Prerequisites

- **JDK 17+** (Temurin recommended). Verify: `java -version`.
- No Gradle install needed — the repo ships the wrapper (`gradlew` / `gradlew.bat`), only
  required if you build from source.

## Install

Installs `scpx` and `scp-mcp-server` from the latest [GitHub Release](https://github.com/Being01Amit/shared-context-protocol/releases)
into a fixed per-user location and adds them to your PATH. Safe to re-run — each run is
an in-place upgrade, so the registered MCP server path below never changes across versions.

**Windows (PowerShell):**

```powershell
irm https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.ps1 | iex
```

**macOS / Linux:**

```sh
curl -fsSL https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.sh | sh
```

Both scripts accept a specific version to pin (e.g. `.\install.ps1 -Version v0.2.0` /
`./install.sh v0.2.0`) and install under `$env:LOCALAPPDATA\scp` /
`$HOME/.scp` by default (override with `SCP_INSTALL_DIR`). Open a new shell afterward so
the PATH change takes effect.

## Build from source

Only needed if you're contributing to SCP itself, or want a build that isn't yet released.

```powershell
.\gradlew.bat build          # everything: compile, tests, ktlint, detekt
.\gradlew.bat installDist    # runnable distributions for both apps
```

Distributions land in:

- `apps/cli/build/install/scpx/bin/scpx[.bat]`
- `apps/mcp-server/build/install/scp-mcp-server/bin/scp-mcp-server[.bat]`

> **Windows note:** if any JVM step fails with `Unable to establish loopback connection`,
> your machine blocks AF_UNIX sockets under `%APPDATA%`-adjacent temp dirs (JDK NIO
> selectors create socket files in the temp dir). Fix: set `TMP`/`TEMP` to a directory
> outside `AppData` (e.g. `C:\Users\<you>\.jvmtmp`) for JVM processes.

## Initialize a workspace

SCP stores everything relative to its working directory (override with `-Dscp.home=<dir>`):

```powershell
cd <your-scp-workspace>
scpx init                                  # storage dirs + default config.yaml + database
scpx create-project --name my-app --description "Payments service rewrite"
scpx list-projects
```

## Register the MCP server with an AI tool

Any MCP-compatible client works identically — that is the point. The server speaks MCP
over stdio and exposes: `update_context`, `hydrate_context`, `search_context`,
`project_summary`, `timeline`, `list_projects`, `save_note`, `create_project`,
`update_todo_status`, `update_decision_status`, `update_project`, `claim_todo`,
`release_todo`.

Use the path printed at the end of the installer (`$env:LOCALAPPDATA\scp\scp-mcp-server\bin\scp-mcp-server.bat`
on Windows, `$HOME/.scp/scp-mcp-server/bin/scp-mcp-server` on macOS/Linux) — it stays the
same across upgrades. Building from source instead, use
`<repo>/apps/mcp-server/build/install/scp-mcp-server/bin/scp-mcp-server.bat`.

**Claude Code:**

```powershell
claude mcp add scp -- "<path-from-above>"
```

**Generic client config (JSON):**

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
- Everything is mirrored human-readably, **project-first then date-first**:

  ```text
  storage/projects/{project}/
    PROJECT.md    index: resume point + every session, newest first
    LATEST.md     copy of the newest session — open this to see where work stopped
    2026-08-05T14-25-30Z-claude-code-a3f9c1d2.md
  ```

- `hydrate_context` returns a **`resumePoint` first**: what the last agent did
  (`session.summary`) and where it stopped (`session.nextStep`), plus files in flight and blocking
  todos. It is charged to the token budget before every other section, so it can never be truncated
  away — a resuming agent continues from there instead of asking what was done. If the repo's git
  branch/commit have moved since that session started, `resumePoint.gitStateNotice` says so.

  Write `nextStep` on every save; it is what the next agent reads first:

  ```powershell
  scpx update --project my-app --summary "what changed" --next-step "what to do next"
  ```

> **Upgrading an existing workspace.** The markdown root moved from `storage/markdown` to
> `storage/projects`. A `config.yaml` written before this change pins the old path explicitly —
> change `markdownPath` to `storage/projects` (or delete the line to take the new default). The
> database migrates itself on first open (schema v1 → v2 adds `session.next_step`); no data is lost
> and no manual step is needed.
- Secrets (API keys, tokens, private keys, `.env` values) are redacted before storage.

## CLI reference

| Command | Purpose |
|---|---|
| `scpx init` | Create storage dirs, default `config.yaml`, database schema |
| `scpx create-project --name N [--description D]` | Register a project |
| `scpx list-projects` | Projects with session counts |
| `scpx update --project N [--tool T] [--summary S] [--next-step S] [--keep-open] [--json payload.json]` | Store context (full payload via `--json`) |
| `scpx hydrate --project N [--tag t]... [--token-limit n]` | Ranked resume payload (JSON) |
| `scpx search <query> [--project N] [--type BUG] [--tag t] [--from ISO] [--to ISO] [--limit n]` | Full-text + filters |
| `scpx summary --project N` | Statistics, decisions, open todos |
| `scpx timeline --project N [--limit n]` | Full chronological history |
| `scpx update-todo-status --project N --todo-id ID --status S` | Mark a todo OPEN/IN_PROGRESS/DONE/DROPPED |
| `scpx update-decision-status --project N --decision-id ID --status S` | Mark a decision OPEN/ACCEPTED/SUPERSEDED/REJECTED |
| `scpx update-project --project N --description D` | Edit a project's description |
| `scpx claim-todo --project N --todo-id ID --tool T` | Claim a todo (fails if claimed by another tool) |
| `scpx release-todo --project N --todo-id ID --tool T` | Release a todo you claimed |
| `scpx doctor` | Resolved workspace paths, WAL/FK/FTS/config checks, stale sessions, and **silent projects** (created but never written to — the symptom of an agent that never calls `update_context`) |

## Configuration

See the commented [config.yaml](../config.yaml). Ranking weights and the token budget are
documented in [docs/05-hydration-ranking.md](05-hydration-ranking.md).

## Encryption at rest (optional)

Set the `SCP_DB_KEY` environment variable to a passphrase and the SQLite database is opened
with transparent whole-file encryption (SQLite3MultipleCiphers). The file is ciphertext on
disk and decrypted in memory, so search, ranking, and hydration are unaffected. The key is
never written to disk (keep it out of `config.yaml` and the repo).

```powershell
$env:SCP_DB_KEY = 'a-long-random-passphrase'
scpx init            # creates an encrypted database
```

For an MCP client, pass it in the server's `env` block alongside `SCP_LOG_DIR`:

```json
"env": { "SCP_DB_KEY": "a-long-random-passphrase", "SCP_LOG_DIR": "<workspace>/storage/logs" }
```

Notes:

- **The key is required to read the data.** A wrong or missing key fails fast with a clear
  message — there is no recovery without it, so store the passphrase safely.
- **Markdown mirrors are disabled while encryption is on** — a plaintext `.md` copy would leak
  exactly what the database encrypts, so the encrypted DB is the single source of truth.
- Encryption is chosen per database by the key's presence; it is not a `config.yaml` setting.
  To encrypt an existing plaintext workspace, start a fresh encrypted one (there is no
  in-place migration in v1).

## Logs

Structured JSON, one file per day: `storage/logs/scp-YYYY-MM-DD.jsonl` (never stdout —
stdout is the MCP transport).
