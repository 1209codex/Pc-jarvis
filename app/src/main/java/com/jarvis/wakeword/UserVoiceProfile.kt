package com.jarvis.wakeword

/**
 * Represents a user's calibrated voice profile for on-device keyword spotting.
 *
 * Stores personalized acoustic attributes derived from recorded wake-word utterances:
 * - Dynamic detection threshold (tailored to the user's vocal articulation)
 * - Calibrated minimum RMS energy floor (adapted to user volume & microphone hardware)
 * - Room ambient noise floor
 */
data class UserVoiceProfile(
    val isEnrolled: Boolean = false,
    val enrolledAt: Long = 0L,
    val wakeWordPhrase: String = "Jarvis",
    val sampleCount: Int = 0,
    val averageScore: Float = 0.0f,
    val calibratedThreshold: Float = 0.65f,
    val calibratedMinEnergyRms: Double = 15.0,
    val ambientNoiseRms: Double = 12.0
) {
    companion object {
        val DEFAULT = UserVoiceProfile()
    }
}
