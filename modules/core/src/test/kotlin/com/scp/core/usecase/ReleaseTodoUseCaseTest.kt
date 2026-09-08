package com.scp.core.usecase

import com.scp.core.FakeProjectRepository
import com.scp.core.FakeTodoRepository
import com.scp.core.FixedClock
import com.scp.core.PassThroughTransactionRunner
import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.Todo
import com.scp.model.mcp.ReleaseTodoInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class ReleaseTodoUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val todos = FakeTodoRepository()
    private val transactions = PassThroughTransactionRunner()
    private val clock = FixedClock(Instant.parse("2026-07-04T12:00:00Z"))
    private val useCase = ReleaseTodoUseCase(projects, todos, transactions, clock)

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "demo project", t0, t0))
        todos.insert(Todo(id = "t1", projectId = "p1", description = "write docs", owner = "claude-code", createdAt = t0))
    }

    @Test
    fun `releases a todo you own`() {
        val result = useCase.execute(ReleaseTodoInput("demo", "t1", "claude-code"))
        assertEquals("t1", result.todoId)
        assertNull(todos.listByProject("p1").single().owner)
        assertEquals(clock.now(), projects.store.getValue("p1").updatedAt)
    }

    @Test
    fun `releasing a todo owned by someone else fails`() {
        assertFailsWith<InvalidInputException> { useCase.execute(ReleaseTodoInput("demo", "t1", "antigravity")) }
        assertEquals("claude-code", todos.listByProject("p1").single().owner, "failed release must not clear owner")
    }

    @Test
    fun `releasing an unclaimed todo fails`() {
        todos.updateOwner("t1", null)
        assertFailsWith<InvalidInputException> { useCase.execute(ReleaseTodoInput("demo", "t1", "claude-code")) }
    }

    @Test
    fun `unknown project fails before touching todos`() {
        assertFailsWith<NotFoundException> { useCase.execute(ReleaseTodoInput("ghost", "t1", "claude-code")) }
        assertEquals("claude-code", todos.listByProject("p1").single().owner)
    }

    @Test
    fun `unknown todo id fails cleanly instead of crashing`() {
        assertFailsWith<NotFoundException> { useCase.execute(ReleaseTodoInput("demo", "ghost-todo", "claude-code")) }
    }
}
