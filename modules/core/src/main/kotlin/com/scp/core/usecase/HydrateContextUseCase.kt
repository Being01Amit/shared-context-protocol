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
import com.scp.model.ResumePoint
import com.scp.model.SessionBrief
import com.scp.model.SessionStatus
import com.scp.model.TodoBrief
import com.scp.model.mcp.HydrateContextInput
import com.scp.model.port.Clock
import com.scp.model.port.ContextEntryRepository
import com.scp.model.port.DecisionRepository
import com.scp.model.port.FileRepository
import com.scp.model.port.GitStateReader
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SessionRepository
import com.scp.model.port.TodoRepository

/**
 * The read path (docs/05): bounded candidate fetches, the single shared scoring function
 * for ordering, then a fixed-section-order token-budget fill with stop-before-overflow.
 * Never returns the full database; truncation is always signaled.
 */
@Suppress("TooManyFunctions")
public class HydrateContextUseCase(
    private val projects: ProjectRepository,
    private val sessions: SessionRepository,
    private val entries: ContextEntryRepository,
    private val decisions: DecisionRepository,
    private val todos: TodoRepository,
    private val files: FileRepository,
    private val clock: Clock,
    private val gitStateReader: GitStateReader,
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
                tags =
                    input.tags
                        .map { it.trim().lowercase() }
                        .filter { it.isNotEmpty() }
                        .toSet(),
                now = clock.now(),
                queryEmbedding = input.embedding?.toFloatArray(),
            )
        val budget = Budget(input.tokenLimit ?: defaultTokenLimit, tokenEstimator)

        // Bounded fetches (SQL LIMIT), scored with the one shared ranking function.
        val recentSessions = sessions.listRecent(project.id, SESSION_COUNT).map { it.toBrief() }
        val openDecisions = rankBy(decisions.findOpenByProject(project.id), RankableItem::fromDecision, query)
        val openTodos = rankBy(todos.findOpenByProject(project.id), RankableItem::fromTodo, query)
        val openBugs = rankBy(entries.findRecentByType(project.id, ContextType.BUG, CANDIDATE_LIMIT), RankableItem::fromEntry, query)
        val rankedPrompts =
            rankBy(entries.findRecentByType(project.id, ContextType.PROMPT, CANDIDATE_LIMIT), RankableItem::fromEntry, query)
        budget.recordOmitted((rankedPrompts.size - TOP_PROMPTS).coerceAtLeast(0))
        val prompts = rankedPrompts.take(TOP_PROMPTS)
        val recentFiles = files.findRecentlyModified(project.id, FILE_COUNT).map { it.toBrief() }
        val priorities = currentPriorities(openDecisions, openTodos, openBugs)

        // Every remaining type — ARCHITECTURE, FEATURE, TASK, LEARNING, REFACTOR, ... — which the
        // type-specific fetches above never reach. Without this section an agent's own record of
        // what it built is stored, indexed, and never returned on the resume path. BUG and PROMPT
        // are excluded here only because they already have dedicated sections; the per-type
        // multipliers in RankingWeights do the prioritizing across the rest.
        val rankedEntries =
            rankBy(
                entries
                    .findRecent(project.id, CANDIDATE_LIMIT)
                    .filterNot { it.type == ContextType.BUG || it.type == ContextType.PROMPT },
                RankableItem::fromEntry,
                query,
            )
        budget.recordOmitted((rankedEntries.size - TOP_ENTRIES).coerceAtLeast(0))
        val recentEntries = rankedEntries.take(TOP_ENTRIES)

        // Section 0: the resume anchor, charged to the budget FIRST so it can never be truncated
        // away. An agent that reads nothing else still knows where the last one stopped.
        val resumePoint = resumePoint(project.id, budget, recentFiles, openTodos)

        // Fixed section order (docs/05 §5). Section 1 is always emitted, hard-truncated if needed.
        val projectSummary = budget.takeOrTruncate(project.description.ifBlank { "(no project description recorded)" })
        val sessionsOut = budget.fill(recentSessions, ::renderSession)
        val decisionsOut = budget.fill(openDecisions.map { it.item.toBrief() }, ::renderDecision)
        val todosOut = budget.fill(openTodos.map { it.item.toBrief() }, ::renderTodo)
        val bugsOut = budget.fill(openBugs.map { it.item.toBrief() }, ::renderEntry)
        val entriesOut = budget.fill(recentEntries.map { it.item.toBrief() }, ::renderEntry)
        val promptsOut = budget.fill(prompts.map { it.item.toBrief() }, ::renderEntry)
        val filesOut = budget.fill(recentFiles, ::renderFile)
        val prioritiesOut = budget.fill(priorities, ::renderPriority)

        return HydrationPayload(
            projectName = project.name,
            projectSummary = projectSummary,
            resumePoint = resumePoint,
            recentSessions = sessionsOut,
            openDecisions = decisionsOut,
            openTodos = todosOut,
            openBugs = bugsOut,
            relevantPrompts = promptsOut,
            recentFiles = filesOut,
            currentPriorities = prioritiesOut,
            recentEntries = entriesOut,
            omittedCount = budget.omitted,
            truncationNotice =
                budget.omitted.takeIf { it > 0 }?.let {
                    "...$it more entries omitted, see full history via /timeline"
                },
            estimatedTokens = budget.used,
        )
    }

    /**
     * Section 0. Charged for everything it emits — including [ResumePoint.filesInFlight] and
     * [ResumePoint.blockingTodos], which also appear in their own sections later; leaving them
     * uncharged let the payload exceed tokenLimit while estimatedTokens under-reported it.
     */
    private fun resumePoint(
        projectId: String,
        budget: Budget,
        recentFiles: List<FileBrief>,
        openTodos: List<Scored<com.scp.model.Todo>>,
    ): ResumePoint? =
        sessions.findLatest(projectId)?.let { latest ->
            val filesInFlight = recentFiles.take(RESUME_FILES)
            val blockingTodos = openTodos.take(RESUME_TODOS).map { it.item.toBrief() }
            val gitStateNotice = gitStateNotice(latest)
            budget.charge(
                latest.summary + " " + latest.nextStep +
                    filesInFlight.joinToString(" ", transform = ::renderFile) +
                    blockingTodos.joinToString(" ", transform = ::renderTodo) +
                    (gitStateNotice ?: ""),
            )
            ResumePoint(
                lastSession = latest.toBrief(),
                whatWasDone = latest.summary.ifBlank { "(no summary recorded for the last session)" },
                whereWeStopped =
                    latest.nextStep.ifBlank {
                        "(no next step recorded — check open todos and recent entries below)"
                    },
                lastSessionWasOpen = latest.status == SessionStatus.OPEN,
                filesInFlight = filesInFlight,
                blockingTodos = blockingTodos,
                gitStateNotice = gitStateNotice,
            )
        }

    /**
     * Null unless both the state recorded at [lastSession]'s start and the repo's current state
     * are known and disagree — either side being unknown (git unavailable, or a session that
     * predates this feature) stays silent rather than guessing.
     */
    private fun gitStateNotice(lastSession: com.scp.model.Session): String? {
        val recordedBranch = lastSession.gitBranch
        val recordedCommit = lastSession.gitCommit
        if (recordedBranch == null && recordedCommit == null) return null
        val current = gitStateReader.read() ?: return null
        if (current.branch == recordedBranch && current.commit == recordedCommit) return null
        return "The repo has moved since this session started: it was on branch " +
            "'${recordedBranch ?: "(unknown)"}' at commit '${recordedCommit ?: "(unknown)"}', " +
            "it's now on branch '${current.branch ?: "(unknown)"}' at commit '${current.commit ?: "(unknown)"}'."
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

        /**
         * Records omissions that happen before fill() ever runs — e.g. a fixed top-N cap
         * dropping already-ranked candidates. Keeps [omitted] the single source of truth for
         * "truncation is always signaled" regardless of which stage caused the drop.
         */
        fun recordOmitted(count: Int) {
            if (count > 0) omitted += count
        }

        fun <T> fill(candidates: List<T>, render: (T) -> String): List<T> {
            val emitted = mutableListOf<T>()
            candidates.forEachIndexed { index, candidate ->
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

        /**
         * Unconditionally charge the budget for content that is emitted whatever the cost —
         * the resume point (section 0). Never omits: a payload that dropped the resume anchor
         * to save tokens would defeat the point of hydrating at all. `remaining` floors at 0 so
         * an oversized resume point simply leaves nothing for the later sections rather than
         * making the budget negative.
         */
        fun charge(text: String) {
            val cost = estimator.estimate(text)
            used += cost
            remaining = (remaining - cost).coerceAtLeast(0)
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

        // Known, deliberately unfixed gap: an entry beyond this SQL LIMIT is never fetched, so it
        // can't be ranked or counted toward `omitted` either — unlike the post-rank TOP_ENTRIES/
        // TOP_PROMPTS caps (see recordOmitted), which are signaled. Closing this would need an
        // extra countByType() call per hydration; deferred given ADR-2's latency findings and the
        // rarity of a single project accumulating 50+ recent entries of one type.
        const val CANDIDATE_LIMIT = 50L
        const val TOP_PROMPTS = 10
        const val TOP_ENTRIES = 15
        const val FILE_COUNT = 10L
        const val PRIORITY_COUNT = 5
        const val RESUME_FILES = 5
        const val RESUME_TODOS = 3
    }
}
