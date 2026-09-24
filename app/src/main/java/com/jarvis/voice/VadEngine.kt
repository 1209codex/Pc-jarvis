package com.jarvis.voice

import kotlin.math.sqrt

sealed class VadEvent {
    object SpeechStarted : VadEvent()
    object SpeechContinues : VadEvent()
    object SpeechEnded : VadEvent()
    object Silence : VadEvent()
}

/**
 * Lightweight adaptive VAD for the 16-kHz PCM pipeline.
 *
 * It tracks a slowly changing noise floor and requires a configurable
 * signal-to-noise margin instead of relying on one hard-coded RMS value.
 */
class VadEngine(
    private val sampleRate: Int = 16000,
    private val energyThresholdRms: Float = 100.0f,
    private val silenceHangoverMs: Long = 600L,
    private val speechSnrMultiplier: Float = 1.4f,
    private val noiseAdaptation: Float = 0.02f
) {
    private var isSpeechActive = false
    private var silentFrameCount = 0
    private var noiseFloorRms = energyThresholdRms.coerceAtLeast(1f)

    @Synchronized
    fun processFrame(frame: ShortArray): VadEvent {
        if (frame.isEmpty()) return VadEvent.Silence

        val frameDurationMs = maxOf(1L, (frame.size * 1000L) / sampleRate)
        val dynamicHangoverFrames = maxOf(1, (silenceHangoverMs / frameDurationMs).toInt())

        val rms = frame.rms()
        val adaptiveThreshold =
            maxOf(energyThresholdRms, noiseFloorRms * speechSnrMultiplier)

        val isFrameSpeech = rms >= adaptiveThreshold

        // Only adapt the floor while the frame looks like background noise.
        if (!isFrameSpeech) {
            noiseFloorRms =
                noiseFloorRms * (1f - noiseAdaptation) +
                    rms * noiseAdaptation
        }

        return if (isFrameSpeech) {
            silentFrameCount = 0
            if (!isSpeechActive) {
                isSpeechActive = true
                VadEvent.SpeechStarted
            } else {
                VadEvent.SpeechContinues
            }
        } else if (isSpeechActive) {
            silentFrameCount++
            if (silentFrameCount >= dynamicHangoverFrames) {
                isSpeechActive = false
                silentFrameCount = 0
                VadEvent.SpeechEnded
            } else {
                VadEvent.SpeechContinues
            }
        } else {
            VadEvent.Silence
        }
    }

    fun noiseFloorRms(): Float = synchronized(this) { noiseFloorRms }

    @Synchronized
    fun reset() {
        isSpeechActive = false
        silentFrameCount = 0
        noiseFloorRms = energyThresholdRms.coerceAtLeast(1f)
    }
}
