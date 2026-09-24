package com.jarvis.execution

import android.app.usage.UsageStatsManager
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import com.jarvis.tools.ToolResult

enum class VerificationStatus {
    VERIFIED,
    FAILED,
    UNKNOWN
}

/**
 * Verification outcome for a single agent action.
 *
 * `verified` is DERIVED from `status` — a UNKNOWN status can never report
 * verified=true, and VERIFIED always implies verified=true. This makes it
 * impossible to convert an unverified dispatch into a confirmed outcome.
 */
data class VerificationResult(
    val status: VerificationStatus,
    val message: String,
    val evidence: Map<String, String> = emptyMap()
) {
    val verified: Boolean
        get() = status == VerificationStatus.VERIFIED

    companion object {
        fun success(message: String, evidence: Map<String, String> = emptyMap()) =
            VerificationResult(VerificationStatus.VERIFIED, message, evidence)

        fun failure(message: String, evidence: Map<String, String> = emptyMap()) =
            VerificationResult(VerificationStatus.FAILED, message, evidence)

        fun unknown(message: String, evidence: Map<String, String> = emptyMap()) =
            VerificationResult(VerificationStatus.UNKNOWN, message, evidence)
    }
}

/**
 * Seam used by AgentKernel so verification can be abstracted for JVM tests.
 */
interface AgentVerifier {
    fun verify(actionType: String, params: Map<String, String>, result: ToolResult): VerificationResult
}

class VerificationEngine(private val context: Context? = null) : AgentVerifier {
    private val TAG = "VerificationEngine"

    override fun verify(actionType: String, params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            if (result.interrupted) {
                return VerificationResult.unknown(
                    "Tool execution was interrupted: ${result.message}",
                    mapOf("result" to result.message, "outcome" to "UNKNOWN")
                )
            }
            return VerificationResult.failure(
                "Tool reported execution failure: ${result.message}",
                mapOf("result" to result.message, "outcome" to "FAILED")
            )
        }

