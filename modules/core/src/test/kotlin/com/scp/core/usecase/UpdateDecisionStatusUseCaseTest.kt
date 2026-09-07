package com.scp.core.usecase

import com.scp.core.FakeDecisionRepository
import com.scp.core.FakeProjectRepository
import com.scp.core.FixedClock
import com.scp.core.PassThroughTransactionRunner
import com.scp.model.Decision
import com.scp.model.DecisionStatus
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.mcp.UpdateDecisionStatusInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateDecisionStatusUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val decisions = FakeDecisionRepository()
    private val transactions = PassThroughTransactionRunner()
    private val clock = FixedClock(Instant.parse("2026-07-04T12:00:00Z"))
    private val useCase = UpdateDecisionStatusUseCase(projects, decisions, transactions, clock)

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "demo project", t0, t0))
        decisions.insert(Decision("d1", "p1", "db choice", "use SQLite", "simplicity", createdAt = t0, updatedAt = t0))
    }

    @Test
    fun `marks a decision accepted and touches the project`() {
        val result = useCase.execute(UpdateDecisionStatusInput("demo", "d1", DecisionStatus.ACCEPTED))
        assertEquals(DecisionStatus.ACCEPTED, result.status)
        assertEquals(clock.now(), result.updatedAt)
        assertEquals(DecisionStatus.ACCEPTED, decisions.listByProject("p1").single().status)
        assertEquals(clock.now(), projects.store.getValue("p1").updatedAt)
    }

    @Test
    fun `unknown project fails before touching decisions`() {
        assertFailsWith<NotFoundException> {
            useCase.execute(UpdateDecisionStatusInput("ghost", "d1", DecisionStatus.ACCEPTED))
        }
        assertEquals(DecisionStatus.OPEN, decisions.listByProject("p1").single().status)
    }

    @Test
    fun `unknown decision id fails cleanly instead of crashing`() {
        assertFailsWith<NotFoundException> {
            useCase.execute(UpdateDecisionStatusInput("demo", "ghost-decision", DecisionStatus.ACCEPTED))
        }
    }
}
