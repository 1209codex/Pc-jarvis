package com.jarvis.tools

import android.content.Context
import android.media.AudioManager
import android.os.Build
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class DeviceSettingsTool(private val context: Context? = null) : Tool {
    override val name: String = "DEVICE_SETTINGS"
    override val description: String =
        "Controls device audio volume, ringer mode, and system hardware settings. Parameters: action ('set_volume', 'mute', 'unmute', 'set_ringer', 'get_status'), volume_percent (0-100), ringer_mode ('normal', 'vibrate', 'silent')."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "DEVICE_SETTINGS",
        description = "Controls device volume percentage, ringer profiles, and audio streams.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    companion object {
        @Volatile
        private var savedPreMuteVolume: Int = -1
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return ToolResult.Failed("AudioManager not available on this device")

        val action = params["action"]?.trim()?.lowercase()
            ?: return ToolResult.Failed("Missing action parameter for DEVICE_SETTINGS. Action must be explicitly specified (e.g. set_volume, mute, unmute, set_ringer, get_status).")

        return try {
            when (action) {
                "volume_up", "raise_volume", "increase_volume", "louder" -> {
                    val stream = when (params["stream"]?.lowercase()) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        else -> AudioManager.STREAM_MUSIC
                    }
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                    val currentVol = audioManager.getStreamVolume(stream)
                    val maxVolume = audioManager.getStreamMaxVolume(stream)
                    val pct = Math.round((currentVol.toFloat() / maxVolume.coerceAtLeast(1)) * 100)
                    ToolResult.Success("Volume increased to $pct%.", mapOf("volume_percent" to pct, "action" to action))
                }

                "volume_down", "lower_volume", "decrease_volume", "quieter" -> {
                    val stream = when (params["stream"]?.lowercase()) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        else -> AudioManager.STREAM_MUSIC
                    }
                    audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                    val currentVol = audioManager.getStreamVolume(stream)
                    val maxVolume = audioManager.getStreamMaxVolume(stream)
                    val pct = Math.round((currentVol.toFloat() / maxVolume.coerceAtLeast(1)) * 100)
                    ToolResult.Success("Volume decreased to $pct%.", mapOf("volume_percent" to pct, "action" to action))
                }

                "set_volume", "volume" -> {
                    val percentStr = params["volume_percent"] ?: params["percent"] ?: params["volume"]
                        ?: return ToolResult.Failed("Missing volume_percent parameter for set_volume action.")
                    val percent = percentStr.toIntOrNull()?.coerceIn(0, 100)
                        ?: return ToolResult.Failed("Invalid volume_percent value '$percentStr'. Must be an integer between 0 and 100.")

                    val stream = when (params["stream"]?.lowercase()) {
                        "ring" -> AudioManager.STREAM_RING
                        "alarm" -> AudioManager.STREAM_ALARM
                        "notification" -> AudioManager.STREAM_NOTIFICATION
                        else -> AudioManager.STREAM_MUSIC
                    }
                    val maxVolume = audioManager.getStreamMaxVolume(stream)
                    val targetVolume = Math.round((percent / 100f) * maxVolume)
                    audioManager.setStreamVolume(stream, targetVolume, AudioManager.FLAG_SHOW_UI)
                    ToolResult.Success(
                        message = "Media volume set to $percent%.",
                        data = mapOf("volume_percent" to percent, "raw_volume" to targetVolume, "max_volume" to maxVolume)
                    )
                }

                "mute" -> {
                    val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    if (currentVol > 0) {
                        savedPreMuteVolume = currentVol
                    }
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, 0, 0)
                    ToolResult.Success("Media audio muted.", mapOf("muted" to true))
                }

                "unmute" -> {
                    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val restoreVol = if (savedPreMuteVolume > 0) {
                        savedPreMuteVolume
                    } else {
                        val passedPercent = params["volume_percent"]?.toIntOrNull()?.coerceIn(0, 100)
                        if (passedPercent != null) {
                            Math.round((passedPercent / 100f) * maxVolume)
                        } else {
                            Math.round(0.5f * maxVolume)
                        }
                    }
                    savedPreMuteVolume = -1
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, restoreVol, 0)
                    val restorePct = Math.round((restoreVol.toFloat() / maxVolume.coerceAtLeast(1)) * 100)
                    ToolResult.Success("Media audio unmuted to $restorePct%.", mapOf("muted" to false, "volume_percent" to restorePct))
                }

                "set_ringer", "ringer" -> {
                    val modeStr = params["ringer_mode"]?.trim()?.lowercase() ?: "normal"
                    when (modeStr) {
                        "silent" -> {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_SILENT
                            ToolResult.Success("Ringer mode set to SILENT.", mapOf("ringer_mode" to "silent"))
                        }
                        "vibrate" -> {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                            ToolResult.Success("Ringer mode set to VIBRATE.", mapOf("ringer_mode" to "vibrate"))
                        }
                        else -> {
                            audioManager.ringerMode = AudioManager.RINGER_MODE_NORMAL
                            ToolResult.Success("Ringer mode set to NORMAL.", mapOf("ringer_mode" to "normal"))
                        }
                    }
                }

                "get_status", "status" -> {
                    val currentMusicVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                    val maxMusicVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                    val musicPct = Math.round((currentMusicVol.toFloat() / maxMusicVol.coerceAtLeast(1)) * 100)
                    val ringerModeStr = when (audioManager.ringerMode) {
                        AudioManager.RINGER_MODE_SILENT -> "silent"
                        AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                        else -> "normal"
                    }
                    ToolResult.Success(
                        message = "Media volume is at $musicPct%, ringer is $ringerModeStr.",
                        data = mapOf("volume_percent" to musicPct, "ringer_mode" to ringerModeStr)
                    )
                }

                "storage_permission", "manage_storage", "permissions", "app_settings" -> {
                    if (context == null) return ToolResult.Failed("Context unavailable to open settings.")
                    try {
                        val intent = if ((action == "storage_permission" || action == "manage_storage") && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                            android.content.Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                                data = android.net.Uri.parse("package:${context.packageName}")
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        } else {
                            android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                data = android.net.Uri.fromParts("package", context.packageName, null)
                                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                        }
                        context.startActivity(intent)
                        ToolResult.Success("Opened permission settings for Jarvis.", mapOf("action" to action))
                    } catch (e: Exception) {
                        ToolResult.Failed("Could not open permission settings: ${e.message}")
                    }
                }

                else -> ToolResult.Failed("Unknown device setting action: '$action'")
            }
        } catch (e: Exception) {
            ToolResult.Failed("Failed to update device settings: ${e.message}")
        }
    }
}