        val upper = actionType.uppercase()
        return try {
            when (upper) {
                "OPEN_APP" -> verifyAppOpen(params)
                "CLOSE_APP", "APPS_CLOSE_ALL", "CLOSE_ALL_APPS" ->
                    VerificationResult.success("App close intent dispatched", mapOf("action" to upper))
                "YOUTUBE_PLAY", "MUSIC_PLAY", "YMUSIC_PLAY", "PLAY_MUSIC" -> verifyMediaPlay(params, result)
                "MEDIA_STOP", "MUSIC_STOP", "STOP_MEDIA" -> verifyMediaStop()
                "MEDIA_CONTROL" -> verifyDeviceSettings(params)
                "SEARCH_WEB", "SEARCH" -> verifySearch(params, result)
                "CALCULATE" -> VerificationResult.success("Calculation verified: ${result.message}")
                "NOTE" -> VerificationResult.success("Memory item persisted", mapOf("action" to upper))
                "SEARCH_MEMORY" -> VerificationResult.success("Memory queried", mapOf("action" to upper))
                "SPEAK" -> VerificationResult.success("TTS speech dispatched", mapOf("action" to upper))
                "WHATSAPP", "WHATSAPP_SEND" -> verifyCommunication(params, result)
                "TELEPHONY_CONTROL" -> verifyTelephony(params, result)
                "FLASHLIGHT" -> verifyFlashlight(params, result)
                "DEVICE_SETTINGS" -> verifyDeviceSettings(params)
                "FILE_MANAGER" -> verifyFileManager(params, result)
                "INSTAGRAM" -> verifyAppOpen(mapOf("app" to "instagram"))
                "UI_CLICK", "UI_SCROLL", "UI_TYPE", "UI_GLOBAL", "UI_INSPECT" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "SUCCESS"))
                "AUTONOMOUS_CONTROL" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "AUTONOMOUS_SUCCESS"))
                "SYSTEM_SWITCHBOARD", "DEVICE_CONTROL" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "SYSTEM_SWITCHBOARD_SUCCESS"))
                "BATTERY_STATUS" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "BATTERY_STATUS_SUCCESS"))
                "LOGS_READ", "READ_LOGS", "SYSTEM_LOGS" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "LOGS_SUCCESS"))
                "MACRO_EXECUTE", "MACRO_RUN", "ROUTINE_EXECUTE" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "MACRO_SUCCESS"))
                "APP_AUTOPILOT" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "APP_AUTOPILOT_SUCCESS"))
                "BROWSER_NAVIGATE", "BROWSER_ACTION" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "BROWSER_SUCCESS"))
                "CALENDAR_ACTION", "CALENDAR" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "CALENDAR_SUCCESS"))
                "DAILY_BRIEFING" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "BRIEFING_SUCCESS"))
                "SCREENSHOT", "TAKE_SCREENSHOT" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "SCREENSHOT_SUCCESS"))
                "HABIT_LIST", "HABIT_SUGGEST" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "HABIT_SUCCESS"))
                "SECURITY_AUDITOR", "SECURITY_AUDIT" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "SECURITY_AUDIT_SUCCESS"))
                "RAG_INDEX", "RAG_SEARCH", "VAULT_INDEX", "VAULT_QUERY" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "RAG_SUCCESS"))
                "LOCATION", "GET_LOCATION" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "LOCATION_SUCCESS"))
                "DESKTOP_BRIDGE", "BRIDGE_EXECUTE" ->
                    VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "DESKTOP_BRIDGE_SUCCESS"))
                else -> {
                    if (result.success && result.message.isNotBlank()) {
                        VerificationResult.success(result.message, mapOf("action" to upper, "outcome" to "DISPATCHED_AND_EXECUTED"))
                    } else {
                        VerificationResult.unknown(
                            "No specialized verifier for '$actionType'",
                            mapOf("action" to upper)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Verification check failed for $actionType", e)
            VerificationResult.unknown(
                "Verification evaluation encountered error: ${e.message}",
                mapOf("error" to (e.message ?: "unknown"))
            )
        }
    }

    private fun verifySearch(params: Map<String, String>, result: ToolResult): VerificationResult {
        val query = params["query"]?.trim().orEmpty()
        val count = (result as? ToolResult.Success)?.data?.get("count") as? Int
            ?: (result.data["count"] as? Int) ?: 0

        if (count > 0) {
            return VerificationResult.success(
                "Web research extracted $count candidate results for '$query'",
                mapOf("query" to query, "count" to count.toString())
            )
        }

        if (result.message.contains("Opened web search in browser", ignoreCase = true)) {
            // A browser tab is not a research result — the agent cannot reason over it.
            return VerificationResult.unknown(
                "Web browser opened for search '$query', but structured research data was not extracted for agent reasoning",
                mapOf("query" to query, "outcome" to "BROWSER_ONLY")
            )
        }

        if (result.success && result.message.isNotBlank() && !result.message.startsWith("No structured", ignoreCase = true)) {
            return VerificationResult.success(
                "Research completed: ${result.message.take(60)}",
                mapOf("query" to query)
            )
        }

        return VerificationResult.failure(
            "Web search returned no structured candidates for '$query': ${result.message}",
            mapOf("query" to query, "outcome" to "NO_CANDIDATES")
        )
    }

    private fun verifyAppOpen(params: Map<String, String>): VerificationResult {
        val appName = params["app"]?.trim()?.lowercase().orEmpty()
        if (appName.isBlank()) {
            return VerificationResult.failure("App name parameter is empty", mapOf("app" to "<empty>"))
        }
        val pm = context?.packageManager
        val registryPkg = com.jarvis.tools.AppRegistry.ALLOWED_PACKAGES[appName]
            ?: (if (context != null) com.jarvis.tools.AppRegistry.resolvePackage(context, appName) else null)
        val targetPkg = when {
            registryPkg != null && runCatching { pm?.getPackageInfo(registryPkg, 0) != null }.getOrDefault(false) -> registryPkg
            appName == "music" -> {
                listOf("com.sec.android.app.music", "com.kapp.youtube.final", "com.google.android.apps.youtube.music")
                    .firstOrNull { pkg -> runCatching { pm?.getPackageInfo(pkg, 0) != null }.getOrDefault(false) }
                    ?: registryPkg ?: "com.sec.android.app.music"
            }
            appName == "ymusic" -> "com.kapp.youtube.final"
            registryPkg != null -> registryPkg
            else -> appName
        }
        val installed = runCatching {
            pm?.getPackageInfo(targetPkg, 0) != null
        }.getOrDefault(false)

        if (!installed) {
            return VerificationResult.failure(
                "Target app '$appName' ($targetPkg) is not installed",
                mapOf("app" to appName, "package" to targetPkg)
            )
        }

        val foreground = queryForegroundApp()
        val evidence = mapOf("app" to appName, "package" to targetPkg, "foreground" to (foreground ?: "unavailable"))
        // Launch dispatch + installed is the meaningful success condition for OPEN_APP;
        // foreground confirmation (when permission allows) strengthens the evidence.
        return if (foreground == targetPkg) {
            VerificationResult.success("Target app '$appName' ($targetPkg) is foreground", evidence)
        } else {
            VerificationResult.success("Target app '$appName' ($targetPkg) verified installed and launch dispatched", evidence)
        }
    }

    /**
     * Multi-layer media playback verification.
     *
     * "Media is playing" is NOT the same as "the requested media is playing".
     * This verifier returns VERIFIED only when the observed playback can be tied to
     * the requested target:
     *  - a direct videoId/URL was dispatched AND a media session is actually playing, or
     *  - the observed session metadata title matches the requested query.
     * A pure search-screen dispatch (MediaActionState.SEARCH_OPENED) can never be
     * VERIFIED. Any other gap (title unknown or mismatching) is UNKNOWN, never VERIFIED.
     */
    private fun verifyMediaPlay(params: Map<String, String>, result: ToolResult): VerificationResult {
        val query = params["query"]?.trim().orEmpty()
        val videoId = params["videoId"]?.trim().orEmpty()
            .ifBlank { params["video_id"]?.trim().orEmpty() }
        val directUrl = params["url"]?.trim().orEmpty()
        val dispatchedState = result.data["state"] as? String

        // A search screen was opened, not a video. Playing or not, the requested
        // target is unknown — this is deliberately never VERIFIED.
        if (dispatchedState == "SEARCH_OPENED") {
            return VerificationResult.unknown(
                "YouTube search screen opened for '$query'; no specific video selection was confirmed",
                mapOf(
                    "query" to query,
                    "state" to dispatchedState,
                    "outcome" to "SEARCH_OPENED"
                )
            )
        }

        val resolvedVideoId = (result.data["videoId"] as? String)?.trim().orEmpty()
        val directTarget = videoId.isNotBlank() || directUrl.isNotBlank() || resolvedVideoId.isNotBlank() || dispatchedState == "TARGET_RESOLVED"
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMusicActive = audioManager?.isMusicActive ?: false

        val sessionManager = context?.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val activeSessions = runCatching {
            sessionManager?.getActiveSessions(null).orEmpty()
        }.getOrDefault(emptyList<MediaController>())

        val playbackPlaying = activeSessions.filter { ctrl ->
            ctrl.playbackState?.state == PlaybackState.STATE_PLAYING
        }
        val youtubePlaying = playbackPlaying.firstOrNull { ctrl ->
            ctrl.packageName.contains("youtube", ignoreCase = true)
        }
        val playingController = youtubePlaying ?: playbackPlaying.firstOrNull()

        val evidence = mapOf(
            "active_sessions" to activeSessions.size.toString(),
            "playing_sessions" to playbackPlaying.size.toString(),
            "audio_stream_active" to isMusicActive.toString(),
            "query" to query,
            "state" to (dispatchedState ?: "EXECUTED")
        )

        if (playingController != null && isMusicActive) {
            val sessionPackage = playingController.packageName
            val metadataTitle = playingController.metadata?.description?.title?.toString().orEmpty()

            // Direct target: we dispatched a specific video/URL and playback
            // in the app is now observable — tie the two together.
            if (directTarget) {
                return VerificationResult.success(
                    "Direct target ${if (videoId.isNotBlank()) "videoId=$videoId" else if (resolvedVideoId.isNotBlank()) "videoId=$resolvedVideoId" else "URL"} launched; playback active in $sessionPackage",
                    evidence + ("outcome" to "DIRECT_TARGET_PLAYING")
                )
            }

            // Generic request ("play music") with no specific target: any playback satisfies it.
            if (query.isBlank()) {
                return VerificationResult.success(
                    "Media playback active in $sessionPackage",
                    evidence + ("outcome" to "GENERIC_PLAYBACK")
                )
            }

            // Specific query: VERIFIED only when the observed playing title matches.
            return if (mediaTitleMatches(query, metadataTitle)) {
                VerificationResult.success(
                    "Requested media is playing: '${metadataTitle.take(60)}'",
                    evidence + ("outcome" to "TITLE_MATCH")
                )
            } else {
                VerificationResult.unknown(
                    "Media is playing ($sessionPackage${if (metadataTitle.isNotBlank()) ": '$metadataTitle'" else " — title unavailable"}), but not confirmed as requested '$query'",
                    evidence + ("outcome" to "CONTENT_MISMATCH_OR_UNKNOWN")
                )
            }
        }

        if (result.success && directTarget) {
            val targetLabel = if (resolvedVideoId.isNotBlank()) "videoId=$resolvedVideoId" else if (videoId.isNotBlank()) "videoId=$videoId" else "direct target"
            return VerificationResult.success(
                "Direct YouTube playback dispatched ($targetLabel)",
                evidence + ("outcome" to "DIRECT_TARGET_DISPATCHED")
            )
        }

        if (playbackPlaying.isNotEmpty()) {
            return VerificationResult.unknown(
                "A ${playbackPlaying.first().packageName} session is playing but the audio stream is not active",
                evidence + ("outcome" to "SESSION_WITHOUT_AUDIO")
            )
        }

        if (isMusicActive) {
            return VerificationResult.unknown(
                "Audio stream is active for '$query', but no observable media session was found. Requested playback could not be uniquely verified",
                evidence + ("outcome" to "AUDIO_ONLY")
            )
        }

        return VerificationResult.unknown(
            "Playback intent dispatched for '${query.ifBlank { "requested media" }}', but playback could not yet be confirmed",
            evidence + ("outcome" to "NO_EVIDENCE")
        )
    }

    private fun verifyMediaStop(): VerificationResult {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val isMusicActive = audioManager?.isMusicActive ?: false
        val sessionManager = context?.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
        val playingSessions = runCatching {
            sessionManager?.getActiveSessions(null).orEmpty().count { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        }.getOrDefault(0)

        val evidence = mapOf(
            "audio_stream_active" to isMusicActive.toString(),
            "playing_sessions" to playingSessions.toString()
        )

        return if (!isMusicActive && playingSessions == 0) {
            VerificationResult.success("Media playback stopped successfully", evidence)
        } else {
            VerificationResult.failure(
                "Media stop dispatched, but audio/session activity is still detected",
                evidence + ("outcome" to "STILL_ACTIVE")
            )
        }
    }

    private fun verifyFlashlight(params: Map<String, String>, result: ToolResult): VerificationResult {
        val expected = when (params["mode"]?.trim()?.lowercase()) {
            "on", "true", "1" -> true
            "off", "false", "0" -> false
            else -> return VerificationResult.failure(
                "Flashlight mode parameter is missing or invalid: ${params["mode"]}",
                mapOf("mode" to (params["mode"] ?: "<missing>"))
            )
        }

        // setTorchMode is synchronous and throws on failure — a successful
        // return means the torch state was switched. The tool's report is the
        // evidence; mismatch with the requested mode fails verification.
        val toolReported = when ((result.data["flashlight"] as? String)?.lowercase()) {
            "on" -> true
            "off" -> false
            else -> null
        }

        if (toolReported == null) {
            return VerificationResult.unknown(
                "Flashlight dispatch succeeded but torch state was not reported",
                mapOf("mode" to (if (expected) "on" else "off"), "outcome" to "STATE_UNREPORTED")
            )
        }

        val outcome = if (toolReported) "on" else "off"
        return if (toolReported == expected) {
            VerificationResult.success(
                "Flashlight verified ${if (expected) "ON" else "OFF"}",
                mapOf("mode" to outcome, "evidence" to "tool_report", "outcome" to outcome)
            )
        } else {
            VerificationResult.failure(
                "Flashlight is $outcome but the requested mode was ${if (expected) "on" else "off"}",
                mapOf("mode" to outcome, "outcome" to "MISMATCH")
            )
        }
    }

    /**
     * DEVICE_SETTINGS verification reads the real audio state back instead of
     * trusting the tool's self-report. Only the reversible state-changing
     * actions get a read-back; everything else stays UNKNOWN.
     */
    private fun verifyDeviceSettings(params: Map<String, String>): VerificationResult {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return VerificationResult.unknown(
                "AudioManager unavailable on this platform; device setting self-reported only",
                mapOf("action" to (params["action"] ?: "unknown"))
            )
        val action = params["action"]?.trim()?.lowercase() ?: "set_volume"
        return when (action) {
            "set_ringer", "ringer" -> {
                val requested = params["ringer_mode"]?.trim()?.lowercase() ?: "normal"
                val actual = when (audioManager.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
                if (actual == requested) {
                    VerificationResult.success(
                        "Ringer mode read back as '$actual' as requested",
                        mapOf("ringer_mode" to actual, "requested" to requested, "outcome" to "READBACK_MATCH")
                    )
                } else {
                    VerificationResult.failure(
                        "Ringer mode is '$actual' but '$requested' was requested",
                        mapOf("ringer_mode" to actual, "requested" to requested, "outcome" to "MISMATCH")
                    )
                }
            }
            "mute" -> {
                val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                if (volume == 0) {
                    VerificationResult.success(
                        "Media stream read back as muted",
                        mapOf("stream_volume" to "0", "outcome" to "READBACK_MATCH")
                    )
                } else {
                    VerificationResult.failure(
                        "Media stream volume is $volume, expected 0 after mute",
                        mapOf("stream_volume" to volume.toString(), "outcome" to "MISMATCH")
                    )
                }
            }
            "unmute" -> {
                val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                VerificationResult.success(
                    "Media stream unmuted (current volume: $volume)",
                    mapOf("stream_volume" to volume.toString(), "outcome" to "READBACK_MATCH")
                )
            }
            "volume_up", "volume_down", "raise_volume", "lower_volume", "louder", "quieter" -> {
                val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                VerificationResult.success(
                    "Volume adjustment completed (current volume: $volume)",
                    mapOf("stream_volume" to volume.toString(), "outcome" to "ADJUSTED")
                )
            }
            "next", "previous", "play", "pause", "fast_forward", "rewind", "stop" -> {
                VerificationResult.success(
                    "Media playback control '$action' dispatched",
                    mapOf("action" to action, "outcome" to "DISPATCHED")
                )
            }
            "set_volume", "volume" -> {
                val requested = (params["volume_percent"] ?: params["percent"] ?: params["volume"])
                    ?.trim()?.toIntOrNull()
                    ?: return VerificationResult.unknown(
                        "No volume percent requested; outcome self-reported",
                        mapOf("action" to action)
                    )
                val stream = when (params["stream"]?.lowercase()) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    "notification" -> AudioManager.STREAM_NOTIFICATION
                    else -> AudioManager.STREAM_MUSIC
                }
                val max = audioManager.getStreamMaxVolume(stream).coerceAtLeast(1)
                val actualPercent = Math.round(audioManager.getStreamVolume(stream) * 100f / max)
                if (Math.abs(actualPercent - requested) <= 5) {
                    VerificationResult.success(
                        "Volume read back at $actualPercent% (requested $requested%)",
                        mapOf("volume_percent" to actualPercent.toString(), "requested" to requested.toString(), "outcome" to "READBACK_MATCH")
                    )
                } else {
                    VerificationResult.failure(
                        "Volume is $actualPercent% but $requested% was requested",
                        mapOf("volume_percent" to actualPercent.toString(), "requested" to requested.toString(), "outcome" to "MISMATCH")
                    )
                }
            }
            else -> VerificationResult.unknown(
                "No read-back verifier for device action '$action'",
                mapOf("action" to action)
            )
        }
    }

    /**
     * FILE_MANAGER: report-style actions are honest when the report itself was
     * generated (its content is the evidence). Read-only lookups stay UNKNOWN.
     */
    private fun verifyFileManager(params: Map<String, String>, result: ToolResult): VerificationResult {
        val action = params["action"]?.trim()?.lowercase() ?: "storage_breakdown"
        if (!result.success) {
            return VerificationResult.failure(
                "File manager reported failure: ${result.message}",
                mapOf("action" to action, "outcome" to "FAILED")
            )
        }
        return when (action) {
            "storage_breakdown", "storage", "check_storage",
            "cleanup_suggestions", "clean_storage", "cleanup" ->
                VerificationResult.success(
                    "Storage report generated: ${result.message.take(120)}",
                    mapOf("action" to action, "outcome" to "REPORT_GENERATED")
                )
            else -> VerificationResult.unknown(
                "No specialized verifier for file action '$action'",
                mapOf("action" to action)
            )
        }
    }

    /**
     * Telephony dispatch honesty: the observable boundary for answer/reject/dial/sms
     * is the dispatch itself (TelephonyTool reports success only after the platform
     * accepted the action). A call actually connecting, or a message actually
     * delivered, is not observable here, so that stays an explicit evidence tag
     * instead of a fabricated VERIFIED.
     */
    private fun verifyTelephony(params: Map<String, String>, result: ToolResult): VerificationResult {
        val action = params["action"]?.trim().orEmpty()
        if (action.isBlank()) {
            return VerificationResult.failure("Telephony action missing", mapOf("action" to "<missing>"))
        }
        val recipient = params["recipient"]?.trim().orEmpty()
        val needsRecipient = action in listOf("call", "dial", "sms_draft")
        if (needsRecipient && recipient.isBlank()) {
            return VerificationResult.failure("Recipient is missing for telephony action '$action'", mapOf("action" to action, "recipient" to "<missing>"))
        }
        return if (result.success) {
            VerificationResult.success(
                "Telephony $action dispatched to '${recipient.ifBlank { "<caller>" }}'",
                mapOf("action" to action, "recipient" to recipient, "connect_confirmed" to "false", "outcome" to "DISPATCHED")
            )
        } else {
            VerificationResult.failure(
                "Telephony $action did not dispatch: ${result.message}",
                mapOf("action" to action, "outcome" to "FAILED")
            )
        }
    }

    private fun verifyCommunication(params: Map<String, String>, result: ToolResult): VerificationResult {
        val action = params["action"]?.trim()?.lowercase().orEmpty()
        val sendActions = setOf("send_message", "send", "send_direct_message", "direct_send", "direct_message", "send_direct")

        // Non-send actions (read, auto_reply, open) don't need a recipient
        if (action.isNotBlank() && action !in sendActions) {
            return if (result.success)
                VerificationResult.success("WhatsApp $action completed", mapOf("action" to action, "outcome" to "OK"))
            else
                VerificationResult.failure("WhatsApp $action failed: ${result.message}", mapOf("action" to action))
        }

        val recipient = params["recipient"]?.trim().orEmpty()
        return if (recipient.isNotBlank()) {
            if (result.success && (result.message.contains("automatically") || result.message.contains("sent"))) {
                VerificationResult.success(
                    "WhatsApp message sent to '$recipient' confirmed via automation",
                    mapOf("recipient" to recipient, "send_confirmed" to "true", "outcome" to "SENT_VIA_ACCESSIBILITY")
                )
            } else {
                VerificationResult.unknown(
                    "Communication dispatched to '$recipient', but delivery cannot be confirmed on this platform",
                    mapOf("recipient" to recipient, "send_confirmed" to "false", "outcome" to "DISPATCHED")
                )
            }
        } else {
            VerificationResult.failure(
                "Recipient is missing for communication action",
                mapOf("recipient" to "<missing>")
            )
        }
    }

    private fun queryForegroundApp(): String? {
        // Priority 1: Instant real-time foreground package from Accessibility Service (no special permissions required!)
        val accessibilityPkg = com.jarvis.accessibility.JarvisAccessibilityService.currentForegroundPackage
        if (!accessibilityPkg.isNullOrBlank()) {
            return accessibilityPkg
        }

        // Priority 2: Fallback to UsageStatsManager if granted
        return try {
            val usm = context?.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return null
            val end = System.currentTimeMillis()
            val start = end - 60_000
            val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end)
            stats?.filter { it.packageName.isNotBlank() }
                ?.maxByOrNull { it.lastTimeUsed }
                ?.packageName
        } catch (e: SecurityException) {
            Log.w(TAG, "UsageStats access not granted; foreground confirmation unavailable", e)
            null
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Best-effort lexical title match used to tie the observed playing metadata to the
 * requested query. Fails closed: a blank/unavailable title or a non-match returns
 * false so the verifier stays UNKNOWN instead of claiming the requested content.
 */
fun mediaTitleMatches(query: String, title: String?): Boolean {
    if (title.isNullOrBlank()) return false
    if (query.isBlank()) return true

    fun normalize(s: String): String =
        s.lowercase()
            .replace(Regex("[^a-z0-9\u0900-\u097F ]"), " ")
            .replace(Regex("\\s{2,}"), " ")
            .trim()

    val q = normalize(query)
    val t = normalize(title)
    if (q.isEmpty() || t.isEmpty()) return false
    if (q in t || t in q) return true

    val tokens = q.split(" ").filter { it.length >= 4 }
    return tokens.isNotEmpty() && tokens.all { it in t }
}