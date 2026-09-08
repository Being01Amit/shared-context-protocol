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

/** The calling process's git branch and commit, when known. Either field may be absent on its own:
 * [branch] is null for a detached HEAD, [commit] is null for a repo with no commits yet. */
public data class GitState(val branch: String?, val commit: String?)

/**
 * Injectable git-state source — deterministic in tests. Returns null when the process isn't
 * running inside a git working tree, `git` isn't available, or the read fails; that means
 * "unknown," never "no branch."
 */
public fun interface GitStateReader {
    public fun read(): GitState?
}
