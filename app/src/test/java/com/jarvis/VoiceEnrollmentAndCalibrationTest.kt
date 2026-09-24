package com.jarvis

import com.jarvis.voice.WakeWordEngine
import com.jarvis.wakeword.UserVoiceProfile
import com.jarvis.wakeword.VoiceEnrollmentCalibrator
import com.jarvis.wakeword.VoiceEnrollmentCalibrator.RejectionReason
import com.jarvis.wakeword.VoiceEnrollmentCalibrator.ValidatedSample
import com.jarvis.wakeword.VoiceEnrollmentCalibrator.ValidationResult
import com.jarvis.wakeword.VoiceSampleRecorder
import com.jarvis.wakeword.WakeWordConfig
import com.jarvis.wakeword.WakeWordDetector
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class VoiceEnrollmentAndCalibrationTest {

    private class MockWakeWordDetector(
        private var scoreToReturn: Float = 0.90f,
        private var available: Boolean = true
    ) : WakeWordDetector {
        var lastAudioSize: Int = 0

        override fun isModelAvailable(): Boolean = available
        override fun getModelLoadError(): String? = if (available) null else "Model not found"
        override fun pushAudio(samples: ShortArray): Float {
            lastAudioSize = samples.size
            return scoreToReturn
        }
        override fun resetDetectorState() { lastAudioSize = 0 }
        override fun close() {}

        fun setScore(score: Float) {
            scoreToReturn = score
        }
    }

    @Test
    fun testUserVoiceProfileDefaultValues() {
        val defaultProfile = UserVoiceProfile.DEFAULT
        assertFalse(defaultProfile.isEnrolled)
        assertEquals("Jarvis", defaultProfile.wakeWordPhrase)
        assertEquals(0, defaultProfile.sampleCount)
        assertEquals(0.65f, defaultProfile.calibratedThreshold, 0.001f)
        assertEquals(15.0, defaultProfile.calibratedMinEnergyRms, 0.001)
        assertEquals(12.0, defaultProfile.ambientNoiseRms, 0.001)
    }

    @Test
    fun testCalibratorRmsCalculation() {
        val calibrator = VoiceEnrollmentCalibrator()

        // Empty array returns 0.0
        assertEquals(0.0, calibrator.calculateRms(ShortArray(0)), 0.001)

        // Constant value 1000 has RMS 1000
        val constantPcm = ShortArray(640) { 1000 }
        assertEquals(1000.0, calibrator.calculateRms(constantPcm), 0.001)

        // Zero array has RMS 0.0
        val zeroPcm = ShortArray(640) { 0 }
        assertEquals(0.0, calibrator.calculateRms(zeroPcm), 0.001)
    }

    @Test
    fun testCalibratorClippingDetection() {
        val calibrator = VoiceEnrollmentCalibrator()

        // Clean unclipped audio
        val cleanPcm = ShortArray(1000) { (it % 10000).toShort() }
        assertEquals(0.0, calibrator.calculateClippingRatio(cleanPcm), 0.001)

        // 10% clipped audio (100 samples at 32500)
        val clippedPcm = ShortArray(1000) { if (it < 100) 32500.toShort() else 1000.toShort() }
        assertEquals(0.10, calibrator.calculateClippingRatio(clippedPcm), 0.001)
    }

    @Test
    fun testValidateSampleRejectsEmptyAndShortAudio() {
        val calibrator = VoiceEnrollmentCalibrator()

        // Empty audio
        val emptyResult = calibrator.validateSample(ShortArray(0))
        assertTrue(emptyResult is ValidationResult.Rejected)
        assertEquals(RejectionReason.EMPTY_AUDIO, (emptyResult as ValidationResult.Rejected).reason)

        // Too short audio (100ms = 1600 samples at 16kHz, required >= 400ms = 6400 samples)
        val shortPcm = ShortArray(1600) { 5000 }
        val shortResult = calibrator.validateSample(shortPcm)
        assertTrue(shortResult is ValidationResult.Rejected)
        assertEquals(RejectionReason.TOO_SHORT, (shortResult as ValidationResult.Rejected).reason)
    }

    @Test
    fun testValidateSampleRejectsTooQuietAudio() {
        val calibrator = VoiceEnrollmentCalibrator(minSpeechRms = 70.0)

        // 1 second of near-silent background noise (RMS = 10)
        val quietPcm = ShortArray(16000) { 10 }
        val result = calibrator.validateSample(quietPcm)
        assertTrue(result is ValidationResult.Rejected)
        assertEquals(RejectionReason.TOO_QUIET, (result as ValidationResult.Rejected).reason)
    }

    @Test
    fun testValidateSampleRejectsClippingAudio() {
        val calibrator = VoiceEnrollmentCalibrator()

        // 1 second of audio with 5% clipping (exceeds 3% limit)
        val clippedPcm = ShortArray(16000) { idx ->
            if (idx % 20 == 0) 32500.toShort() else 5000.toShort()
        }
        val result = calibrator.validateSample(clippedPcm)
        assertTrue(result is ValidationResult.Rejected)
        assertEquals(RejectionReason.CLIPPING_DISTORTION, (result as ValidationResult.Rejected).reason)
    }

    @Test
    fun testValidateSampleRejectsLowConfidenceWithDetector() {
        val calibrator = VoiceEnrollmentCalibrator(minConfidenceScore = 0.35f)
        val mockDetector = MockWakeWordDetector(scoreToReturn = 0.15f)

        // Clean speech audio (high RMS) but mock detector reports low confidence (0.15)
        val cleanSpeechPcm = ShortArray(16000) { (sin(it.toDouble() * 0.1) * 8000).toInt().toShort() }
        val result = calibrator.validateSample(cleanSpeechPcm, mockDetector)
        assertTrue(result is ValidationResult.Rejected)
        assertEquals(RejectionReason.LOW_CONFIDENCE, (result as ValidationResult.Rejected).reason)
    }

    @Test
    fun testValidateSampleAcceptsValidUtterance() {
        val calibrator = VoiceEnrollmentCalibrator()
        val mockDetector = MockWakeWordDetector(scoreToReturn = 0.92f)

        // 1 second of clean speech-like sinusoidal signal
        val speechPcm = ShortArray(16000) { (sin(it.toDouble() * 0.1) * 8000).toInt().toShort() }
        val result = calibrator.validateSample(speechPcm, mockDetector)
        assertTrue(result is ValidationResult.Valid)
        val valid = result as ValidationResult.Valid
        assertTrue("RMS should be > 70", valid.rmsEnergy > 70.0)
        assertEquals(0.92f, valid.score, 0.01f)
        assertTrue("Speech duration should be >= 400ms", valid.speechDurationMs >= 400L)
        assertEquals(0.0, valid.clippingRatio, 0.001)
    }

    @Test
    fun testCalibrateProducesOptimalProfileAndBoundsThresholds() {
        val calibrator = VoiceEnrollmentCalibrator()

        val sample1 = ValidatedSample(ShortArray(640), rmsEnergy = 300.0, score = 0.94f, speechDurationMs = 800L)
        val sample2 = ValidatedSample(ShortArray(640), rmsEnergy = 320.0, score = 0.90f, speechDurationMs = 750L)
        val sample3 = ValidatedSample(ShortArray(640), rmsEnergy = 280.0, score = 0.92f, speechDurationMs = 820L)

        val profile = calibrator.calibrate(listOf(sample1, sample2, sample3))

        assertTrue(profile.isEnrolled)
        assertEquals(3, profile.sampleCount)
        assertEquals(0.92f, profile.averageScore, 0.001f)

        // Expected calibrated threshold: bounded in [0.55, 0.75]
        assertEquals(0.75f, profile.calibratedThreshold, 0.01f)

        // Expected min energy: bounded in [12.0, 45.0]
        assertEquals(45.0, profile.calibratedMinEnergyRms, 0.5)

        // Expected ambient noise: 45.0 * 0.55 = 24.75
        assertEquals(24.75, profile.ambientNoiseRms, 0.5)
    }

    @Test
    fun testCalibrateEnforcesSafetyBoundsOnExtremeValues() {
        val calibrator = VoiceEnrollmentCalibrator()

        // Case 1: Very low score and energy should clamp to safe lower bounds (0.55f and 14.0)
        val lowSample = ValidatedSample(ShortArray(640), rmsEnergy = 40.0, score = 0.40f, speechDurationMs = 500L)
        val lowProfile = calibrator.calibrate(listOf(lowSample))
        assertEquals(0.55f, lowProfile.calibratedThreshold, 0.001f)
        assertEquals(14.0, lowProfile.calibratedMinEnergyRms, 0.001)

        // Case 2: Very high score and loud energy should clamp to safe upper bounds (0.75f and 45.0)
        val highSample = ValidatedSample(ShortArray(640), rmsEnergy = 2000.0, score = 1.0f, speechDurationMs = 1200L)
        val highProfile = calibrator.calibrate(listOf(highSample))
        assertTrue(highProfile.calibratedThreshold <= 0.75f)
        assertEquals(45.0, highProfile.calibratedMinEnergyRms, 0.001)
    }

    @Test
    fun testVoiceSampleRecorderCustomSourceCapture() = runBlocking {
        var callCount = 0
        var rmsReported = false

        // Custom audio source providing 640 samples of 1000 per call
        val customSource: (ShortArray) -> Int = { buffer ->
            callCount++
            for (i in buffer.indices) buffer[i] = 1000
            buffer.size
        }

        val recorder = VoiceSampleRecorder(customAudioSource = customSource)
        val result = recorder.recordSample(durationMs = 200L) { rms ->
            rmsReported = true
            assertTrue(rms >= 0f)
        }

        assertNotNull(result)
        assertTrue(result!!.size >= 3200) // 200ms at 16kHz = 3200 samples
        assertTrue(callCount >= 5)
        assertTrue(rmsReported)
        assertFalse(recorder.isRecording())
    }

    @Test
    fun testWakeWordEngineDynamicProfileApplicationAndThresholding() {
        val mockDetector = MockWakeWordDetector(scoreToReturn = 0.58f)
        val engine = WakeWordEngine(
            context = null,
            config = WakeWordConfig(detectionCooldownMs = 0L),
            customDetector = mockDetector
        )

        var detectedCount = 0
        engine.start(null) { detectedCount++ }

        // Initial default threshold is 0.65f.
        // Mock score is 0.58f, which is below 0.65f -> should NOT detect.
        val testPcm = ShortArray(640) { 1000 }
        val triggeredBefore = engine.acceptAudio(testPcm)
        assertFalse("Should not trigger when score (0.58) < default threshold (0.65)", triggeredBefore)
        assertEquals(0, detectedCount)

        // Apply personal calibrated profile with threshold 0.55f
        val customProfile = UserVoiceProfile(
            isEnrolled = true,
            enrolledAt = System.currentTimeMillis(),
            wakeWordPhrase = "Jarvis",
            sampleCount = 3,
            averageScore = 0.85f,
            calibratedThreshold = 0.55f,
            calibratedMinEnergyRms = 20.0
        )
        engine.applyVoiceProfile(customProfile)

        assertEquals(0.55f, engine.getEffectiveThreshold(), 0.001f)
        assertEquals(customProfile, engine.getVoiceProfile())

        // Now score (0.58) >= calibrated threshold (0.55) -> should TRIGGER!
        engine.reset()
        val triggeredAfter = engine.acceptAudio(testPcm)
        assertTrue("Should trigger when score (0.58) >= calibrated threshold (0.55)", triggeredAfter)
        assertEquals(1, detectedCount)

        // Reset back to factory default
        engine.applyVoiceProfile(UserVoiceProfile.DEFAULT)
        assertEquals(0.65f, engine.getEffectiveThreshold(), 0.001f)
        assertFalse(engine.getVoiceProfile().isEnrolled)

        engine.close()
    }
}
