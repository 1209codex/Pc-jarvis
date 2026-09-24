package com.jarvis

import com.jarvis.voice.AsrEngine
import com.jarvis.voice.AsrState
import com.jarvis.voice.PipelineState
import com.jarvis.voice.VoicePipelineLifecycleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class VoicePipelineOptimizationTest {

    private class MockAsrEngine : AsrEngine {
        private val _state = MutableStateFlow(AsrState.STOPPED)
        override val state: StateFlow<AsrState> = _state
        var isStopped = false

        override suspend fun start(asrDir: File) {
            _state.value = AsrState.READY
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

        override suspend fun pushAudio(samples: ShortArray) {}

        override suspend fun stopListening() {
            _state.value = AsrState.READY
        }

        override suspend fun stop() {
            _state.value = AsrState.STOPPED
            isStopped = true
        }
    }

    @Test
    fun testZeroStartupOverheadStandbyByDefault() {
        val mockAsr = MockAsrEngine()
        val lifecycleManager = VoicePipelineLifecycleManager(asrEngine = mockAsr)

        assertEquals(PipelineState.STANDBY, lifecycleManager.getCurrentState())
        assertEquals(AsrState.STOPPED, mockAsr.state.value)
    }

    @Test
    fun testLazyStateTransitionOnUserActivity() {
        val mockAsr = MockAsrEngine()
        val lifecycleManager = VoicePipelineLifecycleManager(asrEngine = mockAsr)

        lifecycleManager.onUserActivity()
        assertEquals(PipelineState.ACTIVE_ASR, lifecycleManager.getCurrentState())
    }

    @Test
    fun testIdleUnloadPipelineReleasesResources() = runBlocking {
        val mockAsr = MockAsrEngine()
        val lifecycleManager = VoicePipelineLifecycleManager(asrEngine = mockAsr, idleTimeoutMs = 100L)

        lifecycleManager.onUserActivity()
        assertEquals(PipelineState.ACTIVE_ASR, lifecycleManager.getCurrentState())

        lifecycleManager.unloadPipeline()

        assertEquals(PipelineState.STANDBY, lifecycleManager.getCurrentState())
        assertTrue(mockAsr.isStopped)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }
}
