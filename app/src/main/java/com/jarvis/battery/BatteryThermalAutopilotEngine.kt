package com.jarvis.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.jarvis.controlplane.JarvisEvent
import com.jarvis.controlplane.JarvisEventBus
import com.jarvis.device.BatteryMonitor
import com.jarvis.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentLinkedDeque
import java.util.concurrent.atomic.AtomicBoolean

data class BatterySample(
    val percentage: Int,
    val timestamp: Long,
    val temperatureC: Float,
    val isCharging: Boolean
)

data class BatteryAutopilotStatus(
    val percentage: Int,
    val isCharging: Boolean,
    val temperatureC: Float,
    val drainOrChargeRatePerHour: Float, // positive = charging %/hr, negative = discharging %/hr
    val estimatedMinutesRemaining: Int?,
    val thermalStatus: String,
    val healthAdvice: String
)

/**
 * Intelligent Battery Health, Thermal Watchdog & Charging Autopilot.
 * Continuously tracks battery drain/charge velocity, overcharge protection (90% reminder),
 * and thermal watchdog alerts (41°C overheat alert).
 */
class BatteryThermalAutopilotEngine(
    private val context: Context? = null,
    private val ttsEngine: TtsEngine? = null
) : BroadcastReceiver() {

    private val TAG = "BatteryThermalAutopilot"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isRunning = AtomicBoolean(false)

    private val samples = ConcurrentLinkedDeque<BatterySample>()
    private var hasFiredOverchargeAlert = false
    private var lastThermalAlertTime = 0L

    companion object {
        @Volatile
        var instance: BatteryThermalAutopilotEngine? = null
            private set
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        instance = this
        Log.i(TAG, "Starting BatteryThermalAutopilotEngine...")

        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            context?.registerReceiver(this, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery receiver: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            context?.unregisterReceiver(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister battery receiver: ${e.message}")
        }
        samples.clear()
        scope.cancel()
        if (instance == this) instance = null
        Log.i(TAG, "BatteryThermalAutopilotEngine stopped")
    }

    override fun onReceive(ctx: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BATTERY_CHANGED) return

        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val percent = if (level >= 0 && scale > 0) ((level.toFloat() / scale.toFloat()) * 100).toInt() else 0

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL

        val tempTenths = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
        val tempC = tempTenths / 10.0f

        recordSample(percent, tempC, isCharging)
        evaluateSafeguards(percent, tempC, isCharging)
    }

    fun recordSample(percent: Int, tempC: Float, isCharging: Boolean) {
        val now = System.currentTimeMillis()
        samples.addLast(BatterySample(percent, now, tempC, isCharging))

        // Keep last 10 samples
        while (samples.size > 10) {
            samples.pollFirst()
        }

        if (!isCharging) {
            hasFiredOverchargeAlert = false
        }
    }

    fun evaluateSafeguards(percent: Int, tempC: Float, isCharging: Boolean) {
        val now = System.currentTimeMillis()

        // 1. Overcharge Alert at 90%
        if (isCharging && percent >= 90 && !hasFiredOverchargeAlert) {
            hasFiredOverchargeAlert = true
            val alert = "Boss, battery has reached $percent%. Please unplug to preserve battery longevity."
            Log.i(TAG, "Overcharge safeguard triggered: $alert")
            JarvisEventBus.shared.post(JarvisEvent.SystemAlert("BATTERY_OVERCHARGE", alert))
            scope.launch {
                ttsEngine?.speak(alert)
            }
        }

        // 2. Thermal Watchdog (Overheat >= 41.0°C)
        if (tempC >= 41.0f && (now - lastThermalAlertTime) > 15 * 60 * 1000L) {
            lastThermalAlertTime = now
            val alert = "Warning: Battery temperature is ${tempC.toInt()}°C. Recommending cooling down or closing heavy apps."
            Log.w(TAG, "Thermal safeguard triggered: $alert")
            JarvisEventBus.shared.post(JarvisEvent.SystemAlert("BATTERY_OVERHEAT", alert))
            scope.launch {
                ttsEngine?.speak(alert)
            }
        }
    }

    fun getAutopilotStatus(): BatteryAutopilotStatus {
        val last = samples.peekLast() ?: run {
            val state = context?.let { BatteryMonitor.getBatteryState(it) }
                ?: com.jarvis.device.BatteryState(percentage = 80, isCharging = false, temperatureC = 30.0f)
            return BatteryAutopilotStatus(
                percentage = state.percentage,
                isCharging = state.isCharging,
                temperatureC = state.temperatureC,
                drainOrChargeRatePerHour = 0f,
                estimatedMinutesRemaining = null,
                thermalStatus = if (state.temperatureC >= 41f) "HOT" else "NORMAL",
                healthAdvice = "Normal battery operation."
            )
        }

        val rate = calculateRatePerHour()
        val minutesRemaining = estimateMinutesRemaining(last.percentage, last.isCharging, rate)

        val thermalStatus = when {
            last.temperatureC >= 41f -> "OVERHEATING"
            last.temperatureC >= 37f -> "WARM"
            else -> "OPTIMAL"
        }

        val advice = when {
            last.temperatureC >= 41f -> "Device temperature is high. Avoid fast charging or gaming."
            last.isCharging && last.percentage >= 90 -> "Unplugging now will prolong lithium battery health."
            !last.isCharging && last.percentage <= 15 -> "Battery critical. Activate battery saver immediately."
            else -> "Battery condition is healthy and stable."
        }

        return BatteryAutopilotStatus(
            percentage = last.percentage,
            isCharging = last.isCharging,
            temperatureC = last.temperatureC,
            drainOrChargeRatePerHour = rate,
            estimatedMinutesRemaining = minutesRemaining,
            thermalStatus = thermalStatus,
            healthAdvice = advice
        )
    }

    fun calculateRatePerHour(sampleList: List<BatterySample> = samples.toList()): Float {
        if (sampleList.size < 2) return 0f
        val first = sampleList.first()
        val last = sampleList.last()

        val elapsedMs = last.timestamp - first.timestamp
        if (elapsedMs < 1000L) return 0f // Needs at least 1s difference

        val deltaPercent = (last.percentage - first.percentage).toFloat()
        val hours = elapsedMs.toFloat() / (1000f * 60f * 60f)
        return deltaPercent / hours
    }

    fun estimateMinutesRemaining(percent: Int, isCharging: Boolean, ratePerHour: Float): Int? {
        return when {
            isCharging && ratePerHour > 0f -> {
                val needed = (100 - percent).coerceAtLeast(0)
                ((needed / ratePerHour) * 60f).toInt().coerceAtLeast(1)
            }
            !isCharging && ratePerHour < 0f -> {
                val drainPositive = -ratePerHour
                ((percent / drainPositive) * 60f).toInt().coerceAtLeast(1)
            }
            !isCharging && ratePerHour > 0f -> {
                // Rate passed as absolute positive drain rate (%/hr)
                ((percent / ratePerHour) * 60f).toInt().coerceAtLeast(1)
            }
            else -> null
        }
    }

    data class HealthEvaluation(
        val isOverheated: Boolean,
        val isOvercharged: Boolean,
        val advisory: String
    )

    fun evaluateHealth(level: Int, isCharging: Boolean, tempC: Float): HealthEvaluation {
        val isOverheated = tempC >= 41.0f
        val isOvercharged = isCharging && level >= 90
        val advisory = when {
            isOverheated -> "Overheating: Battery temperature is ${tempC.toInt()}°C. Recommending cooling down or closing heavy apps."
            isOvercharged -> "Battery reached $level%. Please unplug to preserve battery longevity."
            else -> "Battery condition is healthy and stable."
        }
        return HealthEvaluation(isOverheated, isOvercharged, advisory)
    }
}
