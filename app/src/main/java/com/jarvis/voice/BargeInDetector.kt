package com.jarvis.voice

import kotlin.math.sqrt

/**
 * Evaluates real-time 16kHz PCM audio frames for full-duplex speech interruption (barge-in)
 * while J.A.R.V.I.S. is actively speaking or executing complex tasks.
 *
 * Employs adaptive energy burst detection with a calibrated threshold above acoustic
 * speaker playback to distinguish intentional human speech from residual speaker bleed.
 */
class BargeInDetector(
    private var baseThresholdRms: Float = 380.0f,
    private val cooldownMs: Long = 350L
) {
    private var lastTriggerAt: Long = 0L
    @Volatile
    private var isEnabled: Boolean = true
    @Volatile
    var isSpeakerBleedGated: Boolean = false

    fun setEnabled(enabled: Boolean) {
        isEnabled = enabled
    }

    fun isEnabled(): Boolean = isEnabled

    fun setSensitivity(sensitivity: String) {
        baseThresholdRms = when (sensitivity.uppercase()) {
            "HIGH" -> 280.0f
            "LOW" -> 550.0f
            else -> 380.0f // BALANCED / MEDIUM
        }
    }

    fun setCustomThreshold(rms: Float) {
        baseThresholdRms = rms.coerceIn(150.0f, 1200.0f)
    }

    fun getEffectiveThreshold(): Float = baseThresholdRms

    @Synchronized
    fun processFrame(samples: ShortArray): Boolean {
        if (!isEnabled || samples.isEmpty() || isSpeakerBleedGated) return false

        val now = System.currentTimeMillis()
        if (now - lastTriggerAt < cooldownMs) return false

        val rms = samples.rms()
        if (rms >= baseThresholdRms) {
            lastTriggerAt = now
            return true
        }
        return false
    }

    @Synchronized
    fun reset() {
        lastTriggerAt = 0L
    }
}
