package com.scp.model

import kotlinx.serialization.Serializable

/**
 * Lifecycle of a [Session]. Stored in SQLite as the lowercase [dbValue]
 * (the schema constrains the column with CHECK (status IN ('open','closed'))).
 */
@Serializable
public enum class SessionStatus(public val dbValue: String) {
    OPEN("open"),
    CLOSED("closed"),
    ;

    public companion object {
        public fun fromDb(value: String): SessionStatus =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown session status: '$value'")
    }
}

@Serializable
public enum class DecisionStatus(public val dbValue: String) {
    OPEN("open"),
    ACCEPTED("accepted"),
    SUPERSEDED("superseded"),
    REJECTED("rejected"),
    ;

    public companion object {
        public fun fromDb(value: String): DecisionStatus =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown decision status: '$value'")
    }
}

@Serializable
public enum class TodoStatus(public val dbValue: String) {
    OPEN("open"),
    IN_PROGRESS("in_progress"),
    DONE("done"),
    DROPPED("dropped"),
    ;

    public companion object {
        public fun fromDb(value: String): TodoStatus =
            entries.firstOrNull { it.dbValue == value }
                ?: throw IllegalArgumentException("Unknown todo status: '$value'")
    }
}
