package com.jarvis.agent

/**
 * Autonomous Tool Execution & Safety Loop Guard Engine for Raphael.
 *
 * Enforces iteration bounds, tool call limits, consecutive tool failure circuit breaking,
 * and multi-step N-Gram action cycle detection to prevent deadlocks and unconstrained loops.
 */
enum class GuardRecommendation {
    CONTINUE,
    CIRCUIT_BREAK_TOOL,
    FORCE_FINAL_RESPONSE,
    STOP_AGENT
}

class AgentLoopGuard(
    val maxIterations: Int = 12,
    val maxToolCalls: Int = 20,
    val maxSameActionRepetitions: Int = 3,
    val maxConsecutiveFailures: Int = 3,
    val historyWindowSize: Int = 12,
    startingIteration: Int = 0
) {
    private var iterations = startingIteration.coerceAtLeast(0)
    private var toolCalls = 0
    private var lastActionHash: String? = null
    private var sameActionCount = 0

    // History tracking for n-gram cycle detection
    private val actionHistory = mutableListOf<String>()

    // Consecutive failure tracking per action type
    private val consecutiveFailures = mutableMapOf<String, Int>()
    private val brokenCircuits = mutableSetOf<String>()

    sealed class GuardCheckResult {
        object Allowed : GuardCheckResult()
        data class Exceeded(
            val reason: String,
            val recommendation: GuardRecommendation = GuardRecommendation.STOP_AGENT,
            val failingTool: String? = null
        ) : GuardCheckResult()
    }

    fun checkNextIteration(): GuardCheckResult {
        return if (iterations + 1 > maxIterations) {
            GuardCheckResult.Exceeded(
                reason = "Agent iteration limit reached (${iterations + 1}/$maxIterations)",
                recommendation = GuardRecommendation.FORCE_FINAL_RESPONSE
            )
        } else {
            GuardCheckResult.Allowed
        }
    }

    fun checkToolCall(actionType: String, params: Map<String, String>): GuardCheckResult {
        // 1. Circuit breaker check
        if (brokenCircuits.contains(actionType)) {
            return GuardCheckResult.Exceeded(
                reason = "Circuit breaker open for tool '$actionType' due to $maxConsecutiveFailures consecutive failures.",
                recommendation = GuardRecommendation.CIRCUIT_BREAK_TOOL,
                failingTool = actionType
            )
        }

        // 2. Max tool call limit check
        if (toolCalls + 1 > maxToolCalls) {
            return GuardCheckResult.Exceeded(
                reason = "Agent tool-call limit reached (${toolCalls + 1}/$maxToolCalls)",
                recommendation = GuardRecommendation.FORCE_FINAL_RESPONSE
            )
        }

        val actionHash = computeActionHash(actionType, params)

        // 3. Immediate single-action repetition check
        if (actionHash == lastActionHash && sameActionCount + 1 > maxSameActionRepetitions) {
            return GuardCheckResult.Exceeded(
                reason = "Agent detected infinite repetitive action loop on: $actionType",
                recommendation = GuardRecommendation.STOP_AGENT,
                failingTool = actionType
            )
        }

        // 4. Multi-step N-Gram cycle detection (2-gram & 3-gram patterns)
        val prospectiveHistory = actionHistory.toMutableList().apply { add(actionHash) }
        val cycleDetected = detectNGramCycle(prospectiveHistory)
        if (cycleDetected != null) {
            return GuardCheckResult.Exceeded(
                reason = "Agent detected multi-step oscillatory cycle pattern (${cycleDetected.first}-gram loop): ${cycleDetected.second}",
                recommendation = GuardRecommendation.STOP_AGENT,
                failingTool = actionType
            )
        }

        return GuardCheckResult.Allowed
    }

    fun nextIteration() {
        iterations++
        // No require() here — checkNextIteration() enforces the limit before this is called.
        // require() would throw IllegalArgumentException, bypassing GuardCheckResult error handling.
        if (iterations > maxIterations) {
            android.util.Log.w("AgentLoopGuard", "nextIteration() called past limit ($iterations/$maxIterations) — guard check may have been skipped")
        }
    }

    fun recordToolCall(actionType: String, params: Map<String, String>) {
        toolCalls++
        // No require() — checkToolCall() enforces limits. require() here caused unhandled crashes.
        if (toolCalls > maxToolCalls) {
            android.util.Log.w("AgentLoopGuard", "recordToolCall() called past tool limit ($toolCalls/$maxToolCalls)")
        }

        val actionHash = computeActionHash(actionType, params)
        actionHistory.add(actionHash)
        if (actionHistory.size > historyWindowSize) {
            actionHistory.removeAt(0)
        }

        if (actionHash == lastActionHash) {
            sameActionCount++
            // No require() — checkToolCall() detects repetition before recordToolCall() is called.
            if (sameActionCount > maxSameActionRepetitions) {
                android.util.Log.w("AgentLoopGuard", "recordToolCall() repetition past limit for: $actionType")
            }
        } else {
            lastActionHash = actionHash
            sameActionCount = 1
        }
    }

    fun recordToolFailure(actionType: String, error: String = ""): GuardCheckResult {
        val currentCount = (consecutiveFailures[actionType] ?: 0) + 1
        consecutiveFailures[actionType] = currentCount

        return if (currentCount >= maxConsecutiveFailures) {
            brokenCircuits.add(actionType)
            GuardCheckResult.Exceeded(
                reason = "Tool '$actionType' failed $currentCount times consecutively ($error). Circuit breaker tripped.",
                recommendation = GuardRecommendation.CIRCUIT_BREAK_TOOL,
                failingTool = actionType
            )
        } else {
            GuardCheckResult.Allowed
        }
    }

    fun recordToolSuccess(actionType: String) {
        consecutiveFailures.remove(actionType)
    }

    fun isCircuitOpen(actionType: String): Boolean = brokenCircuits.contains(actionType)

    fun currentIteration(): Int = iterations
    fun currentToolCalls(): Int = toolCalls
    fun getBrokenCircuits(): Set<String> = brokenCircuits.toSet()

    private fun computeActionHash(actionType: String, params: Map<String, String>): String {
        val sortedParams = params.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }
        return "$actionType:$sortedParams"
    }

    private fun detectNGramCycle(history: List<String>): Pair<Int, String>? {
        if (history.size < 4) return null

        for (n in listOf(2, 3)) {
            if (history.size >= n * 2) {
                val len = history.size
                val sub1 = history.subList(len - n, len)
                val sub2 = history.subList(len - n * 2, len - n)
                if (sub1 == sub2) {
                    return Pair(n, sub1.joinToString(" -> "))
                }
            }
        }
        return null
    }
}
