package com.scp.core.usecase

import com.scp.core.FakeProjectRepository
import com.scp.core.FakeTodoRepository
import com.scp.core.FixedClock
import com.scp.core.PassThroughTransactionRunner
import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.Todo
import com.scp.model.mcp.ClaimTodoInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ClaimTodoUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val todos = FakeTodoRepository()
    private val transactions = PassThroughTransactionRunner()
    private val clock = FixedClock(Instant.parse("2026-07-04T12:00:00Z"))
    private val useCase = ClaimTodoUseCase(projects, todos, transactions, clock)

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "demo project", t0, t0))
        todos.insert(Todo(id = "t1", projectId = "p1", description = "write docs", createdAt = t0))
    }

    @Test
    fun `claims an unclaimed todo`() {
        val result = useCase.execute(ClaimTodoInput("demo", "t1", "claude-code"))
        assertEquals("claude-code", result.owner)
        assertEquals("claude-code", todos.listByProject("p1").single().owner)
        assertEquals(clock.now(), projects.store.getValue("p1").updatedAt)
    }

    @Test
    fun `claiming your own already-claimed todo is idempotent`() {
        useCase.execute(ClaimTodoInput("demo", "t1", "claude-code"))
        val result = useCase.execute(ClaimTodoInput("demo", "t1", "claude-code"))
        assertEquals("claude-code", result.owner)
    }

    @Test
    fun `claiming a todo owned by someone else fails`() {
        useCase.execute(ClaimTodoInput("demo", "t1", "claude-code"))
        assertFailsWith<InvalidInputException> { useCase.execute(ClaimTodoInput("demo", "t1", "antigravity")) }
        assertEquals("claude-code", todos.listByProject("p1").single().owner, "failed claim must not overwrite")
    }

    @Test
    fun `unknown project fails before touching todos`() {
        assertFailsWith<NotFoundException> { useCase.execute(ClaimTodoInput("ghost", "t1", "claude-code")) }
        assertEquals(null, todos.listByProject("p1").single().owner)
    }

    @Test
    fun `unknown todo id fails cleanly instead of crashing`() {
        assertFailsWith<NotFoundException> { useCase.execute(ClaimTodoInput("demo", "ghost-todo", "claude-code")) }
    }
}
