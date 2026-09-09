# SCP Roadmap

Future features and the **concrete extension points already reserved for them** in v1.
Everything here is strictly additive and opt-in; the local-only path keeps working with
zero network calls.

## 1. Embeddings / vector search

- **Reserved:** `context_entry.embedding BLOB` column (nullable, unused). Adding vectors
  is an `UPDATE`, not a migration.
- **Reserved:** `RankingWeights.semantic` (default `0.0`) — adding a
  `w5 · semanticSimilarity` term extends one sum in `Scoring.scoreEntry`; no interface
  change, existing configs behave identically.
- Plan: local embedding model (e.g. via ONNX runtime), cosine similarity over candidate
  sets pre-selected by FTS5; hybrid keyword + semantic retrieval.

## 2. Git integration

- **Reserved:** `file.hash` column and the `COMMIT` context type.
- Plan: a `GitAdapter` implementing a new `model` port; automatic commit summaries as
  `COMMIT` entries on `update_context`; hydration section "recent commits".

## 3. Cloud sync / multi-machine

- **Reserved:** UUID v4 TEXT primary keys (no cross-machine collisions) and ISO 8601 UTC
  timestamps (total ordering) — a sync/merge protocol needs no ID migration.
- Plan: append-oriented sync of sessions/entries (rows are immutable after session
  close), last-writer-wins on project metadata, explicit conflict entries otherwise.

## 4. Multi-user / conflict resolution

- **Reserved:** sessions are per-tool by design and never merged — the same isolation
  property a multi-writer merge protocol needs. `owner` on todos already exists.

## 5. HTTP/SSE transport

- **Reserved:** `modules/api` slot in settings.gradle.kts; the Kotlin MCP SDK ships Ktor
  `mcp { }` extensions. The stdio path is unaffected.

## 6. Session archival / compaction

- Deliberately **not** in v1 (was underspecified). When wanted: summarize sessions older
  than N days into a rollup entry, as an explicit opt-in command with its own spec.

## 7. Kotlin Multiplatform

- `core` and `model` are pure Kotlin with zero JVM-only I/O — the natural first modules
  to move to KMP targets (native CLI binary, JS editor extension). SQLDelight `.sq`
  files carry over to the native driver unchanged.

## 8. Native-image startup (ADR-2 revisit trigger)

- Only if a profiled `scpx hydrate` exceeds 800 ms end-to-end on a medium project:
  GraalVM native-image builds for `apps/cli` and `apps/mcp-server`.
