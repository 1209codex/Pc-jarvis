package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.AutonomousSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.autonomous.AmbientContextEngine
import com.jarvis.autonomous.AmbientState
import com.jarvis.autonomous.AutonomousDaemon
import com.jarvis.autonomous.SelfHealingSupervisor
import com.jarvis.calendar.CalendarManager
import com.jarvis.tools.AutonomousModeTool
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

private class AutoSentinelStubTool(
    override val name: String,
    override val description: String = ""
) : Tool {
    override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult(true, "ok")
}

@OptIn(ExperimentalCoroutinesApi::class)
class AutonomousEngineTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var ambientEngine: AmbientContextEngine
    private lateinit var calendarManager: CalendarManager
    private lateinit var autonomousDaemon: AutonomousDaemon
    private lateinit var selfHealingSupervisor: SelfHealingSupervisor
    private lateinit var autonomousTool: AutonomousModeTool
    private lateinit var autonomousSkill: AutonomousSkill

    @Before
    fun setUp() {
        calendarManager = CalendarManager()
        ambientEngine = AmbientContextEngine(context = null, calendarManager = calendarManager)
        autonomousDaemon = AutonomousDaemon(
            ambientEngine = ambientEngine,
            calendarManager = calendarManager,
            fileManager = null,
            toolExecutor = null,
            coroutineScope = testScope
        )
        selfHealingSupervisor = SelfHealingSupervisor(toolExecutor = null, scope = testScope)
        autonomousTool = AutonomousModeTool(ambientEngine, autonomousDaemon)
        autonomousSkill = AutonomousSkill()
    }

    @Test
    fun testToolRegistryAutonomousAliases() {
        val registry = ToolRegistry()
        registry.register(AutoSentinelStubTool("AUTONOMOUS_CONTROL"))

        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("AUTONOMOUS"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("AUTOPILOT"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("AUTO_PILOT"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("DRIVING_MODE"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("MEETING_MODE"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("FOCUS_MODE"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("SLEEP_MODE"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("NIGHT_MODE"))
        assertEquals("AUTONOMOUS_CONTROL", registry.resolveCanonicalToolName("WORKOUT_MODE"))
    }

    @Test
    fun testAmbientContextInferenceAndOverrides() {
        // Default Standby
        val defaultSnapshot = ambientEngine.inferCurrentContext()
        assertEquals(AmbientState.STANDBY, defaultSnapshot.state)
        assertFalse(defaultSnapshot.isManualOverride)

        // Manual Driving override
        ambientEngine.setManualState(AmbientState.DRIVING)
        val drivingSnapshot = ambientEngine.inferCurrentContext()
        assertEquals(AmbientState.DRIVING, drivingSnapshot.state)
        assertTrue(drivingSnapshot.isManualOverride)

        // Manual Meeting override
        ambientEngine.setManualState(AmbientState.MEETING)
        val meetingSnapshot = ambientEngine.inferCurrentContext()
        assertEquals(AmbientState.MEETING, meetingSnapshot.state)

        // Reset to automatic
        ambientEngine.setManualState(null)
        val autoSnapshot = ambientEngine.inferCurrentContext()
        assertEquals(AmbientState.STANDBY, autoSnapshot.state)
        assertFalse(autoSnapshot.isManualOverride)

        // Active calendar event triggers MEETING
        val now = System.currentTimeMillis()
        calendarManager.addEvent("Executive Sync", startTimeMillis = now - 60_000L, durationMinutes = 30)
        val calMeetingSnapshot = ambientEngine.inferCurrentContext()
        assertEquals(AmbientState.MEETING, calMeetingSnapshot.state)
        assertEquals("Executive Sync", calMeetingSnapshot.activeEventTitle)
    }

    @Test
    fun testAutonomousDaemonProactiveRules() = runBlocking {
        // 1. Upcoming meeting (< 15 mins) rule
        val now = System.currentTimeMillis()
        calendarManager.addEvent("Quarterly Review", startTimeMillis = now + (10 * 60_000L), durationMinutes = 45)

        val action = autonomousDaemon.evaluateAndExecuteTick()
        assertNotNull(action)
        assertEquals("UPCOMING_MEETING", action?.triggerType)
        assertTrue(action?.description?.contains("Quarterly Review") == true)

        // Verify logged in ledger
        val recent = autonomousDaemon.getRecentActions(limit = 5)
        assertEquals(1, recent.size)
        assertEquals("UPCOMING_MEETING", recent.first().triggerType)

        // 2. Low Battery Rule (< 15% and not charging)
        ambientEngine.setSimulatedBattery(percent = 12, charging = false)
        val batAction = autonomousDaemon.evaluateAndExecuteTick()
        assertNotNull(batAction)
        assertEquals("LOW_BATTERY", batAction?.triggerType)
        assertTrue(batAction?.description?.contains("12%") == true)

        // 3. Toggle autopilot
        autonomousDaemon.setAutopilotEnabled(false)
        assertFalse(autonomousDaemon.isAutopilotEnabled())
        assertFalse(autonomousDaemon.state.value.isEnabled)

        autonomousDaemon.setAutopilotEnabled(true)
        assertTrue(autonomousDaemon.isAutopilotEnabled())
        assertTrue(autonomousDaemon.state.value.isEnabled)
    }

    @Test
    fun testSelfHealingSupervisorDeferredTasks() {
        selfHealingSupervisor.clear()
        selfHealingSupervisor.queueDeferredTask("Search Quantum Computing", "Network offline")
        selfHealingSupervisor.queueDeferredTask("Sync Daily Briefing", "Airplane mode")

        val queued = selfHealingSupervisor.getDeferredTasks()
        assertEquals(2, queued.size)
        assertEquals("Search Quantum Computing", queued[0].goal)

        // Restoring network flushes queue
        selfHealingSupervisor.setNetworkStatus(true)
        selfHealingSupervisor.onNetworkRestored("WiFi Connected")
        assertTrue(selfHealingSupervisor.getDeferredTasks().isEmpty())
    }

    @Test
    fun testSelfHealingSupervisorReplaysDeferredTasksToHandler() {
        // Network restore must not just drop tasks: each queued goal reaches the
        // replay handler (the runtime re-runs it through the agent loop).
        val replayed = mutableListOf<String>()
        val supervisor = SelfHealingSupervisor(
            toolExecutor = null,
            scope = testScope,
            onDeferredTaskDue = { task -> replayed.add(task.goal) }
        )
        supervisor.queueDeferredTask("Search Quantum Computing", "Network offline")
        supervisor.queueDeferredTask("Sync Daily Briefing", "Airplane mode")

        supervisor.onNetworkRestored("WiFi Connected")

        assertEquals(listOf("Search Quantum Computing", "Sync Daily Briefing"), replayed)
        assertTrue(supervisor.getDeferredTasks().isEmpty())
    }

    @Test
    fun testSelfHealingSupervisorReplayHandlerIsolation() {
        // One failing replay must not block the remaining deferred tasks.
        val replayed = mutableListOf<String>()
        val supervisor = SelfHealingSupervisor(
            toolExecutor = null,
            scope = testScope,
            onDeferredTaskDue = { task ->
                if (task.goal == "Boom") throw RuntimeException("handler exploded")
                replayed.add(task.goal)
            }
        )
        supervisor.queueDeferredTask("Boom", "Network offline")
        supervisor.queueDeferredTask("Still Replayed", "Network offline")

        supervisor.onNetworkRestored("WiFi Connected")

        assertEquals(listOf("Still Replayed"), replayed)
        assertTrue(supervisor.getDeferredTasks().isEmpty())
    }

    @Test
    fun testAutonomousModeToolActions() = runBlocking {
        // Enable
        val enableRes = autonomousTool.execute(mapOf("action" to "enable_autopilot"))
        assertTrue(enableRes.success)
        assertTrue(enableRes.message.contains("Autopilot engaged"))

        // Set mode: Driving
        val drivingRes = autonomousTool.execute(mapOf("action" to "set_ambient_mode", "mode" to "driving"))
        assertTrue(drivingRes.success)
        assertTrue(drivingRes.message.contains("Driving"))

        // Status
        val statusRes = autonomousTool.execute(mapOf("action" to "status"))
        assertTrue(statusRes.success)
        assertTrue(statusRes.message.contains("Autonomous Core Status"))

        // Reset mode to auto
        val resetRes = autonomousTool.execute(mapOf("action" to "set_ambient_mode", "mode" to "auto"))
        assertTrue(resetRes.success)

        // Disable
        val disableRes = autonomousTool.execute(mapOf("action" to "disable_autopilot"))
        assertTrue(disableRes.success)
        assertTrue(disableRes.message.contains("disengaged"))
    }

    @Test
    fun testAutonomousSkillBilingualMatching() = runBlocking {
        val context = SkillContext(goal = "", workingMemory = AgentWorkingMemory(goal = ""))

        // English Enable
        assertTrue(autonomousSkill.canHandle("enable autonomous mode", context))
        val enableEng = autonomousSkill.execute("enable autonomous mode", context)
        assertEquals("AUTONOMOUS_CONTROL", enableEng.proposedAction?.type)
        assertEquals("enable_autopilot", enableEng.proposedAction?.params?.get("action"))

        // Take over
        assertTrue(autonomousSkill.canHandle("jarvis take over", context))
        val takeOver = autonomousSkill.execute("jarvis take over", context)
        assertEquals("AUTONOMOUS_CONTROL", takeOver.proposedAction?.type)
        assertEquals("enable_autopilot", takeOver.proposedAction?.params?.get("action"))

        // Driving mode
        assertTrue(autonomousSkill.canHandle("activate driving mode", context))
        val driveRes = autonomousSkill.execute("activate driving mode", context)
        assertEquals("AUTONOMOUS_CONTROL", driveRes.proposedAction?.type)
        assertEquals("set_ambient_mode", driveRes.proposedAction?.params?.get("action"))
        assertEquals("driving", driveRes.proposedAction?.params?.get("mode"))

        // Meeting mode in Hindi
        assertTrue(autonomousSkill.canHandle("meeting mode chalu karo", context))
        val meetHin = autonomousSkill.execute("meeting mode chalu karo", context)
        assertEquals("AUTONOMOUS_CONTROL", meetHin.proposedAction?.type)
        assertEquals("set_ambient_mode", meetHin.proposedAction?.params?.get("action"))
        assertEquals("meeting", meetHin.proposedAction?.params?.get("mode"))

        // Disable in Hindi
        assertTrue(autonomousSkill.canHandle("autopilot band karo", context))
        val disableHin = autonomousSkill.execute("autopilot band karo", context)
        assertEquals("AUTONOMOUS_CONTROL", disableHin.proposedAction?.type)
        assertEquals("disable_autopilot", disableHin.proposedAction?.params?.get("action"))
    }

    @Test
    fun testIntentResolverFastPathForAutopilot() {
        // Enable Autopilot
        val enableIntent = IntentResolver.resolve("enable autopilot")
        assertNotNull(enableIntent)
        assertEquals(AssistantIntent.AUTONOMOUS_CONTROL, enableIntent?.intent)
        assertEquals("AUTONOMOUS_CONTROL", enableIntent?.directPlan?.actions?.firstOrNull()?.type)
        assertEquals("enable_autopilot", enableIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))

        // Disable Autopilot
        val disableIntent = IntentResolver.resolve("disable autopilot")
        assertNotNull(disableIntent)
        assertEquals(AssistantIntent.AUTONOMOUS_CONTROL, disableIntent?.intent)
        assertEquals("disable_autopilot", disableIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))

        // Driving Mode
        val driveIntent = IntentResolver.resolve("driving mode")
        assertNotNull(driveIntent)
        assertEquals(AssistantIntent.AUTONOMOUS_CONTROL, driveIntent?.intent)
        assertEquals("driving", driveIntent?.directPlan?.actions?.firstOrNull()?.params?.get("mode"))

        // Meeting Mode
        val meetIntent = IntentResolver.resolve("meeting mode")
        assertNotNull(meetIntent)
        assertEquals(AssistantIntent.AUTONOMOUS_CONTROL, meetIntent?.intent)
        assertEquals("meeting", meetIntent?.directPlan?.actions?.firstOrNull()?.params?.get("mode"))

        // Autopilot Status
        val statusIntent = IntentResolver.resolve("autopilot status")
        assertNotNull(statusIntent)
        assertEquals(AssistantIntent.AUTONOMOUS_CONTROL, statusIntent?.intent)
        assertEquals("status", statusIntent?.directPlan?.actions?.firstOrNull()?.params?.get("action"))
    }
}
