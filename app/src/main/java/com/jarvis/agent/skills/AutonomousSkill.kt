package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Locale

class AutonomousSkill : Skill {
    override val id: String = "autonomous_autopilot"
    override val name: String = "Autonomous Autopilot Skill"
    override val description: String = "Handles autonomous autopilot, ambient driving/meeting/focus modes, and decision logs in English and Hindi."
    override val triggers: List<String> = listOf(
        "autonomous", "autopilot", "auto pilot", "take over",
        "driving mode", "meeting mode", "focus mode", "sleep mode", "night mode", "workout mode", "auto mode", "automatic mode",
        "chalu karo", "band karo", "activate", "deactivate"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()
        if (lower.contains("autonomous") || lower.contains("autopilot") || lower.contains("auto pilot") || lower.contains("take over")) return true
        if (lower.contains("driving mode") || lower.contains("meeting mode") || lower.contains("focus mode") || lower.contains("sleep mode") || lower.contains("night mode") || lower.contains("workout mode") || lower.contains("auto mode") || lower.contains("automatic mode")) return true
        if (lower.contains("driving") && (lower.contains("on") || lower.contains("off") || lower.contains("chalu") || lower.contains("band"))) return true
        if (lower.contains("meeting") && (lower.contains("on") || lower.contains("off") || lower.contains("chalu") || lower.contains("band"))) return true
        if (lower.contains("focus") && (lower.contains("on") || lower.contains("off") || lower.contains("chalu") || lower.contains("band"))) return true
        if (lower.contains("night") && (lower.contains("on") || lower.contains("off") || lower.contains("chalu") || lower.contains("band"))) return true
        if (lower.contains("workout") && (lower.contains("on") || lower.contains("off") || lower.contains("chalu") || lower.contains("band"))) return true
        return false
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        // 1. Disable Autopilot or Ambient Modes
        if (lower.contains("disable") || lower.contains("off") || lower.contains("stop") || lower.contains("band karo") || lower.contains("deactivate")) {
            if (lower.contains("autopilot") || lower.contains("auto pilot") || lower.contains("autonomous")) {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "disable_autopilot")),
                    explanation = "Disengaging autonomous autopilot"
                )
            }
            if (lower.contains("mode") || lower.contains("driving") || lower.contains("meeting") || lower.contains("focus") || lower.contains("night") || lower.contains("workout")) {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "set_ambient_mode", "mode" to "auto")),
                    explanation = "Resetting to automatic ambient mode"
                )
            }
        }

        // 2. Specific Ambient Modes
        when {
            lower.contains("auto mode") || lower.contains("automatic mode") || lower.contains("sensor mode") -> {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "set_ambient_mode", "mode" to "auto")),
                    explanation = "Switching to Automatic Ambient Mode"
                )
            }
            lower.contains("driving") || lower.contains("drive") || lower.contains("car") -> {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "set_ambient_mode", "mode" to "driving")),
                    explanation = "Activating Driving Ambient Mode"
                )
            }
            lower.contains("meeting") -> {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "set_ambient_mode", "mode" to "meeting")),
                    explanation = "Activating Meeting Ambient Mode"
                )
            }
            lower.contains("focus") || lower.contains("work") -> {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "set_ambient_mode", "mode" to "focus")),
                    explanation = "Activating Deep Focus Mode"
                )
            }
            lower.contains("sleep") || lower.contains("night") -> {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "set_ambient_mode", "mode" to "night")),
                    explanation = "Activating Night Wind-Down Mode"
                )
            }
            lower.contains("workout") || lower.contains("gym") -> {
                return SkillResult(
                    handled = true,
                    proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "set_ambient_mode", "mode" to "workout")),
                    explanation = "Activating Workout Session Mode"
                )
            }
        }

        // 3. Autonomous History / Recent Decisions
        if (lower.contains("history") || lower.contains("recent") || lower.contains("what did") || lower.contains("log")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "recent_actions")),
                explanation = "Retrieving recent autonomous decisions"
            )
        }

        // 4. Status Check
        if (lower.contains("status") || lower.contains("kya hai")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "status")),
                explanation = "Checking autonomous core status"
            )
        }

        // 5. Enable Autopilot Default
        return SkillResult(
            handled = true,
            proposedAction = AgentAction("AUTONOMOUS_CONTROL", mapOf("action" to "enable_autopilot")),
            explanation = "Engaging autonomous autopilot"
        )
    }
}
