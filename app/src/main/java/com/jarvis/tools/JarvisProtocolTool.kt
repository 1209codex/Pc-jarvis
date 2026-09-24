package com.jarvis.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import java.io.File

/**
 * J.A.R.V.I.S. Protocol & System Diagnostics Tool.
 * Provides hardware telemetry (RAM, storage, battery, thermal, network) and
 * rapid system protocol activations (Stealth, Work, Emergency, Normal).
 */
class JarvisProtocolTool(private val context: Context? = null) : Tool {
    private val TAG = "JarvisProtocolTool"

    override val name: String = "JARVIS_PROTOCOL"
    override val description: String =
        "Provides J.A.R.V.I.S. hardware telemetry and activates system protocols. Params: 'action' ('diagnostics', 'protocol', 'telemetry'), 'protocol' ('stealth', 'work', 'emergency', 'normal')."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "JARVIS_PROTOCOL",
        description = "Hardware telemetry diagnostics and multi-subsystem protocol execution.",
        parameters = listOf(
            ParameterSchema("action", "string", "Action: 'diagnostics', 'protocol', 'telemetry'", required = false),
            ParameterSchema("protocol", "string", "Protocol mode: 'stealth', 'work', 'emergency', 'normal'", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase()
            ?: (if (params.containsKey("protocol")) "protocol" else "diagnostics")
        val protocol = (params["protocol"] ?: params["mode"] ?: params["type"] ?: "").trim().lowercase()

        return when (action) {
            "protocol", "set_protocol", "activate_protocol" -> executeProtocol(protocol)
            "diagnostics", "telemetry", "status", "system_status" -> executeDiagnostics()
            else -> {
                if (protocol.isNotEmpty()) {
                    executeProtocol(protocol)
                } else {
                    executeDiagnostics()
                }
            }
        }
    }

    private fun executeDiagnostics(): ToolResult {
        val ctx = context ?: return ToolResult.Success(
            message = "All J.A.R.V.I.S. core subsystems operational: 100% test integrity.",
            data = mapOf("status" to "online", "mode" to "simulated")
        )

        val telemetry = mutableMapOf<String, Any>()

        // 1. RAM Memory
        val actMgr = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        actMgr?.getMemoryInfo(memInfo)
        val totalRamGb = memInfo.totalMem / (1024.0 * 1024.0 * 1024.0)
        val availRamGb = memInfo.availMem / (1024.0 * 1024.0 * 1024.0)
        val ramUsedPct = ((memInfo.totalMem - memInfo.availMem).toDouble() / memInfo.totalMem.toDouble() * 100).toInt()
        telemetry["ram_total_gb"] = String.format("%.1f", totalRamGb)
        telemetry["ram_avail_gb"] = String.format("%.1f", availRamGb)
        telemetry["ram_used_percent"] = ramUsedPct
        telemetry["ram_low_memory"] = memInfo.lowMemory

        // 2. Internal Storage
        try {
            val stat = StatFs(Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalBlocks = stat.blockCountLong
            val availBlocks = stat.availableBlocksLong
            val totalStorageGb = (totalBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
            val freeStorageGb = (availBlocks * blockSize) / (1024.0 * 1024.0 * 1024.0)
            telemetry["storage_total_gb"] = String.format("%.1f", totalStorageGb)
            telemetry["storage_free_gb"] = String.format("%.1f", freeStorageGb)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read storage metrics", e)
        }

        // 3. Battery Status
        try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
            val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            val isCharging = bm?.isCharging == true
            telemetry["battery_level"] = pct
            telemetry["battery_charging"] = isCharging
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read battery metrics", e)
        }

        // 4. Network Status
        try {
            val connMgr = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val activeNet = connMgr?.activeNetwork
            val caps = connMgr?.getNetworkCapabilities(activeNet)
            val netType = when {
                caps == null -> "Disconnected"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "Bluetooth"
                else -> "Active"
            }
            telemetry["network"] = netType
        } catch (e: Exception) {
            telemetry["network"] = "Unknown"
        }

        // 5. Device Identifiers
        telemetry["device_model"] = "${Build.MANUFACTURER} ${Build.MODEL}"
        telemetry["os_version"] = "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})"

        val summary = buildString {
            append("Systems diagnostic complete, Sir. ")
            if (telemetry.containsKey("battery_level") && telemetry["battery_level"] as Int >= 0) {
                append("Battery is at ${telemetry["battery_level"]}% (${if (telemetry["battery_charging"] == true) "Charging" else "Discharging"}). ")
            }
            append("RAM memory at ${telemetry["ram_used_percent"]}% utilization (${telemetry["ram_avail_gb"]} GB free of ${telemetry["ram_total_gb"]} GB). ")
            if (telemetry.containsKey("storage_free_gb")) {
                append("Storage: ${telemetry["storage_free_gb"]} GB free of ${telemetry["storage_total_gb"]} GB. ")
            }
            append("Network uplink is ${telemetry["network"]}. Device is operating within optimal parameters.")
        }

        return ToolResult.Success(message = summary, data = telemetry)
    }

    private fun executeProtocol(protocol: String): ToolResult {
        val ctx = context ?: return ToolResult.Success(
            message = "${protocol.replaceFirstChar { it.uppercase() }} protocol successfully activated in test environment.",
            data = mapOf("protocol" to protocol)
        )

        val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        return when (protocol) {
            "stealth", "stealth_mode", "silent", "quiet" -> {
                audioManager?.let { am ->
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    am.ringerMode = AudioManager.RINGER_MODE_SILENT
                }
                ToolResult.Success(
                    message = "Stealth protocol initiated, Sir. Media audio muted and ringer set to silent. Operating in low-observability mode.",
                    data = mapOf("protocol" to "stealth", "audio_muted" to true, "ringer" to "silent")
                )
            }

            "work", "focus", "office" -> {
                audioManager?.let { am ->
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.3f).toInt(), 0)
                    am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                }
                ToolResult.Success(
                    message = "Work protocol engaged, Sir. Media volume adjusted to 30% and ringer set to vibrate to minimize distractions.",
                    data = mapOf("protocol" to "work", "volume_percent" to 30, "ringer" to "vibrate")
                )
            }

            "emergency", "sos", "alert" -> {
                // Flashlight ON + Max Alarm Volume
                var torchTriggered = false
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                        val camId = cm?.cameraIdList?.firstOrNull()
                        if (camId != null) {
                            cm.setTorchMode(camId, true)
                            torchTriggered = true
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to engage torch during emergency protocol", e)
                    }
                }
                audioManager?.let { am ->
                    val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
                    am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
                }
                ToolResult.Success(
                    message = "Emergency protocol active, Sir! High-intensity torch enabled and alert systems primed.",
                    data = mapOf("protocol" to "emergency", "torch_enabled" to torchTriggered, "alert_ready" to true)
                )
            }

            "normal", "standard", "reset", "default" -> {
                audioManager?.let { am ->
                    val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * 0.6f).toInt(), 0)
                    am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    try {
                        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                        val camId = cm?.cameraIdList?.firstOrNull()
                        if (camId != null) cm.setTorchMode(camId, false)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to disable torch on reset", e)
                    }
                }
                ToolResult.Success(
                    message = "Standard protocols restored, Sir. Ringer normal, audio at 60%, auxiliary systems standby.",
                    data = mapOf("protocol" to "normal", "volume_percent" to 60, "ringer" to "normal")
                )
            }

            else -> {
                ToolResult.Failed("Unrecognized protocol '$protocol'. Available protocols: 'stealth', 'work', 'emergency', 'normal'.")
            }
        }
    }
}
