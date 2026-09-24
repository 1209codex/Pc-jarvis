package com.jarvis.agent

import android.content.Context
import android.util.Log
import com.jarvis.agent.skills.DynamicPersistedSkill
import com.jarvis.agent.skills.SkillRegistry
import com.jarvis.foundation.RiskLevel
import com.jarvis.skills.db.PersistedSkill
import com.jarvis.skills.db.PersistedSkillStep
import com.jarvis.skills.db.PersistedSkillType
import com.jarvis.skills.db.PersistentSkillStore

/**
 * Proposed new skill discovered from long commands or explicit user teaching requests.
 */
data class ProposedSkillClarification(
    val skillId: String,
    val proposedName: String,
    val description: String,
    val suggestedTriggers: List<String>,
    val steps: List<PersistedSkillStep>,
    val clarificationPrompt: String
)

/**
 * Interactive Brain Skill Discovery & Expansion Engine.
 *
 * Proactively identifies new capabilities, routines, or workflows inside user commands,
 * formulates structured clarification queries to ask the user, and persists new skills
 * dynamically into the persistent skill database and runtime registry.
 */
class SkillDiscoveryEngine(
    private val context: Context? = null,
    private val skillStore: PersistentSkillStore? = null,
    private val skillRegistry: SkillRegistry? = null
) {
    private val TAG = "SkillDiscoveryEngine"

    /**
     * Proactively inspects a parsed complex command to see if it qualifies for new skill creation.
     */
    fun discoverProposedSkill(parsedCommand: ParsedComplexCommand): ProposedSkillClarification? {
        // 1. Explicit skill teaching intent (e.g. "teach jarvis a new skill called morning routine...")
        if (parsedCommand.isSkillTeachingIntent && parsedCommand.segments.isNotEmpty()) {
            val name = parsedCommand.proposedSkillName?.takeIf { it.isNotBlank() }
                ?: ("Routine " + parsedCommand.segments.first().text.take(20))
            val skillId = "skill_" + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(32)

            val steps = parsedCommand.segments.mapIndexed { idx, seg ->
                PersistedSkillStep(
                    stepIndex = idx,
                    actionType = seg.actionTypeGuess ?: "EXECUTE_INTENT",
                    params = seg.entityRefs + mapOf("goal" to seg.text)
                )
            }

            val triggers = (parsedCommand.suggestedTriggers + listOf(name.lowercase())).distinct()
            val stepSummary = steps.joinToString(" -> ") { "${it.actionType} (${it.params["goal"] ?: it.params["query"] ?: ""})" }
            val prompt = "I can learn this as a new brain skill called '$name' with steps: $stepSummary. Would you like me to save and activate it?"

            return ProposedSkillClarification(
                skillId = skillId,
                proposedName = name,
                description = "User taught skill for: ${parsedCommand.originalUtterance}",
                suggestedTriggers = triggers,
                steps = steps,
                clarificationPrompt = prompt
            )
        }

        // 2. High complexity multi-clause routines (>10 words and >= 3 distinct actionable steps)
        if (parsedCommand.isLongCommand && parsedCommand.segments.size >= 3) {
            val name = "MultiStep_" + parsedCommand.segments.first().text.take(15).replace(Regex("[^a-zA-Z0-9]"), "")
            val skillId = "skill_" + name.lowercase().take(32)

            val steps = parsedCommand.segments.mapIndexed { idx, seg ->
                PersistedSkillStep(
                    stepIndex = idx,
                    actionType = seg.actionTypeGuess ?: "EXECUTE_INTENT",
                    params = seg.entityRefs + mapOf("goal" to seg.text)
                )
            }

            val triggers = listOf(parsedCommand.originalUtterance.lowercase().take(50))
            val stepSummary = steps.joinToString(" -> ") { it.actionType }
            val prompt = "You frequently use complex workflows like this ($stepSummary). Would you like me to remember this as a permanent brain skill '$name'?"

            return ProposedSkillClarification(
                skillId = skillId,
                proposedName = name,
                description = "Auto-discovered complex workflow: ${parsedCommand.originalUtterance.take(60)}",
                suggestedTriggers = triggers,
                steps = steps,
                clarificationPrompt = prompt
            )
        }

        return null
    }

    /**
     * Persists the user-approved skill and registers it live in SkillRegistry.
     */
    fun persistDiscoveredSkill(
        proposal: ProposedSkillClarification,
        userApproved: Boolean = true,
        customTrigger: String? = null
    ): PersistedSkill? {
        if (!userApproved) {
            Log.i(TAG, "Skill proposal '${proposal.proposedName}' was declined by user.")
            return null
        }

        val triggers = if (customTrigger != null && customTrigger.isNotBlank()) {
            (proposal.suggestedTriggers + customTrigger.lowercase().trim()).distinct()
        } else {
            proposal.suggestedTriggers
        }

        val persisted = PersistedSkill(
            id = proposal.skillId,
            name = proposal.proposedName,
            description = proposal.description,
            triggers = triggers,
            type = PersistedSkillType.DYNAMIC,
            version = 1,
            confidence = 1.0,
            status = "ACTIVE",
            riskLevel = RiskLevel.LOW,
            steps = proposal.steps
        )

        skillStore?.saveSkill(persisted)
        skillRegistry?.register(DynamicPersistedSkill(persisted))

        Log.i(TAG, "Successfully learned and activated new brain skill '${persisted.name}' (${persisted.id}) with ${persisted.steps.size} steps.")
        return persisted
    }
}
