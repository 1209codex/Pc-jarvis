package com.jarvis.ai

import java.util.Locale

enum class AssistantIntent {
    APP_OPEN,
    PLAY_MEDIA,
    STOP_MEDIA,
    SEARCH_WEB,
    REMEMBER_FACT,
    QUERY_MEMORY,
    SCREEN_VISION,
    BATTERY_CHECK,
    READ_NOTIFICATIONS,
    DAILY_BRIEFING,
    HABIT_SUGGEST,
    HABIT_LIST,
    ROUTINE_RUN,
    ROUTINE_LIST,
    MACRO_RUN,
    MACRO_LIST,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    MEDIA_RESUME,
    SPOTIFY_PLAY,
    TELEPHONY_CALL,
    TELEPHONY_SMS,
    CALENDAR_SCHEDULE,
    CALENDAR_AGENDA,
    SECURITY_AUDIT,
    FILE_SEARCH,
    AUTONOMOUS_CONTROL,
    RAG_QUERY,
    RAG_INDEX,
    CAMERA_VISION,
    WHATSAPP_READ,
    WHATSAPP_AUTO_REPLY,
    WHATSAPP_SEND,
    READ_LOGS,
    TELEPHONY_ANSWER,
    TELEPHONY_REJECT,
    TELEPHONY_CALLER_INFO,
    TELEPHONY_SMS_READ,
    SET_TIMER,
    SET_ALARM,
    REMIND_ME,
    WHERE_AM_I,
    READ_MESSAGES,
    CANCEL_TASK,
    COMPOUND_TASK,
    APPS_LIST,
    APPS_CLOSE_ALL,
    DEEP_RESEARCH,
    MEDIA_VOLUME,
    DEVICE_SETTINGS,
    SCREEN_LOCK,
    SCREEN_UNLOCK
}

data class ResolvedIntent(
    val intent: AssistantIntent,
    val confidence: Float,
    val params: Map<String, String>,
    val directPlan: ExecutionPlan? = null
)

object IntentResolver {
    fun resolve(utterance: String): ResolvedIntent? {
        val text = utterance.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return null

        // Check for compound multi-step requests
        val subCommands = com.jarvis.agent.MultiTaskDecomposer.decompose(utterance)
        if (subCommands.size > 1) {
            val resolvedSubIntents = subCommands.mapNotNull { resolveSingle(it) }
            if (resolvedSubIntents.size == subCommands.size && resolvedSubIntents.all { it.directPlan != null }) {
                val combinedActions = resolvedSubIntents.flatMap { it.directPlan!!.actions }
                val combinedResponse = resolvedSubIntents
                    .map { it.directPlan!!.response }
                    .filter { it.isNotBlank() }
                    .joinToString(". ")
                return ResolvedIntent(
                    intent = AssistantIntent.COMPOUND_TASK,
                    confidence = resolvedSubIntents.map { it.confidence }.minOrNull() ?: 0.9f,
                    params = mapOf("subtasks_count" to resolvedSubIntents.size.toString()),
                    directPlan = ExecutionPlan(combinedResponse, combinedActions)
                )
            }
            // If any sub-intent cannot be resolved offline to a direct plan, return null so AgentKernel handles it.
            return null
        }

        return resolveSingle(utterance)
    }

    fun resolveSingle(utterance: String): ResolvedIntent? {
        val text = utterance.trim().lowercase(Locale.ROOT)
        if (text.isBlank()) return null
        // 0.1 Flashlight / Torch
        if (text in listOf("turn on flashlight", "torch on karo", "flashlight on", "torch on", "torch chalu karo", "light on karo", "turn on torch", "torch start karo", "light jalao", "light on") ||
            text in listOf("turn off flashlight", "torch band karo", "flashlight off", "torch off", "light band karo", "turn off torch", "torch stop karo", "light bujhao", "light off") ||
            (text.contains("torch") || text.contains("flashlight")) && (text.contains("on") || text.contains("off") || text.contains("chalu") || text.contains("band"))) {
            val off = text.contains("off") || text.contains("band") || text.contains("stop") || text.contains("bujhao")
            val mode = if (off) "off" else "on"
            val params = mapOf("mode" to mode)
            val desc = if (off) "Turning off flashlight." else "Turning on flashlight."
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan(desc, listOf(ActionPlan("FLASHLIGHT", params)))
            )
        }

