package com.scp.database.adapter

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.scp.database.ScpDatabase
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DriverFactoryTest {
    @TempDir
    lateinit var tmp: Path

    private fun pragma(driver: SqlDriver, name: String): String =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0).orEmpty() else "") },
            parameters = 0,
        ).value

    @Test
    fun `connection init applies WAL and foreign keys`() {
        DriverFactory.open(tmp.resolve("scp.db")).use { handle ->
            assertEquals("wal", pragma(handle.driver, "journal_mode").lowercase())
            assertEquals("1", pragma(handle.driver, "foreign_keys"))
        }
    }

    @Test
    fun `user_version is stamped after schema creation`() {
        DriverFactory.open(tmp.resolve("scp.db")).use { handle ->
            assertEquals(ScpDatabase.Schema.version.toString(), pragma(handle.driver, "user_version"))
        }
    }

    @Test
    fun `reopening an existing database does not recreate schema`() {
        val path = tmp.resolve("scp.db")
        DriverFactory.open(path).use { handle ->
            val p = Fixtures.project()
            SqlProjectRepository(handle.database).insert(p)
        }
        DriverFactory.open(path).use { handle ->
            assertEquals(1, SqlProjectRepository(handle.database).listAll().size)
        }
    }

    @Test
    fun `foreign key violations are rejected`() {
        DriverFactory.open(tmp.resolve("scp.db")).use { handle ->
            val orphan = Fixtures.session(projectId = "no-such-project")
            assertFailsWith<Exception> { SqlSessionRepository(handle.database).insert(orphan) }
        }
    }
}
