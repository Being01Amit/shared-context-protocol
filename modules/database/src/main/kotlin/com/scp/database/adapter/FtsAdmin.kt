package com.scp.database.adapter

import app.cash.sqldelight.db.SqlDriver
import org.sqlite.SQLiteErrorCode
import org.sqlite.SQLiteException

/**
 * FTS5 maintenance statements that SQLDelight's grammar cannot express (the special
 * `INSERT INTO fts(fts) VALUES (...)` command form). Semantics per ADR-8: the documented
 * repair when `scpx doctor` finds the index diverged from context_entry.
 */
public object FtsAdmin {
    public fun rebuild(driver: SqlDriver) {
        driver.execute(null, "INSERT INTO context_entry_fts(context_entry_fts) VALUES ('rebuild')", 0)
    }

    /**
     * Whether the index matches context_entry, per FTS5's own `integrity-check` with `rank = 1`,
     * which compares the index against the external content table rather than only checking the
     * index's internal structure.
     *
     * Row counts cannot answer this question: `SELECT count(*)` on an external-content FTS5
     * table scans the content table, so it equals the entry count however broken the index is.
     */
    public fun isConsistent(driver: SqlDriver): Boolean =
        try {
            driver.execute(null, "INSERT INTO context_entry_fts(context_entry_fts, rank) VALUES ('integrity-check', 1)", 0)
            true
        } catch (e: SQLiteException) {
            // A mismatch is reported as corruption; anything else (locked, I/O) is a real failure.
            if (e.resultCode == SQLiteErrorCode.SQLITE_CORRUPT_VTAB || e.resultCode == SQLiteErrorCode.SQLITE_CORRUPT) {
                false
            } else {
                throw e
            }
        }
}
