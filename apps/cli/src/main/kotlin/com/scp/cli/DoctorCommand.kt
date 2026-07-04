package com.scp.cli

import app.cash.sqldelight.db.QueryResult
import com.github.ajalt.clikt.core.CliktError
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

/**
 * scp doctor — health checks (docs/02 ADR-4/8, spec):
 *  1. config.yaml parses and validates (implicit: components built at all)
 *  2. journal_mode is WAL
 *  3. foreign_keys is ON
 *  4. FTS index row count matches context_entry count (offers the rebuild repair)
 *  5. open sessions older than 24h (likely abandoned)
 */
internal class DoctorCommand : ScpCommand("doctor") {
    override fun run(components: CliComponents) {
        var failures = 0

        echo("config.yaml            OK (validated)")

        val journalMode = pragma(components, "journal_mode")
        if (journalMode.equals("wal", ignoreCase = true)) {
            echo("journal_mode           OK (wal)")
        } else {
            failures++
            echo("journal_mode           FAIL (was '$journalMode', expected 'wal')")
        }

        val foreignKeys = pragma(components, "foreign_keys")
        if (foreignKeys == "1") {
            echo("foreign_keys           OK (1)")
        } else {
            failures++
            echo("foreign_keys           FAIL (was '$foreignKeys', expected '1')")
        }

        val entryCount =
            components.handle.database.contextEntryQueries
                .countAll()
                .executeAsOne()
        val indexCount = components.searchIndex.indexedEntryCount()
        if (entryCount == indexCount) {
            echo("fts index              OK ($indexCount rows == $entryCount entries)")
        } else {
            failures++
            echo("fts index              FAIL ($indexCount rows != $entryCount entries) — repairing via rebuild...")
            components.searchIndex.rebuild()
            val repaired = components.searchIndex.indexedEntryCount()
            val verdict = if (repaired == entryCount) "repaired OK" else "still diverged after rebuild"
            echo("fts index              $verdict")
        }

        val cutoff = (Clock.System.now() - 24.hours).toString()
        val stale =
            components.handle.database.sessionQueries
                .findOpenOlderThan(cutoff)
                .executeAsList()
        if (stale.isEmpty()) {
            echo("stale open sessions    OK (none older than 24h)")
        } else {
            echo("stale open sessions    WARN (${stale.size} open session(s) older than 24h — likely abandoned):")
            stale.forEach { echo("  - ${it.id.take(ID_PREVIEW)} tool=${it.tool_name} started=${it.start_time}") }
        }

        if (failures > 0) throw CliktError("doctor found $failures failing check(s)")
        echo("All checks passed.")
    }

    private companion object {
        const val ID_PREVIEW = 8
    }

    private fun pragma(components: CliComponents, name: String): String =
        components.handle.driver
            .executeQuery(
                identifier = null,
                sql = "PRAGMA $name",
                mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0).orEmpty() else "") },
                parameters = 0,
            ).value
}
