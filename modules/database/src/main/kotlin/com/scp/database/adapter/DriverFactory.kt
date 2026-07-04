package com.scp.database.adapter

import app.cash.sqldelight.EnumColumnAdapter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.scp.database.Context_entry
import com.scp.database.ScpDatabase
import org.sqlite.SQLiteConfig
import java.nio.file.Files
import java.nio.file.Path

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
 */
public object DriverFactory {
    public fun open(dbPath: Path): DatabaseHandle {
        dbPath.toAbsolutePath().parent?.let(Files::createDirectories)
        val config =
            SQLiteConfig().apply {
                setJournalMode(SQLiteConfig.JournalMode.WAL)
                enforceForeignKeys(true)
                setBusyTimeout(BUSY_TIMEOUT_MS)
                setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
                setTransactionMode(SQLiteConfig.TransactionMode.IMMEDIATE)
            }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${dbPath.toAbsolutePath()}", config.toProperties())
        createOrMigrate(driver)
        val database =
            ScpDatabase(
                driver = driver,
                context_entryAdapter = Context_entry.Adapter(typeAdapter = EnumColumnAdapter()),
            )
        return DatabaseHandle(driver, database)
    }

    private fun createOrMigrate(driver: SqlDriver) {
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
            current < target -> {
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
