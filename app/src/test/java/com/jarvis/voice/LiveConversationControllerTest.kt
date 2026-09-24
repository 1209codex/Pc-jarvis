package com.jarvis.voice

import com.jarvis.voice.audio.RollingPcmBuffer
import com.jarvis.voice.router.AudioRoutingTarget
import com.jarvis.voice.router.VoiceRouter
import com.jarvis.wakeword.WakeWordDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LiveConversationControllerTest {

    private class MockWakeDetector : WakeWordDetector {
        override fun pushAudio(samples: ShortArray): Float = 0.1f
        override fun resetDetectorState() {}
        override fun isModelAvailable(): Boolean = true
        override fun getModelLoadError(): String? = null
        override fun close() {}
    }

    private class MockAsrEngine : AsrEngine {
        override val state: StateFlow<AsrState> = MutableStateFlow(AsrState.LISTENING)
        override val usesRawAudio: Boolean = true
        override suspend fun start(asrDir: File) {}
        override suspend fun startListening(
            onSpeechStarted: (() -> Unit)?,
            onPartial: (String) -> Unit,
            onFinal: (String) -> Unit,
            onError: ((Int, String) -> Unit)?,
            onEmpty: (() -> Unit)?,
            onRmsChanged: ((Float) -> Unit)?
        ) {}
        override suspend fun pushAudio(samples: ShortArray) {}
        override suspend fun stopListening() {}
        override suspend fun stop() {}
    }

    private fun createVoiceRouter(): VoiceRouter {
        val wakeEngine = WakeWordEngine(customDetector = MockWakeDetector())
        val vadEngine = VadEngine()
        val asrEngine = MockAsrEngine()
        val rollingBuffer = RollingPcmBuffer(capacitySamples = 16000)
        return VoiceRouter(rollingBuffer, wakeEngine, vadEngine, asrEngine)
    }

    @Test
    fun testAmbientNoiseEstimatorInitialAndBoundings() {
        val estimator = AmbientNoiseEstimator(smoothingFactor = 0.1f)
        assertEquals(60.0f, estimator.estimatedNoiseRms, 0.01f)

        // Threshold = 60 * 2.4 = 144, but clamped to min 220
        assertEquals(220.0f, estimator.getCalibratedBargeInThreshold(), 0.01f)

        // Feed gentle ambient noise (e.g. RMS ~120)
        val gentleRoom = ShortArray(320) { 120 }
        estimator.updateSample(gentleRoom)
        assertTrue(estimator.estimatedNoiseRms > 60.0f)

        // If noise floor gets high, threshold is clamped to max 700
        val loudRoom = ShortArray(320) { 500 }
        repeat(50) {
            estimator.updateSample(loudRoom)
        }
        assertTrue(estimator.getCalibratedBargeInThreshold() <= 700.0f)
    }

    @Test
    fun testAmbientNoiseEstimatorIgnoresSuddenLoudBursts() {
        val estimator = AmbientNoiseEstimator(smoothingFactor = 0.05f)
        val initialFloor = estimator.estimatedNoiseRms

        // Sudden voice shout / loud burst > 3x ambient floor
        val speechBurst = ShortArray(320) { 2500 }
        estimator.updateSample(speechBurst)

        // Noise floor should not track the speech burst
        assertEquals(initialFloor, estimator.estimatedNoiseRms, 0.01f)
    }

    @Test
    fun testLiveConversationControllerSessionLifecycle() {
        val router = createVoiceRouter()
        var sessionActiveReported = false

        val controller = LiveConversationController(
            voiceRouter = router,
            ttsEngine = null,
            onStateChanged = { sessionActiveReported = it }
        )

        assertFalse(controller.isSessionActive())
        assertEquals(AudioRoutingTarget.WAKE_WORD_PASSIVE, router.getTarget())

        // Start session
        controller.startSession()
        assertTrue(controller.isSessionActive())
        assertTrue(sessionActiveReported)
        assertEquals(AudioRoutingTarget.ASR_ACTIVE_COMMAND, router.getTarget())

        // Stop session
        controller.stopSession()
        assertFalse(controller.isSessionActive())
        assertFalse(sessionActiveReported)
        assertEquals(AudioRoutingTarget.WAKE_WORD_PASSIVE, router.getTarget())

        controller.release()
        router.close()
    }

    @Test
    fun testBargeInInterruptionHandledWhenActive() {
        val router = createVoiceRouter()
        val controller = LiveConversationController(
            voiceRouter = router,
            ttsEngine = null
        )

        var interrupted = false
        controller.onInterruptionDetected = { interrupted = true }

        // When inactive, barge-in is ignored
        controller.handleBargeInInterruption("test")
        assertFalse(interrupted)

        // When active, barge-in triggers callback and routes to ASR
        controller.startSession()
        controller.handleBargeInInterruption("voice_burst")
        assertTrue(interrupted)
        assertEquals(AudioRoutingTarget.ASR_ACTIVE_COMMAND, router.getTarget())

        controller.release()
        router.close()
    }
}
