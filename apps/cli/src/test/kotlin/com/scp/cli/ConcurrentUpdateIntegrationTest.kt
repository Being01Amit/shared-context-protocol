package com.scp.cli

import com.scp.model.ContextType
import com.scp.model.mcp.CreateProjectInput
import com.scp.model.mcp.NewEntry
import com.scp.model.mcp.UpdateContextInput
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The mandated concurrent-write scenario (docs/04 §5): two independent client stacks —
 * separate connections, separate composition roots, same SQLite file — call
 * update_context on the same project within the same second.
 *
 * Expected: two session rows (never merged, per ADR-16), both tools' entries fully
 * persisted, FTS index consistent, no data loss, no corruption.
 */
class ConcurrentUpdateIntegrationTest {
    @TempDir
    lateinit var tmp: Path

    private fun input(tool: String) =
        UpdateContextInput(
            projectName = "shared",
            toolName = tool,
            summary = "$tool session",
            entries =
                (1..5).map { i ->
                    NewEntry(
                        title = "$tool entry $i",
                        content = "work item $i from $tool",
                        type = ContextType.FEATURE,
                        tags = listOf(tool),
                    )
                },
        )

    @Test
    fun `two tools updating the same project in the same second lose nothing and merge nothing`() {
        Files.createDirectories(tmp.resolve("storage/database"))
        // Stack A creates the project; both stacks then write concurrently.
        CliComponents.build(tmp).use { bootstrap ->
            bootstrap.createProject.execute(CreateProjectInput("shared", "concurrency test"))
        }

        CliComponents.build(tmp).use { claude ->
            CliComponents.build(tmp).use { antigravity ->
                val start = CountDownLatch(1)
                val pool = Executors.newFixedThreadPool(2)
                val futures =
                    listOf(
                        pool.submit<com.scp.model.mcp.UpdateContextResult> {
                            start.await()
                            claude.updateContext.execute(input("claude-code"))
                        },
                        pool.submit<com.scp.model.mcp.UpdateContextResult> {
                            start.await()
                            antigravity.updateContext.execute(input("antigravity"))
                        },
                    )
                start.countDown()
                val results = futures.map { it.get(60, TimeUnit.SECONDS) }
                pool.shutdown()

                // Distinct sessions — two tools never share one.
                assertEquals(2, results.map { it.sessionId }.toSet().size, "each tool must get its own session")
                results.forEach { assertEquals(5, it.entriesWritten) }
            }
        }
        verifyPersistedState()
    }

    /** Verify persisted state through a fresh stack (fresh connection). */
    private fun verifyPersistedState() {
        CliComponents.build(tmp).use { verifier ->
            val db = verifier.handle.database
            val project =
                db.projectQueries
                    .findByName("shared")
                    .executeAsOne()
            val sessions =
                db.sessionQueries
                    .listRecent(project.id, 10)
                    .executeAsList()
            assertEquals(2, sessions.size, "exactly two session rows")
            assertEquals(
                setOf("claude-code", "antigravity"),
                sessions.map { it.tool_name }.toSet(),
                "one session per tool, nothing merged",
            )
            sessions.forEach { assertEquals("closed", it.status) }

            val entryCount =
                db.contextEntryQueries
                    .countAll()
                    .executeAsOne()
            assertEquals(10, entryCount, "all 10 entries persisted, no data loss")
            assertEquals(10, verifier.searchIndex.indexedEntryCount(), "FTS index consistent with entries")

            // Both markdown mirrors exist, project-first under storage/projects, plus the
            // LATEST.md resume anchor and the PROJECT.md index (shared by both writers).
            val projectDir = tmp.resolve("storage/projects/shared")
            val names = Files.list(projectDir).use { s -> s.map { it.fileName.toString() }.toList() }
            assertEquals(
                2,
                names.count { it != "LATEST.md" && it != "PROJECT.md" },
                "one markdown mirror per session",
            )
            assertTrue("LATEST.md" in names, "resume anchor must exist")
            assertTrue("PROJECT.md" in names, "project index must exist")
        }
    }

    @Test
    fun `reader hydrates a consistent snapshot while writers are active`() {
        Files.createDirectories(tmp.resolve("storage/database"))
        CliComponents.build(tmp).use { bootstrap ->
            bootstrap.createProject.execute(CreateProjectInput("shared", "read-during-write test"))
            bootstrap.updateContext.execute(input("seed-tool"))
        }

        CliComponents.build(tmp).use { writer ->
            CliComponents.build(tmp).use { reader ->
                val pool = Executors.newFixedThreadPool(2)
                val writeFuture =
                    pool.submit {
                        repeat(3) { writer.updateContext.execute(input("busy-writer")) }
                    }
                val readFuture =
                    pool.submit<com.scp.model.HydrationPayload> {
                        reader.hydrateContext.execute(
                            com.scp.model.mcp
                                .HydrateContextInput(projectName = "shared"),
                        )
                    }
                val payload = readFuture.get(60, TimeUnit.SECONDS)
                writeFuture.get(60, TimeUnit.SECONDS)
                pool.shutdown()

                assertEquals("shared", payload.projectName)
                assertTrue(payload.recentSessions.isNotEmpty(), "reader saw a consistent committed snapshot")
            }
        }
    }
}
