package com.scp.core.usecase

import com.scp.core.FakeProjectRepository
import com.scp.core.FakeSearchIndex
import com.scp.core.FixedClock
import com.scp.model.ContextEntry
import com.scp.model.ContextTrustNotice
import com.scp.model.ContextType
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.RankingWeights
import com.scp.model.mcp.SearchContextInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SearchContextUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val search = FakeSearchIndex()
    private val clock = FixedClock(Instant.parse("2026-07-04T00:00:00Z"))

    private val useCase =
        SearchContextUseCase(
            search = search,
            projects = projects,
            clock = clock,
            weights = RankingWeights(),
            defaultLimit = 50L,
        )

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "A demo project for search tests", t0, t0))
        search.index(
            ContextEntry(
                "e1",
                "s1",
                t0,
                "jwt rotation",
                "we rotate refresh tokens hourly",
                ContextType.DECISION,
                listOf("auth"),
            ),
            projectId = "p1",
            projectName = "demo",
            toolName = "claude-code",
        )
    }

    @Test
    fun `unknown project fails with NotFound`() {
        assertFailsWith<NotFoundException> {
            useCase.execute(SearchContextInput(query = "jwt", projectName = "ghost"))
        }
    }

    @Test
    fun `finds matches and carries the trust notice`() {
        val result = useCase.execute(SearchContextInput(query = "jwt"))
        assertEquals(ContextTrustNotice.TEXT, result.trustNotice)
        assertEquals(1, result.items.size)
        assertEquals(
            "e1",
            result.items
                .first()
                .entry.id,
        )
    }
}
