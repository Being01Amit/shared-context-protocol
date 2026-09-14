package com.scp.core

import com.scp.model.InvalidInputException
import com.scp.model.NotFoundException
import com.scp.model.Session
import com.scp.model.SessionStatus
import com.scp.model.port.Clock
import com.scp.model.port.GitState
import com.scp.model.port.IdGenerator
import com.scp.model.port.SessionRepository

/**
 * Session resolution policy (docs/04):
 *  1. Explicit wins — a supplied session_id is validated and used, even if closed, but only by
 *     the tool that owns it: an explicit id is not a way around rule 3.
 *  2. Reuse only your own — a single open session is reused only when tool_name matches (ADR-16).
 *  3. Otherwise create — 0 open, >=2 open, or a different tool's session: never merge, never guess.
 *
 * Must be invoked inside TransactionRunner.inWriteTransaction so check-then-act is atomic
 * (BEGIN IMMEDIATE holds the write lock across the SELECT and the INSERT). [GitState] is passed
 * in rather than read here: reading it shells out to `git`, and doing that inside the transaction
 * would hold the database write lock for as long as the subprocess takes.
 */
public class SessionResolver(
    private val sessions: SessionRepository,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
) {
    public data class Resolution(val session: Session, val created: Boolean)

    public fun resolve(
        projectId: String,
        toolName: String,
        explicitSessionId: String?,
        gitState: GitState?,
    ): Resolution {
        if (explicitSessionId != null) {
            return Resolution(explicitSession(projectId, toolName, explicitSessionId), created = false)
        }

        val open = sessions.findOpenByProject(projectId)
        val reusable = open.filter { it.toolName == toolName }.singleOrNull()
        if (reusable != null) return Resolution(reusable, created = false)

        val created =
            Session(
                id = idGenerator.newId(),
                projectId = projectId,
                toolName = toolName,
                startTime = clock.now(),
                status = SessionStatus.OPEN,
                gitBranch = gitState?.branch,
                gitCommit = gitState?.commit,
            )
        sessions.insert(created)
        return Resolution(created, created = true)
    }

    private fun explicitSession(projectId: String, toolName: String, sessionId: String): Session {
        val session =
            sessions.findById(sessionId)
                ?: throw NotFoundException("Session '$sessionId' not found")
        val problem =
            when {
                session.projectId != projectId -> "Session '$sessionId' belongs to a different project"
                session.toolName != toolName ->
                    "Session '$sessionId' belongs to tool '${session.toolName}', not '$toolName' — " +
                        "sessions are never shared between tools; omit sessionId to get your own"
                else -> null
            }
        if (problem != null) throw InvalidInputException(problem)
        return session
    }
}
