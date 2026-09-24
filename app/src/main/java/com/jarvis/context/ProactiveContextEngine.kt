package com.jarvis.context

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.jarvis.calendar.CalendarEvent
import com.jarvis.calendar.CalendarManager
import com.jarvis.device.BatteryMonitor
import com.jarvis.device.BatteryState
import com.jarvis.notification.ProactiveNotificationDispatcher
import com.jarvis.routine.SmartRoutineEngine
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.ui.model.AppSettings
import com.jarvis.voice.VoiceEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Autonomous background intelligence engine that runs 24/7 in the background service.
 * Proactively monitors upcoming calendar events (15-min pre-briefings), critical low battery
 * warnings (<15%), 100% full charge alerts, and situational smart routines.
 */
class ProactiveContextEngine(
    private val context: Context? = null,
    var voiceEngine: VoiceEngine? = null,
    val calendarManager: CalendarManager = CalendarManager(context)
) {
    private val TAG = "ProactiveContextEngine"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var tickerJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    // Cache to prevent duplicate announcements
    val announcedCalendarEvents = ConcurrentHashMap<String, Long>()
    var lastLowBatteryAlertAt: Long = 0L
    var fullChargedAnnounced: Boolean = false

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_POWER_CONNECTED -> {
                    Log.i(TAG, "Charger connected")
                    SmartRoutineEngine.instance?.onChargerConnected()
                    checkBatteryStatus()
                }
                Intent.ACTION_POWER_DISCONNECTED -> {
                    Log.i(TAG, "Charger disconnected")
                    fullChargedAnnounced = false
                    SmartRoutineEngine.instance?.onChargerDisconnected()
                }
                Intent.ACTION_BATTERY_CHANGED, Intent.ACTION_BATTERY_LOW -> {
                    checkBatteryStatus()
                }
            }
        }
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        Log.i(TAG, "Starting ProactiveContextEngine...")

        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
                addAction(Intent.ACTION_BATTERY_LOW)
            }
            context?.registerReceiver(batteryReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery receiver: ${e.message}")
        }

        tickerJob = scope.launch {
            while (isActive && isRunning.get()) {
                try {
                    evaluateContext()
                } catch (e: Exception) {
                    Log.w(TAG, "Error in context ticker: ${e.message}")
                }
                delay(60_000L) // 1 minute ticker
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        Log.i(TAG, "Stopping ProactiveContextEngine...")
        try {
            context?.unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        tickerJob?.cancel()
        tickerJob = null
    }

    fun evaluateContext() {
        val appSettings = runCatching { context?.let { UiPreferencesStore(it).loadSettings() } }.getOrNull() ?: AppSettings()
        val now = System.currentTimeMillis()

        // 1. Calendar Event Pre-Briefings
        if (appSettings.proactiveCalendarAlertsEnabled) {
            evaluateCalendarEvents(now, appSettings.proactivePreBriefingMinutes)
        }

        // 2. Battery Safeguard
        if (appSettings.proactiveBatteryAlertsEnabled) {
            checkBatteryStatus()
        }

        // 3. Situational Routine Ticks
        evaluateSituationalRoutines()

        // Clean up old announced events (older than 2 hours)
        announcedCalendarEvents.entries.removeIf { it.value < now - (2 * 3600_000L) }
    }

    fun evaluateCalendarEvents(now: Long, preBriefingMinutes: Int = 15): List<String> {
        val windowStart = now + 120_000L // At least 2 minutes in future
        val windowEnd = now + (preBriefingMinutes * 60_000L) + 60_000L

        val events = calendarManager.getEventsForRange(windowStart, windowEnd)
        val triggered = mutableListOf<String>()

        for (event in events) {
            val eventKey = "${event.id}_${event.startTimeMillis}"
            if (!announcedCalendarEvents.containsKey(eventKey)) {
                val minsUntil = ((event.startTimeMillis - now) / 60_000L).coerceAtLeast(1)
                val locationStr = if (event.location.isNotBlank()) " on ${event.location}" else ""
                val spokenText = "Boss, you have ${event.title} starting in $minsUntil minutes$locationStr."

                // Dispatch system heads-up notification
                context?.let { ctx ->
                    ProactiveNotificationDispatcher.dispatchNotification(
                        context = ctx,
                        notificationId = eventKey.hashCode(),
                        title = "Upcoming: ${event.title} (in ${minsUntil}m)",
                        message = "Starts at ${formatTime(event.startTimeMillis)}$locationStr"
                    )
                }

                // Dispatch voice announcement
                voiceEngine?.speakProactiveAnnouncement(spokenText)

                announcedCalendarEvents[eventKey] = now
                triggered.add(spokenText)
                Log.i(TAG, "Announced proactive calendar pre-briefing: '$spokenText'")
            }
        }
        return triggered
    }

    fun checkBatteryStatus(): String? {
        val ctx = context ?: return null
        val state = BatteryMonitor.getBatteryState(ctx)
        com.jarvis.controlplane.JarvisEventBus.shared.emit(
            com.jarvis.controlplane.JarvisEvent.BatteryStateChanged(
                level = state.percentage,
                isCharging = state.isCharging
            )
        )
        return evaluateBatteryAlert(state, System.currentTimeMillis())
    }

    fun evaluateBatteryAlert(state: BatteryState, now: Long, settingsOverride: AppSettings? = null): String? {
        val appSettings = settingsOverride ?: runCatching { context?.let { UiPreferencesStore(it).loadSettings() } }.getOrNull() ?: AppSettings()

        // 1. Critical Low Battery Alert (<15% and discharging)
        if (state.percentage in 1..15 && !state.isCharging) {
            if (now - lastLowBatteryAlertAt > 30 * 60_000L) { // 30-min cooldown
                lastLowBatteryAlertAt = now
                val alertText = "Boss, battery level is critically low at ${state.percentage}%. Please connect your charger."

                context?.let { ctx ->
                    ProactiveNotificationDispatcher.dispatchNotification(
                        context = ctx,
                        notificationId = 1099,
                        title = "⚠️ Critical Battery (${state.percentage}%)",
                        message = "Connect charger or switch to battery saver mode."
                    )
                }

                voiceEngine?.speakProactiveAnnouncement(alertText)
                SmartRoutineEngine.instance?.onBatteryLevelChanged(state.percentage, false)
                Log.i(TAG, "Dispatched low battery alert: '$alertText'")
                return alertText
            }
        }

        // 2. Battery Full Charge Alert (100% and plugged in)
        if (state.percentage >= 100 && state.isCharging && !fullChargedAnnounced) {
            if (appSettings.batteryAnnouncementsEnabled) {
                fullChargedAnnounced = true
                val alertText = "Boss, battery is fully charged at 100%. You can unplug your device."

                context?.let { ctx ->
                    ProactiveNotificationDispatcher.dispatchNotification(
                        context = ctx,
                        notificationId = 1100,
                        title = "🔋 Battery 100% Charged",
                        message = "Device is fully charged and ready."
                    )
                }

                voiceEngine?.speakProactiveAnnouncement(alertText)
                SmartRoutineEngine.instance?.onBatteryLevelChanged(100, true)
                Log.i(TAG, "Dispatched full charge announcement: '$alertText'")
                return alertText
            }
        }

        if (!state.isCharging) {
            fullChargedAnnounced = false
        }

        return null
    }

    private fun evaluateSituationalRoutines() {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val minute = cal.get(Calendar.MINUTE)
        SmartRoutineEngine.instance?.onTimeTick(hour, minute)
    }

    private fun formatTime(millis: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        val h = cal.get(Calendar.HOUR_OF_DAY)
        val m = cal.get(Calendar.MINUTE)
        val amPm = if (h < 12) "AM" else "PM"
        val displayHour = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return String.format(java.util.Locale.US, "%02d:%02d %s", displayHour, m, amPm)
    }
}
