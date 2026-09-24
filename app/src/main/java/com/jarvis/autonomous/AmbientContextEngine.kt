package com.jarvis.autonomous

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.jarvis.calendar.CalendarManager
import java.util.Calendar
import java.util.Locale

enum class AmbientState(val displayName: String) {
    STANDBY("Standby"),
    DRIVING("Driving Mode"),
    MEETING("In Meeting"),
    FOCUS("Deep Focus"),
    NIGHT_WIND_DOWN("Night Wind-Down"),
    WORKOUT("Workout Session")
}

data class AmbientSnapshot(
    val state: AmbientState,
    val isManualOverride: Boolean,
    val batteryPercent: Int,
    val isCharging: Boolean,
    val activeEventTitle: String?,
    val timestamp: Long = System.currentTimeMillis()
)

class AmbientContextEngine(
    private val context: Context? = null,
    private val calendarManager: CalendarManager? = null
) {
    private var manualOverrideState: AmbientState? = null
    private var simulatedBatteryPercent: Int = 85
    private var simulatedCharging: Boolean = false

    fun setManualState(state: AmbientState?) {
        manualOverrideState = state
    }

    fun setSimulatedBattery(percent: Int, charging: Boolean) {
        simulatedBatteryPercent = percent
        simulatedCharging = charging
    }

    fun inferCurrentContext(): AmbientSnapshot {
        // 1. Manual override takes highest precedence
        manualOverrideState?.let { override ->
            return AmbientSnapshot(
                state = override,
                isManualOverride = true,
                batteryPercent = getBatteryPercent(),
                isCharging = isCharging(),
                activeEventTitle = getActiveMeetingTitle()
            )
        }

        val battery = getBatteryPercent()
        val charging = isCharging()
        val activeMeeting = getActiveMeetingTitle()

        // 2. Calendar Event Active -> MEETING
        if (activeMeeting != null) {
            return AmbientSnapshot(
                state = AmbientState.MEETING,
                isManualOverride = false,
                batteryPercent = battery,
                isCharging = charging,
                activeEventTitle = activeMeeting
            )
        }

        // 3. Late Night Window (22:00 to 07:00) + Charging -> NIGHT_WIND_DOWN
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val isNightHours = hour >= 22 || hour < 7

        if (isNightHours && charging) {
            return AmbientSnapshot(
                state = AmbientState.NIGHT_WIND_DOWN,
                isManualOverride = false,
                batteryPercent = battery,
                isCharging = charging,
                activeEventTitle = null
            )
        }

        // 4. Standby default
        return AmbientSnapshot(
            state = AmbientState.STANDBY,
            isManualOverride = false,
            batteryPercent = battery,
            isCharging = charging,
            activeEventTitle = null
        )
    }

    private fun getActiveMeetingTitle(): String? {
        val cm = calendarManager ?: return null
        val now = System.currentTimeMillis()
        val currentEvents = cm.getEventsForRange(now - 3600_000L, now + 3600_000L).filter {
            now in it.startTimeMillis..it.endTimeMillis
        }
        return currentEvents.firstOrNull()?.title
    }

    private fun getBatteryPercent(): Int {
        val ctx = context ?: return simulatedBatteryPercent
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = ctx.registerReceiver(null, filter)
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) (level * 100) / scale else simulatedBatteryPercent
        } catch (_: Throwable) {
            simulatedBatteryPercent
        }
    }

    private fun isCharging(): Boolean {
        val ctx = context ?: return simulatedCharging
        return try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val intent = ctx.registerReceiver(null, filter)
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        } catch (_: Throwable) {
            simulatedCharging
        }
    }
}
