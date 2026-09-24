package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

class ProactiveSkill : Skill {
    override val id: String = "proactive_intelligence"
    override val name: String = "Proactive Notifications & Device Health Skill"
    override val description: String = "Handles queries about notifications, battery level, device health, and morning briefings."
    override val triggers: List<String> = listOf(
        // Battery
        "battery", "battery level", "battery check", "check battery", "how much battery",
        "battery kitni hai", "battery kitna hai", "battery bachi hai", "battery percentage",
        "charging status", "charger",
        // Notifications
        "notification", "notifications", "unread messages", "new messages", "read notifications",
        "check notifications", "read my notifications", "what notifications do i have",
        "kiska notification aaya", "kiska message aaya", "notifications padh", "messages padh",
        "whatsapp check", "whatsapp notification", "whatsapp message check", "check whatsapp",
        "check whatsapp message", "check message", "check messages",
        // Briefing
        "briefing", "daily briefing", "morning briefing", "status report", "good morning",
        "subah ki report", "kya chal raha hai", "system status", "device status"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()
        if (triggers.any { lower.contains(it) }) return true
        if ((lower.contains("whatsapp") || lower.contains("message") || lower.contains("messages") || lower.contains("sms") || lower.contains("notif")) &&
            (lower.contains("check") || lower.contains("read") || lower.contains("padh") || lower.contains("aaya") || lower.contains("aaye"))) {
            if (!lower.contains("send") && !lower.contains("bhejo") && !lower.contains("type") && !lower.contains("likho")) {
                return true
            }
        }
        return false
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()
        val action = when {
            // Battery queries
            lower.contains("battery") || (lower.contains("charging") && !lower.contains("notification")) -> {
                AgentAction(type = "BATTERY_CHECK", params = emptyMap())
            }
            // Briefing queries
            lower.contains("briefing") || lower.contains("status report") ||
            lower.contains("good morning") || lower.contains("subah ki report") -> {
                AgentAction(type = "DAILY_BRIEFING", params = emptyMap())
            }
            // Notifications
            else -> {
                val appFilter = when {
                    lower.contains("whatsapp") -> "whatsapp"
                    lower.contains("sms") || lower.contains("message") -> "message"
                    lower.contains("mail") || lower.contains("gmail") -> "gmail"
                    else -> ""
                }
                AgentAction(
                    type = "NOTIFICATIONS_READ",
                    params = if (appFilter.isNotBlank()) mapOf("app" to appFilter) else emptyMap()
                )
            }
        }

        return SkillResult(
            handled = true,
            proposedAction = action,
            explanation = "Handling proactive device/notification request: $goal"
        )
    }
}
