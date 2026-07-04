package com.scp.server

import io.github.oshai.kotlinlogging.KotlinLogging
import io.ktor.utils.io.streams.asInput
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.buffered
import java.nio.file.Path

private val logger = KotlinLogging.logger("com.scp.server.Main")

/**
 * SCP MCP server over stdio. Launched by the calling AI tool (Claude Code, Antigravity,
 * ...); stdout carries the protocol, so all logging goes to files/stderr (see logback.xml).
 */
public fun main() {
    val baseDir = Path.of(System.getProperty("scp.home") ?: System.getProperty("user.dir"))
    logger.info { "SCP MCP server starting, baseDir=$baseDir" }

    AppComponents.build(baseDir).use { components ->
        val server =
            Server(
                Implementation(name = "scp", version = "1.0.0"),
                ServerOptions(
                    capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false)),
                ),
            )
        server.registerScpTools(components)

        val transport =
            StdioServerTransport(
                System.`in`.asInput(),
                System.out.asSink().buffered(),
            )

        runBlocking {
            val session = server.createSession(transport)
            val done = Job()
            session.onClose {
                logger.info { "MCP session closed" }
                done.complete()
            }
            done.join()
        }
    }
    logger.info { "SCP MCP server stopped" }
}
