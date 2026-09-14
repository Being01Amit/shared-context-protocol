package com.scp.server

/**
 * The version this server reports to MCP clients, read from the jar manifest's `Implementation-Version`
 * (stamped by the build from `-Pversion`). Running from unpackaged classes — tests, an IDE — has no
 * manifest, and a build without `-Pversion` has no real version either; both report [DEV].
 */
internal object ServerVersion {
    const val DEV: String = "0.0.0-dev"

    val current: String = resolve(ServerVersion::class.java.`package`?.implementationVersion)

    fun resolve(manifestVersion: String?): String =
        manifestVersion?.trim()?.takeUnless { it.isEmpty() || it == "unspecified" } ?: DEV
}
