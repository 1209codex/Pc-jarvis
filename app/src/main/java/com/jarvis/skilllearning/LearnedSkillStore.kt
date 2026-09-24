package com.jarvis.skilllearning

import com.jarvis.agent.AgentAction
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.RiskLevel
import com.jarvis.ai.EmbeddingVectorizer

/**
 * Versioned store of learned procedural skills.
 *
 * - Version bumps when the step sequence changes; identical re-verified runs
 *   accumulate verifiedRuns and promote DRAFT -> VERIFIED -> STABLE.
 * - A STABLE skill is never overwritten by a weaker re-learn (the honest copy
 *   stays usable until the environment proves it invalid).
 * - Failures erode confidence; below threshold the skill is DEPRECATED so the
 *   planner stops replaying it (invalidation on environment change).
 *
 * ponytail: in-memory only. Persist to a file/SQLite when app restarts must
 * survive learning.
 */
class LearnedSkillStore(
    private val confidenceThreshold: Double = 0.5,
    private val stableThreshold: Double = 0.6,
    private val promotionRuns: Int = 3
) {
    private val skills = linkedMapOf<String, LearnedSkill>()
    private val vectorizer = EmbeddingVectorizer()

    fun all(): List<LearnedSkill> = skills.values.toList()

    fun get(id: String): LearnedSkill? = skills[id]

    /**
     * Learns a reusable workflow from this run's VERIFIED actions. Returns the
     * stored (possibly already existing) skill, or null when nothing was
     * safely learnable.
     */
    fun learnFromRun(
        goal: String,
        verifiedActions: List<Pair<AgentAction, VerificationResult>>,
        riskOf: (String) -> RiskLevel
    ): LearnedSkill? {
        if (verifiedActions.isEmpty()) return null
        if (verifiedActions.any { it.second.status != VerificationStatus.VERIFIED }) return null
        val steps = verifiedActions.map { SkillStep(it.first.type, it.first.params) }
        val mined = WorkflowMiner.mine(goal, steps, riskOf) ?: return null
        return upsert(mined)
    }

    /** Versioned upsert with promotion. Never overwrites STABLE blindly. */
    fun upsert(incoming: LearnedSkill): LearnedSkill {
        val existing = skills[incoming.id]
        if (existing == null) {
            incoming.status = SkillStatus.DRAFT
            skills[incoming.id] = incoming
            return incoming
        }
        if (existing.status == SkillStatus.STABLE && existing.verifiedRuns >= incoming.verifiedRuns) {
            return existing
        }
        val sameWorkflow = existing.steps.signatures() == incoming.steps.signatures()
        val merged = if (sameWorkflow) {
            val runs = existing.verifiedRuns + 1
            incoming.copy(
                version = existing.version,
                verifiedRuns = runs,
                failures = existing.failures,
                confidence = confidence(runs, existing.failures),
                status = statusFor(runs, existing.failures)
            )
        } else {
            incoming.copy(version = existing.version + 1, verifiedRuns = 1, failures = 0)
        }
        skills[incoming.id] = merged
        return merged
    }

    fun recordFailure(id: String, @Suppress("UNUSED_PARAMETER") note: String): LearnedSkill? {
        val skill = skills[id] ?: return null
        skill.failures += 1
        skill.confidence = confidence(skill.verifiedRuns, skill.failures)
        if (skill.confidence < confidenceThreshold) {
            skill.status = SkillStatus.DEPRECATED
        }
        return skill
    }

    fun invalidateByTool(toolName: String): List<LearnedSkill> {
        val affected = skills.values.filter {
            it.status != SkillStatus.DEPRECATED && toolName.uppercase() in it.requiredTools
        }
        affected.forEach { it.status = SkillStatus.DEPRECATED }
        return affected
    }

    /**
     * Replays a learned workflow step-by-step through the shared working-memory
     * facts. Each step only advances after the previous one was verified
     * (`_learned_ok = "1"`); an unverified step stalls replay so real planning
     * takes over, and the session key is cleared when the workflow finishes.
     */
    fun takeStep(goal: String, facts: MutableMap<String, String>): LearnedStepResult? {
        val activeId = facts["_learned_skill"]
        if (activeId != null) {
            if (facts["_learned_ok"] != "1") return null
            val skill = skills[activeId] ?: clearSession(facts).let { return null }
            facts["_learned_ok"] = "0"
            val idx = facts["_learned_step"]?.toIntOrNull() ?: 0
            val step = skill.steps.getOrNull(idx)
            if (step == null) {
                clearSession(facts)
                return null
            }
            facts["_learned_step"] = (idx + 1).toString()
            return LearnedStepResult(AgentAction(step.actionType, step.params), skill.id, idx, skill.steps.size)
        }

        val match = bestFor(goal) ?: return null
        if (match.steps.isEmpty()) return null
        facts["_learned_skill"] = match.id
        facts["_learned_step"] = "1"
        facts["_learned_ok"] = "0"
        val first = match.steps[0]
        return LearnedStepResult(AgentAction(first.actionType, first.params), match.id, 0, match.steps.size)
    }

    /** Marks the running learned step verified so the workflow may advance. */
    fun confirmStepVerified(facts: MutableMap<String, String>) {
        if (facts.containsKey("_learned_skill")) facts["_learned_ok"] = "1"
    }

    /** Abandons learned replay, leaving normal planning in control. */
    fun abandon(facts: MutableMap<String, String>) {
        clearSession(facts)
    }

    private fun bestFor(goal: String): LearnedSkill? {
        val replayable = skills.values.filter { it.replayable }
        if (replayable.isEmpty()) return null
        val goalEmbedding = vectorizer.embed(goal)
        return replayable.maxByOrNull {
            vectorizer.cosine(goalEmbedding, vectorizer.embed(it.intent))
        }?.takeIf {
            vectorizer.cosine(goalEmbedding, vectorizer.embed(it.intent)) >= 0.45
        }
    }

    private fun statusFor(runs: Int, failures: Int): SkillStatus {
        val conf = confidence(runs, failures)
        return when {
            conf < confidenceThreshold -> SkillStatus.DISCOVERED
            runs >= promotionRuns -> if (conf >= stableThreshold) SkillStatus.STABLE else SkillStatus.VERIFIED
            runs >= 2 -> SkillStatus.VERIFIED
            else -> SkillStatus.DRAFT
        }
    }

    private fun confidence(runs: Int, failures: Int): Double {
        val total = (runs + failures).coerceAtLeast(1)
        return runs.toDouble() / total
    }

    private fun clearSession(facts: MutableMap<String, String>) {
        facts.remove("_learned_skill")
        facts.remove("_learned_step")
        facts.remove("_learned_ok")
    }

    private fun List<SkillStep>.signatures(): List<String> = map { it.signature() }
}