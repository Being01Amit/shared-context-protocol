package com.scp.model.mcp

import com.scp.model.ContextEntry
import com.scp.model.InvalidInputException
import io.konform.validation.Validation
import io.konform.validation.constraints.maxItems
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.maximum
import io.konform.validation.constraints.minItems
import io.konform.validation.constraints.minLength
import io.konform.validation.constraints.minimum
import io.konform.validation.constraints.pattern

/**
 * One Konform validation per MCP tool input. Server and tests share these — the MCP
 * boundary rejects anything invalid before it reaches a use-case.
 */
public object McpValidations {
    private const val UUID_PATTERN: String = "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"
    private const val MAX_NAME: Int = 200
    private const val MAX_TITLE: Int = 500
    private const val MAX_CONTENT: Int = 100_000
    private const val MAX_PATH: Int = 1000
    private const val MAX_QUERY: Int = 1000
    private const val MAX_TAGS: Int = 32
    private const val MAX_TAG_LENGTH: Int = 64
    private const val MAX_BATCH: Int = 200
    private const val MIN_TOKEN_LIMIT: Int = 100
    private const val MAX_SEARCH_LIMIT: Long = 500
    private const val MAX_TIMELINE_LIMIT: Long = 10_000

    /**
     * Upper bound on vector length. Comfortably above today's common embedding sizes (384 – 4096)
     * while stopping a malformed or hostile call from submitting an arbitrarily large array.
     */
    private const val MAX_EMBEDDING_DIMENSIONS: Int = 8192

    public val createProject: Validation<CreateProjectInput> =
        Validation {
            CreateProjectInput::name {
                minLength(1)
                maxLength(MAX_NAME)
            }
            CreateProjectInput::description {
                maxLength(MAX_CONTENT)
            }
        }

    public val newEntry: Validation<NewEntry> =
        Validation {
            NewEntry::title {
                minLength(1)
                maxLength(MAX_TITLE)
            }
            NewEntry::content {
                maxLength(MAX_CONTENT)
            }
            NewEntry::priority {
                minimum(ContextEntry.MIN_PRIORITY)
                maximum(ContextEntry.MAX_PRIORITY)
            }
            NewEntry::tags {
                maxItems(MAX_TAGS)
            }
            NewEntry::tags onEach {
                minLength(1)
                maxLength(MAX_TAG_LENGTH)
            }
            NewEntry::embedding ifPresent {
                minItems(1)
                maxItems(MAX_EMBEDDING_DIMENSIONS)
            }
        }

    public val updateContext: Validation<UpdateContextInput> =
        Validation {
            UpdateContextInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            UpdateContextInput::toolName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            UpdateContextInput::sessionId ifPresent {
                pattern(UUID_PATTERN) hint "sessionId must be a UUID"
            }
            UpdateContextInput::summary {
                maxLength(MAX_CONTENT)
            }
            UpdateContextInput::nextStep {
                maxLength(MAX_CONTENT)
            }
            UpdateContextInput::entries {
                maxItems(MAX_BATCH)
            }
            UpdateContextInput::entries onEach {
                run(newEntry)
            }
            // Cosine similarity between vectors of different lengths is undefined and scores 0, so a
            // mixed batch would silently never rank semantically. Reject it where it can be seen.
            UpdateContextInput::entries {
                constrain("all entry embeddings in one call must have the same number of dimensions") { entries ->
                    entries.mapNotNull { it.embedding?.size }.distinct().size <= 1
                }
            }
            UpdateContextInput::decisions {
                maxItems(MAX_BATCH)
            }
            UpdateContextInput::decisions onEach {
                NewDecision::title {
                    minLength(1)
                    maxLength(MAX_TITLE)
                }
                NewDecision::decision {
                    minLength(1)
                    maxLength(MAX_CONTENT)
                }
                NewDecision::reason {
                    maxLength(MAX_CONTENT)
                }
            }
            UpdateContextInput::todos {
                maxItems(MAX_BATCH)
            }
            UpdateContextInput::todos onEach {
                NewTodo::description {
                    minLength(1)
                    maxLength(MAX_CONTENT)
                }
            }
            UpdateContextInput::files {
                maxItems(MAX_BATCH)
            }
            UpdateContextInput::files onEach {
                FileUpdate::path {
                    minLength(1)
                    maxLength(MAX_PATH)
                }
                FileUpdate::summary {
                    maxLength(MAX_CONTENT)
                }
            }
        }

