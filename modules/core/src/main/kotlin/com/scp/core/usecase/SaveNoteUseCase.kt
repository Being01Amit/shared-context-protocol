package com.scp.core.usecase

import com.scp.core.Redaction
import com.scp.core.RedactionPattern
import com.scp.core.SessionResolver
import com.scp.model.ContextEntry
import com.scp.model.NotFoundException
import com.scp.model.mcp.SaveNoteInput
import com.scp.model.mcp.SaveNoteResult
import com.scp.model.port.Clock
import com.scp.model.port.ContextEntryRepository
import com.scp.model.port.IdGenerator
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionRepository
import com.scp.model.port.TransactionRunner

/**
 * Lightweight mid-session capture: one redacted entry into the tool's current (or a new)
 * session, without closing it. The markdown mirror is written when update_context closes
 * the session.
 */
public class SaveNoteUseCase(
    private val projects: ProjectRepository,
    private val sessions: SessionRepository,
    private val entries: ContextEntryRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
    private val ids: IdGenerator,
    private val redactionPatterns: List<RedactionPattern> = Redaction.defaultPatterns,
) {
    private val resolver = SessionResolver(sessions, clock, ids)

    public fun execute(input: SaveNoteInput): SaveNoteResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")
        return transactions.inWriteTransaction {
            val resolution = resolver.resolve(project.id, input.toolName, explicitSessionId = null)
            val now = clock.now()
            val entry =
                ContextEntry(
                    id = ids.newId(),
                    sessionId = resolution.session.id,
                    timestamp = now,
                    title = Redaction.redact(input.title, redactionPatterns),
                    content = Redaction.redact(input.content, redactionPatterns),
                    type = input.type,
                    tags = input.tags,
                    priority = input.priority,
                )
            entries.insert(entry)
            projects.touch(project.id, now)
            SaveNoteResult(
                entryId = entry.id,
                sessionId = resolution.session.id,
                sessionWasCreated = resolution.created,
            )
        }
    }
}
