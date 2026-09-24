package com.jarvis

import com.jarvis.media.MediaDuckingManager
import com.jarvis.ui.model.AppSettings
import com.jarvis.voice.BargeInDetector
import com.jarvis.voice.router.AudioRoutingTarget
import com.jarvis.voice.router.VoiceRouter
import com.jarvis.wakeword.RaphaelPhoneticMatcher
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class FullDuplexBargeInAndDuckingTest {

    @Test
    fun testBargeInDetectorEnergyBurstThreshold() {
        val detector = BargeInDetector(baseThresholdRms = 380f, cooldownMs = 300L)

        // Silent / low energy frames (e.g. ambient silence or quiet bleed)
        val quietFrame = ShortArray(640) { 50 }
        assertFalse("Quiet frame should not trigger barge-in", detector.processFrame(quietFrame))

        // High energy speech burst (e.g. human voice spoken near mic)
        val speechFrame = ShortArray(640) { 1500 }
        assertTrue("Speech burst should trigger barge-in", detector.processFrame(speechFrame))

        // Immediate subsequent frame within cooldown window should not trigger
        assertFalse("Subsequent frame within cooldown should be debounced", detector.processFrame(speechFrame))

        // Reset detector clears cooldown
        detector.reset()
        assertTrue("Frame should trigger after reset", detector.processFrame(speechFrame))
    }

    @Test
    fun testBargeInDetectorSensitivityLevels() {
        val detector = BargeInDetector()

        detector.setSensitivity("HIGH")
        assertEquals(280f, detector.getEffectiveThreshold(), 0.01f)

        detector.setSensitivity("LOW")
        assertEquals(550f, detector.getEffectiveThreshold(), 0.01f)

        detector.setSensitivity("BALANCED")
        assertEquals(380f, detector.getEffectiveThreshold(), 0.01f)
    }

    @Test
    fun testBargeInDetectorDisabled() {
        val detector = BargeInDetector()
        detector.setEnabled(false)
        val loudFrame = ShortArray(640) { 2000 }
        assertFalse("Disabled detector should never trigger", detector.processFrame(loudFrame))
    }

    @Test
    fun testBargeInDetectorSpeakerBleedGated() {
        val detector = BargeInDetector()
        detector.isSpeakerBleedGated = true
        val loudLoudspeakerBleedFrame = ShortArray(640) { 2500 }
        assertFalse("Loudspeaker bleed should be suppressed when gated", detector.processFrame(loudLoudspeakerBleedFrame))

        detector.isSpeakerBleedGated = false
        assertTrue("Speech should trigger once gating is released", detector.processFrame(loudLoudspeakerBleedFrame))
    }

    @Test
    fun testMediaDuckingTargetVolumeCalculations() {
        // Volume 15 at 45% ducking -> (15 * 0.45) = 6.75 -> 7
        assertEquals(7, MediaDuckingManager.calculateTargetVolume(15, 45))

        // Volume 10 at 25% ducking -> (10 * 0.25) = 2.5 -> 3
        assertEquals(3, MediaDuckingManager.calculateTargetVolume(10, 25))

        // Volume 10 at 65% ducking -> (10 * 0.65) = 6.5 -> 7
        assertEquals(7, MediaDuckingManager.calculateTargetVolume(10, 65))

        // Edge case: volume 0 remains 0
        assertEquals(0, MediaDuckingManager.calculateTargetVolume(0, 45))

        // Edge case: minimum clamp is 1 if currentVolume > 0
        assertEquals(1, MediaDuckingManager.calculateTargetVolume(1, 10))
    }

    @Test
    fun testInterruptKeywordSpotting() {
        // Positive interrupt commands
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("stop"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("cancel"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("wait"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("quiet"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("silence"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("pause"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("hold on"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("shut up"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("stop please"))
        assertTrue(RaphaelPhoneticMatcher.isInterruptKeyword("jarvis stop"))

        // Negative non-interrupt commands
        assertFalse(RaphaelPhoneticMatcher.isInterruptKeyword("play synthwave music"))
        assertFalse(RaphaelPhoneticMatcher.isInterruptKeyword("what is the weather today"))
        assertFalse(RaphaelPhoneticMatcher.isInterruptKeyword("call dad"))
        assertFalse(RaphaelPhoneticMatcher.isInterruptKeyword(""))
    }

    @Test
    fun testVoiceRouterRoutingTargets() {
        // Verify AudioRoutingTarget enum values
        val targets = AudioRoutingTarget.values().map { it.name }
        assertTrue(targets.contains("WAKE_WORD_PASSIVE"))
        assertTrue(targets.contains("ASR_ACTIVE_COMMAND"))
        assertTrue(targets.contains("BARGE_IN_LISTENING"))
        assertTrue(targets.contains("MUTED"))
    }

    @Test
    fun testAcousticAndHapticSettingsPersistence() {
        val settings = AppSettings()
        assertTrue("Acoustic feedback should be enabled by default", settings.acousticFeedbackEnabled)
        assertTrue("Haptic feedback should be enabled by default", settings.hapticFeedbackEnabled)

        val custom = AppSettings(
            acousticFeedbackEnabled = false,
            hapticFeedbackEnabled = false,
            mediaDuckingPercent = 25
        )

        val json = JSONObject().apply {
            put("acousticFeedbackEnabled", custom.acousticFeedbackEnabled)
            put("hapticFeedbackEnabled", custom.hapticFeedbackEnabled)
            put("mediaDuckingPercent", custom.mediaDuckingPercent)
        }

        val restored = AppSettings(
            acousticFeedbackEnabled = json.optBoolean("acousticFeedbackEnabled", true),
            hapticFeedbackEnabled = json.optBoolean("hapticFeedbackEnabled", true),
            mediaDuckingPercent = json.optInt("mediaDuckingPercent", 45)
        )

        assertFalse(restored.acousticFeedbackEnabled)
        assertFalse(restored.hapticFeedbackEnabled)
        assertEquals(25, restored.mediaDuckingPercent)
    }
}
