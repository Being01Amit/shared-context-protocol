package com.scp.server

import com.scp.model.ContextType
import com.scp.model.ScpException
import com.scp.model.mcp.CreateProjectInput
import com.scp.model.mcp.HydrateContextInput
import com.scp.model.mcp.McpValidations
import com.scp.model.mcp.ProjectSummaryInput
import com.scp.model.mcp.SaveNoteInput
import com.scp.model.mcp.SearchContextInput
import com.scp.model.mcp.TimelineInput
import com.scp.model.mcp.UpdateContextInput
import com.scp.model.mcp.checkValid
import io.github.oshai.kotlinlogging.KotlinLogging
import io.konform.validation.Validation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private val logger = KotlinLogging.logger("com.scp.server.McpTools")

internal val json: Json =
    Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

/**
 * The MCP boundary pipeline for every tool: deserialize (shape) -> Konform (constraints)
 * -> skill -> serialize. Domain failures become isError results with a readable message,
 * never a protocol-level crash.
 */
private inline fun <reified I, reified R> callTool(
    arguments: JsonObject?,
    validation: Validation<I>,
    block: (I) -> R,
): CallToolResult =
    try {
        val input = json.decodeFromJsonElement<I>(arguments ?: JsonObject(emptyMap()))
        validation.checkValid(input)
        CallToolResult(content = listOf(TextContent(json.encodeToString(block(input)))))
    } catch (e: SerializationException) {
        CallToolResult(content = listOf(TextContent("Invalid arguments: ${e.message}")), isError = true)
    } catch (e: ScpException) {
        CallToolResult(content = listOf(TextContent(e.message ?: "request failed")), isError = true)
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        logger.error(e) { "tool call failed unexpectedly" }
        CallToolResult(content = listOf(TextContent("Internal error: ${e.message}")), isError = true)
    }

private val contextTypeEnum = JsonArray(ContextType.entries.map { JsonPrimitive(it.name) })

private fun JsonObjectBuilder.prop(name: String, type: String, description: String) {
    putJsonObject(name) {
        put("type", type)
        put("description", description)
    }
}

private fun JsonObjectBuilder.arrayProp(name: String, description: String, items: JsonObject) {
    putJsonObject(name) {
        put("type", "array")
        put("description", description)
        put("items", items)
    }
}

private fun stringItems(): JsonObject = buildJsonObject { put("type", "string") }

private fun entryItemSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            prop("title", "string", "Short title for the context entry")
            prop("content", "string", "Full entry content (code, output, decisions, notes)")
            putJsonObject("type") {
                put("type", "string")
                put("enum", contextTypeEnum)
                put("description", "Entry category")
            }
            arrayProp("tags", "Lowercase tags for later retrieval", stringItems())
            prop("priority", "integer", "1 (low) to 5 (critical), default 3")
            prop("timestamp", "string", "ISO 8601 UTC; defaults to now")
        }
        put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("content"), JsonPrimitive("type"))))
    }

private fun decisionItemSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            prop("title", "string", "Decision title")
            prop("decision", "string", "What was decided")
            prop("reason", "string", "Why")
        }
        put("required", JsonArray(listOf(JsonPrimitive("title"), JsonPrimitive("decision"))))
    }

private fun todoItemSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            prop("description", "string", "What needs to be done")
            prop("owner", "string", "Tool or human responsible (optional)")
        }
        put("required", JsonArray(listOf(JsonPrimitive("description"))))
    }

private fun fileItemSchema(): JsonObject =
    buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            prop("path", "string", "Project-relative path with / separators")
            prop("summary", "string", "What this file does / what changed")
            prop("hash", "string", "Content hash (optional)")
        }
        put("required", JsonArray(listOf(JsonPrimitive("path"))))
    }

