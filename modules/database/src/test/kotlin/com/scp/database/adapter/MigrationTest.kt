package com.scp.database.adapter

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.scp.database.ScpDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The upgrade path an existing workspace actually takes. Every real SCP database was created
 * by an earlier binary, so the migrations — not `Schema.create` — are what its data goes
 * through, and `verifyMigrations` only checks that the resulting *schema* matches.
 */
class MigrationTest {
    @TempDir
    lateinit var tmp: Path

    /** The v1 schema, before `next_step` and before `start_time_epoch_nanos`. */
    private fun createVersion1Database(path: Path) {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        driver.use {
            listOf(
                """
                CREATE TABLE project (
                    id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE,
                    description TEXT NOT NULL DEFAULT '', created_at TEXT NOT NULL, updated_at TEXT NOT NULL
                )
                """.trimIndent(),
                """
                CREATE TABLE session (
                    id TEXT NOT NULL PRIMARY KEY,
                    project_id TEXT NOT NULL REFERENCES project(id),
                    tool_name TEXT NOT NULL, start_time TEXT NOT NULL, end_time TEXT,
                    summary TEXT NOT NULL DEFAULT '', token_usage INTEGER,
                    status TEXT NOT NULL DEFAULT 'open' CHECK (status IN ('open', 'closed'))
                )
                """.trimIndent(),
                "CREATE INDEX idx_session_project_status ON session(project_id, status)",
                "CREATE INDEX idx_session_start_time ON session(start_time)",
                "PRAGMA user_version = 1",
            ).forEach { sql -> driver.execute(null, sql, 0) }
        }
    }

    private fun insertLegacySession(driver: SqlDriver, projectId: String, id: String, startTime: String) {
        driver.execute(
            null,
            "INSERT INTO session(id, project_id, tool_name, start_time, status) " +
                "VALUES ('$id', '$projectId', 'legacy-tool', '$startTime', 'closed')",
            0,
        )
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
                parameters = 0,
            ).value

    @Test
    fun `a version 1 database migrates to current and keeps its rows`() {
        val path = tmp.resolve("legacy.db")
        createVersion1Database(path)
        JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}").use { driver ->
            driver.execute(
                null,
                "INSERT INTO project(id, name, description, created_at, updated_at) " +
                    "VALUES ('p1', 'legacy', 'd', '2026-07-15T12:00:00Z', '2026-07-15T12:00:00Z')",
                0,
            )
            // The mixed fraction widths a real workspace accumulates: 9-digit, 6-digit and none.
            insertLegacySession(driver, "p1", "s-nanos", "2026-07-15T12:44:34.732767700Z")
            insertLegacySession(driver, "p1", "s-micros", "2026-07-15T12:14:23.039822Z")
            insertLegacySession(driver, "p1", "s-whole", "2026-07-15T11:00:00Z")
        }

        DriverFactory.open(path).use { handle ->
            assertEquals(ScpDatabase.Schema.version, userVersion(handle.driver))

            val sessions = SqlSessionRepository(handle.database)
            assertEquals(3, sessions.countByProject("p1"), "no rows lost in migration")

            // Backfill is whole-second precision, which is enough to order these three correctly.
            assertEquals(
                listOf("s-whole", "s-micros", "s-nanos"),
                sessions.listChronological("p1").map { it.id },
            )
            assertEquals("s-nanos", sessions.findLatest("p1")?.id)

            // next_step arrived in the 1 -> 2 migration and backfills to the empty string.
            assertTrue(sessions.listChronological("p1").all { it.nextStep.isEmpty() })
        }
    }

    @Test
    fun `migrated and freshly created databases end up at the same version`() {
        val legacy = tmp.resolve("legacy.db")
        createVersion1Database(legacy)
        val fresh = tmp.resolve("fresh.db")

        DriverFactory.open(legacy).use { migrated ->
            DriverFactory.open(fresh).use { created ->
                assertEquals(userVersion(created.driver), userVersion(migrated.driver))
            }
        }
    }

    @Test
    fun `opening an already current database is a no-op`() {
        // Re-running a migration would hit "duplicate column name"; this pins that it does not.
        val path = tmp.resolve("legacy.db")
        createVersion1Database(path)
        DriverFactory.open(path).use { }
        DriverFactory.open(path).use { handle ->
            assertEquals(ScpDatabase.Schema.version, userVersion(handle.driver))
        }
    }
}
