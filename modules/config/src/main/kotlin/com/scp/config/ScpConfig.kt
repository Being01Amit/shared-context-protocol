package com.scp.config

import com.scp.model.RankingWeights
import kotlinx.serialization.Serializable

/**
 * `config.yaml` deserialized via kaml. Absent keys take these documented defaults;
 * present-but-invalid values fail startup loudly (see [ConfigLoader]) — a silently
 * defaulted ranking weight or token limit produces wrong hydration output with no
 * error, the worst failure mode for a memory system.
 */
@Serializable
public data class ScpConfig(
    val databasePath: String = "storage/database/scp.db",
    val markdownPath: String = "storage/markdown",
    val autoSaveIntervalSeconds: Int = 300,
    val hydrationTokenLimit: Int = 12_000,
    val hydrationRankingWeights: RankingWeights = RankingWeights(),
    val searchLimit: Int = 50,
    val logLevel: String = "INFO",
    /** Extends (never replaces) the built-in redaction patterns in core. */
    val secretRedactionPatterns: List<String> = emptyList(),
)
