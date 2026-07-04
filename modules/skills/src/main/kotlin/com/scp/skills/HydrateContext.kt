package com.scp.skills

import com.scp.core.usecase.HydrateContextUseCase
import com.scp.model.HydrationPayload
import com.scp.model.mcp.HydrateContextInput

/** /hydrate-context — the ranked, token-budgeted resume payload. Never the full database. */
public class HydrateContext(private val useCase: HydrateContextUseCase) {
    public fun execute(input: HydrateContextInput): HydrationPayload =
        logged("hydrate_context", input.projectName) { useCase.execute(input) }
}
