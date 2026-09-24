package com.jarvis.skilllearning

import com.jarvis.agent.AgentAction
import com.jarvis.foundation.RiskLevel

enum class SkillStatus { DISCOVERED, DRAFT, VERIFIED, STABLE, DEPRECATED }

data class SkillStep(
    val actionType: String,
    val params: Map<String, String>
) {
    fun signature(): String = "$actionType:${params.toSortedMap()}"
}

data class LearnedSkill(
    val id: String,
    val intent: String,
    val steps: List<SkillStep>,
    val requiredTools: Set<String>,
    val riskClass: RiskLevel,
    val version: Int = 1,
    var status: SkillStatus = SkillStatus.DISCOVERED,
    var confidence: Double = 1.0,
    var verifiedRuns: Int = 1,
    var failures: Int = 0,
    var lastVerifiedAt: Long? = null
) {
    val replayable: Boolean
        get() = (status == SkillStatus.VERIFIED || status == SkillStatus.STABLE) && steps.isNotEmpty()
}

/** Result of advancing a learned workflow by one step. */
data class LearnedStepResult(
    val action: AgentAction,
    val skillId: String,
    val stepIndex: Int,
    val totalSteps: Int
)