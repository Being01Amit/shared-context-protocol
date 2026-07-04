package com.scp.core

import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Session
import com.scp.model.SessionStatus
import com.scp.model.port.Clock
import com.scp.model.port.IdGenerator
import com.scp.model.port.SessionRepository

/**
 * Session resolution policy (docs/04):
 *  1. Explicit wins — a supplied session_id is validated and used, even if closed.
 *  2. Reuse only your own — a single open session is reused only when tool_name matches (ADR-16).
 *  3. Otherwise create — 0 open, >=2 open, or a different tool's session: never merge, never guess.
 *
 * Must be invoked inside TransactionRunner.inWriteTransaction so check-then-act is atomic
 * (BEGIN IMMEDIATE holds the write lock across the SELECT and the INSERT).
 */
public class SessionResolver(
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) {
    public data class Resolution(val session: Session, val created: Boolean)

    public fun resolve(projectId: String, toolName: String, explicitSessionId: String?): Resolution {
        if (explicitSessionId != null) {
            val session =
                sessions.findById(explicitSessionId)
                    ?: throw NotFoundException("Session '$explicitSessionId' not found")
            if (session.projectId != projectId) {
                throw InvalidInputException("Session '$explicitSessionId' belongs to a different project")
            }
            return Resolution(session, created = false)
        }

        val open = sessions.findOpenByProject(projectId)
        val reusable = open.singleOrNull()?.takeIf { it.toolName == toolName }
        if (reusable != null) return Resolution(reusable, created = false)

        val created =
            Session(
                id = idGenerator.newId(),
                projectId = projectId,
                toolName = toolName,
                startTime = clock.now(),
                status = SessionStatus.OPEN,
            )
        sessions.insert(created)
        return Resolution(created, created = true)
    }
}
