package com.scp.core.usecase

import com.scp.core.FakeContextEntryRepository
import com.scp.core.FakeDecisionRepository
import com.scp.core.FakeProjectRepository
import com.scp.core.FakeSessionRepository
import com.scp.core.FakeTodoRepository
import com.scp.model.ContextTrustNotice
import com.scp.model.Decision
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.Todo
import com.scp.model.mcp.ProjectSummaryInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ProjectSummaryUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val sessions = FakeSessionRepository()
    private val entries = FakeContextEntryRepository(sessions)
    private val decisions = FakeDecisionRepository()
    private val todos = FakeTodoRepository()

    private val useCase =
        ProjectSummaryUseCase(
            projects = projects,
            sessions = sessions,
            entries = entries,
            decisions = decisions,
            todos = todos,
        )

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "A demo project for summary tests", t0, t0))
        sessions.insert(Session("s1", "p1", "claude-code", t0))
        decisions.insert(Decision("d1", "p1", "use SQLite", "SQLite over Postgres", "simplicity", createdAt = t0, updatedAt = t0))
        todos.insert(Todo("t1", "p1", "write more tests", createdAt = t0))
    }

    @Test
    fun `unknown project fails with NotFound`() {
        assertFailsWith<NotFoundException> {
            useCase.execute(ProjectSummaryInput(projectName = "ghost"))
        }
    }

    @Test
    fun `summarizes counts and decisions and carries the trust notice`() {
        val result = useCase.execute(ProjectSummaryInput(projectName = "demo"))
        assertEquals(ContextTrustNotice.TEXT, result.trustNotice)
        assertEquals("demo", result.projectName)
        assertEquals(1, result.sessionCount)
        assertEquals(1, result.majorDecisions.size)
        assertEquals(1, result.openTodos.size)
        assertEquals(1, result.recentSessions.size)
    }
}
