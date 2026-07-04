package com.scp.skills

import com.scp.core.usecase.UpdateContextUseCase
import com.scp.model.mcp.UpdateContextInput
import com.scp.model.mcp.UpdateContextResult

/** /update-context — store a session's context, mirror to markdown, close unless keep_open. */
public class UpdateContext(private val useCase: UpdateContextUseCase) {
    public fun execute(input: UpdateContextInput): UpdateContextResult =
        logged("update_context", input.projectName) { useCase.execute(input) }
}
