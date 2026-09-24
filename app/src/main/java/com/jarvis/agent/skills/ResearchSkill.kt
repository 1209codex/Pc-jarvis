package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

class ResearchSkill : Skill {
    override val id: String = "research"
    override val name: String = "Research & Report Skill"
    override val description: String = "Conducts web research, search, factual lookup, and automated background report generation."
    override val triggers: List<String> = listOf(
        "research", "reseach", "re-search", "deep research", "background research",
        "search", "sesarch", "search for", "sesarch for", "find", "find out", "look up",
        "google", "dhoondo", "pata karo", "kya hai", "who is", "what is",
        "report", "make report", "give me report", "prepare report", "explore", "investigate"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()
        return triggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()

        val isDeepReport = lower.contains("report") || lower.contains("deep research") ||
                lower.contains("background research") || lower.contains("make a report") ||
                lower.contains("give me report") || lower.contains("full research") ||
                lower.contains("study on") || lower.contains("comprehensive research") ||
                (lower.startsWith("research ") && lower.length > 25) ||
                (lower.startsWith("reseach ") && lower.length > 25)

        if (isDeepReport) {
            val topic = extractResearchTopic(lower)
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "RESEARCH_DEEP",
                    params = mapOf("topic" to topic, "query" to goal),
                    expectedOutcome = "Deep research report compiled for $topic in JARVIS/Reports folder"
                ),
                explanation = "Executing background deep research on: $topic"
            )
        }

        val query = extractSearchQuery(lower).ifBlank { goal.trim() }

        return SkillResult(
            handled = true,
            proposedAction = AgentAction(
                type = "SEARCH_WEB",
                params = mapOf("query" to query),
                expectedOutcome = "Search results for: $query"
            ),
            explanation = "Executing search query: $query"
        )
    }

    private fun extractSearchQuery(text: String): String {
        return text
            .replace(Regex("(?i)^(please\\s+)?(sesarch\\s+for|search\\s+for|sesarch\\s+about|search\\s+about|sesarch|search|reseach\\s+about|research\\s+about|reseach\\s+on|research\\s+on|reseach|research|look\\s+up|find\\s+out\\s+about|find\\s+out|google|dhoondo|pata\\s+karo)\\s+"), "")
            .replace(Regex("(?i)^(who\\s+is|what\\s+is|tell\\s+me\\s+about|kya\\s+hai)\\s+"), "")
            .trim()
    }

    private fun extractResearchTopic(text: String): String {
        return text
            .replace(Regex("(?i)^(please\\s+)?(make\\s+a\\s+report\\s+on|make\\s+report\\s+on|make\\s+a\\s+report\\s+about|make\\s+report\\s+about|give\\s+me\\s+a\\s+report\\s+on|give\\s+me\\s+report\\s+on|prepare\\s+report\\s+on|prepare\\s+a\\s+report\\s+on)\\s+"), "")
            .replace(Regex("(?i)^(deep\\s+research\\s+on|deep\\s+research\\s+about|background\\s+research\\s+on|background\\s+research\\s+about|reseach\\s+about|research\\s+about|reseach\\s+on|research\\s+on|reseach|research)\\s+"), "")
            .replace(Regex("(?i)\\s+(and\\s+make\\s+report|and\\s+give\\s+me\\s+report|and\\s+prepare\\s+report|ki\\s+report\\s+banao|par\\s+research\\s+karo|report\\s+banao)$"), "")
            .trim()
            .ifBlank { "General_Research" }
    }
}
