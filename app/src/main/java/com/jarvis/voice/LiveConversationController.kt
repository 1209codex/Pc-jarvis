package com.jarvis.voice

import android.util.Log
import com.jarvis.voice.audio.RollingPcmBuffer
import com.jarvis.voice.router.AudioRoutingTarget
import com.jarvis.voice.router.VoiceRouter
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Ambient noise floor estimator using exponential moving average (EMA)
 * to calibrate VAD and barge-in sensitivity dynamically to room acoustics.
 */
class AmbientNoiseEstimator(
    private val smoothingFactor: Float = 0.05f
) {
    @Volatile
    var estimatedNoiseRms: Float = 60.0f
        private set

    fun updateSample(samples: ShortArray) {
        if (samples.isEmpty()) return
        val sum = samples.fold(0.0) { acc, s -> acc + s.toDouble() * s }
        val rms = sqrt(sum / samples.size).toFloat()

        // Only update noise floor during quiet periods to avoid tracking intentional speech
        if (rms < estimatedNoiseRms * 3.0f || estimatedNoiseRms <= 0f) {
            estimatedNoiseRms = (smoothingFactor * rms) + ((1f - smoothingFactor) * estimatedNoiseRms)
        }
    }

    fun getCalibratedBargeInThreshold(): Float {
        // Safe margin of ~2.4x above ambient floor, bounded between 220 and 700 RMS
        return (estimatedNoiseRms * 2.4f).coerceIn(220.0f, 700.0f)
    }
}

/**
 * Full-Duplex Live Conversational Voice Mode Controller.
 * Manages zero-wake-word continuous conversational sessions with sub-50ms
 * barge-in speech interruption and dynamic acoustic turn-taking.
 */
class LiveConversationController(
    private val voiceRouter: VoiceRouter,
    private val ttsEngine: TtsEngine? = null,
    private val rollingBuffer: RollingPcmBuffer? = null,
    private val onStateChanged: (Boolean) -> Unit = {}
) {
    private val TAG = "LiveConversation"

    private val isLiveSessionActive = AtomicBoolean(false)
    val noiseEstimator = AmbientNoiseEstimator()
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    var onInterruptionDetected: (() -> Unit)? = null
    var onTurnReady: ((String) -> Unit)? = null

    fun isSessionActive(): Boolean = isLiveSessionActive.get()

    fun startSession() {
        if (isLiveSessionActive.compareAndSet(false, true)) {
            Log.i(TAG, "Starting Full-Duplex Live Conversation session")
            onStateChanged(true)
            // Calibrate barge-in detector with current ambient noise
            val thresh = noiseEstimator.getCalibratedBargeInThreshold()
            voiceRouter.bargeInDetector.setCustomThreshold(thresh)
            // Route directly to active ASR listening without requiring wake-word trigger
            voiceRouter.routeToAsr(preRollSamples = 0)
        }
    }

    fun stopSession() {
        if (isLiveSessionActive.compareAndSet(true, false)) {
            Log.i(TAG, "Stopping Full-Duplex Live Conversation session")
            onStateChanged(false)
            ttsEngine?.stop()
            voiceRouter.routeToWakeWord()
        }
    }

    /**
     * Executes instantaneous speech barge-in interruption when human speech
     * is detected while TTS is speaking. Halts TTS playback in <50ms and seamlessly
     * transfers audio into ASR buffer.
     */
    fun handleBargeInInterruption(reason: String = "energy_burst") {
        if (!isLiveSessionActive.get()) return

        Log.i(TAG, "Live Conversation Barge-In interruption triggered (reason: $reason). Stopping TTS immediately.")
        // 1. Immediately cut off audio playback
        ttsEngine?.stop()

        // 2. Transition router to active command with 300ms pre-roll (4800 samples @ 16kHz) to recover start of user speech
        voiceRouter.routeToAsr(preRollSamples = 4800)

        // 3. Notify callbacks
        onInterruptionDetected?.invoke()
    }

    /**
     * Processes incoming audio frame to update ambient room acoustic estimation.
     */
    fun processAmbientAudio(samples: ShortArray) {
        noiseEstimator.updateSample(samples)
    }

    fun release() {
        stopSession()
        scope.cancel()
    }
}