    public val hydrateContext: Validation<HydrateContextInput> =
        Validation {
            HydrateContextInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            HydrateContextInput::tags {
                maxItems(MAX_TAGS)
            }
            HydrateContextInput::tags onEach {
                minLength(1)
                maxLength(MAX_TAG_LENGTH)
            }
            HydrateContextInput::tokenLimit ifPresent {
                minimum(MIN_TOKEN_LIMIT)
            }
            HydrateContextInput::embedding ifPresent {
                minItems(1)
                maxItems(MAX_EMBEDDING_DIMENSIONS)
            }
        }

    public val searchContext: Validation<SearchContextInput> =
        Validation {
            SearchContextInput::query {
                minLength(1)
                maxLength(MAX_QUERY)
            }
            SearchContextInput::projectName ifPresent {
                minLength(1)
                maxLength(MAX_NAME)
            }
            SearchContextInput::tag ifPresent {
                minLength(1)
                maxLength(MAX_TAG_LENGTH)
            }
            SearchContextInput::limit ifPresent {
                minimum(1)
                maximum(MAX_SEARCH_LIMIT)
            }
        }

    public val projectSummary: Validation<ProjectSummaryInput> =
        Validation {
            ProjectSummaryInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
        }

    public val timeline: Validation<TimelineInput> =
        Validation {
            TimelineInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            TimelineInput::limit ifPresent {
                minimum(1)
                maximum(MAX_TIMELINE_LIMIT)
            }
        }

    public val saveNote: Validation<SaveNoteInput> =
        Validation {
            SaveNoteInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            SaveNoteInput::toolName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            SaveNoteInput::title {
                minLength(1)
                maxLength(MAX_TITLE)
            }
            SaveNoteInput::content {
                maxLength(MAX_CONTENT)
            }
            SaveNoteInput::priority {
                minimum(ContextEntry.MIN_PRIORITY)
                maximum(ContextEntry.MAX_PRIORITY)
            }
            SaveNoteInput::tags {
                maxItems(MAX_TAGS)
            }
            SaveNoteInput::tags onEach {
                minLength(1)
                maxLength(MAX_TAG_LENGTH)
            }
        }

    public val updateTodoStatus: Validation<UpdateTodoStatusInput> =
        Validation {
            UpdateTodoStatusInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            UpdateTodoStatusInput::todoId {
                pattern(UUID_PATTERN) hint "todoId must be a UUID"
            }
        }

    public val updateDecisionStatus: Validation<UpdateDecisionStatusInput> =
        Validation {
            UpdateDecisionStatusInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            UpdateDecisionStatusInput::decisionId {
                pattern(UUID_PATTERN) hint "decisionId must be a UUID"
            }
        }

    public val updateProject: Validation<UpdateProjectInput> =
        Validation {
            UpdateProjectInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            UpdateProjectInput::description {
                maxLength(MAX_CONTENT)
            }
        }

    public val claimTodo: Validation<ClaimTodoInput> =
        Validation {
            ClaimTodoInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            ClaimTodoInput::todoId {
                pattern(UUID_PATTERN) hint "todoId must be a UUID"
            }
            ClaimTodoInput::toolName {
                minLength(1)
                maxLength(MAX_NAME)
            }
        }

    public val releaseTodo: Validation<ReleaseTodoInput> =
        Validation {
            ReleaseTodoInput::projectName {
                minLength(1)
                maxLength(MAX_NAME)
            }
            ReleaseTodoInput::todoId {
                pattern(UUID_PATTERN) hint "todoId must be a UUID"
            }
            ReleaseTodoInput::toolName {
                minLength(1)
                maxLength(MAX_NAME)
            }
        }
}

/** Runs the validation and throws [InvalidInputException] with every violation listed. */
public fun <T> Validation<T>.checkValid(value: T): T {
    val result = this(value)
    val errors = result.errors
    if (errors.isNotEmpty()) {
        val message = errors.joinToString("; ") { "${it.dataPath}: ${it.message}" }
        throw InvalidInputException("Invalid input: $message")
    }
    return value
}
