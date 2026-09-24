package com.jarvis.tools

import android.util.Log
import com.jarvis.execution.VerificationEngine
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.MetricsCollector
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.PolicyEngine
import com.jarvis.foundation.ToolMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

class ToolRegistry {
    private val tools = java.util.concurrent.ConcurrentHashMap<String, Tool>()
    private val metadataMap = java.util.concurrent.ConcurrentHashMap<String, ToolMetadata>()
    private val aliases = java.util.concurrent.ConcurrentHashMap<String, String>(mapOf(
        // WhatsApp & Messaging
        "WHATSAPP_SEND" to "WHATSAPP",
        "SEND_WHATSAPP" to "WHATSAPP",
        "SEND_MESSAGE" to "WHATSAPP",
        "WHATSAPP_MESSAGE" to "WHATSAPP",
        "WHATSAPP_READ" to "WHATSAPP",
        "READ_WHATSAPP" to "WHATSAPP",
        "WHATSAPP_UNREAD" to "WHATSAPP",
        "CHECK_WHATSAPP" to "WHATSAPP",
        "WHATSAPP_AUTO_REPLY" to "WHATSAPP",
        "AUTO_REPLY_WHATSAPP" to "WHATSAPP",
        "AUTO_REPLY" to "WHATSAPP",
        "WHATSAPP_DICTATE" to "WHATSAPP",
        "REPLY_WHATSAPP" to "WHATSAPP",
        "WHATSAPP_REPLY" to "WHATSAPP",
        "RESPOND_WHATSAPP" to "WHATSAPP",
        "WHATSAPP_BUSINESS" to "WHATSAPP",
        "WHATSAPP_BUSINESS_SEND" to "WHATSAPP",
        "WHATSAPP_BUSINESS_READ" to "WHATSAPP",
        "WHATSAPP_BUSINESS_REPLY" to "WHATSAPP",
        "WA_BUSINESS" to "WHATSAPP",
        // System Logs & Telemetry
        "READ_LOGS" to "LOGS_READ",
        "SHOW_LOGS" to "LOGS_READ",
        "CHECK_LOGS" to "LOGS_READ",
        "SYSTEM_LOGS" to "LOGS_READ",
        "LOGCAT_READ" to "LOGS_READ",
        "DEVICE_LOGS" to "LOGS_READ",
        "GET_LOGS" to "LOGS_READ",
        // App Lifecycle & Discovery
        "CLOSE_ALL_APPS" to "APPS_CLOSE_ALL",
        "CLOSE_ALL" to "APPS_CLOSE_ALL",
        "ALL_APPS_CLOSE" to "APPS_CLOSE_ALL",
        "LAUNCH_APP" to "OPEN_APP",
        "APP_OPEN" to "OPEN_APP",
        "APP_CLOSE" to "CLOSE_APP",
        "SCAN_APPS" to "APPS_LIST",
        "LIST_APPS" to "APPS_LIST",
        "INSTALLED_APPS" to "APPS_LIST",
        "GET_APPS" to "APPS_LIST",
        "ALL_APPS" to "APPS_LIST",
        // Music & Media Playback
        "PLAY_MUSIC" to "MUSIC_PLAY",
        "MUSIC" to "MUSIC_PLAY",
        "PLAY_SONG" to "MUSIC_PLAY",
        "SONG_PLAY" to "MUSIC_PLAY",
        "YMUSIC_PLAY" to "MUSIC_PLAY",
        "YMUSIC" to "MUSIC_PLAY",
        "MEDIA_STOP" to "MEDIA_CONTROL",
        "STOP_MEDIA" to "MEDIA_CONTROL",
        "STOP_MUSIC" to "MEDIA_CONTROL",
        "MUSIC_STOP" to "MEDIA_CONTROL",
        "MEDIA_PAUSE" to "MEDIA_CONTROL",
        "PAUSE_MUSIC" to "MEDIA_CONTROL",
        "PAUSE_MEDIA" to "MEDIA_CONTROL",
        // YouTube
        "YOUTUBE" to "YOUTUBE_PLAY",
        "PLAY_YOUTUBE" to "YOUTUBE_PLAY",
        "YOUTUBE_SEARCH" to "YOUTUBE_PLAY",
        "YOUTUBE_SCROLL" to "APP_AUTOPILOT",
        "PLAY_VIDEO" to "YOUTUBE_PLAY",
        "VIDEO_PLAY" to "YOUTUBE_PLAY",
        // Volume Up/Down (resolved as MEDIA_CONTROL actions)
        "VOLUME_UP" to "MEDIA_CONTROL",
        "INCREASE_VOLUME" to "MEDIA_CONTROL",
        "RAISE_VOLUME" to "MEDIA_CONTROL",
        "LOUDER" to "MEDIA_CONTROL",
        "VOLUME_DOWN" to "MEDIA_CONTROL",
        "DECREASE_VOLUME" to "MEDIA_CONTROL",
        "LOWER_VOLUME" to "MEDIA_CONTROL",
        "QUIETER" to "MEDIA_CONTROL",
        "MEDIA_VOLUME" to "MEDIA_CONTROL",
        "MUSIC_VOLUME" to "MEDIA_CONTROL",
        // Web Search & Research
        "SEARCH" to "SEARCH_WEB",
        "WEB_SEARCH" to "SEARCH_WEB",
        "GOOGLE_SEARCH" to "SEARCH_WEB",
        "GOOGLE" to "SEARCH_WEB",
        "DEEP_RESEARCH" to "RESEARCH_DEEP",
        "BACKGROUND_RESEARCH" to "RESEARCH_DEEP",
        "GENERATE_REPORT" to "RESEARCH_DEEP",
        "RESEARCH_REPORT" to "RESEARCH_DEEP",
        "RESEARCH" to "RESEARCH_DEEP",
        // Calculator & Math
        "CALCULATOR" to "CALCULATE",
        // Weather & Forecast
        "CHECK_WEATHER" to "WEATHER",
        "GET_WEATHER" to "WEATHER",
        "TEMPERATURE" to "WEATHER",
        "MAUSAM" to "WEATHER",
        // Translator
        "TRANSLATE" to "TRANSLATOR",
        "TRANSLATE_TEXT" to "TRANSLATOR",
        "ANUVAD" to "TRANSLATOR",
        // Timer & World Clock
        "TIMER" to "CLOCK",
        "SET_TIMER" to "CLOCK",
        "WORLD_CLOCK" to "CLOCK",
        "TIME_IN" to "CLOCK",
        // Memory & Notes
        "VOICE_NOTE" to "QUICK_NOTES",
        "ADD_NOTE" to "QUICK_NOTES",
        "LIST_NOTES" to "QUICK_NOTES",
        "SEARCH_NOTES" to "QUICK_NOTES",
        "SAVE_MEMORY" to "NOTE",
        "SAVE_NOTE" to "NOTE",
        "REMEMBER" to "NOTE",
        "FIND_MEMORY" to "SEARCH_MEMORY",
        "GET_MEMORY" to "SEARCH_MEMORY",
        // J.A.R.V.I.S. Protocols & Hardware Telemetry
        "DIAGNOSTICS" to "JARVIS_PROTOCOL",
        "SYSTEM_DIAGNOSTICS" to "JARVIS_PROTOCOL",
        "SYSTEM_STATUS" to "JARVIS_PROTOCOL",
        "DEVICE_TELEMETRY" to "JARVIS_PROTOCOL",
        "TELEMETRY" to "JARVIS_PROTOCOL",
        "JARVIS_STATUS" to "JARVIS_PROTOCOL",
        "PROTOCOL" to "JARVIS_PROTOCOL",
        "STEALTH_MODE" to "JARVIS_PROTOCOL",
        "WORK_MODE" to "JARVIS_PROTOCOL",
        "EMERGENCY_MODE" to "JARVIS_PROTOCOL",
        // Real-Time News & RSS Feeds
        "NEWS" to "NEWS_HEADLINES",
        "GET_NEWS" to "NEWS_HEADLINES",
        "TOP_NEWS" to "NEWS_HEADLINES",
        "HEADLINES" to "NEWS_HEADLINES",
        "DAILY_NEWS" to "NEWS_HEADLINES",
        "TECH_NEWS" to "NEWS_HEADLINES",
        "WORLD_NEWS" to "NEWS_HEADLINES",
        // Maps & Navigation
        "NAVIGATE" to "NAVIGATION",
        "DIRECTIONS" to "NAVIGATION",
        "MAPS" to "NAVIGATION",
        "FIND_PLACE" to "NAVIGATION",
        "FIND_NEARBY" to "NAVIGATION",
        "NEARBY" to "NAVIGATION",
        "ROUTE_TO" to "NAVIGATION",
        // Unit & Currency Conversion
        "CONVERT" to "UNIT_CONVERTER",
        "CURRENCY_CONVERTER" to "UNIT_CONVERTER",
        "CURRENCY" to "UNIT_CONVERTER",
        "UNIT_CONVERSION" to "UNIT_CONVERTER",
        "EXCHANGE_RATE" to "UNIT_CONVERTER",
        // Flashlight / Torch
        "TORCH" to "FLASHLIGHT",
        "FLASH" to "FLASHLIGHT",
        // UI & Screen Automation
        "CLICK" to "UI_CLICK",
        "TAP" to "UI_CLICK",
        "SCROLL" to "UI_SCROLL",
        "SWIPE" to "UI_SCROLL",
        "SCROLL_DOWN" to "UI_SCROLL",
        "SCROLL_UP" to "UI_SCROLL",
        "TYPE" to "UI_TYPE",
        "INPUT" to "UI_TYPE",
        "INSPECT" to "UI_INSPECT",
        "INSPECT_SCREEN" to "UI_INSPECT",
        "GLOBAL_ACTION" to "UI_GLOBAL",
        "BACK" to "UI_GLOBAL",
        "HOME" to "UI_GLOBAL",
        "RECENTS" to "UI_GLOBAL",
        "SCREENSHOT" to "UI_GLOBAL",
        // Lock & Unlock Screen
        "LOCK_SCREEN" to "SCREEN_LOCK",
        "LOCK_PHONE" to "SCREEN_LOCK",
        "LOCK_DEVICE" to "SCREEN_LOCK",
        "DEVICE_LOCK" to "SCREEN_LOCK",
        "PHONE_LOCK" to "SCREEN_LOCK",
        "LOCK" to "SCREEN_LOCK",
        "UNLOCK_SCREEN" to "SCREEN_UNLOCK",
        "UNLOCK_PHONE" to "SCREEN_UNLOCK",
        "UNLOCK_DEVICE" to "SCREEN_UNLOCK",
        "DEVICE_UNLOCK" to "SCREEN_UNLOCK",
        "PHONE_UNLOCK" to "SCREEN_UNLOCK",
        "UNLOCK" to "SCREEN_UNLOCK",
        "WAKE_SCREEN" to "SCREEN_UNLOCK",
        "TURN_ON_SCREEN" to "SCREEN_UNLOCK",
        // Screen Vision & Visual Context
        "SCREEN_ANALYZE" to "SCREEN_VISION",
        "SCREEN_READ" to "SCREEN_VISION",
        "SCREEN_LOOK" to "SCREEN_VISION",
        "SCREEN_SUMMARIZE" to "SCREEN_VISION",
        "SEE_SCREEN" to "SCREEN_VISION",
        "LOOK_AT_SCREEN" to "SCREEN_VISION",
        "VISION" to "SCREEN_VISION",
        // Proactive Notifications & Battery
        "READ_NOTIFICATIONS" to "NOTIFICATIONS_READ",
        "GET_NOTIFICATIONS" to "NOTIFICATIONS_READ",
        "CHECK_NOTIFICATIONS" to "NOTIFICATIONS_READ",
        "NOTIFICATIONS" to "NOTIFICATIONS_READ",
        "CHECK_BATTERY" to "BATTERY_CHECK",
        "BATTERY_STATUS" to "BATTERY_CHECK",
        "BATTERY_LEVEL" to "BATTERY_CHECK",
        "BATTERY" to "BATTERY_CHECK",
        "MORNING_BRIEFING" to "DAILY_BRIEFING",
        "BRIEFING" to "DAILY_BRIEFING",
        "STATUS_REPORT" to "DAILY_BRIEFING",
        // Device Audio & Settings
        "VOLUME_CONTROL" to "DEVICE_SETTINGS",
        "SET_VOLUME" to "DEVICE_SETTINGS",
        "VOLUME" to "DEVICE_SETTINGS",
        "MUTE" to "DEVICE_SETTINGS",
        "UNMUTE" to "DEVICE_SETTINGS",
        "SET_RINGER" to "SYSTEM_SWITCHBOARD",
        "RINGER" to "SYSTEM_SWITCHBOARD",
        "RINGER_MODE" to "SYSTEM_SWITCHBOARD",
        "SILENT_MODE" to "SYSTEM_SWITCHBOARD",
        "VIBRATE_MODE" to "SYSTEM_SWITCHBOARD",
        "BRIGHTNESS" to "SYSTEM_SWITCHBOARD",
        "SET_BRIGHTNESS" to "SYSTEM_SWITCHBOARD",
        "SCREEN_BRIGHTNESS" to "SYSTEM_SWITCHBOARD",
        "BATTERY_SAVER" to "SYSTEM_SWITCHBOARD",
        "POWER_SAVER" to "SYSTEM_SWITCHBOARD",
        // Smart Automations & Routines
        "TRIGGER_ROUTINE" to "ROUTINE_MANAGE",
        "RUN_ROUTINE" to "ROUTINE_MANAGE",
        "LIST_ROUTINES" to "ROUTINE_MANAGE",
        "SHOW_ROUTINES" to "ROUTINE_MANAGE",
        "ENABLE_ROUTINE" to "ROUTINE_MANAGE",
        "DISABLE_ROUTINE" to "ROUTINE_MANAGE",
        "ROUTINES" to "ROUTINE_MANAGE",
        // Autonomous UI Macros & Workflows
        "RUN_MACRO" to "MACRO_WORKFLOW",
        "EXECUTE_WORKFLOW" to "MACRO_WORKFLOW",
        "LIST_MACROS" to "MACRO_WORKFLOW",
        "SHOW_MACROS" to "MACRO_WORKFLOW",
        "MACRO" to "MACRO_WORKFLOW",
        "WORKFLOW" to "MACRO_WORKFLOW",
        // Autonomous UI Autopilot
        "UI_NAVIGATE" to "APP_AUTOPILOT",
        "AUTO_CLICK" to "APP_AUTOPILOT",
        "FILL_FORM" to "APP_AUTOPILOT",
        // Cross-Device & Autonomous Media
        "NEXT_TRACK" to "MEDIA_CONTROL",
        "NEXT_SONG" to "MEDIA_CONTROL",
        "SKIP_TRACK" to "MEDIA_CONTROL",
        "SKIP_SONG" to "MEDIA_CONTROL",
        "PREVIOUS_TRACK" to "MEDIA_CONTROL",
        "PREVIOUS_SONG" to "MEDIA_CONTROL",
        "RESUME_MUSIC" to "MEDIA_CONTROL",
        "MEDIA_RESUME" to "MEDIA_CONTROL",
        "PLAY_RESUME" to "MEDIA_CONTROL",
        "SEEK_FORWARD" to "MEDIA_CONTROL",
        "SEEK_BACKWARD" to "MEDIA_CONTROL",
        "SPOTIFY" to "SPOTIFY_PLAY",
        "PLAY_SPOTIFY" to "SPOTIFY_PLAY",
        "SPOTIFY_SEARCH" to "SPOTIFY_PLAY",
        // Telephony & SMS
        "CALL" to "TELEPHONY_CONTROL",
        "DIAL" to "TELEPHONY_CONTROL",
        "PHONE" to "TELEPHONY_CONTROL",
        "SMS" to "TELEPHONY_CONTROL",
        "TEXT_MESSAGE" to "TELEPHONY_CONTROL",
        "SEND_SMS" to "TELEPHONY_CONTROL",
        "FIND_CONTACT" to "TELEPHONY_CONTROL",
        "SEARCH_CONTACT" to "TELEPHONY_CONTROL",
        "ANSWER_CALL" to "TELEPHONY_CONTROL",
        "PICK_UP_CALL" to "TELEPHONY_CONTROL",
        "ACCEPT_CALL" to "TELEPHONY_CONTROL",
        "REJECT_CALL" to "TELEPHONY_CONTROL",
        "DECLINE_CALL" to "TELEPHONY_CONTROL",
        "END_CALL" to "TELEPHONY_CONTROL",
        "READ_SMS" to "TELEPHONY_CONTROL",
        "SMS_READ" to "TELEPHONY_CONTROL",
        "GET_OTP" to "TELEPHONY_CONTROL",
        "READ_OTP" to "TELEPHONY_CONTROL",
        "WHO_IS_CALLING" to "TELEPHONY_CONTROL",
        "CALLER_INFO" to "TELEPHONY_CONTROL",
        // Calendar & Agenda
        "CALENDAR" to "CALENDAR_MANAGE",
        "SCHEDULE" to "CALENDAR_MANAGE",
        "AGENDA" to "CALENDAR_MANAGE",
        "MEETING" to "CALENDAR_MANAGE",
        "ADD_EVENT" to "CALENDAR_MANAGE",
        "CHECK_CONFLICT" to "CALENDAR_MANAGE",
        // Security & Privacy
        "SECURITY" to "SECURITY_AUDIT",
        "PRIVACY_AUDIT" to "SECURITY_AUDIT",
        "PERMISSIONS_CHECK" to "SECURITY_AUDIT",
        "SCAN_SECURITY" to "SECURITY_AUDIT",
        "PRIVACY_SCORE" to "SECURITY_AUDIT",
        // Files & Storage & Databases
        "FILES" to "FILE_MANAGER",
        "FILE_SEARCH" to "FILE_MANAGER",
        "STORAGE_CHECK" to "FILE_MANAGER",
        "CLEANUP_STORAGE" to "FILE_MANAGER",
        "STORAGE_BREAKDOWN" to "FILE_MANAGER",
        "DB_STATUS" to "JARVIS_DB",
        "DB_STATS" to "JARVIS_DB",
        "DATABASE_STATUS" to "JARVIS_DB",
        "VECTOR_DB" to "JARVIS_DB",
        "VECTOR_DB_STATS" to "JARVIS_DB",
        "STORAGE_DB" to "JARVIS_DB",
        "JARVIS_STORAGE" to "JARVIS_DB",
        "VECTOR_SEARCH" to "JARVIS_DB",
        "SEARCH_VECTOR" to "JARVIS_DB",
        "SEARCH_VECTORS" to "JARVIS_DB",
        "INDEX_VECTOR" to "JARVIS_DB",
        "STORE_VECTOR" to "JARVIS_DB",
        // Autonomous Autopilot & Ambient Modes
        "AUTONOMOUS" to "AUTONOMOUS_CONTROL",
        "AUTOPILOT" to "AUTONOMOUS_CONTROL",
        "AUTO_PILOT" to "AUTONOMOUS_CONTROL",
        "DRIVING_MODE" to "AUTONOMOUS_CONTROL",
        "MEETING_MODE" to "AUTONOMOUS_CONTROL",
        "FOCUS_MODE" to "AUTONOMOUS_CONTROL",
        "SLEEP_MODE" to "AUTONOMOUS_CONTROL",
        "NIGHT_MODE" to "AUTONOMOUS_CONTROL",
        "WORKOUT_MODE" to "AUTONOMOUS_CONTROL",
        // RAG & Knowledge Vault Retrieval
        "SEARCH_DOCS" to "RAG_RETRIEVE",
        "QUERY_KNOWLEDGE" to "RAG_RETRIEVE",
        "KNOWLEDGE_SEARCH" to "RAG_RETRIEVE",
        "SEARCH_NOTES" to "RAG_RETRIEVE",
        "FIND_IN_FILES" to "RAG_RETRIEVE",
        "RETRIEVE_KNOWLEDGE" to "RAG_RETRIEVE",
        "INDEX_DOCUMENT" to "RAG_INDEX",
        "INDEX_FILE" to "RAG_INDEX",
        "INDEX_NOTES" to "RAG_INDEX",
        "ADD_TO_VAULT" to "RAG_INDEX",
        // Multimodal Camera Vision & Real-Time Object Perception
        "CAMERA_LOOK" to "CAMERA_VISION",
        "WHAT_AM_I_LOOKING_AT" to "CAMERA_VISION",
        "CAMERA_IDENTIFY" to "CAMERA_VISION",
        "CAMERA_SCAN" to "CAMERA_VISION",
        "IDENTIFY_OBJECT" to "CAMERA_VISION",
        "DESCRIBE_SCENE" to "CAMERA_VISION",
        "READ_OBJECT" to "CAMERA_VISION",
        "SEE_OBJECT" to "CAMERA_VISION",
        "OPTICAL_PERCEPTION" to "CAMERA_VISION"
    ))

