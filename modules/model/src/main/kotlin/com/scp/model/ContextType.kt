package com.scp.model

import kotlinx.serialization.Serializable

/**
 * The single source of truth for context entry categories. Stored in SQLite as `.name`
 * via SQLDelight's `EnumColumnAdapter` — never as duplicated string literals.
 */
@Serializable
public enum class ContextType {
    ARCHITECTURE,
    DECISION,
    BUG,
    FEATURE,
    PROMPT,
    RESEARCH,
    TESTING,
    MEETING,
    TASK,
    LEARNING,
    COMMIT,
    RELEASE,
    REFACTOR,
    PERFORMANCE,
    SECURITY,
    DOCUMENTATION,
}
