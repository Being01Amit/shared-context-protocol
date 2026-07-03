package com.scp.model.port

import com.scp.model.ContextEntry
import com.scp.model.ContextType
import kotlinx.datetime.Instant

/** Full-text query composed with optional structured filters — one SQL query, not chained lookups. */
public data class SearchRequest(
    val query: String,
    val projectId: String? = null,
    val type: ContextType? = null,
    val tag: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val limit: Long = 50,
)

public data class SearchHit(
    val entry: ContextEntry,
    val projectName: String,
    val toolName: String,
    /** Raw FTS5 bm25 rank — pre-selection order only; presented order comes from the shared scoring function. */
    val ftsRank: Double,
)

public interface SearchIndex {
    public fun search(request: SearchRequest): List<SearchHit>

    /** Rows in the FTS index — `scp doctor` compares this against the context entry count. */
    public fun indexedEntryCount(): Long

    /** FTS5 external-content 'rebuild' — the documented repair when counts diverge. */
    public fun rebuild()
}
