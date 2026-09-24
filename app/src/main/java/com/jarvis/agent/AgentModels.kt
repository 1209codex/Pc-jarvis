package com.jarvis.agent

data class AgentAction(
    val type: String,
    val params: Map<String, String> = emptyMap(),
    val expectedOutcome: String? = null,
    val attempt: Int = 1
)

enum class DecisionType {
    ACT,
    RESEARCH,
    RETRIEVE_MEMORY,
    RETRIEVE_KNOWLEDGE,
    ASK_USER,
    RETRY,
    RECOVER,
    COMPLETE,
    FAIL
}

data class AgentDecision(
    val type: DecisionType,
    val action: AgentAction? = null,
    val question: String? = null,
    val reasonCode: String = "",
    val successCriteria: List<String> = emptyList(),
    val confidence: Float = 1.0f
)

sealed class AgentEvent {
    data object Thinking : AgentEvent()
    data object BuildingContext : AgentEvent()
    data class UsingMemory(val message: String) : AgentEvent()
    data class Researching(val query: String) : AgentEvent()
    data class Executing(val action: String) : AgentEvent()
    data class Verifying(val action: String) : AgentEvent()
    data class Recovering(val reason: String) : AgentEvent()
    data class WaitingForUser(val question: String) : AgentEvent()
    data class Completed(val message: String) : AgentEvent()
    data class Failed(val message: String) : AgentEvent()
    data class TaskStarted(val taskId: Long) : AgentEvent()
    data class Cancelled(val message: String) : AgentEvent()
}

data class AgentObservation(
    val source: String,
    val success: Boolean,
    val message: String,
    val data: Map<String, String> = emptyMap(),
    val confidence: Float = 1.0f
)

enum class AgentResultStatus {
    COMPLETED,
    FAILED,
    WAITING_FOR_USER,
    CANCELLED
}

data class AgentResult(
    val status: AgentResultStatus,
    val response: String,
    val taskId: Long,
    val iterations: Int,
    val steps: List<AgentObservation> = emptyList(),
    val failureReason: String? = null,
    val verified: Boolean = false,
    val verifiedActions: List<Pair<AgentAction, com.jarvis.execution.VerificationResult>> = emptyList()
) {
    val success: Boolean
        get() = status == AgentResultStatus.COMPLETED
}

data class AgentWorkingMemory(
    val goal: String,
    var currentObjective: String = "",
    val facts: MutableMap<String, String> = mutableMapOf(),
    val observations: MutableList<String> = mutableListOf(),
    val completedSteps: MutableList<String> = mutableListOf(),
    val failedSteps: MutableList<String> = mutableListOf(),
    val candidates: MutableList<String> = mutableListOf(),
    var activeApp: String? = null,
    var lastToolResult: String? = null,
    val subGoals: MutableList<String> = mutableListOf(),
    val completedSubGoals: MutableSet<Int> = mutableSetOf()
) {
    fun currentPendingSubGoalIndex(): Int = subGoals.indices.firstOrNull { it !in completedSubGoals } ?: -1

    fun currentPendingSubGoal(): String? {
        val idx = currentPendingSubGoalIndex()
        return if (idx != -1) subGoals[idx] else null
    }

    fun isAllSubGoalsCompleted(): Boolean =
        subGoals.isEmpty() || completedSubGoals.size >= subGoals.size
}
