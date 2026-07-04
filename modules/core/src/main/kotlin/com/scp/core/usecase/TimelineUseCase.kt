package com.scp.core.usecase

import com.scp.core.toBrief
import com.scp.model.NotFoundException
import com.scp.model.mcp.TimelineInput
import com.scp.model.mcp.TimelineResult
import com.scp.model.mcp.TimelineSession
import com.scp.model.port.ContextEntryRepository
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionRepository

/** The escape hatch: full chronological history for anything hydration truncated. */
public class TimelineUseCase(
    private val projects: ProjectRepository,
    private val sessions: SessionRepository,
    private val entries: ContextEntryRepository,
) {
    public fun execute(input: TimelineInput): TimelineResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")
        val all = sessions.listChronological(project.id)
        val limited = input.limit?.let { all.take(it.toInt()) } ?: all
        val timeline =
            limited.map { session ->
                TimelineSession(
                    session = session.toBrief(),
                    entries = entries.findBySession(session.id).map { it.toBrief() },
                )
            }
        return TimelineResult(
            projectName = project.name,
            sessions = timeline,
            totalSessions = all.size.toLong(),
            truncated = limited.size < all.size,
        )
    }
}
