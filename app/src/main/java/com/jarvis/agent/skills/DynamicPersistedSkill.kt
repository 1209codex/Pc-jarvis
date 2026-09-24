package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import com.jarvis.skills.db.PersistedSkill

/**
 * Dynamic Skill instance loaded from or saved to the PersistentSkillStore.
 * Allows newly learned or auto-updated procedural workflows to execute directly via the SkillRegistry.
 */
class DynamicPersistedSkill(
    val persisted: PersistedSkill
) : Skill {
    override val id: String = persisted.id
    override val name: String = persisted.name
    override val description: String = persisted.description
    override val triggers: List<String> = persisted.triggers

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        if (persisted.steps.any { it.actionType == "TOOL" || it.actionType.isBlank() }) return false
        val lower = goal.lowercase()
        return triggers.any { trigger -> lower.contains(trigger.lowercase()) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        if (persisted.steps.isEmpty()) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("SPEAK", mapOf("text" to "Executing skill $name")),
                explanation = "Executing dynamic skill '$name'"
            )
        }

        val first = persisted.steps[0]
        return SkillResult(
            handled = true,
            proposedAction = AgentAction(first.actionType, first.params),
            explanation = "Executing step 1/${persisted.steps.size} of dynamic skill '$name'"
        )
    }
}
