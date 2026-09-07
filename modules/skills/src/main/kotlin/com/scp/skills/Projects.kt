package com.scp.skills

import com.scp.core.usecase.CreateProjectUseCase
import com.scp.core.usecase.ListProjectsUseCase
import com.scp.core.usecase.SaveNoteUseCase
import com.scp.core.usecase.UpdateProjectUseCase
import com.scp.model.mcp.CreateProjectInput
import com.scp.model.mcp.CreateProjectResult
import com.scp.model.mcp.ListProjectsResult
import com.scp.model.mcp.SaveNoteInput
import com.scp.model.mcp.SaveNoteResult
import com.scp.model.mcp.UpdateProjectInput
import com.scp.model.mcp.UpdateProjectResult

/** create_project — register a project so tools can write context against it. */
public class CreateProject(private val useCase: CreateProjectUseCase) {
    public fun execute(input: CreateProjectInput): CreateProjectResult =
        logged("create_project", input.name) { useCase.execute(input) }
}

/** list_projects — what exists, when it was last touched, how many sessions. */
public class ListProjects(private val useCase: ListProjectsUseCase) {
    public fun execute(): ListProjectsResult = logged("list_projects", null) { useCase.execute() }
}

/** save_note — quick mid-session capture without closing the session. */
public class SaveNote(private val useCase: SaveNoteUseCase) {
    public fun execute(input: SaveNoteInput): SaveNoteResult =
        logged("save_note", input.projectName) { useCase.execute(input) }
}

/** update_project — edit a project's description after creation. */
public class UpdateProject(private val useCase: UpdateProjectUseCase) {
    public fun execute(input: UpdateProjectInput): UpdateProjectResult =
        logged("update_project", input.projectName) { useCase.execute(input) }
}
