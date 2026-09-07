package com.scp.core.usecase

import com.scp.core.FakeContextEntryRepository
import com.scp.core.FakeProjectRepository
import com.scp.core.FakeSessionRepository
import com.scp.model.ContextEntry
import com.scp.model.ContextTrustNotice
import com.scp.model.ContextType
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.mcp.TimelineInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val sessions = FakeSessionRepository()
    private val entries = FakeContextEntryRepository(sessions)

    private val useCase = TimelineUseCase(projects = projects, sessions = sessions, entries = entries)

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "A demo project for timeline tests", t0, t0))
        sessions.insert(Session("s1", "p1", "claude-code", t0))
        entries.insert(ContextEntry("e1", "s1", t0, "first entry", "body", ContextType.TASK))
    }

    @Test
    fun `unknown project fails with NotFound`() {
        assertFailsWith<NotFoundException> {
            useCase.execute(TimelineInput(projectName = "ghost"))
        }
    }

    @Test
    fun `returns the full untruncated history with its entries and the trust notice`() {
        val result = useCase.execute(TimelineInput(projectName = "demo"))
        assertEquals(ContextTrustNotice.TEXT, result.trustNotice)
        assertEquals(1, result.totalSessions)
        assertFalse(result.truncated)
        assertEquals(1, result.sessions.size)
        assertEquals(
            "e1",
            result.sessions
                .first()
                .entries
                .first()
                .id,
        )
    }

    @Test
    fun `a limit smaller than the session count is reported as truncated`() {
        sessions.insert(Session("s2", "p1", "claude-code", Instant.parse("2026-07-02T00:00:00Z")))
        val result = useCase.execute(TimelineInput(projectName = "demo", limit = 1))
        assertEquals(2, result.totalSessions)
        assertEquals(1, result.sessions.size)
        assertTrue(result.truncated)
    }
}
