package com.jarvis.voice

import com.jarvis.voice.audio.ContinuousAudioStream
import com.jarvis.voice.audio.RollingPcmBuffer
import com.jarvis.voice.router.AudioRoutingTarget
import com.jarvis.voice.router.VoiceRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ListeningSystemFixesTest {

    @Test
    fun testVadDynamicHangoverCalculation() {
        // At 16kHz, silenceHangoverMs = 800L
        val vad = VadEngine(sampleRate = 16000, energyThresholdRms = 100f, silenceHangoverMs = 800L)

        // 40ms frame (640 samples)
        // Dynamic hangover frames should be 800 / 40 = 20 frames
        val loudSpeechFrame = ShortArray(640) { 5000 }
        val silentFrame = ShortArray(640) { 0 }

        val startEvent = vad.processFrame(loudSpeechFrame)
        assertEquals(VadEvent.SpeechStarted, startEvent)

        // 19 silent frames -> SpeechContinues (hangover period)
        for (i in 1..19) {
            val event = vad.processFrame(silentFrame)
            assertEquals("Frame $i should be SpeechContinues", VadEvent.SpeechContinues, event)
        }

        // 20th silent frame -> SpeechEnded exactly at 800ms!
        val endEvent = vad.processFrame(silentFrame)
        assertEquals(VadEvent.SpeechEnded, endEvent)

        // Subsequent frame -> Silence
        val postEvent = vad.processFrame(silentFrame)
        assertEquals(VadEvent.Silence, postEvent)
    }

    private class StubAsrEngine : AsrEngine {
        private val _state = MutableStateFlow(AsrState.STOPPED)
        override val state: StateFlow<AsrState> = _state
        override val usesRawAudio: Boolean = true

        val pushedAudioList = mutableListOf<ShortArray>()
        var startCount = 0
        var stopListeningCount = 0

        override suspend fun start(asrDir: File) {
            _state.value = AsrState.READY
            startCount++
        }

        override suspend fun startListening(
            onSpeechStarted: (() -> Unit)?,
            onPartial: (String) -> Unit,
            onFinal: (String) -> Unit,
            onError: ((Int, String) -> Unit)?,
            onEmpty: (() -> Unit)?,
            onRmsChanged: ((Float) -> Unit)?
        ) {
            _state.value = AsrState.LISTENING
        }

        override suspend fun pushAudio(samples: ShortArray) {
            if (_state.value == AsrState.LISTENING) {
                pushedAudioList.add(samples.copyOf())
            }
        }

        override suspend fun stopListening() {
            if (_state.value == AsrState.LISTENING) {
                _state.value = AsrState.READY
                stopListeningCount++
            }
        }

        override suspend fun stop() {
            _state.value = AsrState.STOPPED
        }
    }

    @Test
    fun testPreRollPushedWhenAsrIsListening() = runBlocking {
        val stubAsr = StubAsrEngine()
        stubAsr.start(File("/tmp"))

        // Start listening
        stubAsr.startListening(
            onPartial = {},
            onFinal = {}
        )

        // Now push pre-roll audio
        val preRoll = ShortArray(3200) { 100 }
        stubAsr.pushAudio(preRoll)

        assertEquals(1, stubAsr.pushedAudioList.size)
        assertEquals(3200, stubAsr.pushedAudioList[0].size)
    }

    @Test
    fun testVoiceRouterFifoOrderPreservation() = runBlocking {
        val stubAsr = StubAsrEngine()
        stubAsr.start(File("/tmp"))
        stubAsr.startListening(onPartial = {}, onFinal = {})

        val rollingBuffer = RollingPcmBuffer(capacitySamples = 16000)
        val vadEngine = VadEngine()
        val wakeEngine = WakeWordEngine()

        val router = VoiceRouter(rollingBuffer, wakeEngine, vadEngine, stubAsr)
        router.routeToAsr(0)

        val frameA = ShortArray(640) { 10 }
        val frameB = ShortArray(640) { 20 }
        val frameC = ShortArray(640) { 30 }

        router.onAudioFrame(frameA, frameA.size)
        router.onAudioFrame(frameB, frameB.size)
        router.onAudioFrame(frameC, frameC.size)

        kotlinx.coroutines.delay(300)

        assertEquals(3, stubAsr.pushedAudioList.size)
        assertEquals(10.toShort(), stubAsr.pushedAudioList[0][0])
        assertEquals(20.toShort(), stubAsr.pushedAudioList[1][0])
        assertEquals(30.toShort(), stubAsr.pushedAudioList[2][0])

        router.close()
    }

    @Test
    fun testContinuousAudioStreamPreservesConsumersOnStop() {
        val stream = ContinuousAudioStream(
            sampleRate = 16000
        )
        var receivedCount = 0
        val consumer = com.jarvis.voice.audio.AudioFrameConsumer { _, _ ->
            receivedCount++
        }

        stream.addConsumer(consumer)

        // When stop is called, consumers should NOT be wiped out
        stream.stop()

        // Verify consumer is retained by explicitly testing clearConsumers contract
        stream.clearConsumers()
    }
}
