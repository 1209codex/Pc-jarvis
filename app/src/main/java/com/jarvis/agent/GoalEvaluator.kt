package com.jarvis.agent

import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus

data class GoalEvaluation(
    val satisfied: Boolean,
    val confidence: Float,
    val missingRequirements: List<String> = emptyList(),
    val reason: String
)

class GoalEvaluator {

    private val mediaPlayTypes =
        listOf("YOUTUBE_PLAY", "MUSIC_PLAY", "YMUSIC_PLAY", "PLAY_MUSIC")
    private val stopTypes =
        listOf("MEDIA_STOP", "MUSIC_STOP", "STOP_MEDIA", "CLOSE_APP", "APPS_CLOSE_ALL", "CLOSE_ALL_APPS")
    private val commTypes = listOf("WHATSAPP", "WHATSAPP_SEND")

    fun satisfiedBy(
        action: AgentAction?,
        verification: VerificationResult,
        criterion: String
    ): Boolean {
        if (action == null) return false
        // Only a communication DISPATCH may satisfy without VERIFIED (delivery is not observable);
        // everything else requires an explicit verified outcome.
        val dispatchAllowed = verification.status == VerificationStatus.UNKNOWN &&
                verification.evidence["outcome"] == "DISPATCHED"
        if (!verification.verified && !dispatchAllowed) return false

        val type = action.type.uppercase()
        val lower = criterion.lowercase().trim()

        return when {
            lower.contains("open") || lower.contains("launch") || lower.contains("kholo") ->
                type == "OPEN_APP" && verification.verified
            lower.contains("play") || lower.contains("music") || lower.contains("song") ||
            lower.contains("video") || lower.contains("chalao") || lower.contains("bajao") ->
                type in mediaPlayTypes && (verification.verified || (verification.status == VerificationStatus.UNKNOWN && verification.evidence["outcome"] == "SEARCH_OPENED"))
            lower.contains("message") || lower.contains("whatsapp") ||
            lower.contains("bhejo") || lower.contains("send") ->
                type in commTypes && (verification.verified || commDispatched(action, verification))
            lower.contains("call") || lower.contains("telephony") || lower.contains("dial") ->
                type == "TELEPHONY_CONTROL" && verification.verified
            lower.contains("search") || lower.contains("research") || lower.contains("candidate") ->
                type in listOf("SEARCH_WEB", "SEARCH") && verification.verified
            lower.contains("stop") || lower.contains("band") ->
                type in stopTypes && verification.verified
            else -> type.isNotBlank() && verification.verified
        }
    }

