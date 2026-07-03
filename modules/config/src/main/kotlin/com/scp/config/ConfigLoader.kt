package com.scp.config

import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlException
import java.nio.file.Files
import java.nio.file.Path

/** Startup failure with a precise, key-naming message — never a stack trace to the user. */
public class ConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

public object ConfigLoader {
    private val validLogLevels = setOf("TRACE", "DEBUG", "INFO", "WARN", "ERROR")

    /**
     * Loads and validates configuration. A missing file is not an error (all keys have
     * documented defaults); an unparsable or invalid file aborts startup.
     */
    public fun load(path: Path): ScpConfig {
        val config =
            if (Files.exists(path)) {
                val text = Files.readString(path)
                if (text.isBlank()) {
                    ScpConfig()
                } else {
                    parse(text, path.toString())
                }
            } else {
                ScpConfig()
            }
        return validated(config)
    }

    public fun parse(yamlText: String, sourceName: String = "config.yaml"): ScpConfig =
        try {
            Yaml.default.decodeFromString(ScpConfig.serializer(), yamlText)
        } catch (e: YamlException) {
            throw ConfigException("$sourceName is not valid: ${e.message} (line ${e.line}, column ${e.column})", e)
        }

    /** Fail-fast constraint validation. Every violation names the offending key. */
    public fun validated(config: ScpConfig): ScpConfig {
        val errors = mutableListOf<String>()
        if (config.databasePath.isBlank()) errors += "databasePath must not be blank"
        if (config.markdownPath.isBlank()) errors += "markdownPath must not be blank"
        if (config.autoSaveIntervalSeconds <= 0) {
            errors += "autoSaveIntervalSeconds must be > 0 (was ${config.autoSaveIntervalSeconds})"
        }
        if (config.hydrationTokenLimit <= 0) {
            errors += "hydrationTokenLimit must be > 0 (was ${config.hydrationTokenLimit})"
        }
        if (config.searchLimit <= 0) errors += "searchLimit must be > 0 (was ${config.searchLimit})"
        if (config.logLevel.uppercase() !in validLogLevels) {
            errors += "logLevel must be one of $validLogLevels (was '${config.logLevel}')"
        }
        val w = config.hydrationRankingWeights
        if (w.recency < 0) errors += "hydrationRankingWeights.recency must be >= 0 (was ${w.recency})"
        if (w.priority < 0) errors += "hydrationRankingWeights.priority must be >= 0 (was ${w.priority})"
        if (w.tagOverlap < 0) errors += "hydrationRankingWeights.tagOverlap must be >= 0 (was ${w.tagOverlap})"
        if (w.type < 0) errors += "hydrationRankingWeights.type must be >= 0 (was ${w.type})"
        if (w.semantic < 0) errors += "hydrationRankingWeights.semantic must be >= 0 (was ${w.semantic})"
        if (w.recencyHalfLifeDays <= 0) {
            errors += "hydrationRankingWeights.recencyHalfLifeDays must be > 0 (was ${w.recencyHalfLifeDays})"
        }
        w.typeMultipliers.forEach { (type, value) ->
            if (value !in 0.0..1.0) {
                errors += "hydrationRankingWeights.typeMultipliers.$type must be in [0, 1] (was $value)"
            }
        }
        config.secretRedactionPatterns.forEachIndexed { index, patternText ->
            try {
                Regex(patternText)
            } catch (e: IllegalArgumentException) {
                errors += "secretRedactionPatterns[$index] is not a valid regex: ${e.message}"
            }
        }
        if (errors.isNotEmpty()) {
            throw ConfigException("Invalid configuration:\n - " + errors.joinToString("\n - "))
        }
        return config
    }
}