        // 0.2 Volume Up / Down / Set / Mute / Unmute
        val volumePercentMatch = Regex("(?:set\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})%?|(?:set\\s+)?(\\d{1,3})%?\\s*volume|(?:volume\\s+)?(\\d{1,3})\\s*(?:percent|%|karo)").find(text)
        if (volumePercentMatch != null && !text.contains("up") && !text.contains("down") && !text.contains("badhao") && !text.contains("kam")) {
            val pctStr = volumePercentMatch.groupValues.drop(1).firstOrNull { it.isNotBlank() } ?: "50"
            val pct = pctStr.toIntOrNull()?.coerceIn(0, 100) ?: 50
            val params = mapOf("action" to "set_volume", "volume_percent" to pct.toString(), "percent" to pct.toString())
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_VOLUME,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Setting volume to $pct%.", listOf(ActionPlan("DEVICE_SETTINGS", params)))
            )
        }

        if (text in listOf("full volume", "max volume", "volume full", "volume 100", "volume 100%", "full volume karo", "volume full karo")) {
            val params = mapOf("action" to "set_volume", "volume_percent" to "100", "percent" to "100")
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_VOLUME,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Setting volume to 100%.", listOf(ActionPlan("DEVICE_SETTINGS", params)))
            )
        }

        if (text in listOf("half volume", "volume half", "volume 50", "volume 50%", "half volume karo", "volume half karo")) {
            val params = mapOf("action" to "set_volume", "volume_percent" to "50", "percent" to "50")
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_VOLUME,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Setting volume to 50%.", listOf(ActionPlan("DEVICE_SETTINGS", params)))
            )
        }

        if (text in listOf("volume up", "increase volume", "raise volume", "volume badhao", "awaaz badhao", "awaz badhao", "volume tez karo", "awaaz tez karo", "louder", "sound up") ||
            text.startsWith("volume up") || text.startsWith("volume badhao") || text.startsWith("awaaz badhao")) {
            val params = mapOf("action" to "volume_up")
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_VOLUME,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Increasing volume.", listOf(ActionPlan("MEDIA_CONTROL", params)))
            )
        }

        if (text in listOf("volume down", "decrease volume", "lower volume", "volume kam karo", "awaaz kam karo", "awaz kam karo", "volume dheeme karo", "quieter", "sound down") ||
            text.startsWith("volume down") || text.startsWith("volume kam karo") || text.startsWith("awaaz kam karo")) {
            val params = mapOf("action" to "volume_down")
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_VOLUME,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Decreasing volume.", listOf(ActionPlan("MEDIA_CONTROL", params)))
            )
        }

        if (text in listOf("mute", "mute karo", "awaaz band karo", "awaz band karo", "silent karo", "silence") || text == "mute") {
            val params = mapOf("action" to "mute")
            return ResolvedIntent(
                intent = AssistantIntent.DEVICE_SETTINGS,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Muting audio.", listOf(ActionPlan("DEVICE_SETTINGS", params)))
            )
        }

        if (text in listOf("unmute", "unmute karo", "awaaz chalu karo", "awaz chalu karo") || text == "unmute") {
            val params = mapOf("action" to "unmute")
            return ResolvedIntent(
                intent = AssistantIntent.DEVICE_SETTINGS,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Unmuting audio.", listOf(ActionPlan("DEVICE_SETTINGS", params)))
            )
        }

        // 0.3 Screen Lock & Unlock
        if (text in listOf("lock screen", "lock phone", "phone lock karo", "screen lock karo", "screen band karo", "phone band karo", "lock device", "lock") ||
            text.startsWith("lock phone") || text.startsWith("phone lock")) {
            val params = mapOf("action" to "lock")
            return ResolvedIntent(
                intent = AssistantIntent.SCREEN_LOCK,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Locking device screen.", listOf(ActionPlan("SCREEN_LOCK", params)))
            )
        }

        if (text in listOf("unlock screen", "unlock phone", "phone unlock karo", "screen unlock karo", "screen on karo", "screen chalu karo", "unlock device", "unlock") ||
            text.startsWith("unlock phone") || text.startsWith("phone unlock")) {
            val params = mapOf("action" to "unlock")
            return ResolvedIntent(
                intent = AssistantIntent.SCREEN_UNLOCK,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Unlocking device screen.", listOf(ActionPlan("SCREEN_UNLOCK", params)))
            )
        }

        // 0.4 Close All Apps / Clear Recents
        if (text in listOf("close all apps", "close all", "clear recents", "clear all apps", "sab apps band karo", "apps close karo", "close apps", "band karo sab apps")) {
            val params = mapOf("action" to "close_all")
            return ResolvedIntent(
                intent = AssistantIntent.APPS_CLOSE_ALL,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Closing all open applications.", listOf(ActionPlan("APPS_CLOSE_ALL", params)))
            )
        }

        // 0.5 WiFi & Bluetooth Toggles
        if (text in listOf("turn on wifi", "wifi on karo", "wifi chalu karo", "turn off wifi", "wifi off karo", "wifi band karo")) {
            val off = text.contains("off") || text.contains("band")
            val params = mapOf("action" to "set_wifi", "state" to if (off) "off" else "on")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan(if (off) "Turning off Wi-Fi." else "Turning on Wi-Fi.", listOf(ActionPlan("SYSTEM_SWITCHBOARD", params)))
            )
        }

        if (text in listOf("turn on bluetooth", "bluetooth on karo", "bluetooth chalu karo", "turn off bluetooth", "bluetooth off karo", "bluetooth band karo")) {
            val off = text.contains("off") || text.contains("band")
            val params = mapOf("action" to "set_bluetooth", "state" to if (off) "off" else "on")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan(if (off) "Turning off Bluetooth." else "Turning on Bluetooth.", listOf(ActionPlan("SYSTEM_SWITCHBOARD", params)))
            )
        }

        // 1. Media stop / pause
        if (text in listOf("stop", "pause", "band karo", "gaana band karo", "stop music", "pause music", "stop video")) {
            return ResolvedIntent(
                intent = AssistantIntent.STOP_MEDIA,
                confidence = 0.98f,
                params = emptyMap(),
                directPlan = ExecutionPlan("Stopping playback.", listOf(ActionPlan("MEDIA_STOP", emptyMap())))
            )
        }

        // 1.1 Cancel current task ("cancel", "cancel task", "task cancel karo", "ruk jao")
        if (text in listOf("cancel", "cancel task", "cancel that", "task cancel karo", "cancel karo", "ruk jao", "stop task", "task band karo", "cancel everything")) {
            return ResolvedIntent(
                intent = AssistantIntent.CANCEL_TASK,
                confidence = 0.98f,
                params = emptyMap()
            )
        }

        // 1.2 Scan / List Installed Apps ("scan all apps", "list installed apps", "show apps")
        if (text in listOf(
            "scan apps", "scan all apps", "scan installed apps", "scan apps on device",
            "list apps", "list all apps", "list installed apps", "installed apps", "installed applications",
            "show all apps", "show apps", "koun koun se apps hai", "kon se app installed hai",
            "apps list", "all apps", "show installed apps"
        ) || text.startsWith("scan apps") || text.startsWith("scan all apps") || text.startsWith("list installed apps") || text.startsWith("scan installed apps")) {
            val action = if (text.contains("scan")) "scan" else "list"
            return ResolvedIntent(
                intent = AssistantIntent.APPS_LIST,
                confidence = 0.98f,
                params = mapOf("action" to action),
                directPlan = ExecutionPlan("Scanning installed applications.", listOf(ActionPlan("APPS_LIST", mapOf("action" to action))))
            )
        }

        // 1.3 Deep App Macro Shortcuts ("open storage care", "device storage", "free up space", "open whatsapp status", "whatsapp status")
        if (text in listOf("free up space", "open storage manager", "open storage care", "device storage", "storage saaf karo", "device care", "storage care") ||
            text.contains("storage care") || text.contains("device storage")) {
            val params = mapOf("action" to "run", "macro_id" to "macro_device_storage")
            return ResolvedIntent(
                intent = AssistantIntent.MACRO_RUN,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Opening Device Storage Care.", listOf(ActionPlan("MACRO_WORKFLOW", params)))
            )
        }

        if (text in listOf("open whatsapp status", "whatsapp status", "whatsapp updates", "show whatsapp status", "status dikhao") ||
            (text.contains("whatsapp") && (text.contains("status") || text.contains("updates")))) {
            val params = mapOf("action" to "run", "macro_id" to "macro_whatsapp_status")
            return ResolvedIntent(
                intent = AssistantIntent.MACRO_RUN,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Opening WhatsApp Status / Updates.", listOf(ActionPlan("MACRO_WORKFLOW", params)))
            )
        }

        // WebApp / Website creation check ("mere liye website banao", "make a website for me where...", "create webapp")
        val isCreationQuery = text.contains("banao") || text.contains("bana") || text.contains("website") ||
                text.contains("webapp") || text.contains("web app") || text.contains("create") ||
                text.contains("build") || text.contains("make") || text.contains("code") || text.contains("develop") || text.contains("design")

        // 2. Open App ("open youtube", "youtube kholo", "launch whatsapp", "open settings")
        val openAppPatterns = listOf(
            Regex("^(?:open|launch|kholo|chalu karo|start)\\s+([a-z0-9_\\s]+)$"),
            Regex("^([a-z0-9_\\s]+)\\s+(?:kholo|launch karo|chalu karo|open karo)$")
        )
        val actionKeywords = setOf(
            "check", "search", "play", "bajao", "chalao", "send", "bhejo", "call",
            "message", "messages", "massages", "unread", "read", "post", "scroll",
            "mode", "routine", "automation", "autopilot", "timer", "alarm"
        )
        if (!isCreationQuery) {
            for (pattern in openAppPatterns) {
                val match = pattern.find(text)
                if (match != null) {
                    val appRaw = match.groupValues[1].trim()
                    val words = appRaw.split(Regex("\\s+"))
                    if (words.size > 3 || words.any { it in actionKeywords }) {
                        continue
                    }
                    val targetApp = normalizeAppName(appRaw)
                    if (targetApp.isNotBlank()) {
                        return ResolvedIntent(
                            intent = AssistantIntent.APP_OPEN,
                            confidence = 0.95f,
                            params = mapOf("app" to targetApp),
                            directPlan = ExecutionPlan("Opening $targetApp.", listOf(ActionPlan("OPEN_APP", mapOf("app" to targetApp))))
                        )
                    }
                }
            }
        }

        // 2.1 Media Next Track ("next song", "skip song", "agla gaana", "next track", "skip")
        if (text in listOf("next", "next song", "next track", "skip", "skip song", "agla gaana", "agla song", "change song")) {
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_NEXT,
                confidence = 0.98f,
                params = mapOf("action" to "next"),
                directPlan = ExecutionPlan("Skipping to next track.", listOf(ActionPlan("MEDIA_CONTROL", mapOf("action" to "next"))))
            )
        }

        // 2.2 Media Previous Track ("previous song", "prev song", "pichla gaana", "previous track")
        if (text in listOf("previous", "previous song", "previous track", "prev track", "prev song", "pichla gaana", "pichhla gaana", "pichla song", "last song")) {
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_PREVIOUS,
                confidence = 0.98f,
                params = mapOf("action" to "previous"),
                directPlan = ExecutionPlan("Returning to previous track.", listOf(ActionPlan("MEDIA_CONTROL", mapOf("action" to "previous"))))
            )
        }

        // 2.3 Media Resume / Play ("resume", "resume music", "continue music", "gaana chalao", "wapas chalao")
        if (text in listOf("resume", "resume music", "continue music", "continue", "play music", "gaana wapas chalao", "wapas bajao")) {
            return ResolvedIntent(
                intent = AssistantIntent.MEDIA_RESUME,
                confidence = 0.98f,
                params = mapOf("action" to "play"),
                directPlan = ExecutionPlan("Resuming media playback.", listOf(ActionPlan("MEDIA_CONTROL", mapOf("action" to "play"))))
            )
        }

        // 2.4 Spotify Play ("play ... on spotify", "spotify pe ... chalao")
        if (text.contains("spotify")) {
            val query = text.replace(Regex("\\b(play|spotify|on|pe|chalao|bajao)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            val params = mapOf("query" to query)
            return ResolvedIntent(
                intent = AssistantIntent.SPOTIFY_PLAY,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Playing on Spotify: $query", listOf(ActionPlan("SPOTIFY_PLAY", params)))
            )
        }

        // 2.5 Lyrics ("lyrics of ...", "show lyrics for ...", "is gaane ke lyrics")
        if (text.contains("lyrics") || text.contains("bol")) {
            val query = text.replace(Regex("\\b(show|lyrics|for|of|gaane ke|bol|batao|dikhao|what are the)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            val params = mapOf("query" to "$query lyrics")
            return ResolvedIntent(
                intent = AssistantIntent.SEARCH_WEB,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Searching web for lyrics: $query", listOf(ActionPlan("SEARCH_WEB", params)))
            )
        }

        // 3. Play music / YouTube ("play believer", "youtube pe chalao kesariya", "open youtube and directly play song", "kesariya gaana bajao")
        if (!isCreationQuery) {
            val playPatterns = listOf(
                Regex("^(?:open\\s+youtube\\s+(?:and|aur)?\\s*(?:directly)?\\s*(?:play|chalao|bajao)|launch\\s+youtube\\s+and\\s+play)\\s*(.*)$"),
                Regex("^(?:directly\\s+)?(?:play|chalao|bajao)\\s+(.+?)(?:\\s+(?:on|in|pe|par)\\s+youtube)?$"),
                Regex("^youtube\\s+(?:pe|par)?\\s*(?:play|chalao|bajao)\\s*(.+)$"),
                Regex("^youtube\\s+(?:pe|par)\\s+(.+?)\\s+(?:play|chalao|bajao|play karo|chala do)$"),
                Regex("^(.+)\\s+(?:song|gaana|music)\\s+(?:play|chalao|bajao|chala do)$"),
                Regex("^(?:directly\\s+)?play\\s*(?:a\\s+)?song$")
            )
            for (pattern in playPatterns) {
                val match = pattern.find(text)
                if (match != null) {
                    var query = if (match.groupValues.size > 1) match.groupValues[1].trim() else ""
                    query = query.replace(Regex("\\b(directly|on youtube|in youtube|pe|par|chalao|bajao|play)\\b", RegexOption.IGNORE_CASE), "").trim()
                    if (query.isBlank() || query.equals("song", ignoreCase = true) || query.equals("gaana", ignoreCase = true)) {
                        query = "trending songs"
                    }
                    return ResolvedIntent(
                        intent = AssistantIntent.PLAY_MEDIA,
                        confidence = 0.94f,
                        params = mapOf("query" to query),
                        directPlan = ExecutionPlan("Playing $query.", listOf(ActionPlan("YOUTUBE_PLAY", mapOf("query" to query))))
                    )
                }
            }
        }

        // 4. Memory store / Note ("remember that my key is X", "yaad rakhna mera favourite X hai")
        val rememberPatterns = listOf(
            Regex("^(?:remember|note down|yaad rakhna|note karo)\\s+(?:that\\s+)?(.+)$")
        )
        for (pattern in rememberPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val fact = match.groupValues[1].trim()
                if (fact.isNotBlank()) {
                    // Derive a stable key from the fact content (first few words) so each
                    // "remember" statement gets its own slot instead of every entry
                    // overwriting a single hardcoded "fact" key (which silently destroyed
                    // all previously remembered facts).
                    val key = deriveNoteKey(fact)
                    return ResolvedIntent(
                        intent = AssistantIntent.REMEMBER_FACT,
                        confidence = 0.90f,
                        params = mapOf("key" to key, "value" to fact),
                        directPlan = ExecutionPlan("I have recorded that.", listOf(ActionPlan("NOTE", mapOf("key" to key, "value" to fact))))
                    )
                }
            }
        }

        // 4.1 Voice Quick Note & Knowledge Vault ("take a note", "create a note", "save note X", "quick note")
        val quickNoteMatch = Regex("^(?:take a note|create a note|quick note|save note|write note|note this down)\\s*(?:that|about|:|to)?\\s*(.+)?$", RegexOption.IGNORE_CASE).find(text)
        if (quickNoteMatch != null || text == "take a note" || text == "quick note" || text == "create a note") {
            val noteContent = quickNoteMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            val params = if (noteContent.isNotBlank()) mapOf("action" to "add", "content" to noteContent) else mapOf("action" to "list")
            val desc = if (noteContent.isNotBlank()) "Saving note and indexing into Knowledge Vault." else "Listing saved notes."
            return ResolvedIntent(
                intent = AssistantIntent.REMEMBER_FACT,
                confidence = 0.94f,
                params = params,
                directPlan = ExecutionPlan(desc, listOf(ActionPlan("QUICK_NOTES", params)))
            )
        }

        // 5. Memory query ("what did I tell you about X", "meri memory me kya hai")
        if (text.startsWith("what did i tell you") || text.startsWith("search memory") || text.contains("yaad hai")) {
            val query = text.replace(Regex("^(what did i tell you about|search memory for|kya tumhe yaad hai)\\s*"), "").trim()
            return ResolvedIntent(
                intent = AssistantIntent.QUERY_MEMORY,
                confidence = 0.88f,
                params = mapOf("query" to query),
                directPlan = ExecutionPlan("Searching local memory.", listOf(ActionPlan("SEARCH_MEMORY", mapOf("query" to query))))
            )
        }

        // 5.9 Background Deep Research & Reports ("research about tony stark and give me report", "make a report on X", "deep research on Y")
        val researchPatterns = listOf(
            Regex("^(?:research about|research on|deep research on|background research on|research)\\s+(.+?)(?:\\s+(?:and give me report|and make report|and prepare report|and generate report|report))?$"),
            Regex("^(?:make a report on|make report on|prepare a report on|prepare report on|report on)\\s+(.+?)$"),
            Regex("^(.+?)\\s+(?:par research karo|ki report banao|report banao)$")
        )
        for (pattern in researchPatterns) {
            val match = pattern.find(text)
            if (match != null) {
                val topic = match.groupValues[1]
                    .replace("and give me report", "")
                    .replace("and make report", "")
                    .replace("and prepare report", "")
                    .replace("and generate report", "")
                    .trim()
                if (topic.isNotBlank() && !topic.startsWith("document") && !topic.startsWith("file")) {
                    return ResolvedIntent(
                        intent = AssistantIntent.DEEP_RESEARCH,
                        confidence = 0.95f,
                        params = mapOf("topic" to topic),
                        directPlan = ExecutionPlan("Conducting background research on $topic and generating report in JARVIS Reports folder.", listOf(ActionPlan("RESEARCH_DEEP", mapOf("topic" to topic))))
                    )
                }
            }
        }

        // 6. Web search ("search weather in delhi", "google search openai")
        if ((text.startsWith("search ") || text.startsWith("google ")) &&
            !text.startsWith("search document") &&
            !text.startsWith("search notes") &&
            !text.startsWith("search file") &&
            !text.startsWith("search memory") &&
            !text.startsWith("search knowledge")
        ) {
            val query = text.substringAfter(" ").trim()
            if (query.isNotBlank()) {
                return ResolvedIntent(
                    intent = AssistantIntent.SEARCH_WEB,
                    confidence = 0.90f,
                    params = mapOf("query" to query),
                    directPlan = ExecutionPlan("Searching for $query.", listOf(ActionPlan("SEARCH_WEB", mapOf("query" to query))))
                )
            }
        }

        // 7. Screen Vision intent ("what is on my screen", "is screen pe kya hai", "read this", etc.)
        val visionTriggers = listOf(
            "what is on my screen", "what's on my screen", "look at my screen",
            "look at this", "look at the screen", "read my screen", "read the screen",
            "read this", "read this message", "read this text", "summarize screen",
            "summarize this", "summarize page", "summarize article", "what does this say",
            "check screen", "analyze screen", "explain this", "explain screen", "explain what you see",
            "read error on screen", "what is this error", "screen error", "check screen error", "crash error",
            "extract code", "extract otp", "extract text", "read the text",
            "is screen pe kya hai", "screen pe kya hai", "ye screen dekho",
            "screen dekho", "ye dekh ke batao", "ye padh ke batao", "padh ke batao",
            "kya likha hai", "screen samjhao", "ye kya hai", "error samjhao", "page samjhao"
        )
        val isScreenQuery = !text.contains("log") && (visionTriggers.any { text.contains(it) } ||
            (text.contains("screen") && (text.contains("error") || text.contains("crash") || text.contains("bug") || text.contains("issue") || text.contains("problem") || text.contains("summar") || text.contains("page") || text.contains("explain"))))
        if (isScreenQuery) {
            val mode = when {
                text.contains("summariz") || text.contains("short") || text.contains("summary") -> "summarize"
                text.contains("error") || text.contains("crash") || text.contains("bug") || text.contains("issue") || text.contains("fail") -> "error_check"
                text.contains("extract") || text.contains("code") || text.contains("otp") || text.contains("number") -> "extract"
                else -> "explain"
            }
            return ResolvedIntent(
                intent = AssistantIntent.SCREEN_VISION,
                confidence = 0.95f,
                params = mapOf("query" to utterance, "mode" to mode),
                directPlan = ExecutionPlan(
                    "Analyzing screen visual context.",
                    listOf(ActionPlan("SCREEN_VISION", mapOf("query" to utterance, "mode" to mode)))
                )
            )
        }

        // 8. Battery Check ("battery check", "battery kitni hai", "how much battery", etc.)
        val batteryTriggers = listOf(
            "battery", "battery check", "check battery", "battery level",
            "battery percentage", "how much battery", "battery kitni hai",
            "battery kitna hai", "battery bachi hai", "charging status"
        )
        if (batteryTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            return ResolvedIntent(
                intent = AssistantIntent.BATTERY_CHECK,
                confidence = 0.96f,
                params = emptyMap(),
                directPlan = ExecutionPlan("Checking battery status.", listOf(ActionPlan("BATTERY_CHECK", emptyMap())))
            )
        }

        // 9. Daily Briefing ("briefing", "daily briefing", "morning briefing", "status report", "good morning")
        val briefingTriggers = listOf(
            "briefing", "daily briefing", "morning briefing", "status report",
            "good morning", "subah ki report", "kya chal raha hai", "system status"
        )
        if (briefingTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            return ResolvedIntent(
                intent = AssistantIntent.DAILY_BRIEFING,
                confidence = 0.95f,
                params = emptyMap(),
                directPlan = ExecutionPlan("Preparing daily briefing.", listOf(ActionPlan("DAILY_BRIEFING", emptyMap())))
            )
        }

        // 10. Read Notifications / Digest / Clear Spam
        val isDigest = text.contains("digest") || (text.contains("summarize") && text.contains("notification")) ||
            text.contains("notifications summary") || text.contains("what did i miss") || text.contains("triage")
        val isClearSpam = (text.contains("clear") || text.contains("delete") || text.contains("remove") || text.contains("hatao")) &&
            (text.contains("spam") || text.contains("promo") || text.contains("junk"))
        val notifTriggers = listOf(
            "read notifications", "check notifications", "what notifications do i have",
            "read my notifications", "any new notifications", "any new messages",
            "kiska notification aaya", "kiska message aaya", "notifications padh",
            "notifications padh ke batao", "whatsapp notification", "whatsapp message check",
            "summarize notifications", "notification digest", "priority notifications",
            "what did i miss", "clear spam notifications", "clear spam", "spam notifications"
        )
        if (isDigest || isClearSpam || notifTriggers.any { text.contains(it) }) {
            val action = when {
                isClearSpam -> "clear_spam"
                isDigest -> "digest"
                text.contains("priority") -> "priority"
                else -> "read"
            }
            val app = if (text.contains("whatsapp")) "whatsapp" else ""
            val params = mutableMapOf("action" to action)
            if (app.isNotBlank()) params["app"] = app
            val desc = when (action) {
                "digest" -> "Generating priority notification digest."
                "clear_spam" -> "Clearing promotional and spam notifications."
                "priority" -> "Reading priority personal notifications."
                else -> "Reading recent notifications."
            }
            return ResolvedIntent(
                intent = AssistantIntent.READ_NOTIFICATIONS,
                confidence = 0.95f,
                params = params,
                directPlan = ExecutionPlan(desc, listOf(ActionPlan("NOTIFICATIONS_READ", params)))
            )
        }

        // 11. Habit Suggestions ("what should i do", "suggest action", "kya karna chahiye", "kya karu", etc.)
        val habitSuggestTriggers = listOf(
            "what should i do", "suggest what to do", "suggest action", "proactive suggestion",
            "kya karna chahiye", "kuch suggest karo", "kya karu", "kya karun", "kya karein",
            "what do i usually do", "kya karta hoon"
        )
        if (habitSuggestTriggers.any { text.contains(it) }) {
            return ResolvedIntent(
                intent = AssistantIntent.HABIT_SUGGEST,
                confidence = 0.95f,
                params = emptyMap(),
                directPlan = ExecutionPlan("Generating personalized habit suggestion.", listOf(ActionPlan("HABIT_SUGGEST", emptyMap())))
            )
        }

        // 12. Habit List ("what are my habits", "my habits", "list habits", "meri habits", "routines")
        val habitListTriggers = listOf(
            "what are my habits", "my habits", "list habits", "show habits", "learned habits",
            "meri habits", "meri kya habits hain", "aadatein", "mera routine", "routine batao",
            "routines", "my routines", "list routines", "show routines"
        )
        if (habitListTriggers.any { text.contains(it) }) {
            return ResolvedIntent(
                intent = AssistantIntent.HABIT_LIST,
                confidence = 0.95f,
                params = emptyMap(),
                directPlan = ExecutionPlan("Listing learned user habits.", listOf(ActionPlan("HABIT_LIST", emptyMap())))
            )
        }

        // 13. Smart Routine Run ("run bedtime routine", "start work routine", "bedtime routine chalu karo", etc.)
        val routineKeywords = listOf("routine", "mode", "chalu", "run", "start", "trigger")
        val routineTargets = listOf("bedtime", "night", "work", "office", "home", "low battery", "battery saver")
        if (routineTargets.any { text.contains(it) } && routineKeywords.any { text.contains(it) }) {
            val matchedId = when {
                text.contains("bedtime") || text.contains("night") -> "routine_bedtime"
                text.contains("work") || text.contains("office") -> "routine_work_mode"
                text.contains("home") -> "routine_home_arrival"
                text.contains("battery") -> "routine_low_battery"
                else -> ""
            }
            if (matchedId.isNotBlank()) {
                val params = mapOf("action" to "run", "routine_id" to matchedId)
                return ResolvedIntent(
                    intent = AssistantIntent.ROUTINE_RUN,
                    confidence = 0.96f,
                    params = params,
                    directPlan = ExecutionPlan("Running $matchedId.", listOf(ActionPlan("ROUTINE_MANAGE", params)))
                )
            }
        }

        // 14. Smart Routine List ("list automations", "show automations", "kya automations hain")
        val routineListTriggers = listOf(
            "list automations", "show automations", "active automations", "kya automations hain",
            "automations dikhao", "list smart routines", "smart routines"
        )
        if (routineListTriggers.any { text.contains(it) }) {
            val params = mapOf("action" to "list")
            return ResolvedIntent(
                intent = AssistantIntent.ROUTINE_LIST,
                confidence = 0.95f,
                params = params,
                directPlan = ExecutionPlan("Listing smart routines.", listOf(ActionPlan("ROUTINE_MANAGE", params)))
            )
        }

        // 15. Macro Run & List ("run macro ...", "macro chalu karo", "list macros")
        if (text.contains("macro") || text.contains("workflow")) {
            if (text.contains("list") || text.contains("show") || text.contains("dikhao") || text.contains("kya")) {
                val params = mapOf("action" to "list")
                return ResolvedIntent(
                    intent = AssistantIntent.MACRO_LIST,
                    confidence = 0.96f,
                    params = params,
                    directPlan = ExecutionPlan("Listing available UI macros.", listOf(ActionPlan("MACRO_WORKFLOW", params)))
                )
            }

            val targetMacroId = when {
                text.contains("clear") || text.contains("close all") -> "macro_clear_apps"
                text.contains("youtube") -> "macro_youtube_search"
                text.contains("update") -> "macro_software_update"
                text.contains("home") -> "macro_quick_home"
                text.contains("storage") || text.contains("clean") || text.contains("space") -> "macro_device_storage"
                text.contains("status") || text.contains("updates") -> "macro_whatsapp_status"
                else -> ""
            }

            if (targetMacroId.isNotBlank()) {
                val params = mapOf("action" to "run", "macro_id" to targetMacroId)
                return ResolvedIntent(
                    intent = AssistantIntent.MACRO_RUN,
                    confidence = 0.96f,
                    params = params,
                    directPlan = ExecutionPlan("Running $targetMacroId.", listOf(ActionPlan("MACRO_WORKFLOW", params)))
                )
            }
        }

        // 16. Telephony Call & Dial ("call mom", "dial 911", "papa ko call karo", "test calling doller")
        //     Exclude: reject/end/answer/kaat keywords so they fall through to rules 33-34
        val callAnswerRejectKeywords = listOf("kaat", "kato", "reject", "decline", "end call", "disconnect", "answer", "uthao", "receive")
        val isCallAnswerRejectContext = callAnswerRejectKeywords.any { text.contains(it) }
        if (!text.contains("whatsapp") && !isCallAnswerRejectContext &&
            (text.startsWith("call ") || text.startsWith("dial ") || text.startsWith("calling ") || text.startsWith("test calling ") ||
             text.contains("phone lagao") || text.contains("call karo") || text.contains("call lagao") || text.contains("ko call"))
        ) {
            val isDial = text.startsWith("dial")
            val target = text.replace(Regex("\\b(test|calling|call|dial|phone|lagao|karo|ko|please|karna hai)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (target.isNotBlank()) {
                val action = if (isDial) "dial" else "call"
                val params = mapOf("action" to action, "recipient" to target)
                return ResolvedIntent(
                    intent = AssistantIntent.TELEPHONY_CALL,
                    confidence = 0.96f,
                    params = params,
                    directPlan = ExecutionPlan("Initiating call to $target.", listOf(ActionPlan("TELEPHONY_CONTROL", params)))
                )
            }
        }

        // 17. Telephony SMS ("send sms to rahul", "text alex")
        if (!text.contains("whatsapp") && (text.startsWith("send sms") || text.startsWith("sms ") || text.startsWith("text "))) {
            val target = text.replace(Regex("\\b(send|sms|text|to|message)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            if (target.isNotBlank()) {
                val params = mapOf("action" to "sms_draft", "recipient" to target)
                return ResolvedIntent(
                    intent = AssistantIntent.TELEPHONY_SMS,
                    confidence = 0.95f,
                    params = params,
                    directPlan = ExecutionPlan("Opening SMS draft for $target.", listOf(ActionPlan("TELEPHONY_CONTROL", params)))
                )
            }
        }

        // 18. Calendar Agenda & Schedule ("today's agenda", "aaj ka schedule", "what is on my calendar")
        val agendaTriggers = listOf("today's agenda", "today agenda", "aaj ka schedule", "what is on my calendar", "my schedule today", "calendar agenda")
        if (agendaTriggers.any { text.contains(it) }) {
            val params = mapOf("action" to "today_agenda")
            return ResolvedIntent(
                intent = AssistantIntent.CALENDAR_AGENDA,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Retrieving today's agenda.", listOf(ActionPlan("CALENDAR_MANAGE", params)))
            )
        }

        if (text.startsWith("schedule meeting") || text.startsWith("schedule event")) {
            val details = text.replace("schedule", "").trim()
            val params = mapOf("action" to "add_event", "title" to details, "time" to text)
            return ResolvedIntent(
                intent = AssistantIntent.CALENDAR_SCHEDULE,
                confidence = 0.95f,
                params = params,
                directPlan = ExecutionPlan("Scheduling: $details.", listOf(ActionPlan("CALENDAR_MANAGE", params)))
            )
        }

        // 19. Storage & App Permissions ("give storage permission", "storage permission do", "open permission settings")
        if (text.contains("storage permission") || text.contains("storage permissions") ||
            text.contains("grant storage") || text.contains("allow storage") ||
            text.contains("permission do") || text.contains("permission de") ||
            text in listOf("give permission", "give permissions", "grant permissions", "allow permissions", "open app settings", "permission settings")) {
            val params = mapOf("action" to "storage_permission")
            return ResolvedIntent(
                intent = AssistantIntent.DEVICE_SETTINGS,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Opening application storage permission settings.", listOf(ActionPlan("DEVICE_SETTINGS", params)))
            )
        }

        // 19.1 Security & Privacy Audit ("check app permissions", "privacy audit", "privacy score", "scan security")
        val securityTriggers = listOf("check permissions", "check app permissions", "privacy audit", "privacy score", "scan security", "who has access to my camera", "camera and mic permissions")
        if (securityTriggers.any { text.contains(it) }) {
            val action = if (text.contains("camera") || text.contains("mic")) "scan_camera_mic_apps" else if (text.contains("score")) "privacy_score" else "audit_permissions"
            val params = mapOf("action" to action)
            return ResolvedIntent(
                intent = AssistantIntent.SECURITY_AUDIT,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Performing security and privacy inspection.", listOf(ActionPlan("SECURITY_AUDIT", params)))
            )
        }

        // 20. File Search & Storage ("find file ...", "storage breakdown", "check storage")
        if (text.contains("storage breakdown") || text.contains("check storage") || text == "storage") {
            val params = mapOf("action" to "storage_breakdown")
            return ResolvedIntent(
                intent = AssistantIntent.FILE_SEARCH,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Retrieving device storage statistics.", listOf(ActionPlan("FILE_MANAGER", params)))
            )
        }

        if (text.startsWith("find file ") || text.startsWith("search file ")) {
            val query = text.replace(Regex("^(?:find|search)\\s+file\\s+"), "").trim()
            val params = mapOf("action" to "search_file", "query" to query)
            return ResolvedIntent(
                intent = AssistantIntent.FILE_SEARCH,
                confidence = 0.95f,
                params = params,
                directPlan = ExecutionPlan("Searching for file: $query.", listOf(ActionPlan("FILE_MANAGER", params)))
            )
        }

        // 21. Autonomous Autopilot & Ambient Modes
        if (text in listOf("disable autopilot", "autopilot off", "turn off autopilot", "stop autopilot", "autopilot band karo", "disable autonomous mode")) {
            val params = mapOf("action" to "disable_autopilot")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Disengaging autonomous autopilot.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text in listOf("enable autopilot", "autopilot on", "turn on autopilot", "jarvis take over", "auto pilot on", "autonomous mode", "autonomous mode chalu karo")) {
            val params = mapOf("action" to "enable_autopilot")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Engaging autonomous autopilot.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text.contains("driving mode")) {
            val params = mapOf("action" to "set_ambient_mode", "mode" to "driving")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Activating Driving Ambient Mode.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text.contains("meeting mode")) {
            val params = mapOf("action" to "set_ambient_mode", "mode" to "meeting")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Activating Meeting Ambient Mode.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text.contains("focus mode") || text.contains("deep work")) {
            val params = mapOf("action" to "set_ambient_mode", "mode" to "focus")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Activating Deep Focus Mode.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text.contains("night mode") || text.contains("sleep mode")) {
            val params = mapOf("action" to "set_ambient_mode", "mode" to "night")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Activating Night Wind-Down Mode.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text.contains("workout mode") || text.contains("gym mode")) {
            val params = mapOf("action" to "set_ambient_mode", "mode" to "workout")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Activating Workout Session Mode.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text.contains("auto mode") || text.contains("automatic mode") || text.contains("sensor mode")) {
            val params = mapOf("action" to "set_ambient_mode", "mode" to "auto")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Switching to Automatic Ambient Mode.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text in listOf("autopilot status", "autonomous status", "autopilot kya hai")) {
            val params = mapOf("action" to "status")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Checking autonomous autopilot status.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        if (text in listOf("decision logs", "autonomous logs", "autopilot history", "autopilot log", "decision log")) {
            val params = mapOf("action" to "recent_actions")
            return ResolvedIntent(
                intent = AssistantIntent.AUTONOMOUS_CONTROL,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Retrieving recent autonomous decisions.", listOf(ActionPlan("AUTONOMOUS_CONTROL", params)))
            )
        }

        // 27. RAG Knowledge Vault Index
        val indexPatterns = listOf(
            Regex("^(?:index|add to vault|save to vault|save document)\\s+(.+)$"),
            Regex("^(.+)\\s+(?:ko index karo|vault me dalo|vault me save karo)$")
        )
        for (p in indexPatterns) {
            val match = p.find(text)
            if (match != null) {
                val target = match.groupValues[1].trim()
                val params = mapOf("content" to target)
                return ResolvedIntent(
                    intent = AssistantIntent.RAG_INDEX,
                    confidence = 0.94f,
                    params = params,
                    directPlan = ExecutionPlan("Indexing into Knowledge Vault.", listOf(ActionPlan("RAG_INDEX", params)))
                )
            }
        }

        // 28. RAG Knowledge Vault Retrieval ("search documents for...", "find in notes...", "what do my notes say about...")
        val ragPatterns = listOf(
            Regex("^(?:search documents|search notes|search knowledge|find in documents|find in notes|vault search)\\s+(?:for\\s+)?(.+)$"),
            Regex("^(?:what do my notes say about|what does the doc say about|notes me kya hai)\\s+(.+)$"),
            Regex("^(.+)\\s+(?:documents me dhundo|notes me dhundo|vault me dhundo)$")
        )
        for (p in ragPatterns) {
            val match = p.find(text)
            if (match != null) {
                val q = match.groupValues[1].trim()
                val params = mapOf("query" to q)
                return ResolvedIntent(
                    intent = AssistantIntent.RAG_QUERY,
                    confidence = 0.95f,
                    params = params,
                    directPlan = ExecutionPlan("Searching Knowledge Vault for \"$q\".", listOf(ActionPlan("RAG_RETRIEVE", params)))
                )
            }
        }

        // 29. Camera Optical Vision & Real-Time Object Perception
        val cameraTriggers = listOf(
            "what am i looking at", "what's this object", "what is this object",
            "look at this", "identify this object", "describe what you see",
            "camera look", "camera scan", "what is in front of me",
            "camera se dekho", "camera on karke dekho", "samne kya hai", "camera se padho"
        )
        if (cameraTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            val facing = if (text.contains("selfie") || text.contains("front")) "front" else "back"
            val params = mapOf("query" to text, "facing" to facing)
            return ResolvedIntent(
                intent = AssistantIntent.CAMERA_VISION,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Looking through camera lens to identify object.", listOf(ActionPlan("CAMERA_VISION", params)))
            )
        }

        // 30. WhatsApp Read & Message Aggregation ("read whatsapp", "whatsapp padho", "koi whatsapp aaya", "check unread whatsapp")
        val waReadTriggers = listOf(
            "read whatsapp", "check whatsapp", "read my whatsapp", "whatsapp padho",
            "whatsapp messages padho", "koi whatsapp aaya", "kisi ka whatsapp aaya",
            "unread whatsapp", "whatsapp par kya aaya", "check unread whatsapp",
            "whatsapp message padho"
        )
        if (waReadTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            val params = mapOf("action" to "read_messages")
            return ResolvedIntent(
                intent = AssistantIntent.WHATSAPP_READ,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Reading recent WhatsApp messages.", listOf(ActionPlan("WHATSAPP", params)))
            )
        }

        // 31. WhatsApp Auto-Reply ("turn on auto reply", "whatsapp auto reply driving", "auto reply off")
        val autoReplyTriggers = listOf(
            "auto reply", "autoreply", "whatsapp auto reply"
        )
        if (autoReplyTriggers.any { text.contains(it) }) {
            val mode = when {
                text.contains("off") || text.contains("stop") || text.contains("band") -> "off"
                text.contains("driving") || text.contains("drive") -> "driving"
                text.contains("meeting") || text.contains("meet") -> "meeting"
                text.contains("busy") -> "busy"
                else -> "driving"
            }
            val params = mapOf("action" to "auto_reply", "mode" to mode)
            return ResolvedIntent(
                intent = AssistantIntent.WHATSAPP_AUTO_REPLY,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Configuring WhatsApp auto-reply mode to $mode.", listOf(ActionPlan("WHATSAPP", params)))
            )
        }

        // 31.1 WhatsApp Send Message ("Doller ko hello send karo whatsapp pe", "send hello to Doller on whatsapp", "whatsapp Doller hello")
        if (text.contains("whatsapp") || text.contains("wa ")) {
            val waSendPatterns = listOf(
                Regex("^(.+?)\\s+ko\\s+(.+?)\\s+(?:send|bhejo|bhej do|message|msg)\\s*(?:karo)?\\s*(?:whatsapp|wa)\\s*(?:pe|par|me|in)?$"),
                Regex("^(?:whatsapp|wa)\\s+(?:pe|par|me)?\\s*(?:message|msg)?\\s*(?:karo)?\\s*(.+?)\\s+ko\\s+(.+?)(?:\\s+(?:send|bhejo|message|msg|karo)\\s*(?:karo)?)?$"),
                Regex("^(.+?)\\s+ko\\s+(?:whatsapp|wa)\\s*(?:pe|par|me)?\\s*(?:message|msg)?\\s*(.+?)(?:\\s+(?:send|bhejo|karo|bhej do))?$"),
                Regex("^(?:send|bhejo)\\s+(.+?)\\s+(?:to|ko)\\s+(.+?)\\s+(?:on|in|pe|par)\\s*(?:whatsapp|wa)$"),
                Regex("^(?:send\\s+)?(?:whatsapp|wa)\\s*(?:message|msg)?\\s*(?:to|ko)?\\s*([a-zA-Z0-9_]+)\\s+(?:saying|that|message|msg|:)?\\s*(.+)$")
            )
            for (p in waSendPatterns) {
                val m = p.find(text)
                if (m != null) {
                    val (rawRecipient, rawMessage) = if (p == waSendPatterns[3]) {
                        Pair(m.groupValues[2].trim(), m.groupValues[1].trim())
                    } else {
                        Pair(m.groupValues[1].trim(), m.groupValues[2].trim())
                    }
                    val recipient = rawRecipient.replace(Regex("\\b(whatsapp|wa|pe|par|me|ko|to|message|msg|send)\\b"), "").trim()
                    val message = rawMessage.replace(Regex("\\b(whatsapp|wa|pe|par|me|bhejo|send|karo|bhej do)\\b"), "").trim()
                    val messageIsPlaceholder = message.equals("message", ignoreCase = true) ||
                            message.equals("msg", ignoreCase = true)
                    if (recipient.isNotBlank() && message.isNotBlank() && !messageIsPlaceholder &&
                        recipient != "auto" && recipient != "read") {
                        val params = mapOf("action" to "send_message", "recipient" to recipient, "message" to message)
                        return ResolvedIntent(
                            intent = AssistantIntent.WHATSAPP_SEND,
                            confidence = 0.96f,
                            params = params,
                            directPlan = ExecutionPlan("Sending WhatsApp message to $recipient.", listOf(ActionPlan("WHATSAPP", params)))
                        )
                    }
                }
            }
        }

        // 32. System Logs & Telemetry ("read logs", "logs padho", "show logs", "check logs", "logcat")
        val logTriggers = listOf(
            "read logs", "read log", "show logs", "show log", "check logs", "check log",
            "system logs", "system log", "view logs", "device logs", "show logcat",
            "read logcat", "logs padho", "logs dikhao", "logcat dikhao", "kya error aaya",
            "error check karo", "kya hua tha", "recent logs", "check error logs",
            "error logs", "error log", "errors check karo", "error log dikhao"
        )
        if (logTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            val filter = if (text.contains("error")) "error" else ""
            val params = if (filter.isNotBlank()) mapOf("filter" to filter) else emptyMap()
            return ResolvedIntent(
                intent = AssistantIntent.READ_LOGS,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Reading live system telemetry logs.", listOf(ActionPlan("LOGS_READ", params)))
            )
        }

        // 33. Telephony Answer Call ("answer call", "phone uthao", "call receive karo", "pick up call", "answer")
        val answerTriggers = listOf(
            "answer call", "answer the call", "pick up the call", "pick up call", "pick up",
            "phone uthao", "call uthao", "call receive karo", "phone receive karo", "call receive",
            "answer phone", "accept call", "call accept karo", "phone pick karo", "answer", "uthalo", "haan uthao"
        )
        if (answerTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            val params = mapOf("action" to "answer_call")
            return ResolvedIntent(
                intent = AssistantIntent.TELEPHONY_ANSWER,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Answering incoming call hands-free.", listOf(ActionPlan("TELEPHONY_CONTROL", params)))
            )
        }

        // Telephony Caller Info ("who is calling", "kaun call kar raha hai", "kiska call hai")
        val callerInfoTriggers = listOf(
            "who is calling", "who's calling", "who is calling me", "who is on the phone",
            "kiska call hai", "kaun call kar raha hai", "kaun hai call pe", "kiska phone hai"
        )
        if (callerInfoTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            val params = mapOf("action" to "caller_info")
            return ResolvedIntent(
                intent = AssistantIntent.TELEPHONY_CALLER_INFO,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Checking incoming caller identity.", listOf(ActionPlan("TELEPHONY_CONTROL", params)))
            )
        }

        // Telephony Reject Call with SMS
        if ((text.contains("reject") || text.contains("kaat") || text.contains("cut") || text.contains("decline") || text.contains("bol do")) &&
            (text.contains("busy") || text.contains("sms") || text.contains("message") || text.contains("meeting") || text.contains("driving"))) {
            val msg = when {
                text.contains("driving") -> "I am driving right now, will call you later."
                text.contains("meeting") -> "I am in a meeting, will get back to you soon."
                else -> "I am currently busy, will call you back later."
            }
            val params = mapOf("action" to "reject_call", "message" to msg)
            return ResolvedIntent(
                intent = AssistantIntent.TELEPHONY_REJECT,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Rejecting incoming call with auto-reply message.", listOf(ActionPlan("TELEPHONY_CONTROL", params)))
            )
        }

        // 34. Telephony Reject Call ("reject call", "call kaat do", "decline call", "phone kato", "reject")
        val rejectTriggers = listOf(
            "reject call", "reject the call", "decline call", "decline the call", "cut the call",
            "call kaat do", "phone kaat do", "call reject karo", "phone reject karo", "phone kato",
            "call kato", "disconnect call", "end call", "reject", "decline", "cut", "kat do"
        )
        if (rejectTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            val params = mapOf("action" to "reject_call")
            return ResolvedIntent(
                intent = AssistantIntent.TELEPHONY_REJECT,
                confidence = 0.98f,
                params = params,
                directPlan = ExecutionPlan("Rejecting incoming call.", listOf(ActionPlan("TELEPHONY_CONTROL", params)))
            )
        }

        // 35. Read SMS & OTP ("read sms", "check sms", "sms padho", "what is my otp", "otp kya aaya")
        val smsReadTriggers = listOf(
            "read sms", "check sms", "read my sms", "read text message", "sms padho",
            "message padho", "koi sms aaya", "check message", "read messages", "what is my otp",
            "get otp", "read otp", "otp kya hai", "otp batao", "otp kya aaya", "kiska sms aaya",
            "latest sms", "recent sms"
        )
        if (smsReadTriggers.any { text == it || text.startsWith("$it ") || text.endsWith(" $it") }) {
            val isOtp = text.contains("otp")
            val params = if (isOtp) mapOf("action" to "get_otp") else mapOf("action" to "sms_read")
            val desc = if (isOtp) "Extracting verification OTP code from recent SMS." else "Reading incoming SMS messages."
            return ResolvedIntent(
                intent = AssistantIntent.TELEPHONY_SMS_READ,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan(desc, listOf(ActionPlan("TELEPHONY_CONTROL", params)))
            )
        }

        // 36. Timer, Alarm & Reminder ("set a timer for 5 minutes", "set alarm 7 am", "remind me in 20 minutes to X", "timer chalao")
        if (text.contains("timer")) {
            val whenText = text.replace(Regex("\\b(set|a|for|timer|chalao|the)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            val params = mapOf("action" to "set_timer", "when" to whenText)
            return ResolvedIntent(
                intent = AssistantIntent.SET_TIMER,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Setting a timer.", listOf(ActionPlan("REMINDER_SCHEDULE", params)))
            )
        }

        if (text.contains("alarm")) {
            val whenText = text.replace(Regex("\\b(set|alarm|at|chalao|karo|the)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            val params = mapOf("action" to "set_alarm", "time" to whenText)
            return ResolvedIntent(
                intent = AssistantIntent.SET_ALARM,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Setting an alarm.", listOf(ActionPlan("REMINDER_SCHEDULE", params)))
            )
        }

        if (text.startsWith("remind me") || text.contains("yaad dila") || text.contains("reminder")) {
            val reachMatch = Regex("(?:remind me to|yaad dilana)\\s+(.+?)\\s+(?:when i reach|reach|at|jab pahunchu)\\s+(home|work|office|ghar)", RegexOption.IGNORE_CASE).find(text)
            if (reachMatch != null) {
                val reminderTask = reachMatch.groupValues[1].trim()
                val rawTarget = reachMatch.groupValues[2].trim().lowercase()
                val targetLoc = if (rawTarget == "ghar") "home" else if (rawTarget == "office") "work" else rawTarget
                val params = mapOf("action" to "remind_at", "target" to targetLoc, "reminder" to reminderTask)
                return ResolvedIntent(
                    intent = AssistantIntent.WHERE_AM_I,
                    confidence = 0.97f,
                    params = params,
                    directPlan = ExecutionPlan("Setting location reminder for $targetLoc.", listOf(ActionPlan("LOCATION", params)))
                )
            }
            val params = mapOf("action" to "remind_me", "what" to text)
            return ResolvedIntent(
                intent = AssistantIntent.REMIND_ME,
                confidence = 0.96f,
                params = params,
                directPlan = ExecutionPlan("Scheduling a reminder.", listOf(ActionPlan("REMINDER_SCHEDULE", params)))
            )
        }

        // 37. Where am I, Ambient Context & Geofence Configuration
        if (text.contains("set home location") || text.contains("set home wifi") || text.contains("this is home") || text.contains("save home")) {
            val params = mapOf("action" to "set_home")
            return ResolvedIntent(
                intent = AssistantIntent.WHERE_AM_I,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Saving current location as home.", listOf(ActionPlan("LOCATION", params)))
            )
        }

        if (text.contains("set work location") || text.contains("set work wifi") || text.contains("this is work") || text.contains("save work") || text.contains("set office")) {
            val params = mapOf("action" to "set_work")
            return ResolvedIntent(
                intent = AssistantIntent.WHERE_AM_I,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Saving current location as work.", listOf(ActionPlan("LOCATION", params)))
            )
        }

        if (text.contains("check context") || text.contains("what is my context") || text.contains("ambient mode") || text.contains("current context")) {
            val params = mapOf("action" to "check_context")
            return ResolvedIntent(
                intent = AssistantIntent.WHERE_AM_I,
                confidence = 0.97f,
                params = params,
                directPlan = ExecutionPlan("Checking ambient context.", listOf(ActionPlan("LOCATION", params)))
            )
        }

        if (text.contains("where am i") || text.contains("my location") || text.contains("mera location") ||
            (text.contains("location") && !text.contains("set"))) {
            val params = mapOf("action" to "where_am_i")
            return ResolvedIntent(
                intent = AssistantIntent.WHERE_AM_I,
                confidence = 0.95f,
                params = params,
                directPlan = ExecutionPlan("Fetching your location.", listOf(ActionPlan("LOCATION", params)))
            )
        }

        // 38. Read messages ("read my messages", "read whatsapp", "check messages padho")
        if ((text.contains("read") || text.contains("check") || text.contains("padho")) &&
            (text.contains("message") || text.contains("msg") || text.contains("sms") || text.contains("whatsapp"))) {
            val action = when {
                text.contains("whatsapp") && !text.contains("sms") && !text.contains("message") -> "read_whatsapp"
                (text.contains("sms") || text.contains("text")) && !text.contains("whatsapp") && !text.contains("message") -> "read_sms"
                else -> "read_all"
            }
            val params = mapOf("action" to action)
            return ResolvedIntent(
                intent = AssistantIntent.READ_MESSAGES,
                confidence = 0.95f,
                params = params,
                directPlan = ExecutionPlan("Reading recent messages.", listOf(ActionPlan("MESSAGE_READER", params)))
            )
        }

        return null
    }

    /**
     * Builds a stable memory key from fact content: the leading words, lowercased,
     * hyphen-joined, capped at 5 words. Two facts that start the same way share a
     * slot; each distinct topic is archived separately when superseded.
     */
    fun deriveNoteKey(fact: String): String {
        val words = fact.lowercase(Locale.ROOT).split(Regex("\\s+")).filter { it.isNotBlank() && it !in listOf("the", "a", "an", "is", "are", "my", "mera", "mere", "ha") }
        val base = words.take(5).joinToString("-")
        return if (base.isBlank()) "fact" else base
    }

    private fun normalizeAppName(raw: String): String {
        val trimmed = raw.trim().lowercase(Locale.ROOT)
        if (com.jarvis.tools.AppRegistry.ALLOWED_PACKAGES.containsKey(trimmed)) {
            return trimmed
        }
        return when {
            trimmed in listOf("yt", "ytube") -> "youtube"
            trimmed in listOf("insta", "ig") -> "instagram"
            trimmed in listOf("wp", "wa", "whats app", "whats up") -> "whatsapp"
            trimmed in listOf("y music", "yt music", "youtube music") -> "ymusic"
            trimmed in listOf("mx", "mx player", "mxplayer", "mx pro", "mx player pro", "mxplayer pro") -> "mx player"
            trimmed in listOf("samsung note", "samsung notes", "s notes", "s-notes", "note", "notes") -> "samsung notes"
            trimmed in listOf("gaming hub", "game hub", "game launcher", "samsung game launcher", "samsung gaming hub") -> "gaming hub"
            trimmed in listOf("chatgpt", "chat gpt", "openai", "open ai") -> "chatgpt"
            trimmed in listOf("gemini", "google gemini", "bard") -> "gemini"
            trimmed in listOf("google", "google search", "google app") -> "google"
            trimmed in listOf("chrome", "google chrome", "chrome browser", "browser") -> "chrome"
            trimmed in listOf("cam", "camera", "photo camera") -> "camera"
            trimmed == "ymusic" || trimmed.startsWith("ymusic") -> "ymusic"
            trimmed == "youtube" || trimmed.startsWith("youtube") -> "youtube"
            trimmed == "whatsapp" || trimmed.startsWith("whatsapp") -> "whatsapp"
            trimmed == "instagram" || trimmed.startsWith("instagram") -> "instagram"
            trimmed == "music" || trimmed.startsWith("music") || trimmed.contains("gaana") -> "music"
            trimmed == "chrome" || trimmed.startsWith("chrome") -> "chrome"
            trimmed == "camera" || trimmed.startsWith("camera") -> "camera"
            trimmed == "settings" || trimmed.startsWith("settings") -> "settings"
            trimmed == "termux" || trimmed.startsWith("termux") -> "termux"
            else -> trimmed
        }
    }
}
