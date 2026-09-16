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

    /**
     * Whether the index matches the stored entries — `scpx doctor`'s health check. Must verify the
     * index itself: an index row count is not evidence, since for an external-content FTS5 table
     * it is read from the content table and always matches.
     */
    public fun isConsistent(): Boolean

    /** FTS5 external-content 'rebuild' — the documented repair when the index has diverged. */
    public fun rebuild()
}
