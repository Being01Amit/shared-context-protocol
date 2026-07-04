package com.scp.core.usecase

import com.scp.core.CharsPerTokenEstimator
import com.scp.core.Scoring
import com.scp.core.TokenEstimator
import com.scp.core.toBrief
import com.scp.model.ContextType
import com.scp.model.DecisionBrief
import com.scp.model.EntryBrief
import com.scp.model.FileBrief
import com.scp.model.HydrationPayload
import com.scp.model.HydrationQuery
import com.scp.model.NotFoundException
import com.scp.model.PriorityBrief
import com.scp.model.RankableItem
import com.scp.model.RankingWeights
import com.scp.model.SessionBrief
import com.scp.model.TodoBrief
import com.scp.model.mcp.HydrateContextInput
import com.scp.model.port.Clock
import com.scp.model.port.ContextEntryRepository
import com.scp.model.port.DecisionRepository
import com.scp.model.port.FileRepository
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionRepository
import com.scp.model.port.TodoRepository

/**
 * The read path (docs/05): bounded candidate fetches, the single shared scoring function
 * for ordering, then a fixed-section-order token-budget fill with stop-before-overflow.
 * Never returns the full database; truncation is always signaled.
 */
public class HydrateContextUseCase(
    private val projects: ProjectRepository,
    private val sessions: SessionRepository,
    private val entries: ContextEntryRepository,
    private val decisions: DecisionRepository,
    private val todos: TodoRepository,
    private val files: FileRepository,
    private val clock: Clock,
    private val weights: RankingWeights,
    private val defaultTokenLimit: Int,
    private val tokenEstimator: TokenEstimator = CharsPerTokenEstimator,
) {
    public fun execute(input: HydrateContextInput): HydrationPayload {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")
        val query =
            HydrationQuery(
                projectId = project.id,
                tags = input.tags.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet(),
                now = clock.now(),
            )
        val budget = Budget(input.tokenLimit ?: defaultTokenLimit, tokenEstimator)

        // Bounded fetches (SQL LIMIT), scored with the one shared ranking function.
        val recentSessions = sessions.listRecent(project.id, SESSION_COUNT).map { it.toBrief() }
        val openDecisions = rankBy(decisions.findOpenByProject(project.id), RankableItem::fromDecision, query)
        val openTodos = rankBy(todos.findOpenByProject(project.id), RankableItem::fromTodo, query)
        val openBugs = rankBy(entries.findRecentByType(project.id, ContextType.BUG, CANDIDATE_LIMIT), RankableItem::fromEntry, query)
        val prompts =
            rankBy(entries.findRecentByType(project.id, ContextType.PROMPT, CANDIDATE_LIMIT), RankableItem::fromEntry, query)
                .take(TOP_PROMPTS)
        val recentFiles = files.findRecentlyModified(project.id, FILE_COUNT).map { it.toBrief() }
        val priorities = currentPriorities(openDecisions, openTodos, openBugs)

        // Fixed section order (docs/05 §5). Section 1 is always emitted, hard-truncated if needed.
        val projectSummary = budget.takeOrTruncate(project.description.ifBlank { "(no project description recorded)" })
        val sessionsOut = budget.fill(recentSessions, ::renderSession)
        val decisionsOut = budget.fill(openDecisions.map { it.item.toBrief() }, ::renderDecision)
        val todosOut = budget.fill(openTodos.map { it.item.toBrief() }, ::renderTodo)
        val bugsOut = budget.fill(openBugs.map { it.item.toBrief() }, ::renderEntry)
        val promptsOut = budget.fill(prompts.map { it.item.toBrief() }, ::renderEntry)
        val filesOut = budget.fill(recentFiles, ::renderFile)
        val prioritiesOut = budget.fill(priorities, ::renderPriority)

        return HydrationPayload(
            projectName = project.name,
            projectSummary = projectSummary,
            recentSessions = sessionsOut,
            openDecisions = decisionsOut,
            openTodos = todosOut,
            openBugs = bugsOut,
            relevantPrompts = promptsOut,
            recentFiles = filesOut,
            currentPriorities = prioritiesOut,
            omittedCount = budget.omitted,
            truncationNotice =
                budget.omitted.takeIf { it > 0 }?.let {
                    "...$it more entries omitted, see full history via /timeline"
                },
            estimatedTokens = budget.used,
        )
    }

    private data class Scored<T>(val item: T, val score: Double)

    private fun <T> rankBy(items: List<T>, toRankable: (T) -> RankableItem, query: HydrationQuery): List<Scored<T>> =
        items
            .map { Scored(it, Scoring.scoreEntry(toRankable(it), query, weights)) }
            .sortedByDescending { it.score }

    private fun currentPriorities(
        decisions: List<Scored<com.scp.model.Decision>>,
        todos: List<Scored<com.scp.model.Todo>>,
        bugs: List<Scored<com.scp.model.ContextEntry>>,
    ): List<PriorityBrief> =
        buildList {
            decisions.forEach { add(PriorityBrief("decision", it.item.id, it.item.title, it.score)) }
            todos.forEach { add(PriorityBrief("todo", it.item.id, it.item.description, it.score)) }
            bugs.forEach { add(PriorityBrief("bug", it.item.id, it.item.title, it.score)) }
        }.sortedByDescending { it.score }.take(PRIORITY_COUNT)

    /** Stop-before-overflow budget (docs/05 §4). Once a section stops, later candidates count as omitted. */
    private class Budget(limit: Int, private val estimator: TokenEstimator) {
        var remaining: Int = limit
            private set
        var used: Int = 0
            private set
        var omitted: Int = 0
            private set

        fun <T> fill(candidates: List<T>, render: (T) -> String): List<T> {
            val emitted = mutableListOf<T>()
            candidates.forEachIndexed { index, candidate ->
                if (emitted.size < index) return@forEachIndexed // already stopped; counted below
                val cost = estimator.estimate(render(candidate))
                if (cost <= remaining) {
                    emitted += candidate
                    remaining -= cost
                    used += cost
                } else {
                    omitted += candidates.size - index
                    return emitted
                }
            }
            return emitted
        }

        /** Section 1 must always exist: hard-truncate rather than omit (docs/05 §4). */
        fun takeOrTruncate(text: String): String {
            val cost = estimator.estimate(text)
            if (cost <= remaining) {
                remaining -= cost
                used += cost
                return text
            }
            val kept = text.take(remaining * CHARS_PER_TOKEN_APPROX)
            used += remaining
            remaining = 0
            omitted += 1
            return "$kept ...[project summary truncated]"
        }

        private companion object {
            const val CHARS_PER_TOKEN_APPROX = 4
        }
    }

    private fun renderSession(s: SessionBrief): String = "${s.id} ${s.toolName} ${s.startTime} ${s.endTime ?: ""} ${s.summary}"

    private fun renderDecision(d: DecisionBrief): String = "${d.id} ${d.title} ${d.decision} ${d.reason}"

    private fun renderTodo(t: TodoBrief): String = "${t.id} ${t.description} ${t.status} ${t.owner ?: ""}"

    private fun renderEntry(e: EntryBrief): String = "${e.id} ${e.title} ${e.content} ${e.tags.joinToString(" ")}"

    private fun renderFile(f: FileBrief): String = "${f.path} ${f.summary}"

    private fun renderPriority(p: PriorityBrief): String = "${p.kind} ${p.title}"

    private companion object {
        const val SESSION_COUNT = 5L
        const val CANDIDATE_LIMIT = 50L
        const val TOP_PROMPTS = 10
        const val FILE_COUNT = 10L
        const val PRIORITY_COUNT = 5
    }
}
