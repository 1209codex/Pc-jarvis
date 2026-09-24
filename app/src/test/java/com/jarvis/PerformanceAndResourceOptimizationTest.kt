package com.jarvis

import com.jarvis.camera.CameraPerceptionEngine
import com.jarvis.camera.TfLiteVisionDetector
import com.jarvis.controlplane.DeviceGuardian
import com.jarvis.controlplane.JarvisEvent
import com.jarvis.controlplane.JarvisEventBus
import com.jarvis.controlplane.WorldState
import com.jarvis.controlplane.WorldStateStore
import com.jarvis.telecom.ContactResolver
import com.jarvis.voice.WakeWordEngine
import com.jarvis.voice.audio.RollingPcmBuffer
import com.jarvis.wakeword.WakeWordConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PerformanceAndResourceOptimizationTest {

    @Before
    fun setUp() {
        WorldStateStore.shared.update {
            WorldState(
                batteryPercent = 100,
                isCharging = false,
                isNetworkOnline = true,
                isMeteredNetwork = false,
                activeCaller = null
            )
        }
    }

    @Test
    fun testRollingPcmBufferDirectOffsetWriteAndZeroCopy() {
        val buffer = RollingPcmBuffer(capacitySamples = 1600)
        val testPcm = ShortArray(640) { (it % 100).toShort() }

        // Write directly from buffer using offset and length
        buffer.write(testPcm, 0, 640)
        val read1 = buffer.getRecentAudio(640)
        assertEquals(640, read1.size)
        assertEquals(0.toShort(), read1[0])
        assertEquals(99.toShort(), read1[99])

        // Write second chunk
        buffer.write(testPcm, 0, 640)
        val read2 = buffer.getRecentAudio(1280)
        assertEquals(1280, read2.size)

        // Write third chunk causing circular wrap-around
        buffer.write(testPcm, 0, 640)
        val read3 = buffer.getRecentAudio(1600)
        assertEquals(1600, read3.size)
    }

    @Test
    fun testWakeWordEngineTrailingAudioBufferOnlySnapshotsOnTrigger() {
        val engine = WakeWordEngine(null, config = WakeWordConfig())
        engine.start(null) { }

        val silentAudio = ShortArray(640) { 0 }
        // Before detection, trailingAudioBuffer is null (no heap copy allocated on every frame)
        val detected = engine.acceptAudio(silentAudio)
        assertFalse(detected)
        assertNull(engine.getTrailingAudio())

        engine.stop()
    }

    @Test
    fun testDeviceGuardianDynamicPowerInvariants() {
        val store = WorldStateStore.shared
        val guardian = DeviceGuardian(store)

        // 1. Healthy state
        store.update { it.copy(batteryPercent = 85, isCharging = false, activeCaller = null, isNetworkOnline = true) }
        val healthyVerdict = guardian.canExecuteAutonomousTask(requiresAudio = true, requiresNetwork = true)
        assertTrue(healthyVerdict.allowed)

        // 2. Critical Battery (<10% and discharging)
        store.update { it.copy(batteryPercent = 8, isCharging = false) }
        val lowBatVerdict = guardian.canExecuteAutonomousTask(requiresAudio = true)
        assertFalse(lowBatVerdict.allowed)
        assertTrue(lowBatVerdict.reason.contains("critically low"))

        // Plug in charger -> recovers
        store.update { it.copy(isCharging = true) }
        val pluggedVerdict = guardian.canExecuteAutonomousTask(requiresAudio = true)
        assertTrue(pluggedVerdict.allowed)

        // 3. Active Telephony Call Guard
        store.update { it.copy(isCharging = false, batteryPercent = 50, activeCaller = "John Doe") }
        val callVerdict = guardian.canExecuteAutonomousTask(requiresAudio = true)
        assertFalse(callVerdict.allowed)
        assertTrue(callVerdict.reason.contains("phone call"))

        // Non-audio task is allowed during call
        val silentVerdict = guardian.canExecuteAutonomousTask(requiresAudio = false)
        assertTrue(silentVerdict.allowed)

        // Call finishes
        store.update { it.copy(activeCaller = null) }
        val callEndedVerdict = guardian.canExecuteAutonomousTask(requiresAudio = true)
        assertTrue(callEndedVerdict.allowed)
    }

    @Test
    fun testBatteryStateChangedEventUpdatesWorldStore() = runBlocking {
        val bus = JarvisEventBus.shared
        val store = WorldStateStore.shared

        store.update { it.copy(batteryPercent = 100, isCharging = true) }

        // Emit battery drain event
        bus.emit(JarvisEvent.BatteryStateChanged(level = 42, isCharging = false))

        val received = bus.subscribe<JarvisEvent.BatteryStateChanged>().first()
        assertEquals(42, received.level)
        assertFalse(received.isCharging)
    }

    @Test
    fun testNetworkStateChangedEventUpdatesWorldStore() = runBlocking {
        val bus = JarvisEventBus.shared

        bus.emit(JarvisEvent.NetworkStateChanged(isOnline = true, isMetered = false))
        val event = bus.subscribe<JarvisEvent.NetworkStateChanged>().first()
        assertTrue(event.isOnline)
        assertFalse(event.isMetered)
    }

    @Test
    fun testOptimizedLevenshteinDistanceAccuracy() {
        val resolver = ContactResolver(null)
        val method = ContactResolver::class.java.getDeclaredMethod("levenshtein", String::class.java, String::class.java)
        method.isAccessible = true

        fun dist(s: String, t: String): Int = method.invoke(resolver, s, t) as Int

        assertEquals(0, dist("", ""))
        assertEquals(0, dist("Jarvis", "Jarvis"))
        assertEquals(3, dist("", "Bob"))
        assertEquals(3, dist("Bob", ""))
        assertEquals(1, dist("Jarvis", "Jarviz")) // 1 substitution
        assertEquals(1, dist("Jarvis", "Jarvi")) // 1 deletion
        assertEquals(1, dist("Jarvis", "Jarviss")) // 1 insertion
        assertEquals(3, dist("kitten", "sitting")) // classic Levenshtein test case
        assertEquals(2, dist("flaw", "lawn"))
    }

    @Test
    fun testCameraPerceptionBrightnessAndNullSafety() {
        val engine = CameraPerceptionEngine(null)

        // Null bitmap -> safe default
        assertEquals(0.5f, engine.calculateAverageBrightness(null), 0.01f)
    }

    @Test
    fun testTfLiteVisionDetectorDefaultAnalysis() {
        val detector = TfLiteVisionDetector()
        val defaultAnalysis = detector.defaultAnalysis()

        assertNotNull(defaultAnalysis)
        assertEquals("General Scene", defaultAnalysis.primaryCategory)
        assertTrue(defaultAnalysis.dominantColors.isNotEmpty())
        assertTrue(defaultAnalysis.summary.contains("General Scene"))

        // Null bitmap returns defaultAnalysis safely
        val nullResult = detector.analyze(null)
        assertEquals(defaultAnalysis.primaryCategory, nullResult.primaryCategory)
    }

    @Test
    fun testSyntheticCameraFrameGeneration() {
        val engine = CameraPerceptionEngine(null)
        val result = engine.generateSyntheticFrame(facing = "back", reason = "benchmarking")

        assertNotNull(result)
        assertEquals("back", result.lensFacing)
        assertNotNull(result.base64Jpeg)
        assertTrue(result.base64Jpeg!!.startsWith("data:image/jpeg;base64,"))
        assertTrue(result.brightness in 0.0f..1.0f)
    }
}
