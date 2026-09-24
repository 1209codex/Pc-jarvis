package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

class TimeSkill : Skill {
    override val id: String = "time_automation"
    override val name: String = "Timers, Alarms and Reminders Skill"
    override val description: String =
        "Sets one-off timers, alarms, reminders and recurring routine jobs at a specific time or after a duration."

    override val triggers: List<String> = listOf(
        "remind me", "remind", "reminder", "yaad dilana", "yaad dila", "yaad karna",
        "set a timer", "timer for", "timer set", "timer chalao", "timer",
        "set alarm", "alarm at", "alarm chalao", "alarm karo", "alarm",
        "schedule routine", "routine every", "daily at", "roz at"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()
        return triggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()

        val (_, params) = when {
            lower.contains("timer") && (lower.contains("minute") || lower.contains("min") || lower.contains("second") || lower.contains("hour")) -> {
                val whenText = lower.replace("set", "").replace("a", "").replace("timer", "").replace("for", "").trim()
                "set_timer" to mapOf("action" to "set_timer", "when" to whenText, "what" to lower.substringAfter(" to ", ""))
            }
            lower.contains("alarm") && (lower.contains("am") || lower.contains("pm") || Regex("\\d").containsMatchIn(lower)) -> {
                val time = lower.replace("set", "").replace("alarm", "").replace("at", "").trim()
                "set_alarm" to mapOf("action" to "set_alarm", "time" to time, "what" to lower.substringAfter(" to ", ""))
            }
            lower.startsWith("remind me") || lower.contains("yaad dila") -> {
                val what = lower.replaceFirst("remind me", "").replaceFirst("yaad dilana", "").replaceFirst("yaad dila", "").trim()
                    .removePrefix("to ").removePrefix("ke liye")
                "remind_me" to mapOf("action" to "remind_me", "what" to what, "when" to if (lower.contains(" at ") || lower.contains(" in ")) lower else "")
            }
            lower.contains("schedule routine") || (lower.contains("routine") && (lower.contains("at") || lower.contains("roz"))) -> {
                val time = when {
                    lower.contains(" at ") -> lower.substringAfterLast(" at ").trim()
                    lower.contains(" roz ") -> lower.substringAfterLast(" roz ").trim()
                    else -> "07:30"
                }.ifBlank { "07:30" }
                "schedule_routine" to mapOf("action" to "schedule_routine", "time" to time, "routine" to goal)
            }
            else -> {
                "remind_me" to mapOf("action" to "remind_me", "what" to goal, "when" to "")
            }
        }

        return SkillResult(
            handled = true,
            proposedAction = AgentAction(type = "REMINDER_SCHEDULE", params = params),
            explanation = "Handling time-based automation request: $goal"
        )
    }
}