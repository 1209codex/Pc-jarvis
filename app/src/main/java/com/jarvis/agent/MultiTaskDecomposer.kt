package com.jarvis.agent

import java.util.Locale

/**
 * Autonomous Multi-Task & Compound Request Decomposer.
 *
 * Breaks compound commands (e.g. "turn on flashlight and play believer",
 * "torch on karo aur kesariya bajao", "open settings then stop music") into
 * ordered, actionable sub-goals while preventing false splits on non-command
 * queries (e.g. "rock and roll", "fast and furious", "tom and jerry").
 */
object MultiTaskDecomposer {

    private val CONJUNCTION_REGEX = Regex(
        "(?:\\s+(?:and then|aur fir|aur phir|ke baad|after that)\\s+)" +
        "|(?:\\s+(?:and|then|aur|fir|phir|also|&)\\s+)" +
        "|(?:\\s*;\\s*)" +
        "|(?:,\\s*(?=(?:open|launch|kholo|play|chalao|bajao|turn|torch|flashlight|stop|pause|band|search|google|call|sms|scroll|swipe|read|mode|driving|meeting|focus|night|workout|autopilot|wifi|bluetooth|volume|mute|unmute)))",
        RegexOption.IGNORE_CASE
    )

    private val ACTION_KEYWORDS = setOf(
        "open", "launch", "kholo", "start", "chalu", "shuru",
        "play", "chalao", "bajao", "sunao", "laga", "lagao", "stream",
        "stop", "pause", "band", "bujha", "hata", "close", "cancel", "ruk",
        "torch", "flashlight", "flash", "light",
        "search", "google", "find", "dhundo", "khojo", "pata",
        "scroll", "swipe", "upar", "niche", "up", "down",
        "call", "dial", "phone", "uthao", "kaat", "kato", "reject", "answer",
        "sms", "message", "whatsapp", "bhejo", "padho", "read", "otp",
        "alarm", "timer", "reminder", "remind", "yaad",
        "next", "skip", "previous", "prev", "resume", "wapas",
        "settings", "camera", "instagram", "youtube", "spotify", "ymusic", "chrome",
        "storage", "battery", "permission", "security", "privacy", "notes", "vault", "rag",
        "calculate", "calc", "math", "hisab", "compute",
        "batao", "dikhao", "samjhao", "dekho", "tell", "show", "explain", "check",
        "mode", "driving", "meeting", "focus", "night", "workout", "autopilot",
        "wifi", "bluetooth", "volume", "mute", "unmute", "sound", "brightness",
        "dnd", "screenshot", "lock", "reboot", "restart", "enable", "disable", "activate", "deactivate"
    )

    val ACTION_VERBS = setOf(
        "open", "launch", "kholo", "start", "chalu", "shuru", "turn",
        "play", "chalao", "bajao", "sunao", "laga", "lagao", "stream",
        "stop", "pause", "band", "bujha", "hata", "close", "cancel", "ruk",
        "torch", "flashlight",
        "search", "google", "find", "dhundo", "khojo",
        "scroll", "swipe", "upar", "niche",
        "call", "dial", "uthao", "kaat", "kato", "reject", "answer",
        "sms", "bhejo", "padho", "read", "send", "share", "write",
        "take", "capture", "click", "photo",
        "alarm", "timer", "reminder", "remind", "set",
        "next", "skip", "previous", "prev", "resume",
        "calculate", "calc",
        "batao", "dikhao", "samjhao", "dekho", "tell", "show", "explain", "check",
        "mute", "unmute", "enable", "disable", "activate", "deactivate",
        "create", "add", "delete", "remove"
    )

    fun hasActionVerb(clause: String): Boolean {
        val lower = clause.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        val words = lower.split(Regex("\\s+"))
        return words.any { it in ACTION_VERBS }
    }

    /**
     * Determines whether [clause] contains command/action keywords and an executable action verb.
     */
    fun isActionable(clause: String): Boolean {
        val lower = clause.trim().lowercase(Locale.ROOT)
        if (lower.isBlank()) return false
        val words = lower.split(Regex("\\s+"))
        return words.any { it in ACTION_KEYWORDS } && words.any { it in ACTION_VERBS }
    }

    /**
     * Decomposes [utterance] into distinct sub-goals if it represents a sequential compound request.
     * If [utterance] is a single command or non-splittable query, returns a single-item list.
     */
    fun decompose(utterance: String): List<String> {
        val text = utterance.trim()
        if (text.isBlank()) return emptyList()

        val lower = text.lowercase(Locale.ROOT)
        // Unified search-and-play / research-and-play requests should be kept whole for SongSearchAndPlaySkill
        val isUnifiedSongSearch = lower.contains("search and play") || lower.contains("research and play") ||
                lower.contains("reseach and play") || lower.contains("find and play") ||
                lower.contains("search and plan") || lower.contains("search song and play") ||
                lower.contains("find song and play") || lower.contains("search for a song and play") ||
                lower.contains("search for song and play") || lower.contains("dhundo aur chalao") ||
                lower.contains("dhoondo aur chalao")
        if (isUnifiedSongSearch) {
            return listOf(text)
        }

        val rawParts = text.split(CONJUNCTION_REGEX)
            .map { it.trim() }
            .filter { it.isNotBlank() }

        if (rawParts.size <= 1) {
            return listOf(text)
        }

        // Must have at least 2 actionable clauses with action verbs, and every non-first clause must have an action verb
        // to prevent false splitting on noun phrases like "rock and roll" or "tom and jerry".
        val actionableCount = rawParts.count { isActionable(it) }
        if (actionableCount >= 2 && rawParts.all { isActionable(it) }) {
            return rawParts
        }

        // For long utterances with conditional or sequential structure, leverage ComplexCommandParser
        if (text.split(Regex("\\s+")).size >= 8) {
            val parsed = ComplexCommandParser.parse(text)
            if (parsed.segments.size > 1 && parsed.segments.any { isActionable(it.text) }) {
                return parsed.segments.map { it.text }
            }
        }

        return listOf(text)
    }

    /**
     * Deep parsing for long and conditional complex commands.
     */
    fun parseComplex(utterance: String): ParsedComplexCommand {
        return ComplexCommandParser.parse(utterance)
    }
}
