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
)

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
) {
    public companion object {
        public fun fromEntry(entry: ContextEntry): RankableItem =
            RankableItem(entry.timestamp, entry.priority, entry.tags.map { it.lowercase() }.toSet(), entry.type)

        public fun fromDecision(decision: Decision): RankableItem =
            RankableItem(decision.updatedAt, ContextEntry.MAX_PRIORITY, emptySet(), ContextType.DECISION)

        public fun fromTodo(todo: Todo): RankableItem =
            RankableItem(todo.createdAt, priority = 4, tags = emptySet(), type = ContextType.TASK)
    }
}