    fun register(tool: Tool) {
        val key = tool.name.uppercase()
        tools[key] = tool
    }

    fun register(tool: Tool, metadata: ToolMetadata) {
        val key = tool.name.uppercase()
        tools[key] = tool
        metadataMap[key] = metadata
    }

    fun registerLazy(
        name: String,
        description: String = "",
        policy: ToolPolicy = ToolPolicy(),
        metadata: ToolMetadata? = null,
        provider: () -> Tool
    ): LazyTool {
        val lazyTool = LazyTool(name, description, policy, provider)
        val key = name.uppercase()
        tools[key] = lazyTool
        if (metadata != null) {
            metadataMap[key] = metadata
        }
        return lazyTool
    }

    /**
     * Evicts cached instances of all lazy tools under memory pressure.
     * Tools will be transparently re-instantiated on next execution.
     */
    fun evictIdleLazyTools(): Int {
        var evictedCount = 0
        for (tool in tools.values) {
            if (tool is LazyTool && tool.isInitialized) {
                tool.evict()
                evictedCount++
            }
        }
        return evictedCount
    }

    fun get(name: String): Tool? {
        val upper = name.uppercase()
        val canonical = aliases[upper] ?: upper
        return tools[canonical] ?: tools[upper]
    }

    /**
     * Resolves any alias or direct name to its one canonical tool name, or null
     * if the name (after alias resolution) is not a registered tool.
     */
    fun resolveCanonicalToolName(name: String): String? {
        val upper = name.uppercase()
        val canonical = aliases[upper] ?: upper
        return if (tools.containsKey(canonical)) canonical else null
    }

