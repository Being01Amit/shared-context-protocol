package com.scp.database.adapter

import app.cash.sqldelight.db.SqlDriver

/**
 * FTS5 maintenance statements that SQLDelight's grammar cannot express (the special
 * `INSERT INTO fts(fts) VALUES ('rebuild')` command form). Semantics per ADR-8: the
 * documented repair when `scp doctor` finds the index diverged from context_entry.
 */
public object FtsAdmin {
    public fun rebuild(driver: SqlDriver) {
        driver.execute(null, "INSERT INTO context_entry_fts(context_entry_fts) VALUES ('rebuild')", 0)
    }
}