@Suppress("LongMethod")
internal fun Server.registerScpTools(components: AppComponents) {
    addTool(
        name = "create_project",
        description = "Register a new SCP project so AI tools can store shared context against it.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        prop("name", "string", "Unique project name")
                        prop("description", "string", "What this project is about")
                    },
                required = listOf("name"),
            ),
    ) { request ->
        callTool<CreateProjectInput, _>(request.arguments, McpValidations.createProject) {
            components.createProject.execute(it)
        }
    }

    addTool(
        name = "update_context",
        description =
            "Store the current session's context into SCP: entries, decisions, todos, file summaries. " +
                "Closes the session unless keepOpen=true. Secrets are redacted before storage.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        prop("projectName", "string", "Target project")
                        prop("toolName", "string", "Calling tool identity, e.g. 'claude-code', 'antigravity'")
                        prop("sessionId", "string", "Explicit session UUID (optional; otherwise resolved automatically)")
                        prop("summary", "string", "One-paragraph session summary")
                        prop("keepOpen", "boolean", "Keep the session open after this update (default false)")
                        prop("tokenUsage", "integer", "Tokens consumed this session, if known")
                        arrayProp("entries", "Context entries captured this session", entryItemSchema())
                        arrayProp("decisions", "Architecture/technology decisions made", decisionItemSchema())
                        arrayProp("todos", "Open work items", todoItemSchema())
                        arrayProp("files", "Files touched this session", fileItemSchema())
                    },
                required = listOf("projectName", "toolName"),
            ),
    ) { request ->
        callTool<UpdateContextInput, _>(request.arguments, McpValidations.updateContext) {
            components.updateContext.execute(it)
        }
    }

    addTool(
        name = "hydrate_context",
        description =
            "Resume a project: returns a ranked, token-budgeted payload (summary, recent sessions, open " +
                "decisions/todos/bugs, relevant prompts, recent files, priorities). Truncation is always signaled.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        prop("projectName", "string", "Project to hydrate")
                        arrayProp("tags", "Keywords/tags for relevance ranking", stringItems())
                        prop("tokenLimit", "integer", "Override the configured hydration token budget")
                    },
                required = listOf("projectName"),
            ),
    ) { request ->
        callTool<HydrateContextInput, _>(request.arguments, McpValidations.hydrateContext) {
            components.hydrateContext.execute(it)
        }
    }

    addTool(
        name = "search_context",
        description = "Full-text search over stored context, composable with project/type/tag/date filters.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        prop("query", "string", "Search keywords")
                        prop("projectName", "string", "Restrict to one project (optional)")
                        putJsonObject("type") {
                            put("type", "string")
                            put("enum", contextTypeEnum)
                            put("description", "Restrict to one entry type (optional)")
                        }
                        prop("tag", "string", "Restrict to entries carrying this tag (optional)")
                        prop("from", "string", "ISO 8601 UTC lower bound (optional)")
                        prop("to", "string", "ISO 8601 UTC upper bound (optional)")
                        prop("limit", "integer", "Max results (default from config)")
                    },
                required = listOf("query"),
            ),
    ) { request ->
        callTool<SearchContextInput, _>(request.arguments, McpValidations.searchContext) {
            components.searchContext.execute(it)
        }
    }

    addTool(
        name = "project_summary",
        description = "Project statistics, entry counts by type, major decisions, open todos, recent sessions.",
        inputSchema =
            ToolSchema(
                properties = buildJsonObject { prop("projectName", "string", "Project to summarize") },
                required = listOf("projectName"),
            ),
    ) { request ->
        callTool<ProjectSummaryInput, _>(request.arguments, McpValidations.projectSummary) {
            components.summarizeContext.execute(it)
        }
    }

    addTool(
        name = "timeline",
        description = "Full chronological session history — the escape hatch for anything hydration truncated.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        prop("projectName", "string", "Project")
                        prop("limit", "integer", "Max sessions, oldest first (optional)")
                    },
                required = listOf("projectName"),
            ),
    ) { request ->
        callTool<TimelineInput, _>(request.arguments, McpValidations.timeline) {
            components.timeline.execute(it)
        }
    }

    addTool(
        name = "list_projects",
        description = "List all SCP projects with last-updated time and session counts.",
        inputSchema = ToolSchema(properties = buildJsonObject {}, required = emptyList()),
    ) { _ ->
        try {
            CallToolResult(content = listOf(TextContent(json.encodeToString(components.listProjects.execute()))))
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            CallToolResult(content = listOf(TextContent("Internal error: ${e.message}")), isError = true)
        }
    }

    addTool(
        name = "save_note",
        description = "Quickly save one context entry mid-session without closing the session.",
        inputSchema =
            ToolSchema(
                properties =
                    buildJsonObject {
                        prop("projectName", "string", "Target project")
                        prop("toolName", "string", "Calling tool identity")
                        prop("title", "string", "Note title")
                        prop("content", "string", "Note content")
                        putJsonObject("type") {
                            put("type", "string")
                            put("enum", contextTypeEnum)
                            put("description", "Entry category (default LEARNING)")
                        }
                        arrayProp("tags", "Tags for retrieval", stringItems())
                        prop("priority", "integer", "1-5, default 3")
                    },
                required = listOf("projectName", "toolName", "title", "content"),
            ),
    ) { request ->
        callTool<SaveNoteInput, _>(request.arguments, McpValidations.saveNote) {
            components.saveNote.execute(it)
        }
    }
}
