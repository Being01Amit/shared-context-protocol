package com.scp.core

import com.scp.model.ContextType
import com.scp.model.HydrationQuery
import com.scp.model.RankableItem
import com.scp.model.RankingWeights
import kotlinx.datetime.Instant
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScoringTest {
    private val now = Instant.parse("2026-07-04T00:00:00Z")
    private val weights = RankingWeights()

    private fun item(
        ageHours: Long = 0,
        priority: Int = 3,
        tags: Set<String> = emptySet(),
        type: ContextType = ContextType.FEATURE,
    ) = RankableItem(
        timestamp = Instant.fromEpochMilliseconds(now.toEpochMilliseconds() - ageHours * 3_600_000),
        priority = priority,
        tags = tags,
        type = type,
    )

    private fun query(tags: Set<String> = emptySet()) = HydrationQuery("p1", tags, now)

    @Test
    fun `docs worked example - 3-day-old DECISION priority 4 tags overlap 1 of 3`() {
        // recency = 2^(-72/168) = 0.7430, priorityNorm = 0.75, jaccard = 1/3, type = 1.0
        // score = 0.35*0.7430 + 0.25*0.75 + 0.25*0.3333 + 0.15*1.0 = 0.6809
        val score =
            Scoring.scoreEntry(
                item(ageHours = 72, priority = 4, tags = setOf("auth", "security"), type = ContextType.DECISION),
                query(tags = setOf("auth", "jwt")),
                weights,
            )
        assertTrue(abs(score - 0.6809) < 0.001, "expected ~0.6809, got $score")
    }

    @Test
    fun `fresh max-priority decision with full tag overlap scores 1`() {
        val score =
            Scoring.scoreEntry(
                item(ageHours = 0, priority = 5, tags = setOf("x"), type = ContextType.DECISION),
                query(tags = setOf("x")),
                weights,
            )
        assertEquals(1.0, score, 1e-9)
    }

    @Test
    fun `recency halves at exactly one half-life`() {
        val fresh = Scoring.scoreEntry(item(ageHours = 0, priority = 1), query(), weights)
        val aged = Scoring.scoreEntry(item(ageHours = 7 * 24, priority = 1), query(), weights)
        // Only the recency term differs; it must halve.
        val freshRecency = fresh - (weights.priority * 0.0) - (weights.type * 0.8)
        val agedRecency = aged - (weights.priority * 0.0) - (weights.type * 0.8)
        assertEquals(freshRecency / 2, agedRecency, 1e-9)
    }

    @Test
    fun `empty query tags contribute zero without error`() {
        val withTags = item(tags = setOf("a", "b"))
        val score = Scoring.scoreEntry(withTags, query(tags = emptySet()), weights)
        val scoreNoEntryTags = Scoring.scoreEntry(item(tags = emptySet()), query(tags = emptySet()), weights)
        assertEquals(score, scoreNoEntryTags, 1e-9, "tag term must be 0 either way")
    }

    @Test
    fun `priority normalizes 1 to 0 and 5 to 1`() {
        val low = Scoring.scoreEntry(item(priority = 1), query(), weights)
        val high = Scoring.scoreEntry(item(priority = 5), query(), weights)
        assertEquals(weights.priority, high - low, 1e-9)
    }

    @Test
    fun `future timestamps clamp to age zero instead of exploding`() {
        val future =
            RankableItem(
                timestamp = Instant.parse("2027-01-01T00:00:00Z"),
                priority = 3,
                tags = emptySet(),
                type = ContextType.FEATURE,
            )
        val score = Scoring.scoreEntry(future, query(), weights)
        assertTrue(score <= 1.0, "clamped score stays in range, got $score")
    }

    @Test
    fun `type multiplier ordering follows configured defaults`() {
        val decision = Scoring.scoreEntry(item(type = ContextType.DECISION), query(), weights)
        val meeting = Scoring.scoreEntry(item(type = ContextType.MEETING), query(), weights)
        assertTrue(decision > meeting, "DECISION must outrank MEETING at equal age/priority")
    }

    @Test
    fun `every context type has a default multiplier`() {
        ContextType.entries.forEach { type ->
            assertTrue(RankingWeights.DEFAULT_TYPE_MULTIPLIERS.containsKey(type), "missing default for $type")
        }
    }
}
