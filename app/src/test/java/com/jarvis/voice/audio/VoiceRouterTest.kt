package com.jarvis.voice.audio

import com.jarvis.voice.AsrEngine
import com.jarvis.voice.AsrState
import com.jarvis.voice.VadEngine
import com.jarvis.voice.WakeWordEngine
import com.jarvis.voice.router.AudioRoutingTarget
import com.jarvis.voice.router.VoiceRouter
import com.jarvis.wakeword.WakeWordDetector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VoiceRouterTest {

    private class MockWakeDetector : WakeWordDetector {
        var pushedFrames = 0
        override fun pushAudio(samples: ShortArray): Float {
            pushedFrames++
            return 0.5f
        }
        override fun resetDetectorState() {}
        override fun isModelAvailable(): Boolean = true
        override fun getModelLoadError(): String? = null
        override fun close() {}
    }

    private class MockAsrEngine : AsrEngine {
        var pushedPcm = 0
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
        override suspend fun pushAudio(samples: ShortArray) {
            pushedPcm++
        }
        override suspend fun stopListening() {}
        override suspend fun stop() {}
    }

    @Test
    fun testRoutingTargetSwitchesCorrectly() {
        val mockWakeDetector = MockWakeDetector()
        val wakeEngine = WakeWordEngine(customDetector = mockWakeDetector)
        wakeEngine.start(null) {}

        val vadEngine = VadEngine()
        val asrEngine = MockAsrEngine()
        val rollingBuffer = RollingPcmBuffer(capacitySamples = 16000)
        val router = VoiceRouter(rollingBuffer, wakeEngine, vadEngine, asrEngine)

        assertEquals(AudioRoutingTarget.WAKE_WORD_PASSIVE, router.getTarget())

        // Feed frame in passive mode
        val frame = ShortArray(640) { 100 }
        router.onAudioFrame(frame, frame.size)
        assertEquals(1, mockWakeDetector.pushedFrames)

        // Switch to ASR
        router.routeToAsr(640)
        assertEquals(AudioRoutingTarget.ASR_ACTIVE_COMMAND, router.getTarget())

        router.mute()
        assertEquals(AudioRoutingTarget.MUTED, router.getTarget())
    }

    @Test
    fun testFifoAudioChannelDeliveryInAsrMode() = kotlinx.coroutines.runBlocking {
        val mockWakeDetector = MockWakeDetector()
        val wakeEngine = WakeWordEngine(customDetector = mockWakeDetector)
        wakeEngine.start(null) {}

        val vadEngine = VadEngine()
        val asrEngine = MockAsrEngine()
        val rollingBuffer = RollingPcmBuffer(capacitySamples = 16000)
        val router = VoiceRouter(rollingBuffer, wakeEngine, vadEngine, asrEngine)

        router.routeToAsr(640)
        assertEquals(AudioRoutingTarget.ASR_ACTIVE_COMMAND, router.getTarget())

        val frame1 = ShortArray(640) { 1 }
        val frame2 = ShortArray(640) { 2 }
        router.onAudioFrame(frame1, frame1.size)
        router.onAudioFrame(frame2, frame2.size)

        // Allow worker coroutine to process
        kotlinx.coroutines.delay(100)
        assertEquals(2, asrEngine.pushedPcm)

        router.close()
    }
}
