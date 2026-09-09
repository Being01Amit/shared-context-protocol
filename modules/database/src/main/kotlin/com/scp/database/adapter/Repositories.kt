package com.scp.database.adapter

import com.scp.database.ScpDatabase
import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.Decision
import com.scp.model.DecisionStatus
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.Todo
import com.scp.model.TodoStatus
import com.scp.model.TrackedFile
import com.scp.model.VectorUtils
import com.scp.model.port.ContextEntryRepository
import com.scp.model.port.DecisionRepository
import com.scp.model.port.FileRepository
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionRepository
import com.scp.model.port.TodoRepository
import kotlinx.datetime.Instant

public class SqlProjectRepository(private val db: ScpDatabase) : ProjectRepository {
    override fun insert(project: Project) {
        db.projectQueries.insertProject(
            id = project.id,
            name = project.name,
            description = project.description,
            createdAt = project.createdAt.toString(),
            updatedAt = project.updatedAt.toString(),
        )
    }

    override fun findById(id: String): Project? =
        db.projectQueries
            .findById(id)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun findByName(name: String): Project? =
        db.projectQueries
            .findByName(name)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun listAll(): List<Project> =
        db.projectQueries
            .listAll()
            .executeAsList()
            .map { it.toDomain() }

    override fun updateDescription(id: String, description: String, updatedAt: Instant) {
        db.projectQueries.updateDescription(description = description, updatedAt = updatedAt.toString(), id = id)
    }

    override fun touch(id: String, updatedAt: Instant) {
        db.projectQueries.touch(updatedAt = updatedAt.toString(), id = id)
    }
}

public class SqlSessionRepository(private val db: ScpDatabase) : SessionRepository {
    override fun insert(session: Session) {
        db.sessionQueries.insertSession(
            id = session.id,
            projectId = session.projectId,
            toolName = session.toolName,
            startTime = session.startTime.toString(),
            endTime = session.endTime?.toString(),
            summary = session.summary,
            tokenUsage = session.tokenUsage,
            status = session.status.dbValue,
            nextStep = session.nextStep,
            startTimeEpochNanos = session.startTime.toEpochNanos(),
            gitBranch = session.gitBranch,
            gitCommit = session.gitCommit,
        )
    }

    override fun findById(id: String): Session? =
        db.sessionQueries
            .findById(id)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun findOpenByProject(projectId: String): List<Session> =
        db.sessionQueries
            .findOpenByProject(projectId)
            .executeAsList()
            .map { it.toDomain() }

    override fun findLatest(projectId: String): Session? =
        db.sessionQueries
            .findLatest(projectId)
            .executeAsOneOrNull()
            ?.toDomain()

    override fun close(id: String, endTime: Instant, summary: String?, tokenUsage: Long?, nextStep: String?) {
        db.sessionQueries.closeSession(
            endTime = endTime.toString(),
            summary = summary,
            tokenUsage = tokenUsage,
            nextStep = nextStep,
            id = id,
        )
    }

    override fun listRecent(projectId: String, limit: Long): List<Session> =
        db.sessionQueries
            .listRecent(projectId, limit)
            .executeAsList()
            .map { it.toDomain() }

    override fun listChronological(projectId: String): List<Session> =
        db.sessionQueries
            .listChronological(projectId)
            .executeAsList()
            .map { it.toDomain() }

    override fun countByProject(projectId: String): Long = db.sessionQueries.countByProject(projectId).executeAsOne()
}

public class SqlContextEntryRepository(private val db: ScpDatabase) : ContextEntryRepository {
    override fun insert(entry: ContextEntry) {
        // Nested transactions join the enclosing one, so this is safe both inside
        // TransactionRunner.inWriteTransaction and standalone.
        db.transaction {
            db.contextEntryQueries.insertEntry(
                id = entry.id,
                sessionId = entry.sessionId,
                timestamp = entry.timestamp.toString(),
                title = entry.title,
                content = entry.content,
                type = entry.type,
                priority = entry.priority.toLong(),
                embedding = VectorUtils.toByteArray(entry.embedding),
            )
            entry.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.distinct().forEach { tag ->
                db.contextEntryTagQueries.insertTag(entryId = entry.id, tag = tag)
            }
        }
    }

    override fun findBySession(sessionId: String): List<ContextEntry> =
        withTags(db.contextEntryQueries.findBySession(sessionId).executeAsList())

    override fun findRecentByType(projectId: String, type: ContextType, limit: Long): List<ContextEntry> =
        withTags(db.contextEntryQueries.findRecentByType(projectId, type, limit).executeAsList())

