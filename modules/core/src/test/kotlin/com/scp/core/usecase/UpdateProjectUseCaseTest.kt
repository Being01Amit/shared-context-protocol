package com.scp.core.usecase

import com.scp.core.FakeProjectRepository
import com.scp.core.FixedClock
import com.scp.core.PassThroughTransactionRunner
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.mcp.UpdateProjectInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateProjectUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val transactions = PassThroughTransactionRunner()
    private val clock = FixedClock(Instant.parse("2026-07-04T12:00:00Z"))
    private val useCase = UpdateProjectUseCase(projects, transactions, clock)

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "old description", t0, t0))
    }

    @Test
    fun `updates the description and bumps updatedAt`() {
        val result = useCase.execute(UpdateProjectInput("demo", "new description"))
        assertEquals("new description", result.description)
        assertEquals(clock.now(), result.updatedAt)
        assertEquals("new description", projects.store.getValue("p1").description)
        assertEquals(clock.now(), projects.store.getValue("p1").updatedAt)
    }

    @Test
    fun `unknown project fails with NotFound`() {
        assertFailsWith<NotFoundException> { useCase.execute(UpdateProjectInput("ghost", "new description")) }
    }
}
