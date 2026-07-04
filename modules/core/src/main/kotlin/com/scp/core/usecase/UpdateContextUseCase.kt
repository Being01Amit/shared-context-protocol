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

                val newDecisions =
                    input.decisions.map { d ->
                        Decision(
                            id = ids.newId(),
                            projectId = project.id,
                            title = redact(d.title),
                            decision = redact(d.decision),
                            reason = redact(d.reason),
                            createdAt = now,
                            updatedAt = now,
                        )
                    }
                val newTodos =
                    input.todos.map { t ->
                        Todo(
                            id = ids.newId(),
                            projectId = project.id,
                            description = redact(t.description),
                            owner = t.owner,
                            createdAt = now,
                        )
                    }
                val newFiles =
                    input.files.map { f ->
                        TrackedFile(
                            id = ids.newId(),
                            projectId = project.id,
                            path = f.path,
                            summary = redact(f.summary),
                            hash = f.hash,
                            updatedAt = now,
                        )
                    }

                input.entries.forEach { e ->
                    entries.insert(
                        ContextEntry(
                            id = ids.newId(),
                            sessionId = session.id,
                            timestamp = e.timestamp ?: now,
                            title = redact(e.title),
                            content = redact(e.content),
                            type = e.type,
                            tags = e.tags,
                            priority = e.priority,
                        ),
                    )
                }
                newDecisions.forEach(decisions::insert)
                newTodos.forEach(todos::insert)
                newFiles.forEach(files::upsert)

                val close = !input.keepOpen
                if (close) {
                    val summary = input.summary.takeIf { it.isNotBlank() }?.let(::redact)
                    sessions.close(session.id, now, summary, input.tokenUsage)
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
        val markdownPath =
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