    fun getMetadata(name: String): ToolMetadata? {
        val upper = name.uppercase()
        val canonical = aliases[upper] ?: upper
        return metadataMap[canonical] ?: metadataMap[upper]
    }

    fun getAvailableToolsDescription(): String {
        return tools.values.distinctBy { it.name }.joinToString("\n") { tool ->
            val meta = metadataMap[tool.name.uppercase()]
            val desc = tool.description.ifBlank { meta?.description ?: "Tool for ${tool.name.lowercase()}" }
            "- ${tool.name}: $desc"
        }
    }

    fun adaptParamsForAlias(actionType: String, originalParams: Map<String, String>): Map<String, String> {
        val upper = actionType.trim().uppercase()
        val mutable = originalParams.toMutableMap()
        when (upper) {
            "MEDIA_PAUSE", "PAUSE_MUSIC", "PAUSE_MEDIA" -> if (!mutable.containsKey("action")) mutable["action"] = "pause"
            "NEXT_TRACK", "NEXT_SONG", "SKIP_TRACK", "SKIP_SONG" -> if (!mutable.containsKey("action")) mutable["action"] = "next"
            "PREVIOUS_TRACK", "PREVIOUS_SONG" -> if (!mutable.containsKey("action")) mutable["action"] = "previous"
            "RESUME_MUSIC", "MEDIA_RESUME", "PLAY_RESUME" -> if (!mutable.containsKey("action")) mutable["action"] = "play"
            "VOLUME_UP", "INCREASE_VOLUME", "RAISE_VOLUME", "LOUDER" -> if (!mutable.containsKey("action")) mutable["action"] = "volume_up"
            "VOLUME_DOWN", "DECREASE_VOLUME", "LOWER_VOLUME", "QUIETER" -> if (!mutable.containsKey("action")) mutable["action"] = "volume_down"
            "VECTOR_SEARCH", "SEARCH_VECTOR", "SEARCH_VECTORS" -> if (!mutable.containsKey("action")) mutable["action"] = "vector_search"
            "INDEX_VECTOR", "STORE_VECTOR" -> if (!mutable.containsKey("action")) mutable["action"] = "index_vector"
            "STEALTH_MODE" -> if (!mutable.containsKey("protocol")) mutable["protocol"] = "stealth"
            "WORK_MODE" -> if (!mutable.containsKey("protocol")) mutable["protocol"] = "work"
            "EMERGENCY_MODE" -> if (!mutable.containsKey("protocol")) mutable["protocol"] = "emergency"
            "REPLY_WHATSAPP", "WHATSAPP_REPLY", "RESPOND_WHATSAPP" -> if (!mutable.containsKey("action")) mutable["action"] = "reply"
            "WHATSAPP_BUSINESS", "WA_BUSINESS" -> if (!mutable.containsKey("platform")) mutable["platform"] = "business"
            "WHATSAPP_BUSINESS_SEND" -> {
                if (!mutable.containsKey("action")) mutable["action"] = "send_message"
                if (!mutable.containsKey("platform")) mutable["platform"] = "business"
            }
            "WHATSAPP_BUSINESS_READ" -> {
                if (!mutable.containsKey("action")) mutable["action"] = "read_messages"
                if (!mutable.containsKey("platform")) mutable["platform"] = "business"
            }
            "WHATSAPP_BUSINESS_REPLY" -> {
                if (!mutable.containsKey("action")) mutable["action"] = "reply"
                if (!mutable.containsKey("platform")) mutable["platform"] = "business"
            }
            "LOCK_SCREEN", "LOCK_PHONE", "LOCK_DEVICE", "LOCK" -> if (!mutable.containsKey("action")) mutable["action"] = "lock"
            "UNLOCK_SCREEN", "UNLOCK_PHONE", "UNLOCK_DEVICE", "UNLOCK", "WAKE_SCREEN" -> if (!mutable.containsKey("action")) mutable["action"] = "unlock"
            "CLOSE_ALL_APPS", "CLOSE_ALL", "ALL_APPS_CLOSE" -> if (!mutable.containsKey("action")) mutable["action"] = "close_all"
            "MUTE" -> if (!mutable.containsKey("action")) mutable["action"] = "mute"
            "UNMUTE" -> if (!mutable.containsKey("action")) mutable["action"] = "unmute"
        }
        return mutable
    }

