package com.jarvis.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VadEngineTest {
    @Test fun silenceDoesNotStartSpeech() {
        val vad = VadEngine(energyThresholdRms = 100f)
        repeat(5) { assertEquals(VadEvent.Silence, vad.processFrame(ShortArray(320))) }
    }

    @Test fun loudFrameStartsSpeech() {
        val vad = VadEngine(energyThresholdRms = 100f)
        val event = vad.processFrame(ShortArray(320) { 1000 })
        assertEquals(VadEvent.SpeechStarted, event)
        assertTrue(vad.noiseFloorRms() > 0)
    }
}
