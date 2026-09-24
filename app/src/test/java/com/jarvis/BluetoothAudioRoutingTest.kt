package com.jarvis

import com.jarvis.bluetooth.BluetoothHeadsetManager
import com.jarvis.voice.TtsEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class BluetoothAudioRoutingTest {

    private class MockTtsEngine : TtsEngine {
        var scoActive = false
        var speakCalled = false

        override fun start(ttsDir: File) {}
        override fun synthesize(text: String, onAudio: (ShortArray) -> Unit) {}
        override suspend fun speak(text: String): Boolean {
            speakCalled = true
            return true
        }
        override fun stop() {}
        override fun cancel() {}
        override fun updateBluetoothScoState(scoActive: Boolean) {
            this.scoActive = scoActive
        }
    }

    @Test
    fun testTtsEngineDefaultUpdateBluetoothScoState() {
        // Test default interface implementation doesn't throw
        val anonymousEngine = object : TtsEngine {
            override fun start(ttsDir: File) {}
            override fun synthesize(text: String, onAudio: (ShortArray) -> Unit) {}
            override suspend fun speak(text: String): Boolean = true
            override fun stop() {}
            override fun cancel() {}
        }

        anonymousEngine.updateBluetoothScoState(true)
        anonymousEngine.updateBluetoothScoState(false)
    }

    @Test
    fun testBluetoothHeadsetManagerScoStateUpdate() {
        val manager = BluetoothHeadsetManager(null, null)
        var callbackFired = false
        var callbackConnected = false

        manager.onHeadsetStateChanged = { connected, _ ->
            callbackFired = true
            callbackConnected = connected
        }

        manager.updateStateForTest(true, "Galaxy Buds2 Pro")
        assertTrue(manager.isHeadsetConnected)
        assertEquals("Galaxy Buds2 Pro", manager.connectedDeviceName)
        assertTrue(callbackFired)
        assertTrue(callbackConnected)
    }

    @Test
    fun testTtsEngineBluetoothScoStatePropagation() {
        val mockTts = MockTtsEngine()
        assertFalse(mockTts.scoActive)

        mockTts.updateBluetoothScoState(true)
        assertTrue(mockTts.scoActive)

        mockTts.updateBluetoothScoState(false)
        assertFalse(mockTts.scoActive)
    }
}
