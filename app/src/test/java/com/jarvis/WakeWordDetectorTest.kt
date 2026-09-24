package com.jarvis

import com.jarvis.voice.WakeWordEngine
import com.jarvis.wakeword.WakeWordConfig
import com.jarvis.wakeword.WakeWordDetector
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Unit tests covering wake word detector contract, multi-window confirmation,
 * noise/silence rejection, and lifecycle reset behavior.
 */
class WakeWordDetectorTest {

    private class FakeWakeWordDetector(
        private var modelAvailable: Boolean = true,
        val config: WakeWordConfig = WakeWordConfig()
    ) : WakeWordDetector {
        private val recentScores = ArrayDeque<Float>()
        private var programmedInferenceScore: Float = 0.0f
        var resetCount = 0
        var pushAudioCount = 0

        fun setInferenceScore(score: Float) {
            programmedInferenceScore = score
        }

        fun setModelAvailable(available: Boolean) {
            modelAvailable = available
        }

        override fun isModelAvailable(): Boolean = modelAvailable

        override fun getModelLoadError(): String? = if (modelAvailable) null else "Model asset missing"

        override fun pushAudio(samples: ShortArray): Float {
            pushAudioCount++
            if (!modelAvailable || samples.isEmpty()) return 0.0f

            // Check if energy is practically zero (silence)
            var sum = 0.0
            for (s in samples) sum += s * s
            val rms = kotlin.math.sqrt(sum / samples.size)
            if (rms < 50.0) {
                updateConfirmation(0.0f)
                return 0.0f
            }

            return updateConfirmation(programmedInferenceScore)
        }

        private fun updateConfirmation(score: Float): Float {
            recentScores.addLast(score)
            while (recentScores.size > config.confirmationWindowCount) {
                recentScores.removeFirst()
            }

            val positives = recentScores.count { it >= config.detectionThreshold }

            if (recentScores.size >= config.confirmationWindowCount &&
                positives >= config.requiredPositiveWindows
            ) {
                recentScores.clear()
                return score
            }
            return 0.0f
        }

        override fun resetDetectorState() {
            resetCount++
            recentScores.clear()
            programmedInferenceScore = 0.0f
        }

        override fun close() {
            modelAvailable = false
            resetDetectorState()
        }
    }

    @Test
    fun test1_modelMissing_disablesDetection() {
        val fakeDetector = FakeWakeWordDetector(modelAvailable = false)
        val engine = WakeWordEngine(
            customDetector = fakeDetector
        )

        assertFalse(engine.isAvailable())
        assertNotNull(engine.getModelError())

        var callbackCount = 0
        engine.start(null) { callbackCount++ }

        val audioFrame = ShortArray(320) { 1000 }
        fakeDetector.setInferenceScore(0.95f)

        val accepted = engine.acceptAudio(audioFrame)
        assertFalse(accepted)
        assertEquals(0, callbackCount)
    }

    @Test
    fun test2_silence_producesNoDetection() {
        val fakeDetector = FakeWakeWordDetector(modelAvailable = true)
        val engine = WakeWordEngine(
            customDetector = fakeDetector
        )

        var callbackCount = 0
        engine.start(null) { callbackCount++ }

        val silentAudio = ShortArray(320) { 0 }
        for (i in 0 until 10) {
            engine.acceptAudio(silentAudio)
        }

        assertEquals(0, callbackCount)
    }

    @Test
    fun test3_randomNoise_belowThreshold_producesNoDetection() {
        val fakeDetector = FakeWakeWordDetector(modelAvailable = true)
        val engine = WakeWordEngine(
            customDetector = fakeDetector
        )

        var callbackCount = 0
        engine.start(null) { callbackCount++ }

        val random = Random(42)
        val noiseAudio = ShortArray(320) { (random.nextInt(4000) - 2000).toShort() }
        fakeDetector.setInferenceScore(0.35f) // Below 0.80 threshold

        for (i in 0 until 10) {
            val accepted = engine.acceptAudio(noiseAudio)
            assertFalse(accepted)
        }

        assertEquals(0, callbackCount)
    }

