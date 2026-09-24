package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

/**
 * Autonomous Cross-App UI Autopilot Skill.
 * Interprets natural voice commands for UI navigation, clicking, form filling, and dialog dismissal.
 */
class AppAutopilotSkill : Skill {

    override val id: String = "app_autopilot_skill"
    override val name: String = "Autonomous UI Autopilot & Form Filling Skill"
    override val description: String =
        "Navigates third-party applications, clicks buttons, enters text into fields, fills forms, and dismisses popups."

    override val triggers: List<String> = listOf(
        // English
        "click on", "click button", "tap on", "tap button", "press button",
        "type inside", "type in field", "enter text in", "fill form", "fill the form",
        "dismiss popup", "close dialog", "skip popup", "skip dialog",
        "scroll down in app", "scroll up in app",
        // Hindi / Hinglish
        "button dabao", "click karo", "tap karo", "text likho", "field me likho",
        "form bhar do", "popup hatao", "dialog band karo", "skip karo",
        // Advanced gestures & spatial triggers
        "long press", "long press on", "der tak dabao", "hold on",
        "double tap", "double tap on", "do baar dabao",
        "toggle switch next to", "turn on switch for", "switch dabao",
        "click at", "tap at", "tap coordinate"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()
        if (triggers.any { lower.contains(it) }) return true
        if (lower.startsWith("click ") || lower.startsWith("tap ") || lower.startsWith("press ")) return true
        if (lower.startsWith("type ") && (lower.contains("in") || lower.contains("into"))) return true
        if (lower.contains("fill") && lower.contains("form")) return true
        if (lower.contains("long press") || lower.contains("double tap")) return true
        return false
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()

        val action: AgentAction = when {
            lower.contains("dismiss") || lower.contains("hatao") || lower.contains("close dialog") || lower.contains("skip") -> {
                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "dismiss_popup"),
                    expectedOutcome = "Dismissed intrusive on-screen popup"
                )
            }

            lower.contains("toggle switch") || lower.contains("switch next to") || lower.contains("switch for") || lower.contains("switch dabao") -> {
                val target = lower.replace(Regex("(?i)^(toggle switch next to|turn on switch for|toggle switch for|switch next to|switch dabao|toggle)\\s+"), "").trim()
                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "toggle_neighbor", "target" to target, "neighbor_direction" to "right"),
                    expectedOutcome = "Toggled switch next to '$target'"
                )
            }

            lower.contains("long press") || lower.contains("der tak dabao") || lower.contains("hold on") -> {
                val target = lower.replace(Regex("(?i)^(long press on|long press|der tak dabao|hold on|hold)\\s+"), "")
                    .replace(Regex("(?i)\\s+(pe|ko|par|der tak dabao)$"), "").trim()
                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "long_press", "target" to target),
                    expectedOutcome = "Long-pressed on '$target'"
                )
            }

            lower.contains("double tap") || lower.contains("do baar dabao") -> {
                val target = lower.replace(Regex("(?i)^(double tap on|double tap|do baar dabao)\\s+"), "")
                    .replace(Regex("(?i)\\s+(pe|ko|par)$"), "").trim()
                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "double_tap", "target" to target),
                    expectedOutcome = "Double-tapped on '$target'"
                )
            }

            lower.contains("scroll") -> {
                val dir = if (lower.contains("up") || lower.contains("upar")) "up" else "down"
                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "scroll", "direction" to dir),
                    expectedOutcome = "Scrolled $dir in current view"
                )
            }

            lower.contains("fill") && lower.contains("form") -> {
                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "fill_form", "text" to goal),
                    expectedOutcome = "Auto-filled form fields"
                )
            }

            lower.startsWith("type ") || lower.contains("likho") || lower.contains("type in") || lower.contains("enter text") -> {
                // e.g. "type hello into search" or "search me type karo hello"
                val target: String
                val text: String
                val intoRegex = Regex("""type\s+(.+?)\s+(?:in|into|inside)\s+(.+)""", RegexOption.IGNORE_CASE)
                val match = intoRegex.find(goal)
                if (match != null) {
                    text = match.groupValues[1].trim()
                    target = match.groupValues[2].trim()
                } else {
                    target = ""
                    text = goal.replace(Regex("(?i)^(type|enter|likho|text)\\s+"), "").trim()
                }
                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "type", "target" to target, "text" to text),
                    expectedOutcome = "Typed '$text' into '$target'"
                )
            }

            else -> {
                // Click action: strip triggers to isolate button/target
                var target = goal
                for (t in triggers) {
                    if (target.contains(t, ignoreCase = true)) {
                        target = target.replace(Regex("(?i)" + Regex.escape(t)), "")
                    }
                }
                target = target.replace(Regex("(?i)^(click|tap|press|dabao|pe)\\s+"), "")
                    .replace(Regex("(?i)\\s+(button|karo|dabao)$"), "")
                    .trim()

                AgentAction(
                    type = "APP_AUTOPILOT",
                    params = mapOf("action" to "click", "target" to target),
                    expectedOutcome = "Clicked '$target'"
                )
            }
        }

        return SkillResult(
            handled = true,
            proposedAction = action,
            explanation = "Autonomously driving UI: ${action.expectedOutcome}"
        )
    }
}
