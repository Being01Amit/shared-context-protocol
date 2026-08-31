package com.scp.database.adapter

import com.scp.model.StorageException
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EncryptionTest {
    @TempDir
    lateinit var tmp: Path

    private val key = "correct horse battery staple"

    /** Reads the database file and every sidecar (-wal/-shm) as one byte blob. */
    private fun allStorageBytes(dbPath: Path): ByteArray =
        listOf(dbPath, Path.of("$dbPath-wal"), Path.of("$dbPath-shm"))
            .filter(Files::exists)
            .fold(ByteArray(0)) { acc, p -> acc + Files.readAllBytes(p) }

    @Test
    fun `encrypted database is ciphertext on disk`() {
        val path = tmp.resolve("enc.db")
        val secret = "top-secret-project-name-9f3a"
        DriverFactory.open(path, key).use { handle ->
            SqlProjectRepository(handle.database).insert(Fixtures.project(secret))
        }

        val header = String(Files.readAllBytes(path).take(HEADER_LEN).toByteArray(), Charsets.US_ASCII)
        assertFalse(header.startsWith(SQLITE_MAGIC), "encrypted db must not carry the plaintext SQLite header")

        val onDisk = String(allStorageBytes(path), Charsets.ISO_8859_1)
        assertFalse(onDisk.contains(secret), "the project name must never appear in cleartext on disk")
    }

    @Test
    fun `reopening with the correct key returns the data`() {
        val path = tmp.resolve("enc.db")
        DriverFactory.open(path, key).use { handle ->
            SqlProjectRepository(handle.database).insert(Fixtures.project("p1"))
        }
        DriverFactory.open(path, key).use { handle ->
            assertEquals(1, SqlProjectRepository(handle.database).listAll().size)
        }
    }

    @Test
    fun `opening an encrypted database without a key fails with StorageException`() {
        val path = tmp.resolve("enc.db")
        DriverFactory.open(path, key).use { handle ->
            SqlProjectRepository(handle.database).insert(Fixtures.project("p1"))
        }
        assertFailsWith<StorageException> { DriverFactory.open(path, null) }
    }

    @Test
    fun `opening with the wrong key fails with StorageException`() {
        val path = tmp.resolve("enc.db")
        DriverFactory.open(path, key).use { handle ->
            SqlProjectRepository(handle.database).insert(Fixtures.project("p1"))
        }
        assertFailsWith<StorageException> { DriverFactory.open(path, "wrong-key") }
    }

    @Test
    fun `an unencrypted database keeps the standard header (no key)`() {
        val path = tmp.resolve("plain.db")
        DriverFactory.open(path).use { handle ->
            SqlProjectRepository(handle.database).insert(Fixtures.project("p1"))
        }
        val header = String(Files.readAllBytes(path).take(HEADER_LEN).toByteArray(), Charsets.US_ASCII)
        assertTrue(header.startsWith(SQLITE_MAGIC), "an unencrypted db retains the plaintext SQLite header")
    }

    private companion object {
        const val SQLITE_MAGIC = "SQLite format 3"
        const val HEADER_LEN = 16
    }
}
