package com.jarvis.ai

enum class ModelTier { LOCAL, STANDARD, STRONG }

data class Route(
    val tier: ModelTier,
    val model: String,
    val privacySensitive: Boolean
) {
    val usesCloud: Boolean get() = !privacySensitive && tier != ModelTier.LOCAL
}

class ModelRouter(
    private val defaultModel: String = LlmConfig.DEFAULT_MODEL,
    private val strongModel: String = LlmConfig.STRONG_MODEL,
    private val strongThresholdChars: Int = 180
) {

    fun route(utterance: String): Route {
        if (privacySensitive(utterance)) {
            return Route(ModelTier.LOCAL, "", privacySensitive = true)
        }
        val tier = classifyComplexity(utterance)
        val model = if (tier == ModelTier.STRONG) strongModel else defaultModel
        return Route(tier, model, privacySensitive = false)
    }

    fun privacySensitive(utterance: String): Boolean = com.jarvis.foundation.SensitiveTerms.contains(utterance)

    fun classifyComplexity(utterance: String): ModelTier {
        val low = utterance.lowercase()
        val multiStep = MULTI_STEP_MARKERS.any { low.contains(it) }
        val needsPlanning = PLANNING_VERBS.any { low.contains(it) }
        val verbose = utterance.length >= strongThresholdChars
        return if (multiStep || needsPlanning || verbose) ModelTier.STRONG else ModelTier.STANDARD
    }

    companion object {
        private val MULTI_STEP_MARKERS = listOf(
            "and then", "then ", "after that", "followed by", "before that",
            "finally", "first ", "next ", "phir", "uske baad", "firstly", "secondly"
        )

        private val PLANNING_VERBS = listOf(
            "plan", "coordinate", "organize", "arrange", "book", "schedule",
            "setup", "set up", "prepare for"
        )
    }
}