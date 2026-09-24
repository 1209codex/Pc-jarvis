package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Locale

class LogSkill : Skill {
    override val id: String = "logs"
    override val name: String = "Log & Telemetry Inspection Skill"
    override val description: String = "Reads and summarizes live device logcat and agent telemetry traces."

    override val triggers: List<String> = listOf(
        "log", "logs", "logcat", "telemetry", "error", "errors", "padho", "dikhao"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()

        val logTriggers = listOf(
            "read log", "read logs", "show log", "show logs", "check log", "check logs",
            "system log", "system logs", "view log", "view logs", "device logs",
            "what are the logs", "what's in the logs", "show logcat", "read logcat",
            "logs padho", "logs dikhao", "logcat dikhao", "log dikhao", "kya error aaya",
            "error check karo", "kya hua tha", "recent logs", "recent log", "telemetry"
        )

        return logTriggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        val filter = when {
            lower.contains("error") || lower.contains("fail") || lower.contains("warning") -> "error"
            lower.contains("voice") || lower.contains("speech") -> "voice"
            lower.contains("tool") -> "tool"
            lower.contains("whatsapp") -> "whatsapp"
            else -> null
        }

        val mode = if (lower.contains("raw") || lower.contains("detail") || lower.contains("pura")) "raw" else "summary"

        val params = mutableMapOf<String, String>(
            "mode" to mode,
            "lines" to "25"
        )
        if (filter != null) {
            params["filter"] = filter
        }

        return SkillResult(
            handled = true,
            proposedAction = AgentAction(
                type = "LOGS_READ",
                params = params,
                expectedOutcome = "Reading real-time system and agent logs"
            ),
            explanation = "Inspecting device logcat and agent telemetry"
        )
    }
}
