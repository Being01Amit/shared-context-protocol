package com.scp.core

import com.scp.model.ContextEntry
import com.scp.model.Decision
import com.scp.model.DecisionBrief
import com.scp.model.EntryBrief
import com.scp.model.FileBrief
import com.scp.model.Session
import com.scp.model.SessionBrief
import com.scp.model.Todo
import com.scp.model.TodoBrief
import com.scp.model.TrackedFile

internal fun Session.toBrief(): SessionBrief = SessionBrief(id, toolName, startTime, endTime, status, summary)

internal fun Decision.toBrief(): DecisionBrief = DecisionBrief(id, title, decision, reason, updatedAt)

internal fun Todo.toBrief(): TodoBrief = TodoBrief(id, description, status, owner, createdAt)

internal fun ContextEntry.toBrief(): EntryBrief = EntryBrief(id, title, content, type, tags, priority, timestamp)

internal fun TrackedFile.toBrief(): FileBrief = FileBrief(path, summary, updatedAt)