    @Test
    fun test4_singlePositiveInference_doesNotTrigger_requiresConfirmation() {
        val config = WakeWordConfig(confirmationWindowCount = 3, requiredPositiveWindows = 2)
        val fakeDetector = FakeWakeWordDetector(modelAvailable = true, config = config)
        val engine = WakeWordEngine(
            config = config,
            customDetector = fakeDetector
        )

        var callbackCount = 0
        engine.start(null) { callbackCount++ }

        val speechFrame = ShortArray(320) { 2000 }

        // Window 1: Low score
        fakeDetector.setInferenceScore(0.20f)
        engine.acceptAudio(speechFrame)
        assertEquals(0, callbackCount)

        // Window 2: High score (1st positive)
        fakeDetector.setInferenceScore(0.90f)
        engine.acceptAudio(speechFrame)
        assertEquals(0, callbackCount) // Still need 2 positives in 3-window history

        // Window 3: Low score
        fakeDetector.setInferenceScore(0.10f)
        engine.acceptAudio(speechFrame)
        assertEquals(0, callbackCount)
    }

    @Test
    fun test5_twoPositivesInThreeWindows_triggersDetection() {
        val config = WakeWordConfig(confirmationWindowCount = 3, requiredPositiveWindows = 2)
        val fakeDetector = FakeWakeWordDetector(modelAvailable = true, config = config)
        val engine = WakeWordEngine(
            config = config,
            customDetector = fakeDetector
        )

        var callbackCount = 0
        engine.start(null) { callbackCount++ }

        val speechFrame = ShortArray(320) { 2000 }

        // Window 1: Positive
        fakeDetector.setInferenceScore(0.85f)
        engine.acceptAudio(speechFrame)
        assertEquals(0, callbackCount)

        // Window 2: Negative
        fakeDetector.setInferenceScore(0.40f)
        engine.acceptAudio(speechFrame)
        assertEquals(0, callbackCount)

        // Window 3: Positive -> 2 of 3 positive windows reached!
        fakeDetector.setInferenceScore(0.88f)
        val triggered = engine.acceptAudio(speechFrame)

        assertTrue(triggered)
        assertEquals(1, callbackCount)
    }

    @Test
    fun test6_consecutivePositives_triggersExactlyOneCallbackUntilReset() {
        val fakeDetector = FakeWakeWordDetector(modelAvailable = true)
        val engine = WakeWordEngine(
            customDetector = fakeDetector
        )

        val callbackCount = AtomicInteger(0)
        engine.start(null) { callbackCount.incrementAndGet() }

        val speechFrame = ShortArray(320) { 2000 }
        fakeDetector.setInferenceScore(0.90f)

        // Push 10 consecutive positive frames
        for (i in 0 until 10) {
            engine.acceptAudio(speechFrame)
        }

        // Exactly one wake callback must be triggered for this wake session
        assertEquals(1, callbackCount.get())
    }

    @Test
    fun test7_reset_clearsDetectionHistory() {
        val fakeDetector = FakeWakeWordDetector(modelAvailable = true)
        val engine = WakeWordEngine(
            customDetector = fakeDetector
        )

        var callbackCount = 0
        engine.start(null) { callbackCount++ }

        val speechFrame = ShortArray(320) { 2000 }

        // Step 1: One positive
        fakeDetector.setInferenceScore(0.85f)
        engine.acceptAudio(speechFrame)

        // Step 2: Reset
        engine.reset()
        assertTrue(fakeDetector.resetCount > 0)

        // Step 3: Another single positive after reset should NOT trigger (previous history wiped)
        fakeDetector.setInferenceScore(0.85f)
        engine.acceptAudio(speechFrame)
        assertEquals(0, callbackCount)
    }

    @Test
    fun test8_stoppedEngine_rejectsAudio() {
        val fakeDetector = FakeWakeWordDetector(modelAvailable = true)
        val engine = WakeWordEngine(
            customDetector = fakeDetector
        )

        var callbackCount = 0
        engine.start(null) { callbackCount++ }
        engine.stop()

        val speechFrame = ShortArray(320) { 2000 }
        fakeDetector.setInferenceScore(0.99f)

        val accepted = engine.acceptAudio(speechFrame)
        assertFalse(accepted)
        assertEquals(0, callbackCount)
    }
}
