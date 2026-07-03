package com.scp.model.port

import kotlinx.datetime.Instant

/**
 * Write-path serialization point. Implementations must begin an IMMEDIATE transaction
 * (write lock up front, making check-then-act atomic across processes), hold a JVM-level
 * per-database-file lock within the process, and retry with backoff (max 3 attempts)
 * on SQLITE_BUSY. See docs/04-session-resolution.md §3.
 */
public interface TransactionRunner {
    public fun <T> inWriteTransaction(block: () -> T): T
}

/** Injectable time source — deterministic in tests. */
public fun interface Clock {
    public fun now(): Instant
}

/** Injectable UUID v4 source — deterministic in tests. */
public fun interface IdGenerator {
    public fun newId(): String
}
