package com.scp.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Least-privilege file-system permissions for SCP's runtime storage (compliance finding 2).
 *
 * On POSIX filesystems, reading a file requires traverse permission on every ancestor
 * directory, so `0700` on the storage directories is the dominant control: it protects the
 * SQLite database, its `-wal`/`-shm` sidecars, the Markdown mirrors, logs, and temp files
 * from other local users — including files created later or created natively by sqlite that
 * we never see at creation time. Restricting the database file itself to `0600` is
 * defense-in-depth on top of that.
 *
 * Every operation is a no-op on non-POSIX filesystems (Windows — the primary platform here),
 * where files under the user profile already inherit a restrictive ACL. All calls are
 * idempotent and safe to run on every startup, which also re-tightens directories that
 * pre-date this fix.
 */
public object SecureFiles {
    private val OWNER_DIR_PERMS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")
    private val OWNER_FILE_PERMS: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")

    /** Creates [path] (and parents) if needed and, on POSIX, restricts it to owner-only (`0700`). */
    public fun secureDirectory(path: Path): Path {
        Files.createDirectories(path)
        if (isPosix(path)) {
            trySetPermissions(path, OWNER_DIR_PERMS)
        }
        return path
    }

    /** On POSIX, restricts an existing file to owner read/write (`0600`); best-effort no-op otherwise. */
    public fun restrict(path: Path) {
        if (Files.exists(path) && isPosix(path)) {
            trySetPermissions(path, OWNER_FILE_PERMS)
        }
    }

    /**
     * Secures every runtime storage directory implied by [config] under [baseDir]: the storage
     * root, the database directory, the Markdown directory, and the log directory
     * (`$SCP_LOG_DIR`, matching logback.xml, or `storage/logs`). Idempotent.
     */
    public fun prepareStorage(baseDir: Path, config: ScpConfig) {
        secureDirectory(baseDir.resolve("storage"))
        baseDir.resolve(config.databasePath).parent?.let(::secureDirectory)
        secureDirectory(baseDir.resolve(config.markdownPath))
        secureDirectory(logDirectory(baseDir))
    }

    /**
     * The one definition of where logs go: `$SCP_LOG_DIR` resolved against [baseDir] (an absolute
     * `SCP_LOG_DIR` wins, per [Path.resolve]), else `<baseDir>/storage/logs`.
     */
    public fun logDirectory(baseDir: Path): Path =
        (System.getenv(LOG_DIR_VAR)?.let(baseDir::resolve) ?: baseDir.resolve("storage/logs"))
            .toAbsolutePath()
            .normalize()

    /**
     * Creates and secures the log directory and pins logback to it, **before the first logger is
     * obtained**.
     *
     * Without this, logback resolves its `${SCP_LOG_DIR:-storage/logs}` appender path against the
     * JVM working directory while everything else here resolves against `baseDir`. Whenever
     * `-Dscp.home` points somewhere other than the working directory, the database and the logs
     * land in two unrelated trees — which is exactly what happened to this project's own workspace:
     * `storage/logs/` full of successful writes sitting next to an empty `storage/database/`.
     *
     * Setting the system property is what fixes it: logback resolves system properties ahead of
     * environment variables, and an absolute path cannot be re-resolved against the wrong root.
     */
    public fun prepareLogging(baseDir: Path): Path {
        val logDir = logDirectory(baseDir)
        secureDirectory(logDir)
        System.setProperty(LOG_DIR_VAR, logDir.toString())
        return logDir
    }

    private const val LOG_DIR_VAR: String = "SCP_LOG_DIR"

    private fun isPosix(path: Path): Boolean = path.fileSystem.supportedFileAttributeViews().contains("posix")

    private fun trySetPermissions(path: Path, perms: Set<PosixFilePermission>) {
        try {
            Files.setPosixFilePermissions(path, perms)
        } catch (_: UnsupportedOperationException) {
            // Filesystem advertised POSIX but rejected the change — leave OS defaults in place.
        }
    }
}
