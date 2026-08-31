package com.scp.core.usecase

import com.scp.core.Redaction
import com.scp.core.RedactionPattern
import com.scp.core.SessionResolver
import com.scp.model.ContextEntry
import com.scp.model.Decision
import com.scp.model.NotFoundException
import com.scp.model.Session
import com.scp.model.Todo
import com.scp.model.TrackedFile
import com.scp.model.mcp.UpdateContextInput
import com.scp.model.mcp.UpdateContextResult
import com.scp.model.port.Clock
import com.scp.model.port.ContextEntryRepository
import com.scp.model.port.DecisionRepository
import com.scp.model.port.FileRepository
import com.scp.model.port.IdGenerator
import com.scp.model.port.MarkdownStore
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionMarkdown
import com.scp.model.port.SessionRepository
import com.scp.model.port.TodoRepository
import com.scp.model.port.TransactionRunner
import io.github.oshai.kotlinlogging.KotlinLogging

private val logger = KotlinLogging.logger("com.scp.core.usecase.UpdateContextUseCase")

/**
 * The write path (docs/01 §5): resolve session -> redact -> persist atomically ->
 * mirror to markdown -> close the session unless keep_open. Redaction happens here,
 * before any repository call — the database never sees a raw secret.
 */
public class UpdateContextUseCase(
    private val projects: ProjectRepository,
    private val sessions: SessionRepository,
    private val entries: ContextEntryRepository,
    private val decisions: DecisionRepository,
    private val todos: TodoRepository,
    private val files: FileRepository,
    private val markdown: MarkdownStore,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val redactionPatterns: List<RedactionPattern> = Redaction.defaultPatterns,
) {
    private val resolver = SessionResolver(sessions, clock, ids)

    public fun execute(input: UpdateContextInput): UpdateContextResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found — create it with create_project")

        val outcome =
            transactions.inWriteTransaction {
                val resolution = resolver.resolve(project.id, input.toolName, input.sessionId)
                val session = resolution.session
                val now = clock.now()

                val newDecisions = input.decisions.map { it.toRedactedDecision(project.id, now) }
                val newTodos = input.todos.map { it.toRedactedTodo(project.id, now) }
                val newFiles = input.files.map { it.toRedactedFile(project.id, now) }

                input.entries.forEach { entries.insert(it.toRedactedEntry(session.id, now)) }
                newDecisions.forEach(decisions::insert)
                newTodos.forEach(todos::insert)
                newFiles.forEach(files::upsert)

                val close = !input.keepOpen
                if (close) {
                    val summary = input.summary.takeIf { it.isNotBlank() }?.let(::redact)
                    val nextStep = input.nextStep.takeIf { it.isNotBlank() }?.let(::redact)
                    sessions.close(session.id, now, summary, input.tokenUsage, nextStep)
                }
                projects.touch(project.id, now)

                TxOutcome(
                    session = checkNotNull(sessions.findById(session.id)) { "session vanished mid-transaction" },
                    sessionWasCreated = resolution.created,
                    sessionClosed = close,
                    sessionEntries = entries.findBySession(session.id),
                    newDecisions = newDecisions,
                    newTodos = newTodos,
                    newFiles = newFiles,
                )
            }

        // File I/O happens outside the write transaction — never hold the DB write lock
        // for markdown generation.
        //
        // The transaction has already COMMITTED by this point, so a mirror failure must not be
        // reported as a failed update: the caller would conclude nothing was saved and retry,
        // and because the session was just closed the retry opens a new one and re-inserts every
        // entry. The mirror is a convenience view; the database is the source of truth. An empty
        // path is the same "no mirror produced" signal NoOpMarkdownStore returns.
        val markdownPath =
            try {
                markdown.write(
                    SessionMarkdown(
                        project = project,
                        session = outcome.session,
                        entries = outcome.sessionEntries,
                        decisions = outcome.newDecisions,
                        todos = outcome.newTodos,
                        files = outcome.newFiles,
                    ),
                )
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                logger.warn(e) {
                    "markdown mirror failed for session ${outcome.session.id} — context IS persisted in the database"
                }
                ""
            }

        return UpdateContextResult(
            sessionId = outcome.session.id,
            sessionWasCreated = outcome.sessionWasCreated,
            sessionClosed = outcome.sessionClosed,
            entriesWritten = input.entries.size,
            decisionsWritten = outcome.newDecisions.size,
            todosWritten = outcome.newTodos.size,
            filesUpserted = outcome.newFiles.size,
            markdownPath = markdownPath,
        )
    }

    private fun redact(text: String): String = Redaction.redact(text, redactionPatterns)

    private fun com.scp.model.mcp.NewEntry.toRedactedEntry(sessionId: String, now: kotlinx.datetime.Instant): ContextEntry =
        ContextEntry(
            id = ids.newId(),
            sessionId = sessionId,
            timestamp = timestamp ?: now,
            title = redact(title),
            content = redact(content),
            type = type,
            tags = tags,
            priority = priority,
        )

    private fun com.scp.model.mcp.NewDecision.toRedactedDecision(projectId: String, now: kotlinx.datetime.Instant): Decision =
        Decision(
            id = ids.newId(),
            projectId = projectId,
            title = redact(title),
            decision = redact(decision),
            reason = redact(reason),
            createdAt = now,
            updatedAt = now,
        )

    private fun com.scp.model.mcp.NewTodo.toRedactedTodo(projectId: String, now: kotlinx.datetime.Instant): Todo =
        Todo(
            id = ids.newId(),
            projectId = projectId,
            description = redact(description),
            owner = owner,
            createdAt = now,
        )

    private fun com.scp.model.mcp.FileUpdate.toRedactedFile(projectId: String, now: kotlinx.datetime.Instant): TrackedFile =
        TrackedFile(
            id = ids.newId(),
            projectId = projectId,
            path = path,
            summary = redact(summary),
            hash = hash,
            updatedAt = now,
        )

    private data class TxOutcome(
        val session: Session,
        val sessionWasCreated: Boolean,
        val sessionClosed: Boolean,
        val sessionEntries: List<ContextEntry>,
        val newDecisions: List<Decision>,
        val newTodos: List<Todo>,
        val newFiles: List<TrackedFile>,
    )
}
