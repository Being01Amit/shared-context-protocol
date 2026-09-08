package com.scp.server

import com.scp.model.mcp.ProjectSummaryInput
import io.modelcontextprotocol.kotlin.sdk.server.ClientConnection
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageRequest
import io.modelcontextprotocol.kotlin.sdk.types.CreateMessageResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequest
import io.modelcontextprotocol.kotlin.sdk.types.ElicitRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ElicitResult
import io.modelcontextprotocol.kotlin.sdk.types.ElicitationCompleteNotification
import io.modelcontextprotocol.kotlin.sdk.types.EmptyResult
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsRequest
import io.modelcontextprotocol.kotlin.sdk.types.ListRootsResult
import io.modelcontextprotocol.kotlin.sdk.types.LoggingMessageNotification
import io.modelcontextprotocol.kotlin.sdk.types.PingRequest
import io.modelcontextprotocol.kotlin.sdk.types.RequestId
import io.modelcontextprotocol.kotlin.sdk.types.ResourceUpdatedNotification
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.types.ServerNotification
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives real tool calls through a real [Server] + [registerScpTools], exercising the actual
 * registered handler (validation -> use case -> SQLite -> markdown mirror) — not just the
 * JSON-decoding shape [McpBoundaryTest] covers.
 *
 * The SDK exposes no public direct-invoke API and ships no in-memory transport or client
 * artifact (verified against the installed 0.14.0 sources), so hand-rolling a full JSON-RPC
 * transport pair would mean reimplementing wire framing this test doesn't need to prove
 * anything about. [RegisteredTool.handler] is public and is the exact closure the SDK's own
 * request routing would dispatch a real "tools/call" to — invoking it directly here exercises
 * every line of SCP's own code in the pipeline while skipping only the SDK's own internal
 * JSON-RPC routing, which is library code, not SCP's.
 */
class McpServerIntegrationTest {
    @TempDir
    lateinit var tmp: Path

    private fun newServer(components: AppComponents): Server {
        val server =
            Server(
                Implementation(name = "scp", version = "1.0.0"),
                ServerOptions(capabilities = ServerCapabilities(tools = ServerCapabilities.Tools(listChanged = false))),
            )
        server.registerScpTools(components)
        return server
    }

    @Test
    fun `create_project then update_context persist through the real registered handlers`() =
        runBlocking {
            Files.createDirectories(tmp.resolve("storage/database"))
            val connection = NoOpClientConnection()

            AppComponents.build(tmp).use { components ->
                val server = newServer(components)

                val createResult =
                    server.tools.getValue("create_project").handler.invoke(
                        connection,
                        CallToolRequest(
                            CallToolRequestParams(
                                name = "create_project",
                                arguments =
                                    buildJsonObject {
                                        put("name", "demo")
                                        put("description", "H6 pipeline test")
                                    },
                            ),
                        ),
                    )
                assertTrue(createResult.isError != true, "create_project: ${createResult.content}")

                val updateResult =
                    server.tools.getValue("update_context").handler.invoke(
                        connection,
                        CallToolRequest(
                            CallToolRequestParams(
                                name = "update_context",
                                arguments =
                                    buildJsonObject {
                                        put("projectName", "demo")
                                        put("toolName", "integration-test")
                                        put("summary", "drove a real tool call end to end")
                                    },
                            ),
                        ),
                    )
                assertTrue(updateResult.isError != true, "update_context: ${updateResult.content}")
            }

            // Fresh connection: proves the write reached SQLite, not just an in-memory fake.
            AppComponents.build(tmp).use { reopened ->
                val summary = reopened.summarizeContext.execute(ProjectSummaryInput("demo"))
                assertEquals(1, summary.sessionCount)
            }
        }

    @Test
    fun `a malformed call returns isError instead of crashing the session`() =
        runBlocking {
            Files.createDirectories(tmp.resolve("storage/database"))
            val connection = NoOpClientConnection()

            AppComponents.build(tmp).use { components ->
                val server = newServer(components)

                // projectName is required; omitting it must reject cleanly, not throw uncaught —
                // the exact failure class 67ac216 fixed, now pinned at this layer too.
                val result =
                    server.tools.getValue("update_context").handler.invoke(
                        connection,
                        CallToolRequest(
                            CallToolRequestParams(
                                name = "update_context",
                                arguments = buildJsonObject { put("toolName", "integration-test") },
                            ),
                        ),
                    )
                assertTrue(result.isError == true)
            }
        }
}

/**
 * SCP's tool handlers never call back into the client, so every member here is unused in
 * practice — this exists only to satisfy [ClientConnection]'s signature for direct handler
 * invocation in tests.
 */
private class NoOpClientConnection(override val sessionId: String = "test-session") : ClientConnection {
    override suspend fun notification(notification: ServerNotification, relatedRequestId: RequestId?) = Unit

    override suspend fun ping(request: PingRequest, options: RequestOptions?): EmptyResult = EmptyResult()

    override suspend fun createMessage(request: CreateMessageRequest, options: RequestOptions?): CreateMessageResult =
        error("not used by SCP tool handlers")

    override suspend fun listRoots(request: ListRootsRequest, options: RequestOptions?): ListRootsResult =
        error("not used by SCP tool handlers")

    override suspend fun createElicitation(
        message: String,
        requestedSchema: ElicitRequestParams.RequestedSchema,
        options: RequestOptions?,
    ): ElicitResult = error("not used by SCP tool handlers")

    override suspend fun createElicitation(
        message: String,
        elicitationId: String,
        url: String,
        options: RequestOptions?,
    ): ElicitResult = error("not used by SCP tool handlers")

    override suspend fun createElicitation(request: ElicitRequest, options: RequestOptions?): ElicitResult =
        error("not used by SCP tool handlers")

    override suspend fun sendLoggingMessage(notification: LoggingMessageNotification) = Unit

    override suspend fun sendResourceUpdated(notification: ResourceUpdatedNotification) = Unit

    override suspend fun sendResourceListChanged() = Unit

    override suspend fun sendToolListChanged() = Unit

    override suspend fun sendPromptListChanged() = Unit

    override suspend fun sendElicitationComplete(notification: ElicitationCompleteNotification) = Unit
}
