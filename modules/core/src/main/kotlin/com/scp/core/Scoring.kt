package com.scp.core

import com.scp.model.HydrationQuery
import com.scp.model.RankableItem
import com.scp.model.RankingWeights
import com.scp.model.VectorUtils
import kotlin.math.pow

/**
 * The single ranking mechanism (docs/05). Used everywhere ranking is needed —
 * hydration section ordering and search relevance — never duplicated ad hoc.
 * Pure function: no I/O, directly unit-testable.
 */
public object Scoring {
    private const val MILLIS_PER_HOUR = 3_600_000.0
    private const val HOURS_PER_DAY = 24.0

    /** Stored priority is 1..5; the normalized span is (5 - 1) = 4. */
    private const val PRIORITY_SPAN = 4

    public fun scoreEntry(item: RankableItem, query: HydrationQuery, weights: RankingWeights): Double {
        val recency = recencyDecay(item, query, weights)
        val priorityNorm = (item.priority - 1).coerceIn(0, PRIORITY_SPAN) / PRIORITY_SPAN.toDouble()
        val tagJaccard = jaccard(item.tags, query.tags)
        val typeWeight = weights.multiplierFor(item.type).coerceIn(0.0, 1.0)
        val semanticSimilarity =
            if (weights.semantic > 0.0 && item.embedding != null && query.queryEmbedding != null) {
                VectorUtils.cosineSimilarity(item.embedding, query.queryEmbedding)
            } else {
                0.0
            }
        return (weights.recency * recency) +
            (weights.priority * priorityNorm) +
            (weights.tagOverlap * tagJaccard) +
            (weights.type * typeWeight) +
            (weights.semantic * semanticSimilarity)
    }

    private fun recencyDecay(item: RankableItem, query: HydrationQuery, weights: RankingWeights): Double {
        val ageHours = (query.now - item.timestamp).inWholeMilliseconds.coerceAtLeast(0) / MILLIS_PER_HOUR
        val halfLifeHours = weights.recencyHalfLifeDays * HOURS_PER_DAY
        return 2.0.pow(-ageHours / halfLifeHours)
    }

    /** Empty union is defined as similarity 0 — a query without tags simply contributes nothing. */
    private fun jaccard(entryTags: Set<String>, queryTags: Set<String>): Double {
        if (entryTags.isEmpty() || queryTags.isEmpty()) return 0.0
        val intersection = entryTags.count { it in queryTags }
        val union = (entryTags + queryTags).size
        return intersection.toDouble() / union
    }
}
