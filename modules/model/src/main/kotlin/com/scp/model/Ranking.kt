package com.scp.model

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Weights for the single shared ranking function (`core.scoreEntry`). Every term is
 * normalized to [0, 1]; the default weights sum to 1.0 so scores are comparable across
 * entity kinds and across time. See docs/05-hydration-ranking.md.
 *
 * [semantic] is the reserved extension point for embedding-based similarity: it defaults
 * to 0.0 so existing configs keep identical behavior until vector search lands.
 */
@Serializable
public data class RankingWeights(
    val recency: Double = 0.35,
    val priority: Double = 0.25,
    val tagOverlap: Double = 0.25,
    val type: Double = 0.15,
    val semantic: Double = 0.0,
    val recencyHalfLifeDays: Double = 7.0,
    val typeMultipliers: Map<ContextType, Double> = emptyMap(),
) {
    /** Effective multiplier for [contextType]: configured value if present, else the documented default. */
    public fun multiplierFor(contextType: ContextType): Double =
        typeMultipliers[contextType] ?: DEFAULT_TYPE_MULTIPLIERS.getValue(contextType)

    public companion object {
        /** Defaults from docs/05-hydration-ranking.md §3 — one value per ContextType. */
        public val DEFAULT_TYPE_MULTIPLIERS: Map<ContextType, Double> =
            mapOf(
                ContextType.DECISION to 1.0,
                ContextType.ARCHITECTURE to 1.0,
                ContextType.SECURITY to 0.9,
                ContextType.BUG to 0.9,
                ContextType.TASK to 0.8,
                ContextType.FEATURE to 0.8,
                ContextType.REFACTOR to 0.7,
                ContextType.PERFORMANCE to 0.7,
                ContextType.RELEASE to 0.6,
                ContextType.COMMIT to 0.6,
                ContextType.PROMPT to 0.6,
                ContextType.TESTING to 0.6,
                ContextType.RESEARCH to 0.5,
                ContextType.DOCUMENTATION to 0.5,
                ContextType.LEARNING to 0.4,
                ContextType.MEETING to 0.3,
            )
    }
}

/** The hydrate/search request context the ranking function scores against. */
public data class HydrationQuery(
    val projectId: String,
    val tags: Set<String> = emptySet(),
    val now: Instant,
    val queryEmbedding: FloatArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is HydrationQuery) return false
        if (projectId != other.projectId) return false
        if (tags != other.tags) return false
        if (now != other.now) return false
        if (queryEmbedding != null) {
            if (other.queryEmbedding == null) return false
            if (!queryEmbedding.contentEquals(other.queryEmbedding)) return false
        } else if (other.queryEmbedding != null) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = projectId.hashCode()
        result = 31 * result + tags.hashCode()
        result = 31 * result + now.hashCode()
        result = 31 * result + (queryEmbedding?.contentHashCode() ?: 0)
        return result
    }
}

/**
 * Uniform shape every rankable entity is mapped onto before scoring, per the mapping
 * table in docs/05-hydration-ranking.md §1: Decisions rank as always-high-priority
 * DECISION items, Todos as priority-4 TASK items.
 */
public data class RankableItem(
    val timestamp: Instant,
    val priority: Int,
    val tags: Set<String>,
    val type: ContextType,
    val embedding: FloatArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RankableItem) return false
        if (timestamp != other.timestamp) return false
        if (priority != other.priority) return false
        if (tags != other.tags) return false
        if (type != other.type) return false
        if (embedding != null) {
            if (other.embedding == null) return false
            if (!embedding.contentEquals(other.embedding)) return false
        } else if (other.embedding != null) {
            return false
        }
        return true
    }

    override fun hashCode(): Int {
        var result = timestamp.hashCode()
        result = 31 * result + priority
        result = 31 * result + tags.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + (embedding?.contentHashCode() ?: 0)
        return result
    }

    public companion object {
        public fun fromEntry(entry: ContextEntry): RankableItem =
            RankableItem(
                timestamp = entry.timestamp,
                priority = entry.priority,
                tags = entry.tags.map { it.lowercase() }.toSet(),
                type = entry.type,
                embedding = entry.embedding,
            )

        public fun fromDecision(decision: Decision): RankableItem =
            RankableItem(decision.updatedAt, ContextEntry.MAX_PRIORITY, emptySet(), ContextType.DECISION)

        public fun fromTodo(todo: Todo): RankableItem =
            RankableItem(todo.createdAt, priority = 4, tags = emptySet(), type = ContextType.TASK)
    }
}