    override fun findRecent(projectId: String, limit: Long): List<ContextEntry> =
        withTags(db.contextEntryQueries.findRecent(projectId, limit).executeAsList())

    override fun listChronological(projectId: String): List<ContextEntry> =
        withTags(db.contextEntryQueries.listChronological(projectId).executeAsList())

    override fun countByProject(projectId: String): Long = db.contextEntryQueries.countByProject(projectId).executeAsOne()

    override fun countByType(projectId: String): Map<ContextType, Long> =
        db.contextEntryQueries
            .countByType(projectId)
            .executeAsList()
            .associate { it.type to it.entry_count }

    /** Batch tag load — one IN query, no per-entry lookups. */
    private fun withTags(rows: List<com.scp.database.Context_entry>): List<ContextEntry> {
        if (rows.isEmpty()) return emptyList()
        val tagsByEntry =
            db.contextEntryTagQueries
                .tagsForEntries(rows.map { it.id })
                .executeAsList()
                .groupBy({ it.entry_id }, { it.tag })
        return rows.map { it.toDomain(tagsByEntry[it.id].orEmpty()) }
    }
}

public class SqlDecisionRepository(private val db: ScpDatabase) : DecisionRepository {
    override fun insert(decision: Decision) {
        db.decisionQueries.insertDecision(
            id = decision.id,
            projectId = decision.projectId,
            title = decision.title,
            decision = decision.decision,
            reason = decision.reason,
            status = decision.status.dbValue,
            createdAt = decision.createdAt.toString(),
            updatedAt = decision.updatedAt.toString(),
        )
    }

    override fun findOpenByProject(projectId: String): List<Decision> =
        db.decisionQueries
            .findOpenByProject(projectId)
            .executeAsList()
            .map { it.toDomain() }

    override fun listByProject(projectId: String): List<Decision> =
        db.decisionQueries
            .listByProject(projectId)
            .executeAsList()
            .map { it.toDomain() }

    override fun updateStatus(id: String, status: DecisionStatus, updatedAt: Instant) {
        db.decisionQueries.updateStatus(status = status.dbValue, updatedAt = updatedAt.toString(), id = id)
    }
}

public class SqlTodoRepository(private val db: ScpDatabase) : TodoRepository {
    override fun insert(todo: Todo) {
        db.todoQueries.insertTodo(
            id = todo.id,
            projectId = todo.projectId,
            description = todo.description,
            status = todo.status.dbValue,
            owner = todo.owner,
            createdAt = todo.createdAt.toString(),
        )
    }

    override fun findOpenByProject(projectId: String): List<Todo> =
        db.todoQueries
            .findOpenByProject(projectId)
            .executeAsList()
            .map { it.toDomain() }

    override fun listByProject(projectId: String): List<Todo> =
        db.todoQueries
            .listByProject(projectId)
            .executeAsList()
            .map { it.toDomain() }

    override fun updateStatus(id: String, status: TodoStatus) {
        db.todoQueries.updateStatus(status = status.dbValue, id = id)
    }

    override fun updateOwner(id: String, owner: String?) {
        db.todoQueries.updateOwner(owner = owner, id = id)
    }
}

public class SqlFileRepository(private val db: ScpDatabase) : FileRepository {
    override fun upsert(file: TrackedFile) {
        db.fileQueries.upsertFile(
            id = file.id,
            projectId = file.projectId,
            path = file.path,
            summary = file.summary,
            hash = file.hash,
            updatedAt = file.updatedAt.toString(),
        )
    }

    override fun findRecentlyModified(projectId: String, limit: Long): List<TrackedFile> =
        db.fileQueries
            .findRecentlyModified(projectId, limit)
            .executeAsList()
            .map { it.toDomain() }

    override fun listByProject(projectId: String): List<TrackedFile> =
        db.fileQueries
            .listByProject(projectId)
            .executeAsList()
            .map { it.toDomain() }
}

/**
 * The sortable form of an instant: whole nanoseconds since the epoch. Written alongside the
 * ISO-8601 `start_time` text because that text is not chronologically sortable — see the
 * comment on `session.start_time_epoch_nanos` in Session.sq. Stays well inside Long range
 * (year 2262 is ~9.2e18 ns).
 */
public fun Instant.toEpochNanos(): Long = epochSeconds * NANOS_PER_SECOND + nanosecondsOfSecond

private const val NANOS_PER_SECOND: Long = 1_000_000_000L
