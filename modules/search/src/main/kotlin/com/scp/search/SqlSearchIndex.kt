package com.scp.search

import com.scp.database.ScpDatabase
import com.scp.database.SearchEntries
import com.scp.database.adapter.FtsAdmin
import app.cash.sqldelight.db.SqlDriver
import com.scp.model.ContextEntry
import com.scp.model.port.SearchHit
import com.scp.model.port.SearchIndex
import com.scp.model.port.SearchRequest
import kotlinx.datetime.Instant

/**
 * FTS5 external-content search: one generated query composes MATCH with all structured
 * filters (project, type, tag, date range) — docs/03 §4. bm25 pre-selection only; the
 * caller (core) applies the shared scoring function for presented order.
 */
public class SqlSearchIndex(
    private val database: ScpDatabase,
    private val driver: SqlDriver,
) : SearchIndex {
    override fun search(request: SearchRequest): List<SearchHit> {
        val ftsQuery = toFtsQuery(request.query)
        if (ftsQuery.isBlank()) return emptyList()
        val rows =
            database.contextEntryFtsQueries
                .searchEntries(
                    query = ftsQuery,
                    projectId = request.projectId,
                    type = request.type,
                    fromTs = request.from?.toString(),
                    toTs = request.to?.toString(),
                    tag = request.tag,
                    limit = request.limit,
                ).executeAsList()
        if (rows.isEmpty()) return emptyList()
        val tagsByEntry =
            database.contextEntryTagQueries
                .tagsForEntries(rows.map { it.id })
                .executeAsList()
                .groupBy({ it.entry_id }, { it.tag })
        return rows.map { row -> row.toHit(tagsByEntry[row.id].orEmpty()) }
    }

    override fun indexedEntryCount(): Long = database.contextEntryFtsQueries.countIndex().executeAsOne()

    override fun rebuild() {
        FtsAdmin.rebuild(driver)
    }

    private fun SearchEntries.toHit(tags: List<String>): SearchHit =
        SearchHit(
            entry =
                ContextEntry(
                    id = id,
                    sessionId = session_id,
                    timestamp = Instant.parse(timestamp),
                    title = title,
                    content = content,
                    type = type,
                    tags = tags,
                    priority = priority.toInt(),
                ),
            projectName = project_name,
            toolName = tool_name,
            ftsRank = fts_rank,
        )

    private companion object {
        /**
         * Raw user text is turned into a safe FTS5 query: each whitespace token becomes a
         * quoted phrase (implicit AND), so MATCH syntax characters in user input can never
         * produce an FTS5 parse error.
         */
        fun toFtsQuery(raw: String): String =
            raw
                .split(Regex("\\s+"))
                .filter { it.isNotBlank() }
                .joinToString(" ") { token -> "\"" + token.replace("\"", "\"\"") + "\"" }
    }
}
