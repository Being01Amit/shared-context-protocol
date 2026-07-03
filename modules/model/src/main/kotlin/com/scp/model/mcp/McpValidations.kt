package com.scp.model.mcp

import com.scp.model.ContextEntry
import com.scp.model.InvalidInputException
import io.konform.validation.Validation
import io.konform.validation.constraints.maxItems
import io.konform.validation.constraints.maxLength
import io.konform.validation.constraints.maximum
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
    private const val MAX_TAGS: Int = 32
    private const val MAX_TAG_LENGTH: Int = 64
    private const val MAX_BATCH: Int = 200

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
                onEach {
                    minLength(1)
                    maxLength(MAX_TAG_LENGTH)
                }
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
            UpdateContextInput::entries {
                maxItems(MAX_BATCH)
                onEach { run(newEntry) }
            }
            UpdateContextInput::decisions {
                maxItems(MAX_BATCH)
                onEach {
                    NewDecision::title {
                        minLength(1)
                        maxLength(MAX_TITLE)
                    }
                    NewDecision::decision {
                        minLength(1)
                        maxLength(MAX_CONTENT)
                    }
                }
            }
            UpdateContextInput::todos {
                maxItems(MAX_BATCH)
                onEach {
                    NewTodo::description {
                        minLength(1)
                        maxLength(MAX_CONTENT)
                    }
                }
            }
            UpdateContextInput::files {
                maxItems(MAX_BATCH)
                onEach {
                    FileUpdate::path {
                        minLength(1)
                        maxLength(1000)
                    }
                    FileUpdate::summary {
                        maxLength(MAX_CONTENT)
                    }
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
                onEach {
                    minLength(1)
                    maxLength(MAX_TAG_LENGTH)
                }
            }
            HydrateContextInput::tokenLimit ifPresent {
                minimum(100)
            }
        }

    public val searchContext: Validation<SearchContextInput> =
        Validation {
            SearchContextInput::query {
                minLength(1)
                maxLength(1000)
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
                maximum(500)
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
                maximum(10_000)
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
                onEach {
                    minLength(1)
                    maxLength(MAX_TAG_LENGTH)
                }
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
