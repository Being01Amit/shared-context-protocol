package com.scp.search

import com.scp.database.adapter.DatabaseHandle
import com.scp.database.adapter.DriverFactory
import com.scp.database.adapter.SqlContextEntryRepository
import com.scp.database.adapter.SqlProjectRepository
import com.scp.database.adapter.SqlSessionRepository
import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.port.SearchRequest
import kotlinx.datetime.Instant
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SqlSearchIndexTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var handle: DatabaseHandle
    private lateinit var index: SqlSearchIndex
    private val t0 = Instant.parse("2026-07-01T10:00:00Z")
    private val projectId = UUID.randomUUID().toString()
    private val sessionId = UUID.randomUUID().toString()

    @BeforeEach
    fun setUp() {
        handle = DriverFactory.open(tmp.resolve("scp.db"))
        index = SqlSearchIndex(handle.database, handle.driver)
        SqlProjectRepository(handle.database).insert(Project(projectId, "search-proj", "d", t0, t0))
        SqlSessionRepository(handle.database).insert(Session(sessionId, projectId, "claude-code", t0))
        val entries = SqlContextEntryRepository(handle.database)
        entries.insert(entry("JWT rotation decided", "we rotate refresh tokens hourly", ContextType.DECISION, listOf("auth"), t0))
        entries.insert(
            entry(
                "login bug",
                "jwt validation fails on expiry boundary",
                ContextType.BUG,
                listOf("auth", "bug"),
                Instant.parse("2026-07-02T10:00:00Z"),
            ),
        )
        entries.insert(entry("unrelated note", "renamed the gradle modules", ContextType.REFACTOR, emptyList(), t0))
    }

    @AfterEach
    fun tearDown() {
        handle.close()
    }

    private fun entry(title: String, content: String, type: ContextType, tags: List<String>, at: Instant) =
        ContextEntry(UUID.randomUUID().toString(), sessionId, at, title, content, type, tags, priority = 3)

    @Test
    fun `keyword search finds title and content matches with tags loaded`() {
        val hits = index.search(SearchRequest(query = "jwt"))
        assertEquals(2, hits.size)
        assertTrue(hits.any { "auth" in it.entry.tags })
    }

    @Test
    fun `filters compose - type, tag, and date range narrow the same query`() {
        assertEquals(1, index.search(SearchRequest(query = "jwt", type = ContextType.BUG)).size)
        assertEquals(2, index.search(SearchRequest(query = "jwt", tag = "auth")).size)
        val fromJuly2 = index.search(SearchRequest(query = "jwt", from = Instant.parse("2026-07-02T00:00:00Z")))
        assertEquals(1, fromJuly2.size)
        assertEquals("login bug", fromJuly2.single().entry.title)
    }

    @Test
    fun `fts syntax characters in user input never throw`() {
        val hits = index.search(SearchRequest(query = "jwt\" OR (malformed"))
        assertTrue(hits.isEmpty(), "quoted-token query matches nothing but must not throw")
    }

    @Test
    fun `blank query returns empty without hitting fts`() {
        assertTrue(index.search(SearchRequest(query = "   ")).isEmpty())
    }

    @Test
    fun `limit bounds the result set`() {
        val hits = index.search(SearchRequest(query = "jwt", limit = 1))
        assertEquals(1, hits.size)
    }

    @Test
    fun `count and rebuild agree with entry count`() {
        assertEquals(3, index.indexedEntryCount())
        index.rebuild()
        assertEquals(3, index.indexedEntryCount())
    }
}
