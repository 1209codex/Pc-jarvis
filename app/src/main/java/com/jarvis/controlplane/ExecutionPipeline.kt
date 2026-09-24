package com.jarvis.controlplane

import android.util.Log
import com.jarvis.execution.VerificationResult
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolResult

data class GoalExecutionResult(
    val goalId: String,
    val success: Boolean,
    val toolResult: ToolResult,
    val verification: VerificationResult,
    val rolledBack: Boolean = false
)

/**
 * Standard execution pipeline enforcing:
 * prepare -> checkPreconditions -> execute -> observe -> verify -> commit -> repair/rollback.
 */
class ExecutionPipeline(
    private val worldStore: WorldStateStore = WorldStateStore.shared,
    private val guardian: DeviceGuardian = DeviceGuardian(worldStore),
    private val verifier: OutcomeVerifier = OutcomeVerifier(),
    private val eventBus: JarvisEventBus = JarvisEventBus.shared,
    private val goalManager: GoalManager = GoalManager.shared
) {
    private val TAG = "ExecutionPipeline"

    suspend fun executeGoal(goal: Goal, executor: ToolExecutor): GoalExecutionResult {
        Log.i(TAG, "Starting goal execution: '${goal.id}' (${goal.actionType})")
        goalManager.updateStatus(goal.id, GoalStatus.RUNNING)

        // 1. Prepare: capture initial state
        val beforeState = worldStore.current

        // 2. Check Preconditions & Invariants
        val requiresNetwork = goal.constraints["requires_network"]?.equals("true", ignoreCase = true) ?: false
        val requiresAudio = goal.constraints["requires_audio"]?.equals("true", ignoreCase = true) ?: false
        val verdict = guardian.canExecuteAutonomousTask(requiresNetwork, requiresAudio)
        if (!verdict.allowed) {
            Log.w(TAG, "Goal '${goal.id}' blocked by guardian: ${verdict.reason}")
            val failedResult = ToolResult.Failed(verdict.reason)
            goalManager.updateStatus(goal.id, GoalStatus.FAILED, verdict.reason, false)
            return GoalExecutionResult(
                goalId = goal.id,
                success = false,
                toolResult = failedResult,
                verification = VerificationResult.failure(verdict.reason)
            )
        }

        // 3. Execute
        val toolResult = try {
            executor.execute(goal.actionType, goal.params, userApprovalGranted = true)
        } catch (e: Exception) {
            Log.e(TAG, "Exception during tool execution for goal '${goal.id}'", e)
            ToolResult.Failed("Execution error: ${e.message}")
        }

        // 4. Observe: capture post-execution state
        val afterState = worldStore.current

        // 5. Verify outcome
        val verification = verifier.verifyStateTransition(
            actionType = goal.actionType,
            params = goal.params,
            result = toolResult,
            before = beforeState,
            after = afterState
        )

        // 6. Commit or Rollback
        var rolledBack = false
        if (!verification.verified && goal.rollbackAction != null) {
            Log.w(TAG, "Verification failed for goal '${goal.id}'. Triggering rollback action.")
            try {
                goal.rollbackAction.invoke()
                rolledBack = true
                goalManager.updateStatus(goal.id, GoalStatus.ROLLED_BACK, "Rolled back after failed verification: ${verification.message}")
            } catch (e: Exception) {
                Log.e(TAG, "Rollback failed for goal '${goal.id}'", e)
            }
        } else if (verification.verified) {
            goalManager.updateStatus(goal.id, GoalStatus.COMPLETED, toolResult.message, true)
            worldStore.update { it.copy(lastVerifiedAction = goal.actionType) }
        } else {
            goalManager.updateStatus(goal.id, GoalStatus.FAILED, toolResult.message, false)
        }

        // Emit completion event
        eventBus.post(
            JarvisEvent.TaskCompleted(
                taskId = goal.id,
                verified = verification.verified,
                message = toolResult.message
            )
        )

        return GoalExecutionResult(
            goalId = goal.id,
            success = toolResult.success && verification.verified,
            toolResult = toolResult,
            verification = verification,
            rolledBack = rolledBack
        )
    }
}
