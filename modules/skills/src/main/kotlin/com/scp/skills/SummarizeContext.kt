package com.scp.skills

import com.scp.core.usecase.ProjectSummaryUseCase
import com.scp.model.mcp.ProjectSummaryInput
import com.scp.model.mcp.ProjectSummaryResult

/** /project-summary — timeline statistics, major decisions, open work. */
public class SummarizeContext(private val useCase: ProjectSummaryUseCase) {
    public fun execute(input: ProjectSummaryInput): ProjectSummaryResult =
        logged("project_summary", input.projectName) { useCase.execute(input) }
}
