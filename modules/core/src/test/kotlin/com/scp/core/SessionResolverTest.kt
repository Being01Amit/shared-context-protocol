package com.scp.core

import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Session
import com.scp.model.SessionStatus
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
        val resolution = resolver.resolve(projectId, "claude-code", null)
        assertTrue(resolution.created)
        assertEquals("claude-code", resolution.session.toolName)
        assertEquals(SessionStatus.OPEN, resolution.session.status)
    }

    @Test
    fun `row 2 - one open session with matching tool is reused`() {
        val mine = openSession("claude-code")
        val resolution = resolver.resolve(projectId, "claude-code", null)
        assertFalse(resolution.created)
        assertEquals(mine.id, resolution.session.id)
    }

    @Test
    fun `row 3 - one open session from another tool creates new, never merges`() {
        val theirs = openSession("claude-code")
        val resolution = resolver.resolve(projectId, "antigravity", null)
        assertTrue(resolution.created)
        assertEquals("antigravity", resolution.session.toolName)
        assertEquals(SessionStatus.OPEN, sessions.findById(theirs.id)!!.status, "other tool's session untouched")
    }

    @Test
    fun `row 4 - two open sessions creates new, never guesses`() {
        openSession("claude-code", id = "s1")
        openSession("claude-code", id = "s2")
        val resolution = resolver.resolve(projectId, "claude-code", null)
        assertTrue(resolution.created)
        assertEquals(3, sessions.findOpenByProject(projectId).size)
    }

    @Test
    fun `explicit session id wins even when closed`() {
        val s = openSession("claude-code")
        sessions.close(s.id, Instant.parse("2026-07-04T11:00:00Z"), null, null)
        val resolution = resolver.resolve(projectId, "antigravity", s.id)
        assertFalse(resolution.created)
        assertEquals(s.id, resolution.session.id)
    }

    @Test
    fun `explicit session id must exist`() {
        assertFailsWith<NotFoundException> { resolver.resolve(projectId, "claude-code", "missing-id") }
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
        assertFailsWith<InvalidInputException> { resolver.resolve(projectId, "claude-code", s.id) }
    }
}
