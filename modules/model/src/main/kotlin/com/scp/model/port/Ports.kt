package com.scp.model.port

import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.Decision
import com.scp.model.DecisionStatus
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.Todo
import com.scp.model.TodoStatus
import com.scp.model.TrackedFile
import kotlinx.datetime.Instant

/**
 * Ports implemented by adapter modules (`database`, `search`, `markdown`) and injected
 * into `core` use-cases. `core` depends on nothing but these interfaces — the Gradle
 * module graph enforces it.
 */
public interface ProjectRepository {
    public fun insert(project: Project)

    public fun findById(id: String): Project?

    public fun findByName(name: String): Project?

    public fun listAll(): List<Project>

    public fun updateDescription(id: String, description: String, updatedAt: Instant)

    public fun touch(id: String, updatedAt: Instant)
}

public interface SessionRepository {
    public fun insert(session: Session)

    public fun findById(id: String): Session?

    /** All sessions with status = open for the project — session resolution's hot query. */
    public fun findOpenByProject(projectId: String): List<Session>

    public fun close(id: String, endTime: Instant, summary: String?, tokenUsage: Long?)

    /** Most recent first. */
    public fun listRecent(projectId: String, limit: Long): List<Session>

    /** Chronological (oldest first) — the /timeline escape hatch. */
    public fun listChronological(projectId: String): List<Session>

    public fun countByProject(projectId: String): Long
}

public interface ContextEntryRepository {
    /** Persists the entry and its tags in the caller's transaction. Content must already be redacted. */
    public fun insert(entry: ContextEntry)

    public fun findBySession(sessionId: String): List<ContextEntry>

    /** Most recent first, tags loaded. */
    public fun findRecentByType(projectId: String, type: ContextType, limit: Long): List<ContextEntry>

    public fun findRecent(projectId: String, limit: Long): List<ContextEntry>

    /** Chronological (oldest first) — /timeline. */
    public fun listChronological(projectId: String): List<ContextEntry>

    public fun countByProject(projectId: String): Long
}

public interface DecisionRepository {
    public fun insert(decision: Decision)

    public fun findOpenByProject(projectId: String): List<Decision>

    public fun listByProject(projectId: String): List<Decision>

    public fun updateStatus(id: String, status: DecisionStatus, updatedAt: Instant)
}

public interface TodoRepository {
    public fun insert(todo: Todo)

    /** Status open or in_progress. */
    public fun findOpenByProject(projectId: String): List<Todo>

    public fun listByProject(projectId: String): List<Todo>

    public fun updateStatus(id: String, status: TodoStatus)
}

public interface FileRepository {
    /** Insert or update on (project_id, path). Summary must already be redacted. */
    public fun upsert(file: TrackedFile)

    public fun findRecentlyModified(projectId: String, limit: Long): List<TrackedFile>

    public fun listByProject(projectId: String): List<TrackedFile>
}
