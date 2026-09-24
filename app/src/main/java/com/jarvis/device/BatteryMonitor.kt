package com.jarvis.device

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log

data class BatteryState(
    val level: Int = 100,
    val scale: Int = 100,
    val percentage: Int = 100,
    val isCharging: Boolean = false,
    val isFull: Boolean = false,
    val pluggedSource: String = "None",
    val temperatureC: Float = 25f,
    val health: String = "Good"
)

object BatteryMonitor {
    private const val TAG = "BatteryMonitor"

    fun getBatteryState(context: Context?): BatteryState {
        if (context == null) return BatteryState(
            level = 85,
            scale = 100,
            percentage = 85,
            isCharging = false,
            isFull = false,
            pluggedSource = "None",
            temperatureC = 25f,
            health = "Good"
        )
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter) ?: return BatteryState(
            level = -1,
            scale = 100,
            percentage = -1,
            isCharging = false,
            isFull = false,
            pluggedSource = "Unknown",
            temperatureC = 0f,
            health = "Unknown"
        )

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val isFull = status == BatteryManager.BATTERY_STATUS_FULL || percent >= 100

        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
        val pluggedSource = when (plugged) {
            BatteryManager.BATTERY_PLUGGED_AC -> "AC"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "Wireless"
            else -> if (isCharging) "Charger" else "None"
        }

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempTenths / 10.0f

        val healthCode = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, BatteryManager.BATTERY_HEALTH_UNKNOWN)
        val healthStr = when (healthCode) {
            BatteryManager.BATTERY_HEALTH_GOOD -> "Good"
            BatteryManager.BATTERY_HEALTH_OVERHEAT -> "Overheat"
            BatteryManager.BATTERY_HEALTH_DEAD -> "Dead"
            BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "Over Voltage"
            else -> "Normal"
        }

        return BatteryState(
            level = level,
            scale = scale,
            percentage = percent,
            isCharging = isCharging,
            isFull = isFull,
            pluggedSource = pluggedSource,
            temperatureC = tempC,
            health = healthStr
        )
    }

    fun formatSummary(state: BatteryState): String {
        if (state.percentage < 0) {
            return "Unable to determine battery status."
        }
        return buildString {
            append("Battery is at ${state.percentage}%. ")
            when {
                state.isFull -> append("Battery is fully charged.")
                state.isCharging -> append("Currently charging via ${state.pluggedSource}.")
                state.percentage <= 15 -> append("Battery is low, please plug in your charger.")
                else -> append("Discharging normally.")
            }
        }
    }
}
