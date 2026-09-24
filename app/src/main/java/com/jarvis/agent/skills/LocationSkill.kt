package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

class LocationSkill : Skill {
    override val id: String = "location_awareness"
    override val name: String = "Location & Place Awareness Skill"
    override val description: String =
        "Answers where you are, whether you're home or at work, and what the network neighbourhood looks like via GPS and Wi-Fi."

    override val triggers: List<String> = listOf(
        "where am i", "my location", "mera location", "current location", "location kya hai", "location",
        "home", "ghar", "ghar ke paas", "work", "office", "at home", "at work", "near home", "near office",
        "proximity", "gps"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()
        if (lower.contains("timer") || lower.contains("alarm") || lower.contains("remind")) return false
        return triggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()

        val action = when {
            lower.contains("where am i") || lower.contains("my location") || lower.contains("mera location") ||
                lower.contains("location kya hai") -> "where_am_i"

            lower.contains("home") || lower.contains("ghar") || lower.contains("work") || lower.contains("office") ||
                lower.contains("proximity") || lower.contains("near") || lower.contains("nearby") || lower.contains("paas") -> "nearby"

            lower.contains("gps") || lower.contains("coordinates") || lower.contains("latitude") -> "get_location"
            else -> "where_am_i"
        }

        return SkillResult(
            handled = true,
            proposedAction = AgentAction(type = "LOCATION", params = mapOf("action" to action)),
            explanation = "Handling location/place query: $goal"
        )
    }
}