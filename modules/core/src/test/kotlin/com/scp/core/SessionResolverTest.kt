package com.scp.core

import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Session
import com.scp.model.SessionStatus
import com.scp.model.port.GitState
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The docs/04 §2 decision table, row by row, plus explicit-id handling. */
class SessionResolverTest {
    private val sessions = FakeSessionRepository()
    private val clock = FixedClock()
    private val resolver = SessionResolver(sessions, clock, SequentialIds())
    private val projectId = "project-1"

    private fun resolve(tool: String, explicitSessionId: String? = null, gitState: GitState? = null) =
        resolver.resolve(projectId, tool, explicitSessionId, gitState)

    private fun openSession(tool: String, id: String = "s-$tool"): Session {
        val s =
            Session(
                id = id,
                projectId = projectId,
                toolName = tool,
                startTime = Instant.parse("2026-07-04T10:00:00Z"),
                status = SessionStatus.OPEN,
            )
        sessions.insert(s)
        return s
    }

    @Test
    fun `row 1 - zero open sessions creates new`() {
        val resolution = resolve("claude-code")
        assertTrue(resolution.created)
        assertEquals("claude-code", resolution.session.toolName)
        assertEquals(SessionStatus.OPEN, resolution.session.status)
    }

    @Test
    fun `row 2 - one open session with matching tool is reused`() {
        val mine = openSession("claude-code")
        val resolution = resolve("claude-code")
        assertFalse(resolution.created)
        assertEquals(mine.id, resolution.session.id)
    }

    @Test
    fun `row 3 - one open session from another tool creates new, never merges`() {
        val theirs = openSession("claude-code")
        val resolution = resolve("antigravity")
        assertTrue(resolution.created)
        assertEquals("antigravity", resolution.session.toolName)
        assertEquals(SessionStatus.OPEN, sessions.findById(theirs.id)!!.status, "other tool's session untouched")
    }

    @Test
    fun `row 4 - two open sessions creates new, never guesses`() {
        openSession("claude-code", id = "s1")
        openSession("claude-code", id = "s2")
        val resolution = resolve("claude-code")
        assertTrue(resolution.created)
        assertEquals(3, sessions.findOpenByProject(projectId).size)
    }

    @Test
    fun `two open sessions from different tools still lets each tool reuse its own`() {
        val mine = openSession("claude-code", id = "s1")
        openSession("antigravity", id = "s2")
        val resolution = resolve("claude-code")
        assertFalse(resolution.created)
        assertEquals(mine.id, resolution.session.id)
    }

    @Test
    fun `explicit session id wins even when closed`() {
        val s = openSession("claude-code")
        sessions.close(s.id, Instant.parse("2026-07-04T11:00:00Z"), null, null, null)
        val resolution = resolve("claude-code", explicitSessionId = s.id)
        assertFalse(resolution.created)
        assertEquals(s.id, resolution.session.id)
    }

    @Test
    fun `explicit session id from another tool is rejected, never merged`() {
        val theirs = openSession("claude-code")
        val error = assertFailsWith<InvalidInputException> { resolve("antigravity", explicitSessionId = theirs.id) }
        assertTrue("claude-code" in error.message.orEmpty(), "error names the owning tool")
        assertEquals(1, sessions.store.size, "no session created as a side effect")
    }

    @Test
    fun `explicit session id must exist`() {
        assertFailsWith<NotFoundException> { resolve("claude-code", explicitSessionId = "missing-id") }
    }

    @Test
    fun `explicit session id must belong to the project`() {
        val s =
            Session(
                id = "other-project-session",
                projectId = "another-project",
                toolName = "claude-code",
                startTime = Instant.parse("2026-07-04T10:00:00Z"),
            )
        sessions.insert(s)
        assertFailsWith<InvalidInputException> { resolve("claude-code", explicitSessionId = s.id) }
    }

    @Test
    fun `records git state on a newly created session when available`() {
        val resolution = resolve("claude-code", gitState = GitState(branch = "main", commit = "abc123"))
        assertEquals("main", resolution.session.gitBranch)
        assertEquals("abc123", resolution.session.gitCommit)
    }

    @Test
    fun `leaves git state null on a newly created session when unavailable`() {
        val resolution = resolve("claude-code", gitState = null)
        assertEquals(null, resolution.session.gitBranch)
        assertEquals(null, resolution.session.gitCommit)
    }
}
