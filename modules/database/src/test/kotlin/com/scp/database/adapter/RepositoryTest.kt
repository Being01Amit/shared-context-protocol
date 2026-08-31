package com.scp.database.adapter

import com.scp.model.ContextType
import com.scp.model.Decision
import com.scp.model.DecisionStatus
import com.scp.model.SessionStatus
import com.scp.model.Todo
import com.scp.model.TodoStatus
import com.scp.model.TrackedFile
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RepositoryTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var handle: DatabaseHandle

    @BeforeEach
    fun setUp() {
        handle = DriverFactory.open(tmp.resolve("scp.db"))
    }

    @AfterEach
    fun tearDown() {
        handle.close()
    }

    @Test
    fun `context entry round-trips with enum type and normalized tags`() {
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val session = Fixtures.session(project.id)
        SqlSessionRepository(handle.database).insert(session)

        val entries = SqlContextEntryRepository(handle.database)
        entries.insert(Fixtures.entry(session.id, type = ContextType.DECISION, tags = listOf(" Auth ", "JWT", "auth")))

        val loaded = entries.findBySession(session.id).single()
        assertEquals(ContextType.DECISION, loaded.type)
        assertEquals(listOf("auth", "jwt"), loaded.tags.sorted())
    }

    @Test
    fun `deleting an entry cascades its tags`() {
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val session = Fixtures.session(project.id)
        SqlSessionRepository(handle.database).insert(session)
        val entries = SqlContextEntryRepository(handle.database)
        val entry = Fixtures.entry(session.id, tags = listOf("a", "b"))
        entries.insert(entry)

        handle.driver.execute(null, "DELETE FROM context_entry WHERE id = '${entry.id}'", 0)
        val remaining =
            handle.database.contextEntryTagQueries
                .tagsForEntry(entry.id)
                .executeAsList()
        assertTrue(remaining.isEmpty(), "tags should cascade on entry delete")
    }

    @Test
    fun `session close sets status end_time and keeps summary when null`() {
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val sessions = SqlSessionRepository(handle.database)
        val session = Fixtures.session(project.id)
        sessions.insert(session)

        val end = Instant.parse("2026-07-01T12:00:00Z")
        sessions.close(session.id, end, summary = null, tokenUsage = 1234, nextStep = null)

        val closed = sessions.findById(session.id)!!
        assertEquals(SessionStatus.CLOSED, closed.status)
        assertEquals(end, closed.endTime)
        assertEquals("", closed.summary)
        assertEquals(1234, closed.tokenUsage)
        assertTrue(sessions.findOpenByProject(project.id).isEmpty())
    }

    @Test
    fun `file upsert replaces summary and hash on same path`() {
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val files = SqlFileRepository(handle.database)
        val t1 = Instant.parse("2026-07-01T11:00:00Z")
        files.upsert(TrackedFile(UUID.randomUUID().toString(), project.id, "src/A.kt", "v1", "h1", Fixtures.t0))
        files.upsert(TrackedFile(UUID.randomUUID().toString(), project.id, "src/A.kt", "v2", "h2", t1))

        val all = files.listByProject(project.id)
        assertEquals(1, all.size)
        assertEquals("v2", all.single().summary)
        assertEquals("h2", all.single().hash)
    }

    @Test
    fun `decision and todo status filters work`() {
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val decisions = SqlDecisionRepository(handle.database)
        val todos = SqlTodoRepository(handle.database)

        val d =
            Decision(
                id = UUID.randomUUID().toString(),
                projectId = project.id,
                title = "Use SQLDelight",
                decision = "yes",
                createdAt = Fixtures.t0,
                updatedAt = Fixtures.t0,
            )
        decisions.insert(d)
        assertEquals(1, decisions.findOpenByProject(project.id).size)
        decisions.updateStatus(d.id, DecisionStatus.ACCEPTED, Fixtures.t0)
        assertTrue(decisions.findOpenByProject(project.id).isEmpty())

        val t = Todo(id = UUID.randomUUID().toString(), projectId = project.id, description = "do it", createdAt = Fixtures.t0)
        todos.insert(t)
        todos.updateStatus(t.id, TodoStatus.IN_PROGRESS)
        assertEquals(1, todos.findOpenByProject(project.id).size, "in_progress counts as open")
        todos.updateStatus(t.id, TodoStatus.DONE)
        assertTrue(todos.findOpenByProject(project.id).isEmpty())
    }

    @Test
    fun `findByName returns null for unknown project`() {
        assertNull(SqlProjectRepository(handle.database).findByName("ghost"))
    }
}
