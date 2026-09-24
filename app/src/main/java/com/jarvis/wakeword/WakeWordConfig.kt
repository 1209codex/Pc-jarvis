package com.jarvis.wakeword

/**
 * Runtime configuration for keyword spotting.
 *
 * The presets intentionally trade sensitivity for false-positive resistance.
 * A custom phrase requires a matching TFLite model placed in assets; the app
 * never pretends to train a model on-device.
 */
enum class WakeSensitivity {
    LOW,       // highest precision / lowest false positives
    BALANCED,
    HIGH       // highest recall
}

data class WakeWordProfile(
    val phrase: String,
    val modelAssetPath: String,
    val threshold: Float
)

data class WakeWordConfig(
    val sensitivity: WakeSensitivity = WakeSensitivity.BALANCED,
    val detectionThreshold: Float = 0.65f,
    val confirmationWindowCount: Int = 3,
    val requiredPositiveWindows: Int = 2,
    val minEnergyRms: Double = 15.0,
    val inferenceIntervalSamples: Int = 1600,
    val windowSamples: Int = 16000,
    val detectionCooldownMs: Long = 1200L,
    val allowDegradedAcousticFallback: Boolean = true,
    val profiles: List<WakeWordProfile> = listOf(
        WakeWordProfile("Jarvis", "jarvis_dscnn_int8.tflite", 0.65f)
    )
) {
    init {
        require(confirmationWindowCount in 1..10)
        require(requiredPositiveWindows in 1..confirmationWindowCount)
        require(detectionThreshold in 0f..1f)
        require(detectionCooldownMs >= 0L)
    }

    fun effectiveThreshold(): Float = when (sensitivity) {
        WakeSensitivity.LOW -> (detectionThreshold + 0.08f).coerceAtMost(0.95f)
        WakeSensitivity.BALANCED -> detectionThreshold
        WakeSensitivity.HIGH -> (detectionThreshold - 0.10f).coerceAtLeast(0.48f)
    }
}
