package com.jarvis.agent

import android.util.Log

enum class FailureType {
    INVALID_INPUT,
    TOOL_NOT_FOUND,
    APP_NOT_INSTALLED,
    PERMISSION_DENIED,
    NETWORK_ERROR,
    TARGET_NOT_FOUND,
    ACTION_NOT_CONFIRMED,
    VERIFICATION_FAILED,
    AMBIGUOUS_TARGET,
    TIMEOUT,
    UNKNOWN
}

enum class RecoveryStrategy {
    RETRY_SAME,
    CORRECT_PARAMETERS,
    SELECT_ALTERNATIVE,
    USE_ALTERNATE_TOOL,
    REOPEN_APP,
    RESEARCH_AGAIN,
    RESET_CURRENT_STEP,
    REPLAN,
    ASK_USER,
    ABORT
}

data class RecoveryPlan(
    val strategy: RecoveryStrategy,
    val modifiedAction: AgentAction? = null,
    val explanation: String
)

class AgentRecoveryManager {
    private val TAG = "AgentRecoveryManager"

    fun classifyFailure(observation: AgentObservation): FailureType {
        val msg = observation.message.lowercase()
        return when {
            msg.contains("permission") -> FailureType.PERMISSION_DENIED
            msg.contains("not installed") || msg.contains("no app") -> FailureType.APP_NOT_INSTALLED
            msg.contains("timeout") || msg.contains("timed out") -> FailureType.TIMEOUT
            msg.contains("not registered") || msg.contains("tool not found") -> FailureType.TOOL_NOT_FOUND
            msg.contains("network") || msg.contains("connection") || msg.contains("offline") -> FailureType.NETWORK_ERROR
            msg.contains("ambiguous") || msg.contains("multiple") -> FailureType.AMBIGUOUS_TARGET
            msg.contains("parameter") || msg.contains("missing") -> FailureType.INVALID_INPUT
            msg.contains("verification") || msg.contains("not confirmed") -> FailureType.VERIFICATION_FAILED
            else -> FailureType.UNKNOWN
        }
    }

    fun determineRecovery(
        failedAction: AgentAction,
        observation: AgentObservation,
        state: AgentState,
        workingMemory: AgentWorkingMemory? = null
    ): RecoveryPlan {
        val failureType = classifyFailure(observation)
        Log.i(TAG, "Determining recovery for $failedAction (Failure: $failureType, Attempt: ${failedAction.attempt})")

        // 1. App not installed -> try alternate app if available
        if (failureType == FailureType.APP_NOT_INSTALLED) {
            val app = failedAction.params["app"]?.lowercase() ?: ""
            if (app in listOf("ymusic", "music", "spotify")) {
                return RecoveryPlan(
                    strategy = RecoveryStrategy.USE_ALTERNATE_TOOL,
                    modifiedAction = AgentAction(
                        type = "YOUTUBE_PLAY",
                        params = mapOf("query" to (failedAction.params["query"] ?: "music")),
                        expectedOutcome = "fallback playback on YouTube"
                    ),
                    explanation = "Default music player not found, falling back to YouTube."
                )
            }
        }

        // 2. Playback / media verification failure -> select next candidate or alternate playback tool
        if (failedAction.type in listOf("YOUTUBE_PLAY", "MUSIC_PLAY", "YMUSIC_PLAY", "PLAY_MUSIC")) {
            val currentQuery = failedAction.params["query"].orEmpty()
            val candidates = workingMemory?.candidates ?: emptyList()
            val nextCandidate = candidates.firstOrNull { !it.contains(currentQuery, ignoreCase = true) && !currentQuery.contains(it, ignoreCase = true) }

            if (nextCandidate != null && state.failureCount <= 2) {
                val cleanCandidate = nextCandidate.substringBefore("(").trim()
                return RecoveryPlan(
                    strategy = RecoveryStrategy.SELECT_ALTERNATIVE,
                    modifiedAction = AgentAction(
                        type = "YOUTUBE_PLAY",
                        params = mapOf("query" to cleanCandidate),
                        expectedOutcome = "Playing alternative candidate: $cleanCandidate"
                    ),
                    explanation = "Playback failed for '$currentQuery'. Switching to alternative candidate: $cleanCandidate"
                )
            }

            if (failedAction.type == "YMUSIC_PLAY" || failedAction.type == "MUSIC_PLAY") {
                return RecoveryPlan(
                    strategy = RecoveryStrategy.USE_ALTERNATE_TOOL,
                    modifiedAction = AgentAction(
                        type = "YOUTUBE_PLAY",
                        params = mapOf("query" to currentQuery.ifBlank { "popular music" }),
                        expectedOutcome = "Playing on YouTube fallback"
                    ),
                    explanation = "Local music player playback failed, falling back to YouTube."
                )
            }
        }

        // 3. Ambiguous target -> ask user
        if (failureType == FailureType.AMBIGUOUS_TARGET) {
            return RecoveryPlan(
                strategy = RecoveryStrategy.ASK_USER,
                explanation = "Multiple matches found. Clarification needed."
            )
        }

        // 4. Transient timeout -> retry up to 2 times
        if (failureType == FailureType.TIMEOUT && failedAction.attempt < 2) {
            return RecoveryPlan(
                strategy = RecoveryStrategy.RETRY_SAME,
                modifiedAction = failedAction.copy(attempt = failedAction.attempt + 1),
                explanation = "Transient timeout detected, retrying step."
            )
        }

        // 5. Default: Replan with alternate strategy
        if (state.failureCount < 2) {
            return RecoveryPlan(
                strategy = RecoveryStrategy.REPLAN,
                explanation = "Attempting alternate planning strategy after failure."
            )
        }

        // 6. Exceeded failures: abort gracefully
        return RecoveryPlan(
            strategy = RecoveryStrategy.ABORT,
            explanation = "Exceeded recovery retry limit. Aborting task safely."
        )
    }
}
