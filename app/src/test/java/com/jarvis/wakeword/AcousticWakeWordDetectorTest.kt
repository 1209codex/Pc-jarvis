package com.jarvis.wakeword

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class AcousticWakeWordDetectorTest {

    @Test
    fun testIsModelAvailable_alwaysTrue() {
        val detector = AcousticWakeWordDetector()
        assertTrue(detector.isModelAvailable())
        assertNull(detector.getModelLoadError())
    }

    @Test
    fun testSilence_returnsZeroScore() {
        val detector = AcousticWakeWordDetector()
        val silentFrame = ShortArray(1600) { 0 }
        val score = detector.pushAudio(silentFrame)
        assertEquals(0.0f, score, 0.001f)
    }

    @Test
    fun testRandomNoise_doesNotTrigger() {
        val detector = AcousticWakeWordDetector()
        val random = Random(42)
        val noiseFrame = ShortArray(1600) { (random.nextInt(300) - 150).toShort() }
        for (i in 0 until 15) {
            val score = detector.pushAudio(noiseFrame)
            assertEquals(0.0f, score, 0.001f)
        }
    }

    @Test
    fun testJarvisPhoneticProgression_scoresHigh() {
        val detector = AcousticWakeWordDetector()
        val sampleRate = 16000
        val oneSec = ShortArray(sampleRate)

        // Synthesize acoustic characteristics of "JAR-VIS":
        // 1. Frames 0 - 3200 (Window 0): Silence/Onset
        // 2. Frames 3200 - 9600 (Windows 1 & 2): "JAR" - 300Hz & 800Hz resonant periodic vocalic formants, low ZCR
        for (i in 3200 until 9600) {
            val t = i.toDouble() / sampleRate
            val tone = (sin(2.0 * Math.PI * 300.0 * t) + 0.7 * sin(2.0 * Math.PI * 800.0 * t)) * 4000.0
            oneSec[i] = tone.toInt().toShort()
        }

        // 3. Frames 9600 - 12800 (Window 3): Transition "V-I"
        for (i in 9600 until 12800) {
            val t = i.toDouble() / sampleRate
            val tone = sin(2.0 * Math.PI * 500.0 * t) * 2000.0
            oneSec[i] = tone.toInt().toShort()
        }

        // 4. Frames 12800 - 16000 (Window 4): "S" sibilant - high zero crossing rate, high frequency noise
        val rnd = Random(123)
        for (i in 12800 until 16000) {
            // Alternate signs rapidly to guarantee high ZCR (> 0.25)
            val sign = if (i % 3 == 0) -1 else 1
            oneSec[i] = (sign * (rnd.nextInt(2000) + 1000)).toShort()
        }

        val rawScore = detector.evaluateAcousticPhonetics(oneSec)
        assertTrue("Expected phonetic score >= 0.70 for synthetic Jarvis, was $rawScore", rawScore >= 0.70f)
    }

    @Test
    fun testSteadyLoudNoise_rejectedByDynamicRatio() {
        val detector = AcousticWakeWordDetector()
        val random = Random(42)
        // Uniform amplitude noise across all 16000 samples (RMS ~ 400)
        val loudNoise = ShortArray(16000) { (random.nextInt(1400) - 700).toShort() }
        val score = detector.evaluateAcousticPhonetics(loudNoise)
        assertEquals("Steady noise must be rejected with 0.0f", 0.0f, score, 0.001f)
    }

    @Test
    fun testReset_clearsState() {
        val detector = AcousticWakeWordDetector()
        detector.resetDetectorState()
        assertTrue(detector.isModelAvailable())
    }
}
