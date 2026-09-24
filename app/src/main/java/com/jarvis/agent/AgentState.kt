package com.jarvis.agent

import com.jarvis.execution.VerificationResult
import com.jarvis.tools.ToolResult

enum class AgentStatus {
    CREATED,
    UNDERSTANDING,
    CONTEXT_BUILDING,
    DECIDING,
    EXECUTING,
    OBSERVING,
    VERIFYING,
    REPLANNING,
    WAITING_FOR_USER,
    WAITING_FOR_PERMISSION,
    COMPLETED,
    FAILED,
    CANCELLED
}

typealias PendingApproval = AgentAction

data class AgentState(
    val taskId: Long,
    val goal: String,
    var status: AgentStatus = AgentStatus.CREATED,
    var iteration: Int = 0,
    var currentObjective: String = "",
    var lastAction: AgentAction? = null,
    var lastObservation: AgentObservation? = null,
    var lastDecision: AgentDecision? = null,
    var goalSatisfied: Boolean = false,
    var failureCount: Int = 0,
    var completedSteps: Int = 0,
    var pendingApproval: PendingApproval? = null,
    val satisfiedCriteria: MutableSet<String> = mutableSetOf(),
    val executedActs: MutableMap<String, ExecutedAct> = mutableMapOf()
)

/**
 * Outcome of an action that already ran in this task loop. Lets the kernel
 * refuse to re-fire an identical action that was never confirmed, and skip
 * re-executing one that already verified (idempotency without side effects).
 */
data class ExecutedAct(
    val result: ToolResult,
    val observation: AgentObservation,
    val verification: VerificationResult
)
