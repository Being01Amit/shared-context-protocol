package com.scp.markdown

import com.scp.model.ContextEntry
import com.scp.model.ContextType
import com.scp.model.port.MarkdownStore
import com.scp.model.port.SessionMarkdown
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Human-readable mirror: one markdown file per session at
 * `storage/markdown/{project}/{YYYY-MM-DD}-{sessionId8}.md` (the 8-char session suffix
 * keeps same-day sessions distinct). Content arrives already redacted. Writes are atomic
 * (temp file + move) so a crash never leaves a half-written mirror.
 */
public class FileMarkdownStore(private val markdownRoot: Path) : MarkdownStore {
    override fun write(sessionMarkdown: SessionMarkdown): String {
        val session = sessionMarkdown.session
        val date = session.startTime.toLocalDateTime(TimeZone.UTC).date
        val dir = markdownRoot.resolve(sanitizeForDirectory(sessionMarkdown.project.name))
        Files.createDirectories(dir)
        val target = dir.resolve("$date-${session.id.take(SESSION_ID_CHARS)}.md")

        val temp = Files.createTempFile(dir, ".scp-", ".md.tmp")
        try {
            Files.writeString(temp, render(sessionMarkdown))
            try {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temp)
        }
        return target.toAbsolutePath().toString()
    }

    private fun render(md: SessionMarkdown): String {
        val session = md.session
        val byType = md.entries.groupBy { it.type }
        val decisionEntries = (byType[ContextType.DECISION].orEmpty()) + (byType[ContextType.ARCHITECTURE].orEmpty())
        val taskEntries = byType[ContextType.TASK].orEmpty()
        val bugEntries = byType[ContextType.BUG].orEmpty()
        val promptEntries = byType[ContextType.PROMPT].orEmpty()
        val coveredTypes = setOf(ContextType.DECISION, ContextType.ARCHITECTURE, ContextType.TASK, ContextType.BUG, ContextType.PROMPT)
        val noteEntries = md.entries.filterNot { it.type in coveredTypes }

        return buildString {
            appendLine("# Session")
            appendLine()
            appendLine("Date: ${session.startTime.toLocalDateTime(TimeZone.UTC).date}")
            appendLine("AI Tool: ${session.toolName}")
            appendLine("Session ID: ${session.id}")
            appendLine("Status: ${session.status.dbValue}")
            appendLine("Summary: ${session.summary.ifBlank { "(none)" }}")
            appendLine()
            section("Architecture Decisions") {
                md.decisions.forEach { appendLine("- **${it.title}** — ${it.decision}${reason(it.reason)}") }
                decisionEntries.forEach { appendEntry(it) }
            }
            section("Tasks Completed") {
                taskEntries.forEach { appendEntry(it) }
            }
            section("Open Issues") {
                md.todos.forEach { appendLine("- [ ] ${it.description}${it.owner?.let { o -> " (owner: $o)" } ?: ""}") }
                bugEntries.forEach { appendEntry(it) }
            }
            section("Files Modified") {
                md.files.forEach { appendLine("- `${it.path}`${if (it.summary.isBlank()) "" else " — ${it.summary}"}") }
            }
            section("Prompts") {
                promptEntries.forEach { appendEntry(it) }
            }
            section("Notes") {
                noteEntries.forEach { appendEntry(it) }
            }
        }
    }

    private fun StringBuilder.section(title: String, body: StringBuilder.() -> Unit) {
        appendLine("## $title")
        val lengthBefore = length
        body()
        if (length == lengthBefore) appendLine("_(none)_")
        appendLine()
    }

    private fun StringBuilder.appendEntry(entry: ContextEntry) {
        val tags = if (entry.tags.isEmpty()) "" else " [${entry.tags.joinToString(", ")}]"
        appendLine("- **${entry.title}**$tags (${entry.type.name.lowercase()}, priority ${entry.priority})")
        entry.content.lineSequence().forEach { appendLine("  $it") }
    }

    private fun reason(reason: String): String = if (reason.isBlank()) "" else " (reason: $reason)"

    private companion object {
        const val SESSION_ID_CHARS = 8

        /** Windows-safe directory name: forbidden characters replaced, trailing dots/spaces trimmed. */
        fun sanitizeForDirectory(name: String): String {
            val cleaned =
                name
                    .map { c -> if (c.isISOControl() || c in "<>:\"/\\|?*") '-' else c }
                    .joinToString("")
                    .trim()
                    .trimEnd('.', ' ')
            return cleaned.ifBlank { "unnamed-project" }
        }
    }
}
