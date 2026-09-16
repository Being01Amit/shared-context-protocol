package com.scp.cli

import com.github.ajalt.clikt.core.CliktError
import com.scp.model.mcp.UpdateContextInput
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ReadJsonPayloadTest {
    @TempDir
    lateinit var tmp: Path

    private val payload = """{"projectName":"demo","toolName":"cli","summary":"s"}"""

    @Test
    fun `a payload with a UTF-8 byte-order mark is accepted`() {
        // What Windows PowerShell 5.1 `Set-Content -Encoding utf8` writes.
        val file = tmp.resolve("bom.json")
        Files.write(file, byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + payload.toByteArray())
        val input = readJsonPayload<UpdateContextInput>(file)
        assertEquals("demo", input.projectName)
    }

    @Test
    fun `malformed json is a clean CLI error, not a stack trace`() {
        val file = tmp.resolve("bad.json")
        Files.writeString(file, "{not json")
        val error = assertFailsWith<CliktError> { readJsonPayload<UpdateContextInput>(file) }
        assertTrue("Invalid --json payload" in error.message.orEmpty())
    }

    @Test
    fun `a missing required field is a clean CLI error`() {
        val file = tmp.resolve("partial.json")
        Files.writeString(file, """{"toolName":"cli"}""")
        assertFailsWith<CliktError> { readJsonPayload<UpdateContextInput>(file) }
    }

    @Test
    fun `an unreadable file is a clean CLI error`() {
        val error = assertFailsWith<CliktError> { readJsonPayload<UpdateContextInput>(tmp.resolve("missing.json")) }
        assertTrue("Cannot read --json file" in error.message.orEmpty())
    }
}
