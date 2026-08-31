package com.scp.database.adapter

import com.scp.model.Session
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Session ordering must be chronological, not lexicographic.
 *
 * `start_time` is ISO-8601 TEXT and kotlinx's `Instant.toString()` trims trailing zero groups
 * from the fraction, so stored widths vary (0, 3, 6 or 9 digits). Under TEXT comparison 'Z'
 * (0x5A) outranks '.' (0x2E) and every digit, which puts an earlier timestamp last. These are
 * the exact shapes that misorder, and they cannot be caught by the in-memory fakes, whose
 * `findLatest` compares real `Instant`s.
 */
class SessionOrderingTest {
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

    private fun sessionAt(projectId: String, at: Instant): Session =
        Session(id = UUID.randomUUID().toString(), projectId = projectId, toolName = "claude-code", startTime = at)

    @Test
    fun `findLatest picks the newest session across mixed fraction widths`() {
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val sessions = SqlSessionRepository(handle.database)

        // Same second. Sorted as text, ".732Z" and "Z" both outrank ".732767700Z", so the
        // pre-fix query returned one of the earlier two as "latest".
        val earliest = Instant.parse("2026-08-31T12:44:34Z")
        val middle = Instant.parse("2026-08-31T12:44:34.732Z")
        val newest = Instant.parse("2026-08-31T12:44:34.732767700Z")
        assertEquals("2026-08-31T12:44:34Z", earliest.toString(), "fraction is trimmed when zero")
        assertEquals("2026-08-31T12:44:34.732Z", middle.toString(), "fraction keeps 3-digit groups")

        val newestSession = sessionAt(project.id, newest)
        listOf(sessionAt(project.id, earliest), sessionAt(project.id, middle), newestSession)
            .forEach(sessions::insert)

        assertEquals(newestSession.id, sessions.findLatest(project.id)?.id)
    }

    @Test
    fun `listRecent and listChronological agree on order`() {
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val sessions = SqlSessionRepository(handle.database)

        val times =
            listOf(
                Instant.parse("2026-08-31T12:44:34Z"),
                Instant.parse("2026-08-31T12:44:34.000000001Z"),
                Instant.parse("2026-08-31T12:44:34.732Z"),
                Instant.parse("2026-08-31T12:44:34.732767700Z"),
                Instant.parse("2026-08-31T12:44:35Z"),
            )
        // Inserted out of order so the result reflects the ORDER BY, not insertion order.
        times.shuffled().forEach { sessions.insert(sessionAt(project.id, it)) }

        assertEquals(times, sessions.listChronological(project.id).map { it.startTime })
        assertEquals(times.reversed(), sessions.listRecent(project.id, times.size.toLong()).map { it.startTime })
    }

    @Test
    fun `findLatest returns an open session when it is the most recent`() {
        // A crashed agent's open session is still where work stopped — hydrate_context must
        // anchor on it rather than falling back to an older closed one.
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val sessions = SqlSessionRepository(handle.database)

        val older = sessionAt(project.id, Instant.parse("2026-08-31T10:00:00Z"))
        val openLater = sessionAt(project.id, Instant.parse("2026-08-31T11:00:00Z"))
        sessions.insert(older)
        sessions.insert(openLater)
        sessions.close(older.id, Instant.parse("2026-08-31T10:30:00Z"), "done", null, "next")

        val latest = sessions.findLatest(project.id)
        assertEquals(openLater.id, latest?.id)
    }
}
