package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

class RoutineSkill : Skill {
    override val id: String = "context_smart_routines"
    override val name: String = "Smart Automation Routines Skill"
    override val description: String =
        "Executes and manages context-aware automation routines such as bedtime wind-down, low battery saver, work arrival, and home arrival."

    override val triggers: List<String> = listOf(
        // English
        "bedtime routine", "night routine", "sleep routine",
        "work routine", "office routine", "work mode",
        "home routine", "home arrival",
        "low battery routine", "battery routine", "battery saver routine",
        "run routine", "trigger routine", "start routine", "execute routine",
        "list routines", "show routines", "my routines", "active automations",
        "smart routines", "automation routines", "context routines",
        // Hindi / Hinglish
        "bedtime routine chalu karo", "night routine chalu karo", "so jao",
        "work routine run karo", "office mode", "kaam ka routine",
        "ghar ka routine", "home routine chalu karo",
        "battery routine", "battery saver mode on karo",
        "routine run karo", "routine chalu karo", "routine start karo",
        "kya automations hain", "automations dikhao", "routines batao"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()
        return triggers.any { lower.contains(it) } ||
                ((lower.contains("routine") || lower.contains("automation")) &&
                        (lower.contains("run") || lower.contains("start") || lower.contains("trigger") ||
                         lower.contains("chalu") || lower.contains("list") || lower.contains("show") || lower.contains("batao")))
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()

        val action = when {
            lower.contains("bedtime") || lower.contains("night") || lower.contains("sleep") || lower.contains("so jao") -> {
                AgentAction(type = "ROUTINE_MANAGE", params = mapOf("action" to "run", "routine_id" to "routine_bedtime"))
            }
            lower.contains("work") || lower.contains("office") || lower.contains("kaam") -> {
                AgentAction(type = "ROUTINE_MANAGE", params = mapOf("action" to "run", "routine_id" to "routine_work_mode"))
            }
            lower.contains("home") || lower.contains("ghar") -> {
                AgentAction(type = "ROUTINE_MANAGE", params = mapOf("action" to "run", "routine_id" to "routine_home_arrival"))
            }
            lower.contains("battery") -> {
                AgentAction(type = "ROUTINE_MANAGE", params = mapOf("action" to "run", "routine_id" to "routine_low_battery"))
            }
            lower.contains("list") || lower.contains("show") || lower.contains("batao") || lower.contains("kya") -> {
                AgentAction(type = "ROUTINE_MANAGE", params = mapOf("action" to "list"))
            }
            else -> {
                AgentAction(type = "ROUTINE_MANAGE", params = mapOf("action" to "run", "name" to goal))
            }
        }

        return SkillResult(
            handled = true,
            proposedAction = action,
            explanation = "Handling contextual automation routine: $goal"
        )
    }
}
