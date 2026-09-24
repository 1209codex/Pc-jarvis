package com.jarvis.controlplane

import com.jarvis.foundation.AutonomyTier
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class GoalStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    ROLLED_BACK,
    CANCELLED
}

data class Goal(
    val id: String = "goal_${UUID.randomUUID().toString().take(8)}",
    val objective: String,
    val actionType: String,
    val params: Map<String, String> = emptyMap(),
    val priority: Int = 0,
    val autonomyTier: AutonomyTier = AutonomyTier.AUTO,
    val constraints: Map<String, String> = emptyMap(),
    val triggerEvent: JarvisEvent? = null,
    val createdAt: Long = System.currentTimeMillis(),
    var status: GoalStatus = GoalStatus.PENDING,
    var resultMessage: String? = null,
    var isVerified: Boolean = false,
    val rollbackAction: (suspend () -> Unit)? = null
)

/**
 * Manages active and historical autonomous goals.
 */
class GoalManager {
    private val activeGoals = ConcurrentHashMap<String, Goal>()
    private val goalHistory = mutableListOf<Goal>()
    private val historyLock = Any()

    fun submitGoal(goal: Goal): String {
        activeGoals[goal.id] = goal
        return goal.id
    }

    fun getGoal(id: String): Goal? = activeGoals[id] ?: synchronized(historyLock) {
        goalHistory.find { it.id == id }
    }

    fun getActiveGoals(): List<Goal> = activeGoals.values.toList().sortedByDescending { it.priority }

    fun updateStatus(id: String, status: GoalStatus, resultMessage: String? = null, verified: Boolean = false) {
        val goal = activeGoals[id] ?: return
        goal.status = status
        goal.resultMessage = resultMessage
        goal.isVerified = verified

        if (status in listOf(GoalStatus.COMPLETED, GoalStatus.FAILED, GoalStatus.ROLLED_BACK, GoalStatus.CANCELLED)) {
            activeGoals.remove(id)
            synchronized(historyLock) {
                goalHistory.add(0, goal)
                if (goalHistory.size > MAX_HISTORY) {
                    goalHistory.removeAt(goalHistory.lastIndex)
                }
            }
        }
    }

    fun getCompletedHistory(limit: Int = 20): List<Goal> = synchronized(historyLock) {
        goalHistory.take(limit)
    }

    companion object {
        const val MAX_HISTORY = 50
        val shared: GoalManager by lazy { GoalManager() }
    }
}
