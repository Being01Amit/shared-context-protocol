package com.scp.skills

import com.scp.core.usecase.TimelineUseCase
import com.scp.model.mcp.TimelineInput
import com.scp.model.mcp.TimelineResult

/** /timeline — full chronological history; the escape hatch for anything hydration omitted. */
public class Timeline(private val useCase: TimelineUseCase) {
    public fun execute(input: TimelineInput): TimelineResult =
        logged("timeline", input.projectName) { useCase.execute(input) }
}
