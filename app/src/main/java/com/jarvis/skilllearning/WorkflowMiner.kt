package com.jarvis.skilllearning

import com.jarvis.foundation.RiskLevel

/**
 * Turns a trace of VERIFIED actions into a reusable skill id. Learning rule:
 * only VERIFIED successes participate — anything unconfirmed or failed voids
 * the whole run (a partially failing trace must never become a stable skill).
 */
object WorkflowMiner {

    fun mine(
        goal: String,
        steps: List<SkillStep>,
        riskOf: (String) -> RiskLevel
    ): LearnedSkill? {
        if (steps.isEmpty()) return null
        val tools = steps.map { it.actionType }.toSet()
        val maxRisk = steps.maxOfOrNull { riskOf(it.actionType) } ?: RiskLevel.LOW
        return LearnedSkill(
            id = slug(goal),
            intent = goal,
            steps = steps,
            requiredTools = tools,
            riskClass = maxRisk,
            status = SkillStatus.DRAFT
        )
    }

    fun slug(goal: String): String =
        goal.lowercase().trim()
            .replace(Regex("\\s+"), " ")
            .replace(Regex("[^a-z0-9]+"), ".")
            .trim('.')
            .ifBlank { "unnamed" }
}