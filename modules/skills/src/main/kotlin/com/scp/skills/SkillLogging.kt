package com.scp.skills

import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("com.scp.skills")

/**
 * Every skill invocation is logged with skill name, project, duration, and outcome —
 * the debugging surface for multi-tool workflows. Applies to both MCP and CLI callers
 * because it lives here, not in the delivery layer.
 */
internal inline fun <T> logged(skill: String, project: String?, block: () -> T): T {
    val start = System.nanoTime()
    return try {
        val result = block()
        logger.info {
            "skill=$skill project=${project ?: "-"} durationMs=${elapsedMs(start)} outcome=ok"
        }
        result
    } catch (
        // Log-and-rethrow instrumentation must observe every failure kind.
        @Suppress("TooGenericExceptionCaught") t: Throwable,
    ) {
        logger.warn {
            "skill=$skill project=${project ?: "-"} durationMs=${elapsedMs(start)} outcome=error error=${t.message}"
        }
        throw t
    }
}

private const val NANOS_PER_MILLI = 1_000_000L

@PublishedApi
internal fun elapsedMs(startNanos: Long): Long = (System.nanoTime() - startNanos) / NANOS_PER_MILLI
