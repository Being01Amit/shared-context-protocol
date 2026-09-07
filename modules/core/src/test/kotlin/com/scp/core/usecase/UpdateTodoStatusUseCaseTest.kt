package com.scp.core.usecase

import com.scp.core.FakeProjectRepository
import com.scp.core.FakeTodoRepository
import com.scp.core.FixedClock
import com.scp.core.PassThroughTransactionRunner
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.Todo
import com.scp.model.TodoStatus
import com.scp.model.mcp.UpdateTodoStatusInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UpdateTodoStatusUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val todos = FakeTodoRepository()
    private val transactions = PassThroughTransactionRunner()
    private val clock = FixedClock(Instant.parse("2026-07-04T12:00:00Z"))
    private val useCase = UpdateTodoStatusUseCase(projects, todos, transactions, clock)

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "demo project", t0, t0))
        todos.insert(Todo(id = "t1", projectId = "p1", description = "write docs", createdAt = t0))
    }

    @Test
    fun `marks a todo done and touches the project`() {
        val result = useCase.execute(UpdateTodoStatusInput("demo", "t1", TodoStatus.DONE))
        assertEquals(TodoStatus.DONE, result.status)
        assertEquals(TodoStatus.DONE, todos.listByProject("p1").single().status)
        assertEquals(clock.now(), projects.store.getValue("p1").updatedAt)
    }

    @Test
    fun `unknown project fails before touching todos`() {
        assertFailsWith<NotFoundException> { useCase.execute(UpdateTodoStatusInput("ghost", "t1", TodoStatus.DONE)) }
        assertEquals(TodoStatus.OPEN, todos.listByProject("p1").single().status)
    }

    @Test
    fun `unknown todo id fails cleanly instead of crashing`() {
        assertFailsWith<NotFoundException> { useCase.execute(UpdateTodoStatusInput("demo", "ghost-todo", TodoStatus.DONE)) }
    }
}
