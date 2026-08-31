package com.scp.core.usecase

import com.scp.core.FakeContextEntryRepository
import com.scp.core.FakeDecisionRepository
import com.scp.core.FakeFileRepository
import com.scp.core.FakeProjectRepository
import com.scp.core.FakeSessionRepository
import com.scp.core.FakeTodoRepository
import com.scp.core.FixedClock
import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.Decision
import com.scp.model.Project
import com.scp.model.RankingWeights
import com.scp.model.Session
import com.scp.model.Todo
import com.scp.model.mcp.HydrateContextInput
import kotlinx.datetime.Instant
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HydrateContextUseCaseTest {
    private val t0 = Instant.parse("2026-07-01T00:00:00Z")
    private val projects = FakeProjectRepository()
    private val sessions = FakeSessionRepository()
    private val entries = FakeContextEntryRepository(sessions)
    private val decisions = FakeDecisionRepository()
    private val todos = FakeTodoRepository()
    private val files = FakeFileRepository()
    private val clock = FixedClock(Instant.parse("2026-07-04T00:00:00Z"))

    private fun useCase(tokenLimit: Int = 12_000) =
        HydrateContextUseCase(
            projects = projects,
            sessions = sessions,
            entries = entries,
            decisions = decisions,
            todos = todos,
            files = files,
            clock = clock,
            weights = RankingWeights(),
            defaultTokenLimit = tokenLimit,
        )

    @BeforeTest
    fun seed() {
        projects.insert(Project("p1", "demo", "A demo project for hydration tests", t0, t0))
        sessions.insert(Session("s1", "p1", "claude-code", t0))
        (1..10).forEach { i ->
            decisions.insert(Decision("d$i", "p1", "decision $i", "choose option $i", "because $i", createdAt = t0, updatedAt = t0))
            todos.insert(Todo("t$i", "p1", "todo item $i", createdAt = t0))
            entries.insert(
                ContextEntry("b$i", "s1", t0, "bug $i", "bug details $i", ContextType.BUG, listOf("bug"), priority = 4),
            )
            entries.insert(
                ContextEntry("pr$i", "s1", t0, "prompt $i", "prompt body $i", ContextType.PROMPT, listOf("prompt"), priority = 2),
            )
        }
    }

    @Test
    fun `unknown project fails with NotFound`() {
        assertFailsWith<com.scp.model.NotFoundException> {
            useCase().execute(HydrateContextInput(projectName = "ghost"))
        }
    }

    @Test
    fun `generous budget emits all sections without truncation`() {
        val payload = useCase().execute(HydrateContextInput(projectName = "demo"))
        assertEquals("demo", payload.projectName)
        assertEquals(1, payload.recentSessions.size)
        assertEquals(10, payload.openDecisions.size)
        assertEquals(10, payload.openTodos.size)
        assertEquals(10, payload.openBugs.size)
        assertEquals(10, payload.relevantPrompts.size)
        assertEquals(5, payload.currentPriorities.size)
        assertEquals(0, payload.omittedCount)
        assertNull(payload.truncationNotice)
        assertTrue(payload.estimatedTokens > 0)
    }

    @Test
    fun `tiny budget truncates with explicit marker, never silently`() {
        val payload = useCase(tokenLimit = 120).execute(HydrateContextInput(projectName = "demo"))
        assertTrue(payload.omittedCount > 0, "tiny budget must omit items")
        assertNotNull(payload.truncationNotice)
        assertTrue("/timeline" in payload.truncationNotice!!, "marker must point at the escape hatch")
        assertTrue(payload.estimatedTokens <= 120, "budget must never be exceeded")
        assertTrue(payload.projectSummary.isNotBlank(), "section 1 is always emitted")
    }

    @Test
    fun `budget smaller than the summary hard-truncates the summary`() {
        val payload = useCase(tokenLimit = 4).execute(HydrateContextInput(projectName = "demo"))
        assertTrue("[project summary truncated]" in payload.projectSummary)
        assertTrue(payload.openDecisions.isEmpty())
        assertTrue(payload.omittedCount > 0)
    }

    @Test
    fun `query tags boost matching entries to the top of prompts`() {
        entries.insert(
            ContextEntry(
                "special",
                "s1",
                clock.now(),
                "special prompt",
                "about auth",
                ContextType.PROMPT,
                listOf("auth"),
                priority = 2,
            ),
        )
        val payload = useCase().execute(HydrateContextInput(projectName = "demo", tags = listOf("AUTH")))
        assertEquals("special", payload.relevantPrompts.first().id, "tag match (case-insensitive) must rank first")
    }

    // --- resume point (section 0) ---

    @Test
    fun `resume point reports what was done and where the last agent stopped`() {
        sessions.close(
            "s1",
            endTime = t0,
            summary = "extracted PaymentProcessor into a port",
            tokenUsage = null,
            nextStep = "implement PayPalAdapter.capture()",
        )
        val resume = assertNotNull(useCase().execute(HydrateContextInput(projectName = "demo")).resumePoint)
        assertEquals("extracted PaymentProcessor into a port", resume.whatWasDone)
        assertEquals("implement PayPalAdapter.capture()", resume.whereWeStopped)
        assertEquals("claude-code", resume.lastSession.toolName)
        assertTrue(!resume.lastSessionWasOpen, "closed session must not be reported as open")
    }

    @Test
    fun `resume point anchors on the most recent session, not the first`() {
        sessions.insert(Session("s2", "p1", "antigravity", Instant.parse("2026-07-03T00:00:00Z")))
        sessions.close("s2", endTime = t0, summary = "later work", tokenUsage = null, nextStep = "do the next thing")
        val resume = assertNotNull(useCase().execute(HydrateContextInput(projectName = "demo")).resumePoint)
        assertEquals("s2", resume.lastSession.id)
        assertEquals("antigravity", resume.lastSession.toolName)
    }

    @Test
    fun `resume point flags a still-open session so the next agent checks before taking over`() {
        val resume = assertNotNull(useCase().execute(HydrateContextInput(projectName = "demo")).resumePoint)
        assertTrue(resume.lastSessionWasOpen, "s1 was never closed")
    }

    @Test
    fun `resume point survives a budget far too small for any other section`() {
        sessions.close("s1", endTime = t0, summary = "did a thing", tokenUsage = null, nextStep = "do the next thing")
        val payload = useCase(tokenLimit = 4).execute(HydrateContextInput(projectName = "demo"))
        val resume = assertNotNull(payload.resumePoint, "section 0 must never be truncated away")
        assertEquals("do the next thing", resume.whereWeStopped)
        assertTrue(payload.openDecisions.isEmpty(), "everything else starves first")
    }

    @Test
    fun `project with no sessions has no resume point rather than a fabricated one`() {
        projects.insert(Project("p2", "fresh", "brand new", t0, t0))
        assertNull(useCase().execute(HydrateContextInput(projectName = "fresh")).resumePoint)
    }

    // --- all entry types reach the resume path ---

    @Test
    fun `non-bug non-prompt entries are hydrated, not silently dropped`() {
        entries.insert(
            ContextEntry("arch1", "s1", t0, "port-adapter split", "PaymentProcessor is now a port", ContextType.ARCHITECTURE),
        )
        entries.insert(
            ContextEntry("feat1", "s1", t0, "stripe adapter", "StripeAdapter implements capture", ContextType.FEATURE),
        )
        entries.insert(ContextEntry("task1", "s1", t0, "wire it up", "wired the adapter", ContextType.TASK))
        val ids = useCase().execute(HydrateContextInput(projectName = "demo")).recentEntries.map { it.id }
        assertTrue("arch1" in ids, "ARCHITECTURE entries must reach hydration")
        assertTrue("feat1" in ids, "FEATURE entries must reach hydration")
        assertTrue("task1" in ids, "TASK entries must reach hydration")
    }

    @Test
    fun `recent entries do not duplicate the dedicated bug and prompt sections`() {
        entries.insert(ContextEntry("arch1", "s1", t0, "arch", "body", ContextType.ARCHITECTURE))
        val payload = useCase().execute(HydrateContextInput(projectName = "demo"))
        val recentTypes = payload.recentEntries.map { it.type }.toSet()
        assertTrue(ContextType.BUG !in recentTypes, "bugs have their own section")
        assertTrue(ContextType.PROMPT !in recentTypes, "prompts have their own section")
        assertTrue(payload.openBugs.isNotEmpty() && payload.relevantPrompts.isNotEmpty())
    }

    @Test
    fun `priorities pull the top scored items across decisions todos and bugs`() {
        val payload = useCase().execute(HydrateContextInput(projectName = "demo"))
        assertEquals(5, payload.currentPriorities.size)
        payload.currentPriorities.forEach { assertTrue(it.kind in setOf("decision", "todo", "bug")) }
        // Decisions map to priority 5 + type weight 1.0, so they outrank todos and bugs here.
        assertEquals("decision", payload.currentPriorities.first().kind)
    }
}
