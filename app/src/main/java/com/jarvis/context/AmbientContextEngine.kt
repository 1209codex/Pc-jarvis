package com.jarvis.context

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.jarvis.bluetooth.BluetoothHeadsetManager
import com.jarvis.controlplane.JarvisEvent
import com.jarvis.controlplane.JarvisEventBus
import com.jarvis.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

enum class AmbientContext {
    HOME,
    WORK,
    DRIVING,
    NIGHT_REST,
    TRANSIT,
    UNKNOWN
}

data class GeofenceReminder(
    val id: String,
    val targetContext: AmbientContext,
    val reminderText: String,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * Smart Ambient Context & Geofence Autopilot Engine.
 * Detects whether the user is at Home, Work, Driving, or in Night Rest.
 * Automatically executes ambient mode transitions and triggers location-based voice reminders.
 */
class AmbientContextEngine(
    private val context: Context? = null,
    private val bluetoothHeadsetManager: BluetoothHeadsetManager? = null,
    private val ttsEngine: TtsEngine? = null
) {
    private val TAG = "AmbientContextEngine"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val isRunning = AtomicBoolean(false)
    private var monitorJob: Job? = null

    @Volatile
    var currentContext: AmbientContext = AmbientContext.UNKNOWN
        private set

    val homeSsids = CopyOnWriteArrayList<String>()
    val workSsids = CopyOnWriteArrayList<String>()
    val pendingReminders = CopyOnWriteArrayList<GeofenceReminder>()

    companion object {
        @Volatile
        var instance: AmbientContextEngine? = null
            private set
    }

    init {
        instance = this
        // Load default saved SSIDs from shared prefs
        context?.let { ctx ->
            val prefs = ctx.getSharedPreferences("jarvis_ambient_context", Context.MODE_PRIVATE)
            val home = prefs.getString("home_ssid", "") ?: ""
            val work = prefs.getString("work_ssid", "") ?: ""
            if (home.isNotBlank()) homeSsids.add(home)
            if (work.isNotBlank()) workSsids.add(work)
        }
    }

    fun setHomeSsid(ssid: String) {
        val clean = ssid.replace("\"", "").trim()
        if (clean.isNotBlank()) {
            homeSsids.clear()
            homeSsids.add(clean)
            context?.getSharedPreferences("jarvis_ambient_context", Context.MODE_PRIVATE)
                ?.edit()?.putString("home_ssid", clean)?.apply()
            Log.i(TAG, "Saved home SSID: '$clean'")
        }
    }

    fun setWorkSsid(ssid: String) {
        val clean = ssid.replace("\"", "").trim()
        if (clean.isNotBlank()) {
            workSsids.clear()
            workSsids.add(clean)
            context?.getSharedPreferences("jarvis_ambient_context", Context.MODE_PRIVATE)
                ?.edit()?.putString("work_ssid", clean)?.apply()
            Log.i(TAG, "Saved work SSID: '$clean'")
        }
    }

    fun addGeofenceReminder(targetContext: AmbientContext, text: String): GeofenceReminder {
        val reminder = GeofenceReminder(
            id = "geo_${System.currentTimeMillis()}",
            targetContext = targetContext,
            reminderText = text.trim()
        )
        pendingReminders.add(reminder)
        Log.i(TAG, "Added geofence reminder for ${targetContext.name}: '$text'")
        return reminder
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        Log.i(TAG, "Starting AmbientContextEngine...")

        monitorJob = scope.launch {
            while (isRunning.get()) {
                evaluateCurrentContext()
                delay(30_000L) // evaluate every 30 seconds
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        monitorJob?.cancel()
        monitorJob = null
        scope.cancel()
        if (instance == this) instance = null
        Log.i(TAG, "AmbientContextEngine stopped")
    }

    fun evaluateCurrentContext(): AmbientContext {
        val ssid = getCurrentSsid().lowercase(Locale.ROOT)
        val isHeadset = bluetoothHeadsetManager?.isHeadsetConnected == true

        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val isNightTime = hour >= 23 || hour < 6

        val detectedContext = when {
            // 1. Home WiFi match
            homeSsids.any { it.isNotBlank() && ssid.contains(it.lowercase(Locale.ROOT)) } -> AmbientContext.HOME

            // 2. Work WiFi match
            workSsids.any { it.isNotBlank() && ssid.contains(it.lowercase(Locale.ROOT)) } -> AmbientContext.WORK

            // 3. Bluetooth Car Audio / Headset while outdoors -> Driving
            isHeadset && ssid.isBlank() -> AmbientContext.DRIVING

            // 4. Night Rest
            isNightTime && (ssid.isNotBlank() || !isHeadset) -> AmbientContext.NIGHT_REST

            // 5. General Transit
            ssid.isBlank() -> AmbientContext.TRANSIT

            else -> AmbientContext.UNKNOWN
        }

        if (detectedContext != currentContext && detectedContext != AmbientContext.UNKNOWN) {
            val previous = currentContext
            currentContext = detectedContext
            onContextTransition(previous, detectedContext)
        }

        return detectedContext
    }

    private fun onContextTransition(from: AmbientContext, to: AmbientContext) {
        Log.i(TAG, "Ambient Context Transition: $from -> $to")

        // 1. Check pending geofence reminders
        val firedReminders = pendingReminders.filter { it.targetContext == to }
        if (firedReminders.isNotEmpty()) {
            pendingReminders.removeAll(firedReminders)
            scope.launch {
                for (r in firedReminders) {
                    val spoken = "Location reminder for ${to.name.lowercase()}: ${r.reminderText}"
                    Log.i(TAG, "Firing geofence reminder: $spoken")
                    ttsEngine?.speak(spoken)
                    JarvisEventBus.shared.post(JarvisEvent.ScheduledTrigger(r.id, spoken))
                    delay(500)
                }
            }
        }

        // 2. Announce major ambient mode transitions
        when (to) {
            AmbientContext.HOME -> {
                Log.i(TAG, "Arrived at Home context")
            }
            AmbientContext.WORK -> {
                Log.i(TAG, "Arrived at Work context")
            }
            AmbientContext.DRIVING -> {
                Log.i(TAG, "Engaging Driving Mode")
            }
            AmbientContext.NIGHT_REST -> {
                Log.i(TAG, "Engaging Night Rest Mode")
            }
            else -> {}
        }
    }

    fun getCurrentSsid(): String {
        val ctx = context ?: return ""
        return try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            wifi?.connectionInfo?.ssid?.replace("\"", "")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    fun evaluateContext(
        currentSsid: String,
        isDrivingAudio: Boolean,
        isNightHour: Boolean,
        isCharging: Boolean = false
    ): AmbientContext {
        val lowerSsid = currentSsid.lowercase(Locale.ROOT)
        return when {
            isDrivingAudio -> AmbientContext.DRIVING
            homeSsids.any { it.isNotBlank() && lowerSsid.contains(it.lowercase(Locale.ROOT)) } -> AmbientContext.HOME
            workSsids.any { it.isNotBlank() && lowerSsid.contains(it.lowercase(Locale.ROOT)) } -> AmbientContext.WORK
            isNightHour && isCharging -> AmbientContext.NIGHT_REST
            lowerSsid.isBlank() -> AmbientContext.TRANSIT
            else -> AmbientContext.UNKNOWN
        }
    }

    fun checkAndPopGeofenceReminders(target: AmbientContext): List<String> {
        val matches = pendingReminders.filter { it.targetContext == target }
        if (matches.isNotEmpty()) {
            pendingReminders.removeAll(matches)
        }
        return matches.map { it.reminderText }
    }

    fun clearGeofenceReminders() {
        pendingReminders.clear()
    }
}
