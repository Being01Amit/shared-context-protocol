package com.scp.cli

import com.scp.config.ConfigLoader
import com.scp.config.ScpConfig
import com.scp.config.SecureFiles
import com.scp.core.Redaction
import com.scp.core.usecase.CreateProjectUseCase
import com.scp.core.usecase.HydrateContextUseCase
import com.scp.core.usecase.ListProjectsUseCase
import com.scp.core.usecase.ProjectSummaryUseCase
import com.scp.core.usecase.SaveNoteUseCase
import com.scp.core.usecase.SearchContextUseCase
import com.scp.core.usecase.TimelineUseCase
import com.scp.core.usecase.UpdateContextUseCase
import com.scp.database.adapter.DatabaseHandle
import com.scp.database.adapter.DriverFactory
import com.scp.database.adapter.SqlContextEntryRepository
import com.scp.database.adapter.SqlDecisionRepository
import com.scp.database.adapter.SqlFileRepository
import com.scp.database.adapter.SqlProjectRepository
import com.scp.database.adapter.SqlSessionRepository
import com.scp.database.adapter.SqlTodoRepository
import com.scp.database.adapter.SqliteTransactionRunner
import com.scp.markdown.FileMarkdownStore
import com.scp.markdown.NoOpMarkdownStore
import com.scp.model.port.Clock
import com.scp.model.port.IdGenerator
import com.scp.model.port.MarkdownStore
import com.scp.search.SqlSearchIndex
import com.scp.skills.CreateProject
import com.scp.skills.HydrateContext
import com.scp.skills.ListProjects
import com.scp.skills.SaveNote
import com.scp.skills.SearchContext
import com.scp.skills.SummarizeContext
import com.scp.skills.Timeline
import com.scp.skills.UpdateContext
import java.nio.file.Path
import java.util.UUID

/** CLI composition root (ADR-12) — mirror of the server's, kept per-app on purpose. */
internal class CliComponents private constructor(
    val config: ScpConfig,
    val handle: DatabaseHandle,
    val dbPath: Path,
    val searchIndex: SqlSearchIndex,
    val updateContext: UpdateContext,
    val hydrateContext: HydrateContext,
    val searchContext: SearchContext,
    val summarizeContext: SummarizeContext,
    val timeline: Timeline,
    val listProjects: ListProjects,
    val saveNote: SaveNote,
    val createProject: CreateProject,
) : AutoCloseable {
    override fun close() {
        handle.close()
    }

    companion object {
        fun build(baseDir: Path): CliComponents {
            val config = ConfigLoader.load(baseDir.resolve("config.yaml"))
            SecureFiles.prepareStorage(baseDir, config)
            val dbKey = System.getenv("SCP_DB_KEY")?.takeIf { it.isNotBlank() }
            val dbPath = baseDir.resolve(config.databasePath)
            val handle = DriverFactory.open(dbPath, dbKey)
            SecureFiles.restrict(dbPath)
            val db = handle.database

            val projects = SqlProjectRepository(db)
            val sessions = SqlSessionRepository(db)
            val entries = SqlContextEntryRepository(db)
            val decisions = SqlDecisionRepository(db)
            val todos = SqlTodoRepository(db)
            val files = SqlFileRepository(db)
            val transactions = SqliteTransactionRunner(db, dbPath)
            // Encryption on -> no plaintext markdown mirror (it would leak what the DB encrypts).
            val markdown: MarkdownStore =
                if (dbKey != null) NoOpMarkdownStore() else FileMarkdownStore(baseDir.resolve(config.markdownPath))
            val searchIndex = SqlSearchIndex(db, handle.driver)
            val clock =
                Clock {
                    kotlinx.datetime.Clock.System
                        .now()
                }
            val ids = IdGenerator { UUID.randomUUID().toString() }
            val redaction = Redaction.compile(config.secretRedactionPatterns)
            val weights = config.hydrationRankingWeights

            return CliComponents(
                config = config,
                handle = handle,
                dbPath = dbPath,
                searchIndex = searchIndex,
                updateContext =
                    UpdateContext(
                        UpdateContextUseCase(
                            projects,
                            sessions,
                            entries,
                            decisions,
                            todos,
                            files,
                            markdown,
                            transactions,
                            clock,
                            ids,
                            redaction,
                        ),
                    ),
                hydrateContext =
                    HydrateContext(
                        HydrateContextUseCase(
                            projects,
                            sessions,
                            entries,
                            decisions,
                            todos,
                            files,
                            clock,
                            weights,
                            config.hydrationTokenLimit,
                        ),
                    ),
                searchContext =
                    SearchContext(
                        SearchContextUseCase(searchIndex, projects, clock, weights, config.searchLimit.toLong()),
                    ),
                summarizeContext =
                    SummarizeContext(ProjectSummaryUseCase(projects, sessions, entries, decisions, todos)),
                timeline = Timeline(TimelineUseCase(projects, sessions, entries)),
                listProjects = ListProjects(ListProjectsUseCase(projects, sessions)),
                saveNote = SaveNote(SaveNoteUseCase(projects, sessions, entries, transactions, clock, ids, redaction)),
                createProject = CreateProject(CreateProjectUseCase(projects, transactions, clock, ids)),
            )
        }
    }
}
