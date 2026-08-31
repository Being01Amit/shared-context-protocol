package com.scp.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Regression tests for the log/data split: logback resolves a relative appender path against the
 * JVM working directory, while everything else resolves against `baseDir`. Whenever `-Dscp.home`
 * points elsewhere, the two diverge and logs land in a different tree from the database — which is
 * exactly how this project's own workspace ended up with a log file full of successful writes
 * sitting next to an empty `storage/database/`.
 *
 * [SecureFiles.prepareLogging] fixes it by pinning an absolute, baseDir-derived path into the
 * system property logback reads.
 */
class SecureFilesTest {
    @TempDir
    lateinit var tmp: Path

    private val savedProperty: String? = System.getProperty("SCP_LOG_DIR")

    @AfterTest
    fun restore() {
        if (savedProperty == null) System.clearProperty("SCP_LOG_DIR") else System.setProperty("SCP_LOG_DIR", savedProperty)
    }

    @Test
    fun `log directory resolves under baseDir, not the working directory`() {
        val resolved = SecureFiles.logDirectory(tmp)
        assertEquals(tmp.resolve("storage/logs").toAbsolutePath().normalize(), resolved)
        assertTrue(resolved.isAbsolute, "an absolute path cannot be re-resolved against the wrong root")
    }

    @Test
    fun `prepareLogging creates the directory and pins logback to an absolute path`() {
        val logDir = SecureFiles.prepareLogging(tmp)

        assertTrue(Files.isDirectory(logDir), "log directory must exist before the first log line")
        val pinned = Path.of(System.getProperty("SCP_LOG_DIR"))
        assertTrue(pinned.isAbsolute, "logback must be given an absolute path")
        assertEquals(logDir, pinned)
        assertTrue(pinned.startsWith(tmp), "logs must live under baseDir, not the working directory")
    }

    @Test
    fun `prepareLogging is idempotent across restarts`() {
        val first = SecureFiles.prepareLogging(tmp)
        val second = SecureFiles.prepareLogging(tmp)
        assertEquals(first, second)
        assertTrue(Files.isDirectory(second))
    }

    @Test
    fun `prepareStorage creates every configured runtime directory`() {
        val config = ScpConfig(databasePath = "storage/database/scp.db", markdownPath = "storage/projects")
        SecureFiles.prepareStorage(tmp, config)

        assertTrue(Files.isDirectory(tmp.resolve("storage/database")), "database directory")
        assertTrue(Files.isDirectory(tmp.resolve("storage/projects")), "markdown root is storage/projects by default")
        assertTrue(Files.isDirectory(tmp.resolve("storage/logs")), "log directory")
    }

    @Test
    fun `markdown root defaults to storage projects`() {
        assertEquals("storage/projects", ScpConfig().markdownPath)
    }
}
