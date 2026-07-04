package com.scp.database.adapter

import com.scp.database.ScpDatabase
import com.scp.model.port.TransactionRunner
import java.nio.file.Path
import java.sql.SQLException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The write-path serialization point (docs/04 §3):
 *  - a JVM-level lock per database file guards the write path within this process;
 *  - the underlying connection begins transactions with BEGIN IMMEDIATE (DriverFactory),
 *    making resolve-then-insert atomic across processes;
 *  - SQLITE_BUSY from a concurrent process is retried with backoff, max 3 attempts.
 */
public class SqliteTransactionRunner(
    private val database: ScpDatabase,
    dbPath: Path,
) : TransactionRunner {
    private val lock = locks.computeIfAbsent(dbPath.toAbsolutePath().normalize().toString()) { ReentrantLock() }

    override fun <T> inWriteTransaction(block: () -> T): T =
        lock.withLock {
            var attempt = 0
            while (true) {
                try {
                    return database.transactionWithResult { block() }
                } catch (
                    // Busy detection must inspect any wrapped failure; non-busy errors rethrow.
                    @Suppress("TooGenericExceptionCaught") e: Exception,
                ) {
                    attempt++
                    if (!isBusy(e) || attempt > MAX_RETRIES) throw e
                    Thread.sleep(BACKOFF_MS[attempt - 1])
                }
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        }

    private fun isBusy(e: Throwable): Boolean {
        var cause: Throwable? = e
        while (cause != null) {
            if (cause is SQLException) {
                val message = cause.message.orEmpty()
                if ("SQLITE_BUSY" in message || "database is locked" in message) return true
            }
            cause = cause.cause
        }
        return false
    }

    private companion object {
        private const val MAX_RETRIES = 3
        private val BACKOFF_MS = longArrayOf(50, 150, 400)
        private val locks = ConcurrentHashMap<String, ReentrantLock>()
    }
}
