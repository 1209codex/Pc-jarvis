package com.jarvis.wakeword

import kotlin.math.sqrt

/**
 * Validates voice audio samples and computes tailored acoustic calibration
 * for personalized wake-word detection.
 *
 * Calibration derives:
 * - Calibrated detection threshold adapted to user's pronunciation clarity.
 * - Calibrated minimum RMS energy floor adapted to microphone characteristics.
 * - Ambient noise floor estimation.
 */
class VoiceEnrollmentCalibrator(
    private val minDurationMs: Long = 400L,
    private val minSpeechRms: Double = 70.0,
    private val maxClippingRatio: Double = 0.03,
    private val minConfidenceScore: Float = 0.35f,
    private val sampleRate: Int = 16000
) {
    sealed class ValidationResult {
        data class Valid(
            val rmsEnergy: Double,
            val score: Float,
            val speechDurationMs: Long,
            val clippingRatio: Double
        ) : ValidationResult()

        data class Rejected(
            val reason: RejectionReason,
            val message: String
        ) : ValidationResult()
    }

    enum class RejectionReason {
        EMPTY_AUDIO,
        TOO_SHORT,
        TOO_QUIET,
        CLIPPING_DISTORTION,
        LOW_CONFIDENCE
    }

    data class ValidatedSample(
        val pcm: ShortArray,
        val rmsEnergy: Double,
        val score: Float,
        val speechDurationMs: Long
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ValidatedSample) return false
            return pcm.contentEquals(other.pcm) &&
                    rmsEnergy == other.rmsEnergy &&
                    score == other.score &&
                    speechDurationMs == other.speechDurationMs
        }

        override fun hashCode(): Int {
            var result = pcm.contentHashCode()
            result = 31 * result + rmsEnergy.hashCode()
            result = 31 * result + score.hashCode()
            result = 31 * result + speechDurationMs.hashCode()
            return result
        }
    }

    /**
     * Calculates the Root-Mean-Square (RMS) amplitude of audio PCM samples.
     */
    fun calculateRms(pcm: ShortArray): Double {
        if (pcm.isEmpty()) return 0.0
        var sumSq = 0.0
        for (sample in pcm) {
            val v = sample.toDouble()
            sumSq += v * v
        }
        return sqrt(sumSq / pcm.size)
    }

    /**
     * Calculates the proportion of samples exceeding the clipping threshold.
     */
    fun calculateClippingRatio(pcm: ShortArray, clippingThreshold: Short = 32000): Double {
        if (pcm.isEmpty()) return 0.0
        var clippedCount = 0
        for (sample in pcm) {
            if (sample >= clippingThreshold || sample <= -clippingThreshold) {
                clippedCount++
            }
        }
        return clippedCount.toDouble() / pcm.size
    }

    /**
     * Estimates active speech duration by evaluating RMS in 40ms frames.
     */
    fun estimateSpeechDurationMs(pcm: ShortArray, frameSize: Int = 640): Long {
        if (pcm.isEmpty()) return 0L
        var activeFrames = 0
        val frameDurationMs = (frameSize * 1000L) / sampleRate

        for (i in 0 until pcm.size step frameSize) {
            val len = minOf(frameSize, pcm.size - i)
            var sumSq = 0.0
            for (j in 0 until len) {
                val v = pcm[i + j].toDouble()
                sumSq += v * v
            }
            val frameRms = sqrt(sumSq / len)
            if (frameRms >= minSpeechRms * 0.70) {
                activeFrames++
            }
        }
        return activeFrames * frameDurationMs
    }

    /**
     * Validates a recorded utterance against acoustic quality thresholds and optional
     * wake-word model confidence.
     */
    fun validateSample(pcm: ShortArray, detector: WakeWordDetector? = null): ValidationResult {
        if (pcm.isEmpty()) {
            return ValidationResult.Rejected(
                RejectionReason.EMPTY_AUDIO,
                "No audio captured. Please try recording again."
            )
        }

        val totalDurationMs = (pcm.size * 1000L) / sampleRate
        if (totalDurationMs < minDurationMs) {
            return ValidationResult.Rejected(
                RejectionReason.TOO_SHORT,
                "Audio duration too short (${totalDurationMs}ms < ${minDurationMs}ms)."
            )
        }

        val clippingRatio = calculateClippingRatio(pcm)
        if (clippingRatio > maxClippingRatio) {
            val pct = (clippingRatio * 100).toInt()
            return ValidationResult.Rejected(
                RejectionReason.CLIPPING_DISTORTION,
                "Microphone clipping detected ($pct%). Please speak slightly further from the microphone."
            )
        }

        val overallRms = calculateRms(pcm)
        if (overallRms < minSpeechRms) {
            return ValidationResult.Rejected(
                RejectionReason.TOO_QUIET,
                "Audio too quiet (RMS ${overallRms.toInt()} < ${minSpeechRms.toInt()}). Please speak louder."
            )
        }

        val speechDurationMs = estimateSpeechDurationMs(pcm)
        if (speechDurationMs < minDurationMs) {
            return ValidationResult.Rejected(
                RejectionReason.TOO_SHORT,
                "Speech utterance too brief (${speechDurationMs}ms active speech). Please say 'Jarvis' clearly."
            )
        }

        // Evaluate model confidence if detector is available
        var peakScore = 0.88f
        if (detector != null && detector.isModelAvailable()) {
            detector.resetDetectorState()
            var maxDetected = 0f
            val frameSize = 640
            for (i in 0 until pcm.size step frameSize) {
                val len = minOf(frameSize, pcm.size - i)
                val chunk = ShortArray(len)
                System.arraycopy(pcm, i, chunk, 0, len)
                val score = detector.pushAudio(chunk)
                if (score > maxDetected) {
                    maxDetected = score
                }
            }
            peakScore = maxDetected
            if (peakScore < minConfidenceScore) {
                val matchPct = (peakScore * 100).toInt()
                return ValidationResult.Rejected(
                    RejectionReason.LOW_CONFIDENCE,
                    "Keyword 'Jarvis' not recognized clearly (confidence $matchPct%). Please try again."
                )
            }
        }

        return ValidationResult.Valid(
            rmsEnergy = overallRms,
            score = peakScore,
            speechDurationMs = speechDurationMs,
            clippingRatio = clippingRatio
        )
    }

    /**
     * Calibrates a personalized [UserVoiceProfile] from validated utterance samples.
     */
    fun calibrate(
        samples: List<ValidatedSample>,
        wakeWordPhrase: String = "Jarvis"
    ): UserVoiceProfile {
        require(samples.isNotEmpty()) { "Cannot calibrate voice profile with empty sample set." }

        val avgScore = samples.map { it.score }.average().toFloat()
        val avgRms = samples.map { it.rmsEnergy }.average()

        // Calibrate dynamic threshold: 86% of mean confidence score, safely bounded between [0.55, 0.75]
        val calibratedThreshold = (avgScore * 0.86f).coerceIn(0.55f, 0.75f)

        // Calibrate minimum energy gate: 35% of mean speech RMS, safely bounded between [12.0, 45.0]
        val calibratedMinEnergy = (avgRms * 0.35).coerceIn(12.0, 45.0)

        // Ambient noise floor estimate: conservative fraction of energy gate
        val ambientNoise = (calibratedMinEnergy * 0.55).coerceIn(8.0, 25.0)

        return UserVoiceProfile(
            isEnrolled = true,
            enrolledAt = System.currentTimeMillis(),
            wakeWordPhrase = wakeWordPhrase,
            sampleCount = samples.size,
            averageScore = avgScore,
            calibratedThreshold = calibratedThreshold,
            calibratedMinEnergyRms = calibratedMinEnergy,
            ambientNoiseRms = ambientNoise
        )
    }
}
