package com.scp.core

import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.Decision
import com.scp.model.DecisionStatus
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.SessionStatus
import com.scp.model.Todo
import com.scp.model.TodoStatus
import com.scp.model.TrackedFile
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
import kotlinx.datetime.Instant

/** Deterministic in-memory fakes — core tests need no database and no mocking framework. */
class FixedClock(var current: Instant = Instant.parse("2026-07-04T12:00:00Z")) : Clock {
    override fun now(): Instant = current
}

class SequentialIds : IdGenerator {
    private var counter = 0

    override fun newId(): String {
        val n = counter++
        return "00000000-0000-4000-8000-%012d".format(n)
    }
}

class PassThroughTransactionRunner : TransactionRunner {
    var transactionCount: Int = 0
        private set

    override fun <T> inWriteTransaction(block: () -> T): T {
        transactionCount++
        return block()
    }
}

class FakeProjectRepository : ProjectRepository {
    val store = mutableMapOf<String, Project>()

    override fun insert(project: Project) {
        store[project.id] = project
    }

    override fun findById(id: String): Project? = store[id]

    override fun findByName(name: String): Project? = store.values.firstOrNull { it.name == name }

    override fun listAll(): List<Project> = store.values.sortedBy { it.name }

    override fun updateDescription(id: String, description: String, updatedAt: Instant) {
        store[id] = store.getValue(id).copy(description = description, updatedAt = updatedAt)
    }

    override fun touch(id: String, updatedAt: Instant) {
        store[id] = store.getValue(id).copy(updatedAt = updatedAt)
    }
}

class FakeSessionRepository : SessionRepository {
    val store = mutableMapOf<String, Session>()

    override fun insert(session: Session) {
        store[session.id] = session
    }

    override fun findById(id: String): Session? = store[id]

    override fun findOpenByProject(projectId: String): List<Session> =
        store.values
            .filter { it.projectId == projectId && it.status == SessionStatus.OPEN }
            .sortedBy { it.startTime }

    override fun findLatest(projectId: String): Session? =
        store.values
            .filter { it.projectId == projectId }
            .maxByOrNull { it.startTime }

    override fun close(id: String, endTime: Instant, summary: String?, tokenUsage: Long?, nextStep: String?) {
        val s = store.getValue(id)
        store[id] =
            s.copy(
                status = SessionStatus.CLOSED,
                endTime = endTime,
                summary = summary ?: s.summary,
                tokenUsage = tokenUsage ?: s.tokenUsage,
                nextStep = nextStep ?: s.nextStep,
            )
    }

    override fun listRecent(projectId: String, limit: Long): List<Session> =
        store.values
            .filter { it.projectId == projectId }
            .sortedByDescending { it.startTime }
            .take(limit.toInt())

    override fun listChronological(projectId: String): List<Session> =
        store.values.filter { it.projectId == projectId }.sortedBy { it.startTime }

    override fun countByProject(projectId: String): Long = store.values.count { it.projectId == projectId }.toLong()
}

class FakeContextEntryRepository(private val sessions: FakeSessionRepository) : ContextEntryRepository {
    val store = mutableListOf<ContextEntry>()

    override fun insert(entry: ContextEntry) {
        store +=
            entry.copy(
                tags =
                    entry.tags
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                        .distinct(),
            )
    }

    override fun findBySession(sessionId: String): List<ContextEntry> =
        store.filter { it.sessionId == sessionId }.sortedBy { it.timestamp }

    override fun findRecentByType(projectId: String, type: ContextType, limit: Long): List<ContextEntry> =
        byProject(projectId)
            .filter { it.type == type }
            .sortedByDescending { it.timestamp }
            .take(limit.toInt())

    override fun findRecent(projectId: String, limit: Long): List<ContextEntry> =
        byProject(projectId).sortedByDescending { it.timestamp }.take(limit.toInt())

    override fun listChronological(projectId: String): List<ContextEntry> = byProject(projectId).sortedBy { it.timestamp }

    override fun countByProject(projectId: String): Long = byProject(projectId).size.toLong()

    override fun countByType(projectId: String): Map<ContextType, Long> =
        byProject(projectId).groupingBy { it.type }.eachCount().mapValues { it.value.toLong() }

    private fun byProject(projectId: String): List<ContextEntry> {
        val sessionIds =
            sessions.store.values
                .filter { it.projectId == projectId }
                .map { it.id }
                .toSet()
        return store.filter { it.sessionId in sessionIds }
    }
}

class FakeDecisionRepository : DecisionRepository {
    val store = mutableListOf<Decision>()

    override fun insert(decision: Decision) {
        store += decision
    }

    override fun findOpenByProject(projectId: String): List<Decision> =
        store.filter { it.projectId == projectId && it.status == DecisionStatus.OPEN }

    override fun listByProject(projectId: String): List<Decision> = store.filter { it.projectId == projectId }

    override fun updateStatus(id: String, status: DecisionStatus, updatedAt: Instant) {
        val index = store.indexOfFirst { it.id == id }
        store[index] = store[index].copy(status = status, updatedAt = updatedAt)
    }
}

class FakeTodoRepository : TodoRepository {
    val store = mutableListOf<Todo>()

    override fun insert(todo: Todo) {
        store += todo
    }

    override fun findOpenByProject(projectId: String): List<Todo> =
        store.filter { it.projectId == projectId && (it.status == TodoStatus.OPEN || it.status == TodoStatus.IN_PROGRESS) }

    override fun listByProject(projectId: String): List<Todo> = store.filter { it.projectId == projectId }

    override fun updateStatus(id: String, status: TodoStatus) {
        val index = store.indexOfFirst { it.id == id }
        store[index] = store[index].copy(status = status)
    }
}

class FakeFileRepository : FileRepository {
    val store = mutableListOf<TrackedFile>()

    override fun upsert(file: TrackedFile) {
        store.removeAll { it.projectId == file.projectId && it.path == file.path }
        store += file
    }

    override fun findRecentlyModified(projectId: String, limit: Long): List<TrackedFile> =
        store
            .filter { it.projectId == projectId }
            .sortedByDescending { it.updatedAt }
            .take(limit.toInt())

    override fun listByProject(projectId: String): List<TrackedFile> =
        store.filter { it.projectId == projectId }.sortedBy { it.path }
}

class RecordingMarkdownStore : MarkdownStore {
    val written = mutableListOf<SessionMarkdown>()

    override fun write(sessionMarkdown: SessionMarkdown): String {
        written += sessionMarkdown
        return "storage/markdown/${sessionMarkdown.project.name}/fake-${sessionMarkdown.session.id.take(8)}.md"
    }
}
