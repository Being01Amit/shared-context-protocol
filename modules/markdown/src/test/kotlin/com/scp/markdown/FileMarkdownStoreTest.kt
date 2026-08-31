package com.scp.markdown

import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.Decision
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.Todo
import com.scp.model.TrackedFile
import com.scp.model.port.SessionMarkdown
import kotlinx.datetime.Instant
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileMarkdownStoreTest {
    @TempDir
    lateinit var tmp: Path

    private val t0 = Instant.parse("2026-07-04T09:30:00Z")
    private val project = Project("p1", "demo", "demo project", t0, t0)
    private val session = Session("abcdef12-3456-4789-8abc-def123456789", "p1", "claude-code", t0, summary = "built auth")

    private fun store() = FileMarkdownStore(tmp)

    private fun sessionMarkdown(entries: List<ContextEntry> = emptyList()) =
        SessionMarkdown(
            project = project,
            session = session,
            entries = entries,
            decisions = listOf(Decision("d1", "p1", "Use SQLite", "WAL mode", "local-first", createdAt = t0, updatedAt = t0)),
            todos = listOf(Todo("t1", "p1", "write docs", owner = "amit", createdAt = t0)),
            files = listOf(TrackedFile("f1", "p1", "src/Auth.kt", "token flow", "hash1", t0)),
        )

    @Test
    fun `writes project-first then timestamp-first, with tool and session suffix`() {
        val path = store().write(sessionMarkdown())
        val file = Path.of(path)
        assertEquals("2026-07-04T09-30-00Z-claude-code-abcdef12.md", file.name)
        assertEquals("demo", file.parent.name)
        assertTrue(Files.exists(file))
    }

    @Test
    fun `session file names sort chronologically as plain text`() {
        val early = session.copy(id = "11111111-0000-4000-8000-000000000000", startTime = Instant.parse("2026-07-04T09:00:00Z"))
        val late = session.copy(id = "22222222-0000-4000-8000-000000000000", startTime = Instant.parse("2026-12-31T23:59:59Z"))
        val earlyName = Path.of(store().write(sessionMarkdown().copy(session = early))).name
        val lateName = Path.of(store().write(sessionMarkdown().copy(session = late))).name
        assertTrue(earlyName < lateName, "'$earlyName' must sort before '$lateName'")
    }

    @Test
    fun `LATEST and PROJECT index are written alongside the session file`() {
        val dir = Path.of(store().write(sessionMarkdown())).parent
        val latest = dir.resolve("LATEST.md")
        val index = dir.resolve("PROJECT.md")
        assertTrue(Files.exists(latest), "LATEST.md is the resume anchor")
        assertTrue(Files.exists(index), "PROJECT.md is the project index")
        assertTrue("built auth" in latest.readText())
        assertTrue("# demo" in index.readText())
        assertTrue("## Resume here" in index.readText())
    }

    @Test
    fun `LATEST tracks the newest session and the index lists newest first`() {
        val older = session.copy(id = "11111111-0000-4000-8000-000000000000", summary = "older work")
        val newer =
            session.copy(
                id = "22222222-0000-4000-8000-000000000000",
                startTime = Instant.parse("2026-08-01T10:00:00Z"),
                summary = "newer work",
                nextStep = "carry on from the adapter split",
            )
        store().write(sessionMarkdown().copy(session = older))
        val dir = Path.of(store().write(sessionMarkdown().copy(session = newer))).parent

        assertTrue("newer work" in dir.resolve("LATEST.md").readText(), "LATEST must follow the newest write")
        val index = dir.resolve("PROJECT.md").readText()
        assertTrue("carry on from the adapter split" in index, "index must surface the resume point")
        assertTrue(
            index.indexOf("2026-08-01T10-00-00Z") < index.indexOf("2026-07-04T09-30-00Z"),
            "sessions must be listed newest first",
        )
    }

    @Test
    fun `index and latest are excluded from the session listing`() {
        val dir = Path.of(store().write(sessionMarkdown())).parent
        val index = dir.resolve("PROJECT.md").readText()
        assertTrue("- [LATEST.md]" !in index, "LATEST.md is not a session")
        assertTrue("- [PROJECT.md]" !in index, "the index does not list itself")
    }

    @Test
    fun `template carries all sections and content lands in the right one`() {
        val entries =
            listOf(
                ContextEntry("e1", session.id, t0, "chose JWT", "RS256 keys", ContextType.DECISION, listOf("auth")),
                ContextEntry("e2", session.id, t0, "wire login", "done via form", ContextType.TASK),
                ContextEntry("e3", session.id, t0, "expiry bug", "boundary off by one", ContextType.BUG),
                ContextEntry("e4", session.id, t0, "prompt used", "implement refresh flow", ContextType.PROMPT),
                ContextEntry("e5", session.id, t0, "learned", "sqlite fts5 basics", ContextType.LEARNING),
            )
        val text = Path.of(store().write(sessionMarkdown(entries))).readText()

        listOf(
            "# Session",
            "AI Tool: claude-code",
            "Session ID: ${session.id}",
            "Summary: built auth",
            "## Architecture Decisions",
            "## Tasks Completed",
            "## Open Issues",
            "## Files Modified",
            "## Prompts",
            "## Notes",
        ).forEach { assertTrue(it in text, "missing '$it'") }

        assertTrue("Use SQLite" in text)
        assertTrue("chose JWT" in text)
        assertTrue(text.indexOf("wire login") > text.indexOf("## Tasks Completed"))
        assertTrue(text.indexOf("expiry bug") > text.indexOf("## Open Issues"))
        assertTrue(text.indexOf("prompt used") > text.indexOf("## Prompts"))
        assertTrue(text.indexOf("sqlite fts5 basics") > text.indexOf("## Notes"))
        assertTrue("- [ ] write docs (owner: amit)" in text)
        assertTrue("`src/Auth.kt`" in text)
    }

    @Test
    fun `rewriting the same session replaces the file`() {
        val first = store().write(sessionMarkdown())
        val second = store().write(sessionMarkdown(listOf(ContextEntry("e9", session.id, t0, "new note", "body", ContextType.LEARNING))))
        assertEquals(first, second)
        assertTrue("new note" in Path.of(second).readText())
        // Exactly the session file + LATEST.md + PROJECT.md — no temp files left behind.
        assertEquals(3, Files.list(Path.of(first).parent).use { it.count() }, "no temp files left behind")
    }

    @Test
    fun `project names with windows-invalid characters are sanitized`() {
        val nasty = project.copy(name = "my:proj/ect?")
        val path = store().write(sessionMarkdown().copy(project = nasty))
        assertEquals("my-proj-ect-", Path.of(path).parent.name)
    }

    @Test
    fun `empty sections render a none placeholder`() {
        val text =
            Path
                .of(
                    store().write(SessionMarkdown(project = project, session = session, entries = emptyList())),
                ).readText()
        assertTrue("_(none)_" in text)
    }
}
