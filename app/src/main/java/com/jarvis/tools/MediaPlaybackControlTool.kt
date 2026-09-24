package com.jarvis.tools

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class MediaPlaybackControlTool(private val context: Context? = null) : Tool {
    override val name: String = "MEDIA_CONTROL"
    override val description: String =
        "Controls universal media playback (next track, previous track, play/resume, pause, seek forward, seek backward, stop). Parameter: action ('next', 'previous', 'play', 'pause', 'fast_forward', 'rewind', 'stop')."
    override val policy: ToolPolicy = ToolPolicy(idempotent = false, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "MEDIA_CONTROL",
        description = "Universal background media playback controller (next, previous, play, pause, seek).",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "stop"
        val am = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        when (action) {
            "volume_up", "raise_volume", "louder" -> {
                if (am == null) return ToolResult.Failed("AudioManager not available")
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
                return ToolResult.Success("Volume increased.", mapOf("action" to action))
            }
            "volume_down", "lower_volume", "quieter" -> {
                if (am == null) return ToolResult.Failed("AudioManager not available")
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
                return ToolResult.Success("Volume decreased.", mapOf("action" to action))
            }
            "set_volume", "volume" -> {
                if (am == null) return ToolResult.Failed("AudioManager not available")
                val target = params["level"] ?: params["volume"] ?: params["target"] ?: "50%"
                val percent = target.filter { it.isDigit() }.toIntOrNull() ?: 50
                val maxVol = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val newVol = ((percent.coerceIn(0, 100) / 100f) * maxVol).toInt().coerceIn(0, maxVol)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, newVol, AudioManager.FLAG_SHOW_UI)
                return ToolResult.Success("Music volume set to $percent%.", mapOf("volume" to newVol, "percent" to percent))
            }
            "mute", "silence_media" -> {
                if (am == null) return ToolResult.Failed("AudioManager not available")
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_MUTE, AudioManager.FLAG_SHOW_UI)
                return ToolResult.Success("Media muted.", mapOf("action" to action))
            }
            "unmute" -> {
                if (am == null) return ToolResult.Failed("AudioManager not available")
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_UNMUTE, AudioManager.FLAG_SHOW_UI)
                return ToolResult.Success("Media unmuted.", mapOf("action" to action))
            }
        }

        val keyCode = when (action) {
            "next", "skip", "forward" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous", "prev", "back" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            "play", "resume", "start" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "fast_forward", "seek_forward" -> KeyEvent.KEYCODE_MEDIA_FAST_FORWARD
            "rewind", "seek_backward" -> KeyEvent.KEYCODE_MEDIA_REWIND
            "stop" -> KeyEvent.KEYCODE_MEDIA_STOP
            else -> return ToolResult.Failed("Unknown media control action: '$action'. Supported: next, previous, play, pause, fast_forward, rewind, stop, volume_up, volume_down, set_volume, mute, unmute.")
        }

        return try {
            if (am == null) return ToolResult.Failed("AudioManager not available")

            val keyDown = KeyEvent(KeyEvent.ACTION_DOWN, keyCode)
            val keyUp = KeyEvent(KeyEvent.ACTION_UP, keyCode)
            am.dispatchMediaKeyEvent(keyDown)
            am.dispatchMediaKeyEvent(keyUp)

            val actionDesc = when (keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> "Skipped to next track"
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "Returned to previous track"
                KeyEvent.KEYCODE_MEDIA_PLAY -> "Resumed media playback"
                KeyEvent.KEYCODE_MEDIA_PAUSE -> "Paused media playback"
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> "Fast-forwarded media"
                KeyEvent.KEYCODE_MEDIA_REWIND -> "Rewound media"
                KeyEvent.KEYCODE_MEDIA_STOP -> "Stopped media playback"
                else -> "Dispatched media action: $action"
            }

            ToolResult.Success(
                message = "$actionDesc.",
                data = mapOf("action" to action, "keycode" to keyCode)
            )
        } catch (e: Exception) {
            ToolResult.Failed("Failed to dispatch media control action '$action': ${e.message}")
        }
    }
}
