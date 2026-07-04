package com.scp.core.usecase

import com.scp.core.FakeContextEntryRepository
import com.scp.core.FakeDecisionRepository
import com.scp.core.FakeFileRepository
import com.scp.core.FakeProjectRepository
import com.scp.core.FakeSessionRepository
import com.scp.core.FakeTodoRepository
import com.scp.core.FixedClock
import com.scp.core.PassThroughTransactionRunner
import com.scp.core.RecordingMarkdownStore
import com.scp.core.SequentialIds
import com.scp.model.ContextType
import com.scp.model.NotFoundException
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.SessionStatus
import com.scp.model.mcp.FileUpdate
import com.scp.model.mcp.NewDecision
import com.scp.model.mcp.NewEntry
import com.scp.model.mcp.NewTodo
import com.scp.model.mcp.UpdateContextInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdateContextUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val sessions = FakeSessionRepository()
    private val entries = FakeContextEntryRepository(sessions)
    private val decisions = FakeDecisionRepository()
    private val todos = FakeTodoRepository()
    private val files = FakeFileRepository()
    private val markdown = RecordingMarkdownStore()
    private val transactions = PassThroughTransactionRunner()
    private val clock = FixedClock(Instant.parse("2026-07-04T12:00:00Z"))

    private val useCase =
        UpdateContextUseCase(
            projects = projects,
            sessions = sessions,
            entries = entries,
            decisions = decisions,
            todos = todos,
            files = files,
            markdown = markdown,
            transactions = transactions,
            clock = clock,
            ids = SequentialIds(),
        )

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "demo project", t0, t0))
    }

    private fun input(
        toolName: String = "claude-code",
        keepOpen: Boolean = false,
        sessionId: String? = null,
    ) = UpdateContextInput(
        projectName = "demo",
        toolName = toolName,
        sessionId = sessionId,
        summary = "did things",
        keepOpen = keepOpen,
        entries =
            listOf(
                NewEntry("worked on auth", "content with AKIAIOSFODNN7EXAMPLE inside", ContextType.FEATURE, listOf("Auth")),
            ),
        decisions = listOf(NewDecision("db choice", "use sqlite", "local-first")),
        todos = listOf(NewTodo("write docs")),
        files = listOf(FileUpdate("src/Auth.kt", "auth module config:\nSECRET_TOKEN=abc123", "abc")),
    )

    @Test
    fun `unknown project fails before any write`() {
        assertFailsWith<NotFoundException> { useCase.execute(input().copy(projectName = "ghost")) }
        assertTrue(sessions.store.isEmpty())
    }

    @Test
    fun `full update creates session, persists everything, closes, and writes markdown`() {
        val result = useCase.execute(input())

        assertTrue(result.sessionWasCreated)
        assertTrue(result.sessionClosed)
        assertEquals(1, result.entriesWritten)
        assertEquals(1, result.decisionsWritten)
        assertEquals(1, result.todosWritten)
        assertEquals(1, result.filesUpserted)

        val session = sessions.findById(result.sessionId)!!
        assertEquals(SessionStatus.CLOSED, session.status)
        assertEquals(clock.now(), session.endTime)
        assertEquals("did things", session.summary)

        assertEquals(1, markdown.written.size, "markdown mirror written once")
        assertEquals(result.markdownPath.isNotBlank(), true)
    }

    @Test
    fun `secrets are redacted before persistence and before markdown`() {
        useCase.execute(input())
        val entry = entries.store.single()
        assertFalse("AKIAIOSFODNN7EXAMPLE" in entry.content, "db must never see the raw secret")
        assertTrue("[REDACTED:aws-access-key]" in entry.content)

        val file = files.store.single()
        assertFalse("abc123" in file.summary)

        val mirrored = markdown.written.single()
        assertFalse(mirrored.entries.any { "AKIAIOSFODNN7EXAMPLE" in it.content })
    }

    @Test
    fun `keepOpen leaves the session open`() {
        val result = useCase.execute(input(keepOpen = true))
        assertFalse(result.sessionClosed)
        assertEquals(SessionStatus.OPEN, sessions.findById(result.sessionId)!!.status)
    }

    @Test
    fun `same tool reuses its single open session`() {
        val first = useCase.execute(input(keepOpen = true))
        val second = useCase.execute(input(keepOpen = false))
        assertFalse(second.sessionWasCreated, "single open session from same tool is reused")
        assertEquals(first.sessionId, second.sessionId)
    }

    @Test
    fun `concurrent tool gets its own session instead of merging`() {
        val first = useCase.execute(input(toolName = "claude-code", keepOpen = true))
        val second = useCase.execute(input(toolName = "antigravity"))
        assertTrue(second.sessionWasCreated)
        assertFalse(first.sessionId == second.sessionId)
        // Claude's session is untouched and still open.
        assertEquals(SessionStatus.OPEN, sessions.findById(first.sessionId)!!.status)
    }

    @Test
    fun `explicit session id appends to that session`() {
        sessions.insert(Session("explicit", "p1", "cursor", t0))
        val result = useCase.execute(input(toolName = "claude-code", sessionId = "explicit"))
        assertEquals("explicit", result.sessionId)
        assertFalse(result.sessionWasCreated)
        assertEquals("explicit", entries.store.single().sessionId)
    }

    @Test
    fun `all writes run inside one write transaction`() {
        useCase.execute(input())
        assertEquals(1, transactions.transactionCount)
    }
}
