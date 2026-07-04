package com.scp.skills

import com.scp.core.usecase.SearchContextUseCase
import com.scp.model.mcp.SearchContextInput
import com.scp.model.mcp.SearchContextResult

/** /search-context — FTS5 full-text composed with structured filters. */
public class SearchContext(private val useCase: SearchContextUseCase) {
    public fun execute(input: SearchContextInput): SearchContextResult =
        logged("search_context", input.projectName) { useCase.execute(input) }
}
