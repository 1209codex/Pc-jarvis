package com.jarvis.voice.audio

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Emits low-latency acoustic cues (earcons) and subtle haptic pulses for key voice events:
 * wake detection, speech interruption (barge-in), command listening start, and completion.
 */
class AcousticFeedbackEngine(
    private val context: Context
) {
    private val TAG = "AcousticFeedbackEngine"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var toneGenerator: ToneGenerator? = null

    @Volatile
    var isAudioEnabled: Boolean = true
    @Volatile
    var isHapticEnabled: Boolean = true

    /** Set to true when Bluetooth SCO is active so earcons route through the BT speaker. */
    @Volatile var isBluetoothScoActive: Boolean = false
        set(value) {
            if (field != value) {
                field = value
                // Recreate ToneGenerator with the appropriate stream for BT or phone speaker
                try { toneGenerator?.release() } catch (_: Exception) {}
                toneGenerator = null
                ensureToneGenerator()
                Log.d(TAG, "Bluetooth SCO state changed: earcons will route through ${if (value) "Bluetooth" else "phone speaker"}")
            }
        }

    init {
        ensureToneGenerator()
    }

    fun start() {
        ensureToneGenerator()
    }

    private fun ensureToneGenerator(): ToneGenerator? {
        if (toneGenerator == null) {
            try {
                // Use STREAM_VOICE_CALL when BT SCO is active so tones play through BT speaker
                val stream = if (isBluetoothScoActive) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_NOTIFICATION
                toneGenerator = ToneGenerator(stream, 75)
            } catch (e: Exception) {
                Log.w(TAG, "ToneGenerator initialization failed: ${e.message}")
            }
        }
        return toneGenerator
    }

    /** Plays alert tone when wake word is detected */
    fun playWakeTone() {
        if (!isAudioEnabled) return
        scope.launch {
            try {
                ensureToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP2, 90)
            } catch (e: Exception) {
                Log.w(TAG, "Error playing wake tone: ${e.message}")
            }
        }
    }

    /** Plays instant crisp earcon and tactile haptic pulse acknowledging speech barge-in */
    fun playBargeInEarcon() {
        if (isHapticEnabled) {
            triggerHapticPulse(35L)
        }
        if (isAudioEnabled) {
            scope.launch {
                try {
                    ensureToneGenerator()?.startTone(ToneGenerator.TONE_PROP_ACK, 60)
                } catch (e: Exception) {
                    Log.w(TAG, "Error playing barge-in earcon: ${e.message}")
                }
            }
        }
    }

    /** Plays soft tone when mic opens for command listening */
    fun playListeningTone() {
        if (!isAudioEnabled) return
        scope.launch {
            try {
                ensureToneGenerator()?.startTone(ToneGenerator.TONE_PROP_BEEP, 40)
            } catch (e: Exception) {
                Log.w(TAG, "Error playing listening tone: ${e.message}")
            }
        }
    }

    /** Plays completion tone upon successful task completion */
    fun playSuccessTone() {
        if (!isAudioEnabled) return
        scope.launch {
            try {
                ensureToneGenerator()?.startTone(ToneGenerator.TONE_PROP_PROMPT, 70)
            } catch (e: Exception) {
                Log.w(TAG, "Error playing success tone: ${e.message}")
            }
        }
    }

    private fun triggerHapticPulse(durationMs: Long) {
        scope.launch {
            try {
                val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                    vm?.defaultVibrator
                } else {
                    @Suppress("DEPRECATION")
                    context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                }

                if (vibrator != null && vibrator.hasVibrator()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
                    } else {
                        @Suppress("DEPRECATION")
                        vibrator.vibrate(durationMs)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Haptic pulse error: ${e.message}")
            }
        }
    }

    fun release() {
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing ToneGenerator: ${e.message}")
        }
    }
}
