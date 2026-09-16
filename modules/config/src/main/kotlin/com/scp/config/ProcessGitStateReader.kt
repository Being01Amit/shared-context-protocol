package com.scp.config

import com.scp.model.port.GitState
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Real [com.scp.model.port.GitStateReader] implementation: shells out to `git` against the
 * process's actual working directory (`user.dir`), not the `-Dscp.home`-overridable storage
 * `baseDir` — the two can diverge when `scp.home` points at fixed/shared storage, and the git
 * repo that matters is always wherever the process was actually launched from.
 */
public object ProcessGitStateReader {
    public fun read(): GitState? {
        val cwd = File(System.getProperty("user.dir"))
        val branch = runForOutput(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), cwd)?.takeUnless { it == "HEAD" }
        val commit = runForOutput(listOf("git", "rev-parse", "HEAD"), cwd)
        if (branch == null && commit == null) return null
        return GitState(branch = branch, commit = commit)
    }

    /**
     * Runs [command] and returns its trimmed stdout, or null on timeout, non-zero exit, empty output
     * or any launch failure.
     *
     * Waits for exit BEFORE reading stdout. Reading first blocks until the process closes the stream —
     * that is, until it exits — so a hung process would never reach the timeout at all. Waiting first
     * is safe here because `git rev-parse` prints one short line, far below the OS pipe buffer, so the
     * child can never block on a full pipe. stderr is discarded rather than left unread, for the same
     * reason.
     */
    internal fun runForOutput(command: List<String>, cwd: File, timeoutMillis: Long = TIMEOUT_MILLIS): String? =
        try {
            val process =
                ProcessBuilder(command)
                    .directory(cwd)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
            if (!process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
                null
            } else {
                val output =
                    process.inputStream
                        .bufferedReader()
                        .readText()
                        .trim()
                output.takeIf { process.exitValue() == 0 && it.isNotEmpty() }
            }
        } catch (
            // git not installed, cwd not a repo, process I/O failure — all mean "state unknown".
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    private const val TIMEOUT_MILLIS: Long = 2_000
}
