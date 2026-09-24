package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

class MacroSkill(private val macroEngine: com.jarvis.macro.MacroWorkflowEngine? = null) : Skill {
    override val id: String = "macro_workflow_skill"
    override val name: String = "Autonomous App Navigation & Macro Workflow Skill"
    override val description: String =
        "Executes multi-step UI macros, custom user voice routines, automated app navigation workflows, and task sequencing."

    override val triggers: List<String> = listOf(
        // English
        "run macro", "execute macro", "play macro", "start macro", "trigger macro",
        "run routine", "execute routine", "start routine", "trigger routine",
        "clear apps macro", "clear all apps macro", "close all apps macro",
        "youtube search macro", "software update macro", "home macro",
        "list macros", "show macros", "my macros", "available macros",
        "ui macro", "ui workflow", "app macro",
        // Hindi / Hinglish
        "macro run karo", "macro chalu karo", "macro chalao", "macro start karo",
        "routine chalu karo", "routine run karo",
        "saari apps clear karne ka macro", "apps band karne ka macro",
        "youtube macro chalao", "update check karne ka macro",
        "macros dikhao", "macros ki list", "workflow run karo"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()
        if (triggers.any { lower.contains(it) }) return true
        if ((lower.contains("macro") || lower.contains("workflow") || lower.contains("routine")) &&
            (lower.contains("run") || lower.contains("play") || lower.contains("start") ||
             lower.contains("chalu") || lower.contains("chalao") || lower.contains("list") || lower.contains("show"))) {
            return true
        }
        val matched = macroEngine?.getMacro(goal)
        if (matched != null) return true
        return false
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val matched = macroEngine?.getMacro(goal)
        if (matched != null) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "MACRO_WORKFLOW",
                    params = mapOf("action" to "run", "macro_id" to matched.id)
                ),
                explanation = "Executing voice macro '${matched.name}'"
            )
        }

        val lower = goal.lowercase().trim()

        val action = when {
            lower.contains("clear") || lower.contains("close all") || lower.contains("band kar") -> {
                AgentAction(type = "MACRO_WORKFLOW", params = mapOf("action" to "run", "macro_id" to "macro_clear_apps"))
            }
            lower.contains("youtube") -> {
                AgentAction(type = "MACRO_WORKFLOW", params = mapOf("action" to "run", "macro_id" to "macro_youtube_search"))
            }
            lower.contains("update") -> {
                AgentAction(type = "MACRO_WORKFLOW", params = mapOf("action" to "run", "macro_id" to "macro_software_update"))
            }
            lower.contains("home") || lower.contains("ghar") -> {
                AgentAction(type = "MACRO_WORKFLOW", params = mapOf("action" to "run", "macro_id" to "macro_quick_home"))
            }
            lower.contains("list") || lower.contains("show") || lower.contains("dikhao") || lower.contains("kya") -> {
                AgentAction(type = "MACRO_WORKFLOW", params = mapOf("action" to "list"))
            }
            else -> {
                AgentAction(type = "MACRO_WORKFLOW", params = mapOf("action" to "run", "macro" to goal))
            }
        }

        return SkillResult(
            handled = true,
            proposedAction = action,
            explanation = "Handling macro workflow request: $goal"
        )
    }
}
