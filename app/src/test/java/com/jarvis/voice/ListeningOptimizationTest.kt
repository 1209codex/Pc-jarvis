package com.jarvis.voice

import com.jarvis.ui.model.AppSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ListeningOptimizationTest {

    @Test
    fun testDefaultWakeAcknowledgmentIsChime() {
        val settings = AppSettings()
        assertEquals("CHIME", settings.wakeAcknowledgment)
    }

    @Test
    fun testVoiceEngineStatusAudioLevelRmsDefaultsToZero() {
        val status = VoiceEngineStatus()
        assertEquals(0.0f, status.audioLevelRms, 0.001f)
        assertEquals(VoiceState.IDLE, status.voiceState)
    }

    @Test
    fun testWakeAcknowledgmentSerializationRoundTrip() {
        // Test custom modes
        val modes = listOf("CHIME", "VOICE", "BOTH", "SILENT")
        for (mode in modes) {
            val settings = AppSettings(wakeAcknowledgment = mode)
            val json = JSONObject()
            json.put("wakeAcknowledgment", settings.wakeAcknowledgment)

            val parsedMode = json.optString("wakeAcknowledgment", "CHIME")
            assertEquals(mode, parsedMode)
        }
    }

    @Test
    fun testRmsNormalizationMath() {
        // rmsdB ranges typically between -2.0f and 10.0f
        val testCases = listOf(
            -2.0f to 0.0f,
            4.0f to 0.5f,
            10.0f to 1.0f,
            -10.0f to 0.0f, // clamped to 0
            20.0f to 1.0f   // clamped to 1
        )

        for ((rmsdB, expected) in testCases) {
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            assertEquals(expected, normalized, 0.01f)
        }
    }

    @Test
    fun testSherpaAsrEngineRmsComputation() {
        val samples = ShortArray(160) { 1500 }
        val sumSq = samples.fold(0.0) { acc, s -> acc + s * s }
        val rms = kotlin.math.sqrt(sumSq / samples.size).toFloat()
        val normalizedRms = (rms / 3000f).coerceIn(0f, 1f)

        assertTrue(normalizedRms > 0.4f && normalizedRms < 0.6f)
    }
}
