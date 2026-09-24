package com.jarvis.skilllearning

import android.content.Context
import android.util.Log
import com.jarvis.agent.AgentAction
import com.jarvis.agent.skills.DynamicPersistedSkill
import com.jarvis.agent.skills.SkillRegistry
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.RiskLevel
import com.jarvis.skills.db.PersistedSkill
import com.jarvis.skills.db.PersistedSkillStep
import com.jarvis.skills.db.PersistedSkillType
import com.jarvis.skills.db.PersistentSkillStore

/**
 * Observes executed actions and multi-step tasks, detects novel patterns,
 * auto-evolves skills, persists them in the SQLite DB, and registers them dynamically into SkillRegistry.
 */
class AdaptiveSkillEvolution(
    private val context: Context,
    private val skillStore: PersistentSkillStore = PersistentSkillStore(context),
    private val skillRegistry: SkillRegistry? = null
) {
    private val TAG = "AdaptiveSkillEvolution"

    /**
     * Initializes registry with all skills persisted in the database.
     */
    fun loadPersistedSkillsIntoRegistry() {
        val registry = skillRegistry ?: return
        try {
            val persistedSkills = skillStore.getAllSkills(activeOnly = true)
            for (pSkill in persistedSkills) {
                if (pSkill.type != PersistedSkillType.BUILT_IN) {
                    registry.register(DynamicPersistedSkill(pSkill))
                    Log.d(TAG, "Loaded dynamic skill '${pSkill.id}' into SkillRegistry")
                }
            }
            Log.i(TAG, "Successfully loaded ${persistedSkills.size} skills from database.")
        } catch (e: Exception) {
            Log.e(TAG, "Error loading persisted skills into registry", e)
        }
    }

    /**
     * Learns or auto-updates a skill when a successful multi-step action sequence completes.
     */
    fun onTaskCompleted(
        goal: String,
        verifiedActions: List<Pair<AgentAction, VerificationResult>>
    ): PersistedSkill? {
        if (verifiedActions.isEmpty() || goal.isBlank()) return null
        if (verifiedActions.any { it.second.status != VerificationStatus.VERIFIED }) return null

        val skillId = generateSkillId(goal)
        val existing = skillStore.getSkill(skillId)

        val steps = verifiedActions.mapIndexed { index, pair ->
            PersistedSkillStep(
                stepIndex = index,
                actionType = pair.first.type,
                params = pair.first.params
            )
        }

        val triggers = mutableSetOf(
            goal.lowercase().trim(),
            goal.lowercase().replace(Regex("^(please|can you|could you)\\s+"), "").trim()
        ).filter { it.isNotBlank() }

        val evolved = if (existing != null) {
            val newVersion = existing.version + 1
            val newConfidence = ((existing.confidence * existing.version + 1.0) / newVersion).coerceIn(0.0, 1.0)
            existing.copy(
                version = newVersion,
                confidence = newConfidence,
                steps = steps,
                triggers = (existing.triggers + triggers).distinct(),
                updatedAt = System.currentTimeMillis()
            )
        } else {
            PersistedSkill(
                id = skillId,
                name = goal.take(40).replaceFirstChar { it.uppercase() },
                description = "Auto-evolved skill for: '$goal'",
                triggers = triggers,
                type = PersistedSkillType.DYNAMIC,
                version = 1,
                confidence = 1.0,
                status = "ACTIVE",
                riskLevel = RiskLevel.LOW,
                steps = steps
            )
        }

        skillStore.saveSkill(evolved)
        skillRegistry?.register(DynamicPersistedSkill(evolved))
        Log.i(TAG, "Auto-evolved skill '${evolved.id}' (v${evolved.version}) with ${steps.size} steps.")
        return evolved
    }

    private fun generateSkillId(goal: String): String {
        return "skill_" + goal.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(32)
    }
}
