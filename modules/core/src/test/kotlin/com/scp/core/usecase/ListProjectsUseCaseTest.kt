package com.scp.core.usecase

import com.scp.core.FakeProjectRepository
import com.scp.core.FakeSessionRepository
import com.scp.model.Project
import com.scp.model.Session
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ListProjectsUseCaseTest {
    private val projects = FakeProjectRepository()
    private val sessions = FakeSessionRepository()
    private val useCase = ListProjectsUseCase(projects, sessions)

    @Test
    fun `reports creation and last-update times separately, with session counts`() {
        val created = Instant.parse("2026-07-01T00:00:00Z")
        val touched = Instant.parse("2026-07-10T00:00:00Z")
        projects.insert(Project("p1", "alpha", "d", createdAt = created, updatedAt = touched))
        projects.insert(Project("p2", "beta", "d", createdAt = created, updatedAt = created))
        sessions.insert(Session("s1", "p1", "claude-code", touched))

        val items = useCase.execute().projects.associateBy { it.name }

        // doctor's silent-project check ages projects by createdAt; updatedAt moves on edits that
        // record no context, so the two must not be conflated.
        assertEquals(created, items.getValue("alpha").createdAt)
        assertEquals(touched, items.getValue("alpha").updatedAt)
        assertEquals(1L, items.getValue("alpha").sessionCount)
        assertEquals(0L, items.getValue("beta").sessionCount)
    }
}
