package com.scp.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The subprocess runner behind the git state read. Uses a tiny Java program (single-file source
 * launch, JDK 11+) as the child so the test runs identically on every OS without needing git.
 */
class ProcessGitStateReaderTest {
    @TempDir
    lateinit var tmp: Path

    private val java: String = Path.of(System.getProperty("java.home"), "bin", "java").toString()

    private fun program(name: String, body: String): Path {
        val file = tmp.resolve("$name.java")
        Files.writeString(file, "public class $name { public static void main(String[] a) throws Exception { $body } }")
        return file
    }

    @Test
    fun `returns trimmed stdout of a successful command`() {
        val source = program("Hello", "System.out.println(\"  main  \");")
        assertEquals("main", ProcessGitStateReader.runForOutput(listOf(java, source.toString()), tmp.toFile(), timeoutMillis = 60_000))
    }

    @Test
    fun `non-zero exit is unknown state`() {
        val source = program("Fails", "System.out.println(\"partial\"); System.exit(3);")
        assertNull(ProcessGitStateReader.runForOutput(listOf(java, source.toString()), tmp.toFile(), timeoutMillis = 60_000))
    }

    @Test
    fun `a hung process is abandoned at the timeout instead of blocking forever`() {
        // Prints output and then keeps stdout open — reading stdout before waiting would block
        // for the full minute, which is exactly the bug this pins.
        val source = program("Hangs", "System.out.println(\"x\"); System.out.flush(); Thread.sleep(60000);")
        val started = System.nanoTime()
        val result = ProcessGitStateReader.runForOutput(listOf(java, source.toString()), tmp.toFile(), timeoutMillis = 1_500)
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000
        assertNull(result)
        assertTrue(elapsedMillis < 20_000, "returned after ${elapsedMillis}ms; the child sleeps 60 000ms")
    }

    @Test
    fun `a command that cannot be launched is unknown state`() {
        assertNull(ProcessGitStateReader.runForOutput(listOf("scp-no-such-binary-xyz"), tmp.toFile()))
    }
}
