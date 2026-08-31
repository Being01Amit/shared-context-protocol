package com.scp.server

import com.scp.model.ContextType
import com.scp.model.mcp.CreateProjectInput
import com.scp.model.mcp.NewEntry
import com.scp.model.mcp.SaveNoteInput
import com.scp.model.mcp.UpdateContextInput
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The MCP boundary is the first thing a tool call touches, and it runs *before* SkillLogging,
 * so anything it rejects leaves no row, no markdown mirror and no log entry. These tests pin
 * the decoding behaviour for the argument shapes language models actually emit.
 */
class McpBoundaryTest {
    private fun args(raw: String): JsonObject = json.parseToJsonElement(raw).jsonObject

    @Test
    fun `explicit nulls for optional fields decode to their defaults`() {
        // The regression: models routinely send null for an optional argument they chose not to
        // fill. Every one of these fields is non-nullable-with-default, and explicitNulls governs
        // only encoding, so before coerceInputValues a single null here rejected the whole call.
        val input =
            json.decodeFromJsonElement<UpdateContextInput>(
                args(
                    """
                    {
                      "projectName": "my-app",
                      "toolName": "claude-code",
                      "summary": null,
                      "nextStep": null,
                      "keepOpen": null,
                      "sessionId": null,
                      "tokenUsage": null,
                      "entries": null,
                      "decisions": null,
                      "todos": null,
                      "files": null
                    }
                    """.trimIndent(),
                ),
            )

        assertEquals("my-app", input.projectName)
        assertEquals("claude-code", input.toolName)
        assertEquals("", input.summary)
        assertEquals("", input.nextStep)
        assertEquals(false, input.keepOpen)
        assertNull(input.sessionId)
        assertNull(input.tokenUsage)
        assertTrue(input.entries.isEmpty())
        assertTrue(input.decisions.isEmpty())
        assertTrue(input.todos.isEmpty())
        assertTrue(input.files.isEmpty())
    }

    @Test
    fun `explicit nulls decode for the other tools too`() {
        val project =
            json.decodeFromJsonElement<CreateProjectInput>(args("""{"name": "my-app", "description": null}"""))
        assertEquals("", project.description)

        val note =
            json.decodeFromJsonElement<SaveNoteInput>(
                args(
                    """
                    {"projectName":"my-app","toolName":"cli","title":"t","content":"c",
                     "type":null,"tags":null,"priority":null}
                    """.trimIndent(),
                ),
            )
        assertEquals(ContextType.LEARNING, note.type)
        assertTrue(note.tags.isEmpty())
    }

    @Test
    fun `unknown arguments are ignored rather than rejected`() {
        val input =
            json.decodeFromJsonElement<UpdateContextInput>(
                args("""{"projectName":"my-app","toolName":"cli","somethingTheModelInvented":"x"}"""),
            )
        assertEquals("my-app", input.projectName)
    }

    @Test
    fun `entry timestamps are parsed leniently instead of failing the call`() {
        val canonical = "2026-08-31T10:00:00Z"
        val cases =
            mapOf(
                canonical to Instant.parse(canonical),
                // No zone, and a space separator instead of 'T' — both read as UTC.
                "2026-08-31T10:00:00" to Instant.parse(canonical),
                "2026-08-31 10:00:00" to Instant.parse(canonical),
                // Date only -> start of that day, UTC.
                "2026-08-31" to Instant.parse("2026-08-31T00:00:00Z"),
                // An explicit offset is still honoured exactly.
                "2026-08-31T12:00:00+02:00" to Instant.parse(canonical),
            )

        cases.forEach { (raw, expected) ->
            val entry =
                json.decodeFromJsonElement<NewEntry>(
                    args("""{"title":"t","content":"c","type":"FEATURE","timestamp":"$raw"}"""),
                )
            assertEquals(expected, entry.timestamp, "timestamp '$raw'")
        }
    }

    @Test
    fun `an unparseable timestamp costs the field, not the whole payload`() {
        // Falling back to null means UpdateContextUseCase stamps it with `now`; throwing here
        // would discard every entry, decision, todo and file in the same call.
        listOf("not a date", "", "31/08/2026").forEach { raw ->
            val entry =
                json.decodeFromJsonElement<NewEntry>(
                    args("""{"title":"t","content":"c","type":"FEATURE","timestamp":"$raw"}"""),
                )
            assertNull(entry.timestamp, "timestamp '$raw'")
        }
    }

    @Test
    fun `epoch numbers are accepted as timestamps`() {
        val seconds =
            json.decodeFromJsonElement<NewEntry>(
                args("""{"title":"t","content":"c","type":"FEATURE","timestamp":1788170400}"""),
            )
        val millis =
            json.decodeFromJsonElement<NewEntry>(
                args("""{"title":"t","content":"c","type":"FEATURE","timestamp":1788170400000}"""),
            )
        assertEquals(Instant.fromEpochSeconds(1_788_170_400), seconds.timestamp)
        assertEquals(seconds.timestamp, millis.timestamp)
    }

    @Test
    fun `a missing or null timestamp stays null`() {
        val absent =
            json.decodeFromJsonElement<NewEntry>(args("""{"title":"t","content":"c","type":"FEATURE"}"""))
        val explicitNull =
            json.decodeFromJsonElement<NewEntry>(
                args("""{"title":"t","content":"c","type":"FEATURE","timestamp":null}"""),
            )
        assertNull(absent.timestamp)
        assertNull(explicitNull.timestamp)
    }
}
