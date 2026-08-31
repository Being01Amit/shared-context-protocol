package com.scp.database.adapter

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.scp.database.Context_entry
import com.scp.database.ScpDatabase
import com.scp.model.StorageException
import org.sqlite.SQLiteConfig
import org.sqlite.mc.SQLiteMCConfig
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties

/** The open database: generated typed API plus the raw driver (for PRAGMA checks in doctor). */
public class DatabaseHandle(
    public val driver: SqlDriver,
    public val database: ScpDatabase,
) : AutoCloseable {
    override fun close() {
        driver.close()
    }
}

/**
 * Opens the SQLite database with the connection init required by ADR-4, applied to every
 * connection sqlite-jdbc creates:
 *  - journal_mode = WAL      (cross-process readers never block the writer)
 *  - foreign_keys = ON       (SQLite does NOT default to this)
 *  - busy_timeout = 5000     (wait before SQLITE_BUSY)
 *  - synchronous = NORMAL    (safe with WAL)
 *  - transaction mode IMMEDIATE (write lock taken at BEGIN — atomic check-then-act)
 *
 * When [encryptionKey] is non-blank the database is opened with transparent at-rest
 * encryption (SQLite3MultipleCiphers). The whole file is ciphertext on disk and decrypted
 * in memory, so FTS5 search and ranking are unaffected. The key is applied to every
 * connection via properties; a wrong or missing key surfaces as a [StorageException].
 */
public object DriverFactory {
    public fun open(dbPath: Path, encryptionKey: String? = null): DatabaseHandle {
        dbPath.toAbsolutePath().parent?.let(Files::createDirectories)
        val config =
            SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                enforceForeignKeys(true)
                setBusyTimeout(BUSY_TIMEOUT_MS)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
            }
        val properties = connectionProperties(config, encryptionKey)
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", properties)
        // Built before the schema work so migrations can run inside its transaction; constructing
        // the transacter touches no connection.
        val database =
            ScpDatabase(
                driver = driver,
                context_entryAdapter = Context_entry.Adapter(typeAdapter = EnumColumnAdapter()),
            )
        try {
            createOrMigrate(driver, database)
        } catch (
            @Suppress("TooGenericExceptionCaught") t: Throwable,
        ) {
            driver.close()
            throw asStorageFailure(t, encryptionKey)
        }
        return DatabaseHandle(driver, database)
    }

    /**
     * Merges the ADR-4 pragma config with the encryption key (when present). The cipher
     * key must survive; the explicit pragmas win on any overlap, so they are applied last.
     */
    private fun connectionProperties(config: SQLiteConfig, encryptionKey: String?): Properties {
        val properties = Properties()
        if (!encryptionKey.isNullOrBlank()) {
            val cipher =
                SQLiteMCConfig
                    .Builder()
                    .withKey(encryptionKey)
                    .build()
                    .toProperties()
            properties.putAll(cipher)
        }
        properties.putAll(config.toProperties())
        return properties
    }

    /** Turns a low-level open failure into an actionable [StorageException] about the key. */
    private fun asStorageFailure(cause: Throwable, encryptionKey: String?): StorageException {
        val looksEncrypted =
            generateSequence(cause) { it.cause }.any {
                val m = it.message.orEmpty()
                "not a database" in m || "NOTADB" in m || "file is encrypted" in m
            }
        val message =
            when {
                !encryptionKey.isNullOrBlank() && looksEncrypted ->
                    "Could not open the database: the SCP_DB_KEY is incorrect, or the file is not encrypted with it."
                encryptionKey.isNullOrBlank() && looksEncrypted ->
                    "The database is encrypted but SCP_DB_KEY is not set. Set SCP_DB_KEY to open it."
                else -> "Could not open the database at rest: ${cause.message}"
            }
        return StorageException(message, cause)
    }

    private fun createOrMigrate(driver: SqlDriver, database: ScpDatabase) {
        // sqlite-jdbc wraps every autocommit statement in its own transaction, so schema
        // creation cannot be wrapped in a manual BEGIN/COMMIT here. A concurrent first-open
        // from another process is tolerated instead: "already exists" is accepted when the
        // other process won the race and the schema is in place.
        val current = userVersion(driver)
        val target = ScpDatabase.Schema.version
        when {
            current == 0L && !hasProjectTable(driver) -> {
                try {
                    ScpDatabase.Schema.create(driver)
                } catch (
                    @Suppress("TooGenericExceptionCaught") t: Throwable,
                ) {
                    if (!hasProjectTable(driver)) throw t
                }
                driver.execute(null, "PRAGMA user_version = $target", 0)
            }
            // Migrating and stamping the version must commit together. As two autocommit
            // statements, a crash in between leaves the schema migrated but user_version behind,
            // and the next open replays the migration onto a table that already has the column:
            // "duplicate column name", wrapped as StorageException, on every launch thereafter.
            current < target ->
                database.transaction {
                    ScpDatabase.Schema.migrate(driver, current, target)
                    driver.execute(null, "PRAGMA user_version = $target", 0)
                }
            else -> Unit
        }
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
                },
                parameters = 0,
            ).value

    private fun hasProjectTable(driver: SqlDriver): Boolean =
        driver
            .executeQuery(
                identifier = null,
                sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = 'project'",
                mapper = { cursor ->
                    QueryResult.Value(if (cursor.next().value) (cursor.getLong(0) ?: 0L) > 0 else false)
                },
                parameters = 0,
            ).value

    private const val BUSY_TIMEOUT_MS: Int = 5_000
}