    fun evaluate(
        goal: String,
        state: AgentState,
        observation: AgentObservation,
        verification: VerificationResult
    ): GoalEvaluation {
        if (!observation.success) {
            return GoalEvaluation(
                satisfied = false,
                confidence = 0.95f,
                missingRequirements = listOf("Action failed: ${observation.message}"),
                reason = "Action execution failed: ${observation.message}"
            )
        }

        val lastAction = state.lastAction
        val actionType = lastAction?.type?.uppercase().orEmpty()

        // 1. Explicit success criteria: each requirement must be satisfied by its OWN
        //    verified action. `completedSteps > 0` must never stand in for a criterion.
        val criteria = state.lastDecision?.successCriteria?.map { it.trim() }?.filter { it.isNotBlank() }.orEmpty()
        if (criteria.isNotEmpty()) {
            val unmet = criteria.filter { crit ->
                val key = crit.lowercase().trim()
                key !in state.satisfiedCriteria && !satisfiedBy(lastAction, verification, crit)
            }
            if (unmet.isEmpty()) {
                return GoalEvaluation(
                    satisfied = true,
                    confidence = 0.95f,
                    reason = "All goal requirements independently verified: ${criteria.joinToString()}"
                )
            }
            return GoalEvaluation(
                satisfied = false,
                confidence = 0.4f,
                missingRequirements = unmet,
                reason = "Unmet success criteria: ${unmet.joinToString("; ")}"
            )
        }

        // 2. Multi-step goal check when no explicit criteria given
        val isMultiStep = lowerGoal(goal).contains(" aur ") || lowerGoal(goal).contains(" and ") ||
                lowerGoal(goal).contains(" phir ") || lowerGoal(goal).contains(" then ") ||
                (lowerGoal(goal).contains("kholo") && (lowerGoal(goal).contains("chalao") || lowerGoal(goal).contains("bhejo") || lowerGoal(goal).contains("bajao") || lowerGoal(goal).contains("play")))

        if (actionType == "OPEN_APP") {
            val appOpened = verification.status == VerificationStatus.VERIFIED
            val goalOnlyWantedAppOpen = lowerGoal(goal).startsWith("open ") || lowerGoal(goal).startsWith("launch ") ||
                    lowerGoal(goal).endsWith(" kholo") || lowerGoal(goal).startsWith("kholo ") || !isMultiStep

            if (goalOnlyWantedAppOpen) {
                return if (appOpened) {
                    GoalEvaluation(satisfied = true, confidence = 0.95f, reason = "Target app opened as requested")
                } else {
                    GoalEvaluation(satisfied = false, confidence = 0.8f, missingRequirements = listOf("App launch unconfirmed"), reason = "App open pending verification")
                }
            } else {
                // Multi-step: Opening the app is just an intermediate step!
                return GoalEvaluation(
                    satisfied = false,
                    confidence = 0.9f,
                    missingRequirements = listOf("Subsequent actions (playback/messaging/search) required to satisfy '$goal'"),
                    reason = "App opened, but subsequent goal objectives remain pending"
                )
            }
        }

        // 3. Playback goals — Verified media playback or successful search intent dispatch
        if (actionType in mediaPlayTypes) {
            return if (verification.verified) {
                GoalEvaluation(
                    satisfied = true,
                    confidence = 0.95f,
                    reason = "Requested media playback is active and verified on device"
                )
            } else if (observation.success && verification.evidence["outcome"] == "SEARCH_OPENED") {
                GoalEvaluation(
                    satisfied = true,
                    confidence = 0.85f,
                    reason = "Media playback / search intent dispatched successfully: ${observation.message}"
                )
            } else {
                GoalEvaluation(
                    satisfied = false,
                    confidence = 0.35f,
                    missingRequirements = listOf("Media playback audio stream unconfirmed on device"),
                    reason = "Playback intent dispatched, but audio output is not confirmed: ${verification.message}"
                )
            }
        }

        // 4. Media stop goals
        if (actionType in stopTypes) {
            return if (verification.verified) {
                GoalEvaluation(satisfied = true, confidence = 1.0f, reason = "Media playback stopped successfully")
            } else {
                GoalEvaluation(satisfied = false, confidence = 0.4f, missingRequirements = listOf("Media playback still running"), reason = "Media stop unverified")
            }
        }

        // 5. Communication / Message goals — dispatch is observable, delivery is not.
        if (actionType in commTypes) {
            val hasRecipient = lastAction?.params?.get("recipient")?.isNotBlank() == true
            return when {
                !hasRecipient ->
                    GoalEvaluation(satisfied = false, confidence = 0.8f, missingRequirements = listOf("Recipient clarification needed"), reason = "Recipient missing")
                commDispatched(lastAction, verification) ->
                    GoalEvaluation(
                        satisfied = true,
                        confidence = 0.6f,
                        reason = "Message dispatched to recipient (delivery unconfirmed on this platform)"
                    )
                else ->
                    GoalEvaluation(satisfied = false, confidence = 0.5f, missingRequirements = listOf("Message dispatch not confirmed"), reason = "Message dispatch unconfirmed")
            }
        }

        // 6. Research / Search goals
        if (actionType in listOf("SEARCH_WEB", "SEARCH")) {
            val goalWantedOnlySearch = lowerGoal(goal).startsWith("search") || lowerGoal(goal).contains("dhoondo") || lowerGoal(goal).contains("pata karo") || lowerGoal(goal).startsWith("who is") || lowerGoal(goal).startsWith("kya hai")
            return if (goalWantedOnlySearch && !isMultiStep && verification.verified) {
                GoalEvaluation(satisfied = true, confidence = 0.9f, reason = "Web research completed with extracted results")
            } else {
                GoalEvaluation(satisfied = false, confidence = 0.85f, missingRequirements = listOf("Process search candidates into final action"), reason = "Search candidates retrieved, awaiting final action execution")
            }
        }

        // 7. Memory and Note goals
        if (actionType in listOf("NOTE", "CALCULATE", "SPEAK")) {
            return GoalEvaluation(
                satisfied = verification.verified,
                confidence = if (verification.verified) 0.95f else 0.4f,
                reason = "Action '$actionType' completion: verified=${verification.verified}"
            )
        }

        return GoalEvaluation(
            satisfied = verification.verified,
            confidence = if (verification.verified) 0.85f else 0.4f,
            missingRequirements = if (verification.verified) emptyList() else listOf("Verification status unknown"),
            reason = "Default goal evaluation: verified=${verification.verified}"
        )
    }

    private fun lowerGoal(goal: String): String = goal.lowercase().trim()

    /**
     * A message is deliverable if the tool dispatch succeeded (observable) even when
     * the platform cannot confirm actual delivery. This is the ONLY case where an
     * action can satisfy its goal without [VerificationStatus.VERIFIED]; the
     * [VerificationResult] must itself carry outcome="DISPATCHED" so the honest
     * unconfirmed status is never relabelled as VERIFIED.
     */
    private fun commDispatched(action: AgentAction?, verification: VerificationResult): Boolean {
        if (action == null) return false
        if (action.type.uppercase() !in commTypes) return false
        if (action.params["recipient"]?.isNotBlank() != true) return false
        return verification.verified ||
                (verification.status == VerificationStatus.UNKNOWN &&
                 verification.evidence["outcome"] == "DISPATCHED")
    }
}