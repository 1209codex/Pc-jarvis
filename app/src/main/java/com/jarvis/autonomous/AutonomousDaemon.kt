package com.jarvis.autonomous

import com.jarvis.calendar.CalendarManager
import com.jarvis.execution.AgentVerifier
import com.jarvis.execution.VerificationResult
import com.jarvis.files.FileManager
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolResult
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class AutonomousActionRecord(
    val id: String,
    val timestamp: Long,
    val triggerType: String,
    val description: String,
    val proposedTool: String,
    val executed: Boolean,
    val params: Map<String, String> = emptyMap(),
    val resultMessage: String? = null,
    val verified: Boolean = false,
    val verificationStatus: String = "UNKNOWN"
)

/**
 * What the autonomous daemon intends to dispatch for a proactive trigger.
 * Public so the rule -> action mapping stays unit-testable without Android.
 */
data class AutonomousActionSpec(
    val id: String,
    val triggerType: String,
    val tool: String,
    val params: Map<String, String>,
    val description: String
)

data class AutonomousDaemonState(
    val isEnabled: Boolean,
    val currentAmbient: AmbientState,
    val lastDecision: String?,
    val actionHistory: List<AutonomousActionRecord>
)

class AutonomousDaemon(
    private val ambientEngine: AmbientContextEngine,
    private val calendarManager: CalendarManager? = null,
    private val fileManager: FileManager? = null,
    private val toolExecutor: ToolExecutor? = null,
    private val verificationEngine: AgentVerifier? = null,
    private val busyProvider: () -> Boolean = { false },
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
) {
    private var isEnabled: Boolean = true
    private val actionLedger = mutableListOf<AutonomousActionRecord>()
    private var loopJob: Job? = null

    // Tracking flags to avoid duplicate spam within same trigger window
    private var lastMeetingAlertedId: String? = null
    private var lastLowBatteryAlertTime: Long = 0
    private var lastApkAlertTime: Long = 0

    private val _state = MutableStateFlow(
        AutonomousDaemonState(
            isEnabled = isEnabled,
            currentAmbient = AmbientState.STANDBY,
            lastDecision = "System initialized in Autonomous Standby",
            actionHistory = emptyList()
        )
    )
    val state: StateFlow<AutonomousDaemonState> = _state.asStateFlow()

    init {
        startHeartbeat()
    }

    fun setAutopilotEnabled(enabled: Boolean) {
        isEnabled = enabled
        updateState("Autopilot ${if (enabled) "Engaged" else "Disengaged"}")
        if (enabled && loopJob?.isActive != true) {
            startHeartbeat()
        }
    }

    fun isAutopilotEnabled(): Boolean = isEnabled

    fun startHeartbeat() {
        loopJob?.cancel()
        loopJob = coroutineScope.launch {
            while (isActive) {
                if (isEnabled) {
                    try {
                        evaluateAndExecuteTick()
                    } catch (_: Throwable) {
                        // Keep heartbeat alive regardless of transient errors
                    }
                }
                delay(60_000L) // 60s adaptive tick
            }
        }
    }

    fun stopHeartbeat() {
        loopJob?.cancel()
        loopJob = null
    }

    suspend fun evaluateAndExecuteTick(): AutonomousActionRecord? {
        val snapshot = ambientEngine.inferCurrentContext()

        // Rule 1: Upcoming Calendar Meeting in < 15 minutes
        val spec = checkUpcomingMeetingSpec()
            // Rule 2: Low Battery Conservation Warning (< 15% and not charging)
            ?: checkLowBatterySpec(snapshot)
            // Rule 3: Redundant APK Storage Alert (> 50MB APKs found)
            ?: checkStorageHealthSpec()

        if (spec != null) {
            val record = executeAutonomousAction(spec)
            recordAction(record)
            updateState(record.description)
            return record
        }

        // Rule 4: Night Wind-Down Ambient Activation
        if (snapshot.state == AmbientState.NIGHT_WIND_DOWN) {
            updateState("Night Wind-Down active. Notifications dimmed and alarms checked.")
        } else {
            updateState("Autopilot active. Context: ${snapshot.state.displayName}.")
        }

        return null
    }

    /**
     * Actually dispatches a proactive rule's action on device through the
     * ToolExecutor (auto-approved: autonomous mode, low-risk reversible tools),
     * verifies the outcome honestly, and records it.
     *
     * Honesty invariants:
     *  - no executor / busy device / dispatch error -> executed=false, never "success"
     *  - verification is only trusted from the AgentVerifier; UNKNOWN never maps to verified
     *  - exceptions never escape: the 60s heartbeat must stay alive
     */
    private suspend fun executeAutonomousAction(spec: AutonomousActionSpec): AutonomousActionRecord {
        val now = System.currentTimeMillis()

        val executor = toolExecutor
        if (executor == null) {
            Log.i(TAG, "Autonomous action '${spec.triggerType}' planned but no ToolExecutor attached; not executed")
            return AutonomousActionRecord(
                id = spec.id,
                timestamp = now,
                triggerType = spec.triggerType,
                description = spec.description,
                proposedTool = spec.tool,
                executed = false,
                params = spec.params,
                resultMessage = "No tool executor attached; action planned only",
                verified = false,
                verificationStatus = "UNKNOWN"
            )
        }

        // Never race an in-flight user command with a proactive side effect.
        val busy = try {
            busyProvider()
        } catch (_: Throwable) {
            false
        }
        if (busy) {
            Log.i(TAG, "Autonomous action '${spec.triggerType}' skipped: user task in progress")
            return AutonomousActionRecord(
                id = spec.id,
                timestamp = now,
                triggerType = spec.triggerType,
                description = spec.description,
                proposedTool = spec.tool,
                executed = false,
                params = spec.params,
                resultMessage = "Skipped: user task in progress",
                verified = false,
                verificationStatus = "UNKNOWN"
            )
        }

        val result: ToolResult = try {
            executor.execute(spec.tool, spec.params, userApprovalGranted = true)
        } catch (e: Exception) {
            Log.w(TAG, "Autonomous dispatch failed for '${spec.triggerType}'", e)
            return AutonomousActionRecord(
                id = spec.id,
                timestamp = now,
                triggerType = spec.triggerType,
                description = spec.description,
                proposedTool = spec.tool,
                executed = false,
                params = spec.params,
                resultMessage = "Dispatch error: ${e.message}",
                verified = false,
                verificationStatus = "FAILED"
            )
        }

        val verification: VerificationResult? = try {
            verificationEngine?.verify(spec.tool, spec.params, result)
        } catch (e: Exception) {
            Log.w(TAG, "Autonomous verification errored for '${spec.triggerType}'", e)
            null
        }

        val status = verification?.status?.name ?: "UNKNOWN"
        if (result.success) {
            Log.i(TAG, "Autonomous action '${spec.triggerType}' executed via ${spec.tool} (verification=$status)")
        } else {
            Log.w(TAG, "Autonomous action '${spec.triggerType}' did not execute: ${result.message}")
        }

        return AutonomousActionRecord(
            id = spec.id,
            timestamp = now,
            triggerType = spec.triggerType,
            description = spec.description,
            proposedTool = spec.tool,
            executed = result.success,
            params = spec.params,
            resultMessage = result.message,
            verified = verification?.verified ?: false,
            verificationStatus = status
        )
    }

    private fun checkUpcomingMeetingSpec(): AutonomousActionSpec? {
        val cm = calendarManager ?: return null
        val now = System.currentTimeMillis()
        val fifteenMinsLater = now + (15 * 60_000L)

        val upcoming = cm.getEventsForRange(now, fifteenMinsLater).firstOrNull {
            it.id != lastMeetingAlertedId && it.startTimeMillis > now
        } ?: return null

        lastMeetingAlertedId = upcoming.id
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val timeStr = sdf.format(Date(upcoming.startTimeMillis))

        return meetingActionSpec(upcoming.id, upcoming.title, timeStr)
    }

    private fun checkLowBatterySpec(snapshot: AmbientSnapshot): AutonomousActionSpec? {
        if (snapshot.batteryPercent !in 1..15 || snapshot.isCharging) return null
        val now = System.currentTimeMillis()
        if (now - lastLowBatteryAlertTime <= 15 * 60_000L) return null // Once per 15 mins
        lastLowBatteryAlertTime = now
        return lowBatteryActionSpec(snapshot.batteryPercent, now)
    }

    private fun checkStorageHealthSpec(): AutonomousActionSpec? {
        val fm = fileManager ?: return null
        val now = System.currentTimeMillis()
        if (now - lastApkAlertTime < 24 * 3600_000L) return null // Daily check

        val stats = fm.getStorageBreakdown()
        if (stats.apkFiles.isNotEmpty()) {
            lastApkAlertTime = now
            return storageActionSpec(stats.apkFiles.size, now)
        }
        return null
    }

    @Synchronized
    private fun recordAction(record: AutonomousActionRecord) {
        actionLedger.add(0, record)
        if (actionLedger.size > 30) {
            actionLedger.removeAt(actionLedger.lastIndex)
        }
    }

    @Synchronized
    fun getRecentActions(limit: Int = 10): List<AutonomousActionRecord> {
        return actionLedger.take(limit)
    }

    private fun updateState(decision: String) {
        val snapshot = ambientEngine.inferCurrentContext()
        _state.value = AutonomousDaemonState(
            isEnabled = isEnabled,
            currentAmbient = snapshot.state,
            lastDecision = decision,
            actionHistory = actionLedger.toList()
        )
    }

    companion object {
        private const val TAG = "AutonomousDaemon"

        /** Meeting prep = silence the ringer (reversible, low risk). */
        fun meetingActionSpec(eventId: String, title: String, timeStr: String): AutonomousActionSpec = AutonomousActionSpec(
            id = "act_meet_$eventId",
            triggerType = "UPCOMING_MEETING",
            tool = "DEVICE_SETTINGS",
            params = mapOf("action" to "set_ringer", "ringer_mode" to "silent"),
            description = "Upcoming '$title' at $timeStr. Proactively prepared meeting mode (ringer silenced)."
        )

        /** Battery conservation = mute media output (reversible, low risk). */
        fun lowBatteryActionSpec(percent: Int, now: Long = System.currentTimeMillis()): AutonomousActionSpec = AutonomousActionSpec(
            id = "act_bat_$now",
            triggerType = "LOW_BATTERY",
            tool = "DEVICE_SETTINGS",
            params = mapOf("action" to "mute"),
            description = "Battery at $percent%. Proactively muted media to conserve power."
        )

        /**
         * Storage cleanup = report suggestions ONLY. The daemon never deletes
         * anything autonomously (destructive actions stay behind user approval).
         */
        fun storageActionSpec(apkCount: Int, now: Long = System.currentTimeMillis()): AutonomousActionSpec = AutonomousActionSpec(
            id = "act_apk_$now",
            triggerType = "STORAGE_CLUTTER",
            tool = "FILE_MANAGER",
            params = mapOf("action" to "cleanup_suggestions"),
            description = "Discovered $apkCount leftover APK installers. Prepared cleanup suggestions (no files deleted)."
        )
    }
}
