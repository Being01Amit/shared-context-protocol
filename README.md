# Shared Context Protocol (SCP)

**Git for AI Context** — a local-first, cross-AI persistent context and memory layer that sits behind the Model Context Protocol (MCP).

SCP is **not** an AI. It is the storage and retrieval layer that lets Claude Code, ChatGPT, Cursor, Antigravity, Gemini CLI, and any future MCP-compatible tool continue the same project without losing context:

```
Claude Code → work 2h → /update-context → SCP stores everything
                     ...later...
Antigravity → /hydrate-context → continues exactly where Claude stopped
```

## Status

| Phase | Scope | State |
|-------|-------|-------|
| 1 | Architecture, module structure, SQLDelight schema, technology decisions, session resolution, hydration ranking spec | ✅ Done |
| 2 | Gradle scaffold, `model` + `config` + `database` modules (compiling, tested) | ✅ Done |
| 3 | `core` use-cases, `search`, `markdown`, `skills` | ✅ Done |
| 4 | MCP server (stdio, official Kotlin SDK), CLI (Clikt) | ✅ Done |
| 5 | Integration tests (incl. concurrent writes), `scp doctor`, setup guide, roadmap | ✅ Done |

Quick start — install, then see the **[setup guide](docs/06-setup-guide.md)** for `scp init` and MCP client registration:

```powershell
# Windows
irm https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.ps1 | iex
```

```sh
# macOS / Linux
curl -fsSL https://raw.githubusercontent.com/Being01Amit/shared-context-protocol/main/scripts/install.sh | sh
```

Building from source instead: `.\gradlew.bat build`, then `.\gradlew.bat installDist` for runnable `scp` / `scp-mcp-server` distributions.

## Documentation

- [Architecture & module structure](docs/01-architecture.md)
- [Technology decisions](docs/02-technology-decisions.md) — pinned stack, JVM cold-start call, deviations flagged for approval
- [Database schema (SQLDelight)](docs/03-database-schema.md) — tables, FTS5 external-content design, PRAGMAs, indexes
- [Session resolution](docs/04-session-resolution.md) — pseudocode + sequence diagrams, concurrency handling
- [Hydration ranking](docs/05-hydration-ranking.md) — scoring formula, default weights, token budget algorithm
- [Setup guide](docs/06-setup-guide.md) — install, build, init, MCP client registration, CLI reference
- [Roadmap](docs/07-roadmap.md) — future features and the extension points already reserved for them
- [Releasing](docs/08-releasing.md) — how versioned releases are cut
- [Security](SECURITY.md) — known limitations, including an unresolved prompt-injection / memory-poisoning risk in shared context — read this before relying on SCP across agents you don't fully trust

## Design principles (non-negotiable)

1. **Local first** — fully offline, zero network calls on the core path.
2. **Cross-AI** — no client-specific storage; any MCP client works identically.
3. **Fast** — hydration < 1s for ~500 entries / ~50 sessions, including JVM startup.
4. **Human readable** — everything mirrored to plain Markdown under `storage/projects/`.
5. **Extensible** — concrete extension points for embeddings, sync, git integration.
6. **Concurrency-safe** — WAL mode + transactions + retry; concurrent tools never corrupt or silently merge each other's work.
7. **Bounded output** — every read path has an explicit token ceiling; truncation is always signaled, never silent.
