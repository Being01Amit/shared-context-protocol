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
        val branch = run("rev-parse", "--abbrev-ref", "HEAD", cwd = cwd)?.takeUnless { it == "HEAD" }
        val commit = run("rev-parse", "HEAD", cwd = cwd)
        if (branch == null && commit == null) return null
        return GitState(branch = branch, commit = commit)
    }

    private fun run(vararg args: String, cwd: File): String? =
        try {
            val process =
                ProcessBuilder("git", *args)
                    .directory(cwd)
                    .redirectErrorStream(false)
                    .start()
            val output = process.inputStream.bufferedReader().readText().trim()
            val finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                null
            } else if (process.exitValue() != 0 || output.isEmpty()) {
                null
            } else {
                output
            }
        } catch (
            // git not installed, cwd not a repo, process I/O failure — all mean "state unknown".
            @Suppress("TooGenericExceptionCaught", "SwallowedException") e: Exception,
        ) {
            null
        }

    private const val TIMEOUT_SECONDS: Long = 2
}
