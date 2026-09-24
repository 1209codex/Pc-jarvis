package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

class AppControlSkill : Skill {
    override val id: String = "app_control"
    override val name: String = "App & UI Automation Skill"
    override val description: String = "Controls application opening, closing, UI gestures, scanning installed apps, and system navigation."
    override val triggers: List<String> = listOf(
        "open", "launch", "kholo", "start", "close", "band karo", "hatao",
        "scroll", "swipe", "niche karo", "uper karo", "back", "home",
        "screenshot", "screen shot", "recents", "recent apps", "click", "tap",
        "scan apps", "scan all apps", "list apps", "list installed apps", "installed apps", "installed applications"
    )

    private val compoundKeywords = listOf(
        " and ", " aur ", " then ", " fir ", " phir ",
        " check ", " search ", " play ", " send ", " message ", " massage ",
        " dm ", " unread ", " reel ", " reels ", " video ", " song ", " gana "
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()

        // Yield to social/specialized skills or multi-step execution if compound/specialized intent detected
        if (lower.contains("instagram") || lower.contains("insta")) {
            val hasSpecialized = lower.contains("message") || lower.contains("massage") ||
                    lower.contains("dm") || lower.contains("chat") ||
                    lower.contains("unread") || lower.contains("reel") || lower.contains("reels")
            if (hasSpecialized) return false
        }

        if (compoundKeywords.any { lower.contains(it) }) {
            return false
        }

        return triggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()
        return when {
            // App Scanning / Listing
            lower.contains("scan apps") || lower.contains("scan all apps") ||
            lower.contains("list apps") || lower.contains("list installed") ||
            lower.contains("installed apps") || lower.contains("installed applications") ||
            lower.contains("koun koun se apps") || lower.contains("kon se app") -> {
                val action = if (lower.contains("scan")) "scan" else "list"
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("APPS_LIST", mapOf("action" to action), "Scanning installed applications"),
                    explanation = "Scanning installed applications on device"
                )
            }

            // Screen & Gesture Automation
            lower.contains("screenshot") || lower.contains("screen shot") -> {
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("UI_GLOBAL", mapOf("action" to "screenshot"), "Taking screenshot"),
                    explanation = "Capturing device screenshot"
                )
            }
            lower.contains("back") || lower.contains("piche") -> {
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("UI_GLOBAL", mapOf("action" to "back"), "Going back"),
                    explanation = "Navigating back"
                )
            }
            lower.contains("home") || lower.contains("ghar") -> {
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("UI_GLOBAL", mapOf("action" to "home"), "Navigating home"),
                    explanation = "Navigating to home screen"
                )
            }
            lower.contains("recents") || lower.contains("recent apps") -> {
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("UI_GLOBAL", mapOf("action" to "recents"), "Opening recent apps"),
                    explanation = "Opening recent applications overview"
                )
            }
            lower.contains("scroll") || lower.contains("swipe") || lower.contains("niche karo") || lower.contains("uper karo") -> {
                val direction = if (lower.contains("up") || lower.contains("uper")) "up" else "down"
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("UI_SCROLL", mapOf("direction" to direction), "Scrolling $direction"),
                    explanation = "Scrolling active screen $direction"
                )
            }
            lower.startsWith("click ") || lower.startsWith("tap ") || lower.contains(" per click") || lower.contains(" par click") -> {
                val target = lower
                    .replace("click on", "")
                    .replace("click", "")
                    .replace("tap on", "")
                    .replace("tap", "")
                    .replace("per click", "")
                    .replace("par click", "")
                    .replace("karo", "")
                    .trim()
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("UI_CLICK", mapOf("target" to target), "Clicking $target"),
                    explanation = "Clicking on $target"
                )
            }
            // App Lifecycle
            lower.contains("close all") || lower.contains("sab band") -> {
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("APPS_CLOSE_ALL", emptyMap(), "Closing all apps"),
                    explanation = "Closing all running background applications"
                )
            }
            lower.startsWith("close ") || lower.contains(" band karo") -> {
                val app = extractAppName(lower, isClosing = true)
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("CLOSE_APP", mapOf("app" to app), "Closing app: $app"),
                    explanation = "Closing application $app"
                )
            }
            else -> {
                val app = extractAppName(lower, isClosing = false)
                SkillResult(
                    handled = true,
                    proposedAction = AgentAction("OPEN_APP", mapOf("app" to app), "Opening app: $app"),
                    explanation = "Launching application $app"
                )
            }
        }
    }

    private fun extractAppName(text: String, isClosing: Boolean): String {
        var clean = text
        if (isClosing) {
            clean = clean
                .replace("band karo", "")
                .replace("close app", "")
                .replace("close", "")
                .replace("hatao", "")
                .trim()
        } else {
            clean = clean
                .replace("kholo app", "")
                .replace("open app", "")
                .replace("launch app", "")
                .replace("start app", "")
                .replace("kholo", "")
                .replace("open", "")
                .replace("launch", "")
                .replace("start", "")
                .replace("chalao", "")
                .replace("please", "")
                .trim()
        }

        return clean.ifBlank { "youtube" }
    }
}
