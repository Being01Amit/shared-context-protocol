package com.scp.model.port

import com.scp.model.ContextEntry
import com.scp.model.Decision
import com.scp.model.Project
import com.scp.model.Session
import com.scp.model.Todo
import com.scp.model.TrackedFile

/** Everything the per-session markdown mirror renders. */
public data class SessionMarkdown(
    val project: Project,
    val session: Session,
    val entries: List<ContextEntry>,
    val decisions: List<Decision> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val files: List<TrackedFile> = emptyList(),
)

public interface MarkdownStore {
    /**
     * Writes (or overwrites) `storage/markdown/{project}/{YYYY-MM-DD}-{sessionId8}.md`.
     * Returns the absolute path written. Content must already be redacted.
     */
    public fun write(sessionMarkdown: SessionMarkdown): String
}
