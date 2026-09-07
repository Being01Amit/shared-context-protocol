package com.scp.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.CliktError
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.scp.config.SecureFiles
import com.scp.model.ContextTrustNotice
import com.scp.model.ContextType
import com.scp.model.ScpException
import com.scp.model.mcp.CreateProjectInput
import com.scp.model.mcp.HydrateContextInput
import com.scp.model.mcp.ProjectSummaryInput
import com.scp.model.mcp.SearchContextInput
import com.scp.model.mcp.TimelineInput
import com.scp.model.mcp.UpdateContextInput
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path

internal val cliJson: Json =
    Json {
        prettyPrint = true
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        // --json reads a hand- or agent-written UpdateContextInput through the same DTOs the MCP
        // boundary uses, so it needs the same tolerance: an explicit null for an optional field
        // must fall back to the default instead of rejecting the whole payload.
        coerceInputValues = true
    }

internal fun baseDir(): Path =
    Path
        .of(System.getProperty("scp.home") ?: System.getProperty("user.dir"))
        .toAbsolutePath()
        .normalize()

/** Shared behavior: open components, run, translate domain errors to clean CLI errors. */
internal abstract class ScpCommand(name: String) : CliktCommand(name = name) {
    final override fun run() {
        try {
            CliComponents.build(baseDir()).use { components -> run(components) }
        } catch (e: ScpException) {
            throw CliktError(e.message ?: "command failed", cause = e)
        } catch (e: com.scp.config.ConfigException) {
            throw CliktError(e.message ?: "invalid configuration", cause = e)
        }
    }

    abstract fun run(components: CliComponents)
}

internal class InitCommand : CliktCommand(name = "init") {
    override fun run() {
        val base = baseDir()
        SecureFiles.secureDirectory(base.resolve("storage"))
        // storage/projects IS the markdown root (config.markdownPath); there is no separate
        // storage/markdown. SecureFiles.prepareStorage creates the configured paths on every
        // start, so this list only covers what must exist before a config is even loaded.
        listOf("storage/projects", "storage/database", "storage/logs").forEach {
            SecureFiles.secureDirectory(base.resolve(it))
        }
        val configFile = base.resolve("config.yaml")
        if (!Files.exists(configFile)) {
            Files.writeString(configFile, DEFAULT_CONFIG_YAML)
            echo("Wrote default config.yaml")
        }
        // Opening the database applies PRAGMAs and creates the schema (encrypted when SCP_DB_KEY is set).
        try {
            CliComponents.build(base).use { components ->
                echo("Database ready at ${components.dbPath}")
            }
        } catch (e: ScpException) {
            throw CliktError(e.message ?: "initialization failed", cause = e)
        } catch (e: com.scp.config.ConfigException) {
            throw CliktError(e.message ?: "invalid configuration", cause = e)
        }
        echo("SCP initialized under $base")
    }
}

internal class CreateProjectCommand : ScpCommand("create-project") {
    private val name by option("--name", help = "Unique project name").required()
    private val description by option("--description", help = "What the project is about").default("")

    override fun run(components: CliComponents) {
        val result = components.createProject.execute(CreateProjectInput(name, description))
        echo("Created project '${result.name}' (${result.projectId})")
    }
}

internal class ListProjectsCommand : ScpCommand("list-projects") {
    override fun run(components: CliComponents) {
        val result = components.listProjects.execute()
        if (result.projects.isEmpty()) {
            echo("No projects yet — create one with: scp create-project --name <name>")
            return
        }
        result.projects.forEach {
            echo("${it.name}  sessions=${it.sessionCount}  updated=${it.updatedAt}  — ${it.description.take(DESCRIPTION_PREVIEW)}")
        }
    }

    private companion object {
        const val DESCRIPTION_PREVIEW = 80
    }
}

internal class UpdateCommand : ScpCommand("update") {
    private val project by option("--project", help = "Project name").required()
    private val tool by option("--tool", help = "Tool identity").default("cli")
    private val summary by option("--summary", help = "Session summary: what changed").default("")
    private val nextStep by option("--next-step", help = "Where the next agent should start").default("")
    private val keepOpen by option("--keep-open", help = "Do not close the session").flag()
    private val sessionId by option("--session-id", help = "Explicit session UUID")
    private val jsonFile by option("--json", help = "Path to a full UpdateContextInput JSON payload")