    /** Actions whose success cannot be reduced to the dispatch itself and therefore always need verification. */
    fun requiresOutcomeVerification(actionType: String): Boolean {
        val upper = actionType.uppercase()
        return upper in listOf(
            "YOUTUBE_PLAY", "MUSIC_PLAY", "YMUSIC_PLAY", "PLAY_MUSIC",
            "WHATSAPP", "WHATSAPP_SEND", "SEARCH_WEB", "SEARCH",
            "TELEPHONY_CONTROL", "FLASHLIGHT", "SPOTIFY_PLAY", "CALENDAR_MANAGE", "FILE_WRITE"
        )
    }
}

fun interface AuditSink {
    fun log(actionType: String, riskLevel: String, reason: String, status: String, details: String)
}

class ToolExecutor(
    val registry: ToolRegistry,
    private val policyEngine: PolicyEngine = PolicyEngine(),
    private val verificationEngine: VerificationEngine? = null,
    private val metrics: MetricsCollector = MetricsCollector.shared,
    private val auditSink: AuditSink? = null
) {
    private val TAG = "ToolExecutor"

    suspend fun execute(
        actionType: String,
        params: Map<String, String>,
        userApprovalGranted: Boolean = false
    ): ToolResult {
        val start = System.currentTimeMillis()
        val result = executeInternal(actionType, params, userApprovalGranted)
        metrics.record("tool", actionType, ok = result.success, wallMs = System.currentTimeMillis() - start)
        return result
    }

    private suspend fun executeInternal(
        actionType: String,
        params: Map<String, String>,
        userApprovalGranted: Boolean
    ): ToolResult {
        val effectiveParams = registry.adaptParamsForAlias(actionType, params)
        val tool = registry.get(actionType)
            ?: run {
                val err = "Tool '$actionType' is not registered in ToolRegistry"
                Log.w(TAG, err)
                return ToolResult.Failed(err)
            }

        // Policy evaluation with canonical tool alias resolution
        val canonicalType = registry.resolveCanonicalToolName(actionType) ?: actionType
        val metadata = registry.getMetadata(canonicalType) ?: registry.getMetadata(actionType)
        val effectiveMetadata: ToolMetadata
        val policyResult = if (metadata != null) {
            effectiveMetadata = metadata
            policyEngine.evaluate(metadata, effectiveParams)
        } else {
            // No registry metadata: fall back to the tool's own policy so ToolPolicy.riskLevel
            // stays the single risk source instead of auto-approving everything.
            effectiveMetadata = ToolMetadata(
                name = canonicalType,
                description = tool.description.ifBlank { "Tool $canonicalType" },
                parameters = emptyList<ParameterSchema>(),
                riskLevel = tool.policy.riskLevel
            )
            policyEngine.evaluate(effectiveMetadata, effectiveParams)
        }

        if (!policyResult.allowed) {
            val reason = "Execution blocked by policy: ${policyResult.reason}"
            Log.w(TAG, reason)
            auditSink?.log(canonicalType, effectiveMetadata.riskLevel.name, policyResult.reason, "BLOCKED", reason)
            return ToolResult.Failed(reason)
        }

        if (policyResult.requiresApproval && !userApprovalGranted) {
            val approvalPrompt = "Action '$actionType' requires confirmation: ${policyResult.reason}"
            Log.i(TAG, approvalPrompt)
            auditSink?.log(canonicalType, effectiveMetadata.riskLevel.name, policyResult.reason, "NEEDS_CONFIRMATION", approvalPrompt)
            return ToolResult.NeedsConfirmation(actionType, approvalPrompt)
        }

        // Gateway schema validation: required params must be present before any execution.
        val schemaError = validateParams(canonicalType, effectiveMetadata, effectiveParams)
        if (schemaError != null) {
            auditSink?.log(canonicalType, effectiveMetadata.riskLevel.name, "Schema validation failed", "REJECTED", schemaError)
            return ToolResult.Failed(schemaError)
        }

        Log.i(TAG, "Executing tool '$actionType' with params $effectiveParams")
        val result = try {
            withTimeout(tool.policy.timeoutMs) { tool.execute(effectiveParams) }
        } catch (e: TimeoutCancellationException) {
            val msg = "Tool '$actionType' timed out after ${tool.policy.timeoutMs}ms"
            Log.w(TAG, msg)
            auditSink?.log(canonicalType, effectiveMetadata.riskLevel.name, "Timeout", "TIMEOUT", msg)
            return ToolResult.Failed(msg)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Tool execution failed for '$actionType'", e)
            val msg = e.message ?: "Tool execution failed"
            auditSink?.log(canonicalType, effectiveMetadata.riskLevel.name, "Exception", "FAILED", msg)
            return ToolResult.Failed(msg)
        }

        // Post-execution verification: prefer the tool's own verifier, fall back to the central engine.
        val verification = verify(canonicalType, effectiveParams, result, tool)
        if (!verification.verified) {
            Log.w(TAG, "Verification notice for $actionType: ${verification.message}")
        }
        auditSink?.log(
            canonicalType,
            effectiveMetadata.riskLevel.name,
            "Execution ${if (result.success) "succeeded" else "failed"}",
            if (result.success) "EXECUTED" else "FAILED",
            result.message.take(200)
        )

        return result
    }

    private fun verify(
        canonicalType: String,
        params: Map<String, String>,
        result: ToolResult,
        tool: Tool
    ): VerificationResult {
        val toolResult = tool.verify(params, result)
        val engine = verificationEngine
        if (toolResult.status != VerificationStatus.UNKNOWN || engine == null) {
            return toolResult
        }
        return engine.verify(canonicalType, params, result)
    }

    private fun validateParams(name: String, metadata: ToolMetadata, params: Map<String, String>): String? {
        val missing = metadata.parameters
            .filter { it.required && params[it.name].isNullOrBlank() }
            .map { it.name }
        if (missing.isNotEmpty()) {
            return "Missing required parameter(s): ${missing.joinToString(", ")} for '$name'"
        }

        // Type and schema enforcement
        for (paramSchema in metadata.parameters) {
            val value = params[paramSchema.name] ?: continue
            if (value.isBlank()) continue

            when (paramSchema.type.lowercase()) {
                "integer", "int" -> {
                    if (value.toIntOrNull() == null) {
                        return "Parameter '${paramSchema.name}' for '$name' must be an integer, got: '$value'"
                    }
                }
                "boolean", "bool" -> {
                    val cleanBool = value.lowercase().trim()
                    if (cleanBool !in setOf("true", "false", "1", "0", "yes", "no", "on", "off")) {
                        return "Parameter '${paramSchema.name}' for '$name' must be a boolean, got: '$value'"
                    }
                }
                "float", "double", "number" -> {
                    if (value.toDoubleOrNull() == null) {
                        return "Parameter '${paramSchema.name}' for '$name' must be a numeric value, got: '$value'"
                    }
                }
            }
        }
        return null
    }
}
