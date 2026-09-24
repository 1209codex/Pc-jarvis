package com.jarvis

import com.jarvis.autonomous.AmbientContextEngine
import com.jarvis.autonomous.AutonomousDaemon
import com.jarvis.calendar.CalendarManager
import com.jarvis.execution.AgentVerifier
import com.jarvis.execution.VerificationEngine
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.PolicyEngine
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM regression tests for the autonomous automation wiring:
 * the proactive daemon must actually DISPATCH its rule actions through the
 * ToolExecutor, verify them honestly, and never claim an unverified outcome.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DaemonAutomationTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    /** Records every dispatched (tool, params) and returns a canned response. */
    private class RecordingTool(
        override val name: String,
        var response: ToolResult = ToolResult.Success("done")
    ) : Tool {
        val dispatched = mutableListOf<Pair<String, Map<String, String>>>()
        override val description: String = "records dispatches"
        override suspend fun execute(params: Map<String, String>): ToolResult {
            dispatched.add(name to params)
            return response
        }
    }

    /** Verifier that confirms success results honestly, fails the rest. */
    private class FakeVerifier : AgentVerifier {
        override fun verify(actionType: String, params: Map<String, String>, result: ToolResult): VerificationResult =
            if (result.success) VerificationResult.success("confirmed by fake verifier")
            else VerificationResult.failure("tool reported failure")
    }

    private lateinit var ambientEngine: AmbientContextEngine
    private lateinit var calendarManager: CalendarManager
    private lateinit var deviceSettingsTool: RecordingTool
    private lateinit var fileManagerTool: RecordingTool
    private lateinit var toolExecutor: ToolExecutor

    @Before
    fun setUp() {
        calendarManager = CalendarManager()
        ambientEngine = AmbientContextEngine(context = null, calendarManager = calendarManager)
        deviceSettingsTool = RecordingTool("DEVICE_SETTINGS")
        fileManagerTool = RecordingTool("FILE_MANAGER")
        val registry = ToolRegistry()
        registry.register(deviceSettingsTool)
        registry.register(fileManagerTool)
        toolExecutor = ToolExecutor(registry, PolicyEngine(autonomousFullAuto = true))
    }

    private fun buildDaemon(
        executor: ToolExecutor? = toolExecutor,
        verifier: AgentVerifier? = FakeVerifier(),
        busy: () -> Boolean = { false }
    ): AutonomousDaemon = AutonomousDaemon(
        ambientEngine = ambientEngine,
        calendarManager = calendarManager,
        fileManager = null,
        toolExecutor = executor,
        verificationEngine = verifier,
        busyProvider = busy,
        coroutineScope = testScope
    )

    @Test
    fun testMeetingPrepDispatchesRingerSilentAndVerifies() = runBlocking {
        val daemon = buildDaemon()
        calendarManager.addEvent("Quarterly Review", startTimeMillis = System.currentTimeMillis() + (10 * 60_000L), durationMinutes = 45)

        val record = daemon.evaluateAndExecuteTick()

        assertNotNull(record)
        assertEquals("UPCOMING_MEETING", record?.triggerType)
        assertTrue(record?.executed == true)
        assertEquals("DEVICE_SETTINGS", record?.proposedTool)
        assertEquals(1, deviceSettingsTool.dispatched.size)
        val params = deviceSettingsTool.dispatched.single().second
        assertEquals("set_ringer", params["action"])
        assertEquals("silent", params["ringer_mode"])
        assertEquals(record?.params, params)
        assertTrue(record?.verified == true)
        assertEquals("VERIFIED", record?.verificationStatus)
        // Meeting prep must not touch storage
        assertTrue(fileManagerTool.dispatched.isEmpty())
    }

    @Test
    fun testLowBatteryDispatchesMute() = runBlocking {
        val daemon = buildDaemon()
        ambientEngine.setSimulatedBattery(percent = 12, charging = false)

        val record = daemon.evaluateAndExecuteTick()

        assertNotNull(record)
        assertEquals("LOW_BATTERY", record?.triggerType)
        assertTrue(record?.executed == true)
        assertEquals(1, deviceSettingsTool.dispatched.size)
        assertEquals("mute", deviceSettingsTool.dispatched.single().second["action"])
        assertTrue(record?.verified == true)
    }

    @Test
    fun testStorageRuleOnlySuggestsAndNeverDeletes() {
        val spec = AutonomousDaemon.storageActionSpec(apkCount = 3)
        assertEquals("FILE_MANAGER", spec.tool)
        assertEquals("cleanup_suggestions", spec.params["action"])
        assertEquals("STORAGE_CLUTTER", spec.triggerType)
        // Never an autonomous destructive action
        assertFalse(spec.params.values.any { it.contains("delete", true) || it.contains("remove", true) })
    }

    @Test
    fun testBusyGateSkipsDispatchAndRecordsHonestly() = runBlocking {
        val daemon = buildDaemon(busy = { true })
        calendarManager.addEvent("Board Meeting", startTimeMillis = System.currentTimeMillis() + (5 * 60_000L), durationMinutes = 30)

        val record = daemon.evaluateAndExecuteTick()

        assertNotNull(record)
        assertEquals("UPCOMING_MEETING", record?.triggerType)
        assertFalse(record?.executed == true)
        assertTrue(record?.resultMessage?.contains("user task in progress") == true)
        assertTrue(deviceSettingsTool.dispatched.isEmpty())
    }

    @Test
    fun testNoExecutorRecordsPlannedOnlyAndStaysUnknown() = runBlocking {
        val daemon = buildDaemon(executor = null)
        ambientEngine.setSimulatedBattery(percent = 10, charging = false)

        val record = daemon.evaluateAndExecuteTick()

        assertNotNull(record)
        assertEquals("LOW_BATTERY", record?.triggerType)
        assertFalse(record?.executed == true)
        assertFalse(record?.verified == true)
        assertEquals("UNKNOWN", record?.verificationStatus)
        assertTrue(deviceSettingsTool.dispatched.isEmpty())
    }

    @Test
    fun testFailedDispatchNeverClaimsExecutedOrVerified() = runBlocking {
        deviceSettingsTool.response = ToolResult.Failed("AudioManager not available")
        val daemon = buildDaemon()
        calendarManager.addEvent("Sync", startTimeMillis = System.currentTimeMillis() + (8 * 60_000L), durationMinutes = 30)

        val record = daemon.evaluateAndExecuteTick()

        assertNotNull(record)
        assertFalse(record?.executed == true)
        assertFalse(record?.verified == true)
        assertEquals("FAILED", record?.verificationStatus)
    }

    @Test
    fun testRealVerifierStaysUnknownWithoutPlatformAudio() = runBlocking {
        // On the JVM the real VerificationEngine has no AudioManager; it must stay
        // UNKNOWN rather than inventing a VERIFIED outcome.
        val realVerifier = VerificationEngine(context = null)
        val daemon = buildDaemon(verifier = realVerifier)
        ambientEngine.setSimulatedBattery(percent = 9, charging = false)

        val record = daemon.evaluateAndExecuteTick()

        assertNotNull(record)
        assertTrue(record?.executed == true) // fake tool succeeded
        assertFalse(record?.verified == true)
        assertEquals("UNKNOWN", record?.verificationStatus)
    }
}