    override fun run(components: CliComponents) {
        val input =
            if (jsonFile != null) {
                cliJson.decodeFromString<UpdateContextInput>(Files.readString(Path.of(jsonFile!!)))
            } else {
                UpdateContextInput(
                    projectName = project,
                    toolName = tool,
                    sessionId = sessionId,
                    summary = summary,
                    nextStep = nextStep,
                    keepOpen = keepOpen,
                )
            }
        val result = components.updateContext.execute(input)
        echo(cliJson.encodeToString(result))
    }
}

internal class HydrateCommand : ScpCommand("hydrate") {
    private val project by option("--project", help = "Project name").required()
    private val tags by option("--tag", help = "Relevance tag (repeatable)").multiple()
    private val tokenLimit by option("--token-limit", help = "Override hydration token budget").int()

    override fun run(components: CliComponents) {
        val payload = components.hydrateContext.execute(HydrateContextInput(project, tags, tokenLimit))
        echo(cliJson.encodeToString(payload))
    }
}

internal class SearchCommand : ScpCommand("search") {
    private val query by argument(help = "Full-text query")
    private val project by option("--project", help = "Restrict to project")
    private val type by option("--type", help = "Restrict to entry type (e.g. BUG, DECISION)")
    private val tag by option("--tag", help = "Restrict to tag")
    private val from by option("--from", help = "ISO 8601 UTC lower bound")
    private val to by option("--to", help = "ISO 8601 UTC upper bound")
    private val limit by option("--limit", help = "Max results").long()

    override fun run(components: CliComponents) {
        val input =
            SearchContextInput(
                query = query,
                projectName = project,
                type = type?.let { raw -> ContextType.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } },
                tag = tag,
                from = from?.let(Instant::parse),
                to = to?.let(Instant::parse),
                limit = limit,
            )
        val result = components.searchContext.execute(input)
        if (result.items.isEmpty()) {
            echo("No matches.")
            return
        }
        echo(ContextTrustNotice.TEXT)
        result.items.forEach {
            echo(
                "[%.3f] %s  (%s, %s) #%s".format(
                    it.score,
                    it.entry.title,
                    it.entry.type,
                    it.projectName,
                    it.entry.id.take(ID_PREVIEW),
                ),
            )
        }
    }

    private companion object {
        const val ID_PREVIEW = 8
    }
}

internal class SummaryCommand : ScpCommand("summary") {
    private val project by option("--project", help = "Project name").required()

    override fun run(components: CliComponents) {
        echo(cliJson.encodeToString(components.summarizeContext.execute(ProjectSummaryInput(project))))
    }
}

internal class TimelineCommand : ScpCommand("timeline") {
    private val project by option("--project", help = "Project name").required()
    private val limit by option("--limit", help = "Max sessions, oldest first").long()

    override fun run(components: CliComponents) {
        echo(cliJson.encodeToString(components.timeline.execute(TimelineInput(project, limit))))
    }
}

private val DEFAULT_CONFIG_YAML: String =
    """
    # SCP configuration. All keys optional; shown values are the defaults.
    databasePath: storage/database/scp.db
    markdownPath: storage/projects   # {project}/{ts}-{tool}-{id8}.md + PROJECT.md + LATEST.md
    autoSaveIntervalSeconds: 300
    hydrationTokenLimit: 12000
    hydrationRankingWeights:
      recency: 0.35
      priority: 0.25
      tagOverlap: 0.25
      type: 0.15
      recencyHalfLifeDays: 7
      # typeMultipliers:          # per-type overrides, e.g.
      #   MEETING: 0.2
    searchLimit: 50
    logLevel: INFO
    # secretRedactionPatterns:    # extra regexes, ADDED to the built-ins
    #   - '\bACME-[0-9]{6}\b'
    """.trimIndent() + "\n"
