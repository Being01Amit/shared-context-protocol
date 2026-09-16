package com.scp.database.adapter

import com.scp.model.ContextType
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The FTS5 external-content index must track context_entry through the sync triggers (ADR-8). */
class FtsSyncTest {
    @TempDir
    lateinit var tmp: Path

    private lateinit var handle: DatabaseHandle
    private lateinit var sessionId: String

    @BeforeEach
    fun setUp() {
        handle = DriverFactory.open(tmp.resolve("scp.db"))
        val project = Fixtures.project()
        SqlProjectRepository(handle.database).insert(project)
        val session = Fixtures.session(project.id)
        SqlSessionRepository(handle.database).insert(session)
        sessionId = session.id
    }

    @AfterEach
    fun tearDown() {
        handle.close()
    }

    private fun search(query: String) =
        handle.database.contextEntryFtsQueries
            .searchEntries(
                query = query,
                projectId = null,
                type = null,
                fromTs = null,
                toTs = null,
                tag = null,
                limit = 10,
            ).executeAsList()

    @Test
    fun `insert is indexed via trigger`() {
        SqlContextEntryRepository(handle.database)
            .insert(Fixtures.entry(sessionId, title = "JWT refresh flow", content = "rotate tokens hourly"))
        assertTrue(FtsAdmin.isConsistent(handle.driver))
        assertEquals(1, search("jwt").size)
        assertEquals(1, search("rotate").size, "content is indexed, not just title")
    }

    @Test
    fun `update reindexes and delete removes from index`() {
        val entries = SqlContextEntryRepository(handle.database)
        val entry = Fixtures.entry(sessionId, title = "alpha topic", content = "first version")
        entries.insert(entry)

        handle.driver.execute(null, "UPDATE context_entry SET title = 'omega topic' WHERE id = '${entry.id}'", 0)
        assertTrue(search("alpha").isEmpty(), "old term must be gone after update")
        assertEquals(1, search("omega").size)

        handle.driver.execute(null, "DELETE FROM context_entry WHERE id = '${entry.id}'", 0)
        assertTrue(search("omega").isEmpty())
        assertTrue(FtsAdmin.isConsistent(handle.driver), "delete trigger kept the index in sync")
    }

    @Test
    fun `structured filters compose with full-text match`() {
        val entries = SqlContextEntryRepository(handle.database)
        entries.insert(
            Fixtures.entry(sessionId, title = "auth bug", type = ContextType.BUG, tags = listOf("auth")),
        )
        entries.insert(
            Fixtures.entry(sessionId, title = "auth decision", type = ContextType.DECISION, tags = listOf("jwt")),
        )

        val bugsOnly =
            handle.database.contextEntryFtsQueries
                .searchEntries(
                    query = "auth",
                    projectId = null,
                    type = ContextType.BUG,
                    fromTs = null,
                    toTs = null,
                    tag = null,
                    limit = 10,
                ).executeAsList()
        assertEquals(1, bugsOnly.size)
        assertEquals("auth bug", bugsOnly.single().title)

        val taggedJwt =
            handle.database.contextEntryFtsQueries
                .searchEntries(
                    query = "auth",
                    projectId = null,
                    type = null,
                    fromTs = null,
                    toTs = null,
                    tag = "jwt",
                    limit = 10,
                ).executeAsList()
        assertEquals(1, taggedJwt.size)
        assertEquals("auth decision", taggedJwt.single().title)
    }

    @Test
    fun `a healthy index passes the integrity check`() {
        SqlContextEntryRepository(handle.database).insert(Fixtures.entry(sessionId, title = "needle"))
        assertTrue(FtsAdmin.isConsistent(handle.driver))
    }

    @Test
    fun `integrity check detects an index that diverged from its entries, and rebuild repairs it`() {
        SqlContextEntryRepository(handle.database).insert(Fixtures.entry(sessionId, title = "needle", content = "haystack"))
        // Drop the entry's postings from the index only, leaving the context_entry row in place —
        // the drift a row count cannot see (for external content, count(*) reads the content table).
        handle.driver.execute(
            null,
            "INSERT INTO context_entry_fts(context_entry_fts, rowid, title, content) " +
                "SELECT 'delete', rowid, title, content FROM context_entry",
            0,
        )
        assertEquals(0, search("needle").size, "precondition: the index really is broken")
        assertFalse(FtsAdmin.isConsistent(handle.driver))

        FtsAdmin.rebuild(handle.driver)
        assertTrue(FtsAdmin.isConsistent(handle.driver))
        assertEquals(1, search("needle").size)
    }
}
