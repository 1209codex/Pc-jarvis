package com.jarvis.wakeword

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin
import kotlin.random.Random

class HybridWakeWordDetectorTest {

    private class MockPrimaryDetector(
        private var available: Boolean = true,
        private var scoreToReturn: Float = 0.0f
    ) : WakeWordDetector {
        var pushAudioCount = 0
        var resetCount = 0
        var closed = false

        fun setScore(score: Float) { scoreToReturn = score }
        fun setAvailable(avail: Boolean) { available = avail }

        override fun isModelAvailable(): Boolean = available
        override fun getModelLoadError(): String? = if (available) null else "Model load error"
        override fun pushAudio(samples: ShortArray): Float {
            pushAudioCount++
            return scoreToReturn
        }
        override fun resetDetectorState() { resetCount++ }
        override fun close() { closed = true }
    }

    @Test
    fun testIsModelAvailable_alwaysTrueWithoutPrimary() {
        val hybrid = HybridWakeWordDetector(customPrimary = null)
        assertTrue(hybrid.isModelAvailable())
        assertFalse(hybrid.isNeuralModelActive())
    }

    @Test
    fun testWithPrimary_reportsNeuralActive() {
        val mockPrimary = MockPrimaryDetector(available = true)
        val hybrid = HybridWakeWordDetector(customPrimary = mockPrimary)
        assertTrue(hybrid.isModelAvailable())
        assertTrue(hybrid.isNeuralModelActive())
        assertNull(hybrid.getModelLoadError())
    }

    @Test
    fun testPrimaryDetection_triggersImmediately() {
        val mockPrimary = MockPrimaryDetector(available = true, scoreToReturn = 0.90f)
        val hybrid = HybridWakeWordDetector(customPrimary = mockPrimary)

        val frame = ShortArray(1600) { 1000 }
        val score = hybrid.pushAudio(frame)
        assertEquals(0.90f, score, 0.001f)
        assertTrue(mockPrimary.pushAudioCount > 0)
    }

    @Test
    fun testPrimaryActive_blocksAcousticFalsePositive() {
        val mockPrimary = MockPrimaryDetector(available = true, scoreToReturn = 0.0f)
        val hybrid = HybridWakeWordDetector(customPrimary = mockPrimary)

        // When primary is active and returns 0.0, even loud synthetic noise should return primary score (0.0f)
        val frame = ShortArray(1600) { 1000 }
        val score = hybrid.pushAudio(frame)
        assertEquals(0.0f, score, 0.001f)
    }

    @Test
    fun testSyntheticAcousticMatchCannotOverrideActiveNeuralNegative() {
        val hybrid = HybridWakeWordDetector(customPrimary = MockPrimaryDetector(scoreToReturn = 0.0f))
        val audio = ShortArray(16000)
        for (i in 3200 until 9600) {
            val t = i.toDouble() / 16000
            audio[i] = ((sin(2.0 * Math.PI * 300.0 * t) + 0.7 * sin(2.0 * Math.PI * 800.0 * t)) * 4000.0).toInt().toShort()
        }
        for (i in 9600 until 12800) {
            val t = i.toDouble() / 16000
            audio[i] = (sin(2.0 * Math.PI * 500.0 * t) * 2000.0).toInt().toShort()
        }
        val random = Random(123)
        for (i in 12800 until 16000) audio[i] = ((if (i % 3 == 0) -1 else 1) * (random.nextInt(2000) + 1000)).toShort()

        var score = 0.0f
        repeat(4) {
            for (start in audio.indices step 1600) {
                score = maxOf(score, hybrid.pushAudio(audio.copyOfRange(start, start + 1600)))
            }
        }
        assertEquals("Acoustic heuristic must not override a negative neural result", 0.0f, score, 0.001f)
    }

    @Test
    fun strictNeuralPathRejectsWhenOnlyDegradedAcousticDetectorExists() {
        val hybrid = HybridWakeWordDetector(
            customPrimary = null,
            config = WakeWordConfig(allowDegradedAcousticFallback = true),
            allowDegradedFallback = true
        )
        assertEquals(WakeDetectorMode.DEGRADED_ACOUSTIC, hybrid.getDetectorMode())
        assertEquals(0.0f, hybrid.pushNeuralAudio(ShortArray(16000)), 0.0f)
    }

    @Test
    fun testEnsemble_triggersWhenBothAgree() {
        val mockPrimary = MockPrimaryDetector(available = true, scoreToReturn = 0.85f)
        val hybrid = HybridWakeWordDetector(customPrimary = mockPrimary)

        val frame = ShortArray(1600) { 1000 }
        val score = hybrid.pushAudio(frame)
        assertTrue(score >= 0.82f)
    }

    @Test
    fun testPrimaryUnavailable_fallsBackToAcoustic() {
        val mockPrimary = MockPrimaryDetector(available = false, scoreToReturn = 0.0f)
        val hybrid = HybridWakeWordDetector(customPrimary = mockPrimary)

        assertTrue(hybrid.isModelAvailable())
        assertFalse(hybrid.isNeuralModelActive())

        val frame = ShortArray(1600) { 0 }
        val score = hybrid.pushAudio(frame)
        assertEquals(0.0f, score, 0.001f)
        assertEquals(0, mockPrimary.pushAudioCount) // primary skipped because not available
    }

    @Test
    fun testReset_resetsBothDetectors() {
        val mockPrimary = MockPrimaryDetector(available = true)
        val hybrid = HybridWakeWordDetector(customPrimary = mockPrimary)
        hybrid.resetDetectorState()
        assertEquals(1, mockPrimary.resetCount)
    }

    @Test
    fun testClose_releasesResources() {
        val mockPrimary = MockPrimaryDetector(available = true)
        val hybrid = HybridWakeWordDetector(customPrimary = mockPrimary)
        hybrid.close()
        assertTrue(mockPrimary.closed)
    }
}
