package com.scp.core.usecase

import com.scp.model.NotFoundException
import com.scp.model.mcp.UpdateDecisionStatusInput
import com.scp.model.mcp.UpdateDecisionStatusResult
import com.scp.model.port.Clock
import com.scp.model.port.DecisionRepository
import com.scp.model.port.ProjectRepository
import com.scp.model.port.TransactionRunner

/** Marks a decision's lifecycle status (open/accepted/superseded/rejected). No state machine enforced. */
public class UpdateDecisionStatusUseCase(
    private val projects: ProjectRepository,
    private val decisions: DecisionRepository,
    private val transactions: TransactionRunner,
    private val clock: Clock,
) {
    public fun execute(input: UpdateDecisionStatusInput): UpdateDecisionStatusResult {
        val project =
            projects.findByName(input.projectName)
                ?: throw NotFoundException("Project '${input.projectName}' not found")

        return transactions.inWriteTransaction {
            decisions.listByProject(project.id).firstOrNull { it.id == input.decisionId }
                ?: throw NotFoundException("Decision '${input.decisionId}' not found in project '${input.projectName}'")

            val now = clock.now()
            decisions.updateStatus(input.decisionId, input.status, now)
            projects.touch(project.id, now)

            UpdateDecisionStatusResult(decisionId = input.decisionId, status = input.status, updatedAt = now)
        }
    }
}
