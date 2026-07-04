package com.scp.core.usecase

import com.scp.core.Scoring
import com.scp.core.toBrief
import com.scp.model.HydrationQuery
import com.scp.model.NotFoundException
import com.scp.model.RankableItem
import com.scp.model.RankingWeights
import com.scp.model.mcp.SearchContextInput
import com.scp.model.mcp.SearchContextResult
import com.scp.model.mcp.SearchResultItem
import com.scp.model.port.Clock
import com.scp.model.port.ProjectRepository
import com.scp.model.port.SearchIndex
import com.scp.model.port.SearchRequest

/**
 * FTS5 bm25 pre-selects a bounded candidate set inside SQL; the presented order comes
 * from the single shared scoring function — no second ad hoc ranking (docs/05 §1).
 */
public class SearchContextUseCase(
    private val search: SearchIndex,
    private val projects: ProjectRepository,
    private val clock: Clock,
    private val weights: RankingWeights,
    private val defaultLimit: Long,
) {
    public fun execute(input: SearchContextInput): SearchContextResult {
        val projectId =
            input.projectName?.let { name ->
                projects.findByName(name)?.id ?: throw NotFoundException("Project '$name' not found")
            }
        val limit = input.limit ?: defaultLimit
        val hits =
            search.search(
                SearchRequest(
                    query = input.query,
                    projectId = projectId,
                    type = input.type,
                    tag = input.tag?.trim()?.lowercase(),
                    from = input.from,
                    to = input.to,
                    limit = limit,
                ),
            )
        val query =
            HydrationQuery(
                projectId = projectId.orEmpty(),
                tags = setOfNotNull(input.tag?.trim()?.lowercase()),
                now = clock.now(),
            )
        val items =
            hits
                .map { hit ->
                    SearchResultItem(
                        entry = hit.entry.toBrief(),
                        projectName = hit.projectName,
                        toolName = hit.toolName,
                        score = Scoring.scoreEntry(RankableItem.fromEntry(hit.entry), query, weights),
                    )
                }.sortedByDescending { it.score }
        return SearchContextResult(items = items, totalShown = items.size, limitApplied = limit)
    }
}
