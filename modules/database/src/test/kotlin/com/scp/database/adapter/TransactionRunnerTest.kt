package com.scp.database.adapter

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TransactionRunnerTest {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `two connections to the same file both persist their writes`() {
        val path = tmp.resolve("scp.db")
        // Two independent handles simulate two processes (separate connections, WAL file shared).
        DriverFactory.open(path).use { a ->
            DriverFactory.open(path).use { b ->
                val project = Fixtures.project()
                SqlProjectRepository(a.database).insert(project)

                val runnerA = SqliteTransactionRunner(a.database, path)
                val runnerB = SqliteTransactionRunner(b.database, path)
                val sessionsA = SqlSessionRepository(a.database)
                val sessionsB = SqlSessionRepository(b.database)
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                val results =
                    listOf(
                        pool.submit<Unit> {
                            start.await()
                            runnerA.inWriteTransaction { sessionsA.insert(Fixtures.session(project.id, "claude-code")) }
                        },
                        pool.submit<Unit> {
                            start.await()
                            runnerB.inWriteTransaction { sessionsB.insert(Fixtures.session(project.id, "antigravity")) }
                        },
                    )
                start.countDown()
                results.forEach { it.get(30, TimeUnit.SECONDS) }
                pool.shutdown()

                assertEquals(2, sessionsA.countByProject(project.id), "both concurrent writes must persist")
            }
        }
    }

    @Test
    fun `writer does not block reader (WAL snapshot)`() {
        val path = tmp.resolve("scp.db")
        DriverFactory.open(path).use { writer ->
            DriverFactory.open(path).use { reader ->
                val project = Fixtures.project()
                SqlProjectRepository(writer.database).insert(project)

                val runner = SqliteTransactionRunner(writer.database, path)
                val inTransaction = CountDownLatch(1)
                val readDone = CountDownLatch(1)
                var readCount = -1L
                val pool = Executors.newFixedThreadPool(2)

                val writeFuture =
                    pool.submit<Unit> {
                        runner.inWriteTransaction {
                            SqlSessionRepository(writer.database).insert(Fixtures.session(project.id))
                            inTransaction.countDown()
                            // Hold the write transaction open while the reader runs.
                            check(readDone.await(10, TimeUnit.SECONDS)) { "reader never finished" }
                        }
                    }
                val readFuture =
                    pool.submit<Unit> {
                        check(inTransaction.await(10, TimeUnit.SECONDS)) { "writer never started" }
                        readCount = SqlSessionRepository(reader.database).countByProject(project.id)
                        readDone.countDown()
                    }
                writeFuture.get(30, TimeUnit.SECONDS)
                readFuture.get(30, TimeUnit.SECONDS)
                pool.shutdown()

                assertEquals(0, readCount, "reader sees the pre-transaction snapshot, uncommitted write invisible")
                assertTrue(
                    SqlSessionRepository(reader.database).countByProject(project.id) == 1L,
                    "after commit the reader sees the write",
                )
            }
        }
    }
}
