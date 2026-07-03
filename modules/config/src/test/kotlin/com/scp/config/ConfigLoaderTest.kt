package com.scp.config

import com.scp.model.ContextType
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigLoaderTest {
    @Test
    fun `valid yaml loads with all keys applied`() {
        val yaml =
            """
            databasePath: /data/scp.db
            markdownPath: /data/md
            autoSaveIntervalSeconds: 60
            hydrationTokenLimit: 8000
            hydrationRankingWeights:
              recency: 0.4
              priority: 0.2
              tagOverlap: 0.2
              type: 0.2
              recencyHalfLifeDays: 14
              typeMultipliers:
                DECISION: 1.0
                MEETING: 0.2
            searchLimit: 25
            logLevel: DEBUG
            """.trimIndent()
        val config = ConfigLoader.validated(ConfigLoader.parse(yaml))
        assertEquals("/data/scp.db", config.databasePath)
        assertEquals(8000, config.hydrationTokenLimit)
        assertEquals(0.4, config.hydrationRankingWeights.recency)
        assertEquals(14.0, config.hydrationRankingWeights.recencyHalfLifeDays)
        assertEquals(0.2, config.hydrationRankingWeights.typeMultipliers[ContextType.MEETING])
        assertEquals(25, config.searchLimit)
    }

    @Test
    fun `absent optional keys use documented defaults`() {
        val config = ConfigLoader.validated(ConfigLoader.parse("logLevel: WARN"))
        assertEquals("storage/database/scp.db", config.databasePath)
        assertEquals(12_000, config.hydrationTokenLimit)
        assertEquals(0.35, config.hydrationRankingWeights.recency)
        assertEquals(7.0, config.hydrationRankingWeights.recencyHalfLifeDays)
        assertEquals("WARN", config.logLevel)
    }

    @Test
    fun `missing file falls back to defaults`() {
        val dir = Files.createTempDirectory("scp-config-test")
        val config = ConfigLoader.load(dir.resolve("does-not-exist.yaml"))
        assertEquals(ScpConfig(), config)
    }

    @Test
    fun `negative weight fails naming the key`() {
        val e =
            assertFailsWith<ConfigException> {
                ConfigLoader.validated(ScpConfig(hydrationRankingWeights = com.scp.model.RankingWeights(recency = -0.1)))
            }
        assertTrue("hydrationRankingWeights.recency" in e.message!!, "message was: ${e.message}")
    }

    @Test
    fun `zero token limit fails naming the key`() {
        val e = assertFailsWith<ConfigException> { ConfigLoader.validated(ScpConfig(hydrationTokenLimit = 0)) }
        assertTrue("hydrationTokenLimit" in e.message!!)
    }

    @Test
    fun `bad log level fails naming the key`() {
        val e = assertFailsWith<ConfigException> { ConfigLoader.validated(ScpConfig(logLevel = "LOUD")) }
        assertTrue("logLevel" in e.message!!)
    }

    @Test
    fun `invalid redaction regex fails naming the index`() {
        val e =
            assertFailsWith<ConfigException> {
                ConfigLoader.validated(ScpConfig(secretRedactionPatterns = listOf("[unclosed")))
            }
        assertTrue("secretRedactionPatterns[0]" in e.message!!)
    }

    @Test
    fun `out-of-range type multiplier fails naming the type`() {
        val e =
            assertFailsWith<ConfigException> {
                ConfigLoader.validated(
                    ScpConfig(
                        hydrationRankingWeights =
                            com.scp.model.RankingWeights(typeMultipliers = mapOf(ContextType.BUG to 1.5)),
                    ),
                )
            }
        assertTrue("typeMultipliers.BUG" in e.message!!)
    }

    @Test
    fun `unparsable yaml fails with location info`() {
        val e = assertFailsWith<ConfigException> { ConfigLoader.parse("hydrationTokenLimit: [not-an-int") }
        assertTrue("line" in e.message!!)
    }
}
