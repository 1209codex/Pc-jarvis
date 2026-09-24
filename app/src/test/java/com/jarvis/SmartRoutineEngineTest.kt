package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.RoutineSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.routine.RoutineAction
import com.jarvis.routine.RoutineTrigger
import com.jarvis.routine.SmartRoutine
import com.jarvis.routine.SmartRoutineEngine
import com.jarvis.routine.TriggerType
import com.jarvis.tools.RoutineManageTool
import com.jarvis.tools.ToolRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SmartRoutineEngineTest {

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var routineEngine: SmartRoutineEngine
    private lateinit var routineManageTool: RoutineManageTool
    private lateinit var routineSkill: RoutineSkill

    @Before
    fun setUp() {
        routineEngine = SmartRoutineEngine(toolExecutor = null, coroutineScope = testScope)
        routineEngine.resetToDefaults()
        routineManageTool = RoutineManageTool(routineEngine)
        routineSkill = RoutineSkill()
    }

    @Test
    fun testSmartRoutineRegistrationAndDefaults() {
        val all = routineEngine.getAllRoutines()
        assertEquals(4, all.size)

        val bedtime = routineEngine.getRoutine("bedtime")
        assertNotNull(bedtime)
        assertEquals("Bedtime Wind-Down", bedtime?.name)

        val lowBattery = routineEngine.getRoutine("routine_low_battery")
        assertNotNull(lowBattery)
        assertEquals("Low Battery Saver", lowBattery?.name)

        val workMode = routineEngine.getRoutine("work")
        assertNotNull(workMode)
        assertEquals("Work Arrival", workMode?.name)

        val homeArrival = routineEngine.getRoutine("home")
        assertNotNull(homeArrival)
        assertEquals("Home Arrival", homeArrival?.name)
    }

    @Test
    fun testWifiConnectedTriggerMatchesSsidAndFires() = runBlocking {
        val customRoutine = SmartRoutine(
            id = "custom_office_routine",
            name = "Office Desk Setup",
            description = "Custom test office routine",
            trigger = RoutineTrigger(TriggerType.WIFI_CONNECTED, parameter = "Office_5G"),
            actions = listOf(
                RoutineAction("TEST_ACTION", emptyMap(), "Sample action")
            ),
            isEnabled = true,
            cooldownMinutes = 10
        )
        routineEngine.registerRoutine(customRoutine)

        // Mismatched SSID -> should not trigger
        routineEngine.onWifiConnected("Public_Starbucks_WiFi")
        assertEquals(0L, customRoutine.lastTriggeredAt)

        // Matched SSID -> triggers and updates lastTriggeredAt
        routineEngine.onWifiConnected("Office_5G_Main")
        assertTrue(customRoutine.lastTriggeredAt > 0L)
    }

    @Test
    fun testBatteryDropsBelowThresholdTrigger() = runBlocking {
        val lowBattery = routineEngine.getRoutine("routine_low_battery")!!
        lowBattery.lastTriggeredAt = 0L

        // Level is 15% but device IS charging -> do not fire
        routineEngine.onBatteryLevelChanged(level = 15, isCharging = true)
        assertEquals(0L, lowBattery.lastTriggeredAt)

        // Level is 18% and NOT charging -> fire!
        routineEngine.onBatteryLevelChanged(level = 18, isCharging = false)
        assertTrue(lowBattery.lastTriggeredAt > 0L)
    }

    @Test
    fun testCooldownGuardPreventsRapidDuplicateFire() = runBlocking {
        val routine = routineEngine.getRoutine("routine_home_arrival")!!
        routine.lastTriggeredAt = 0L

        // First trigger succeeds
        val res1 = routineEngine.executeRoutine(routine, isManual = false)
        assertTrue(res1.executed)
        val firstTriggerTime = routine.lastTriggeredAt
        assertTrue(firstTriggerTime > 0L)

        // Immediate second trigger should be blocked by cooldown
        val res2 = routineEngine.executeRoutine(routine, isManual = false)
        assertFalse(res2.executed)
        assertTrue(res2.reason.contains("Cooldown active"))

        // Manual execution overrides cooldown
        val resManual = routineEngine.executeRoutine(routine, isManual = true)
        assertTrue(resManual.executed)
    }

    @Test
    fun testEnableDisableRoutine() = runBlocking {
        val routine = routineEngine.getRoutine("routine_work_mode")!!
        assertTrue(routine.isEnabled)

        routineEngine.enableRoutine("routine_work_mode", false)
        assertFalse(routine.isEnabled)

        // Trigger should not fire when disabled
        val res = routineEngine.executeRoutine(routine, isManual = false)
        assertFalse(res.executed)
        assertEquals("Routine is disabled", res.reason)

        // Re-enable
        routineEngine.enableRoutine("routine_work_mode", true)
        assertTrue(routine.isEnabled)
    }

    @Test
    fun testRoutineManageToolListAndRun() = runBlocking {
        // List
        val listResult = routineManageTool.execute(mapOf("action" to "list"))
        assertTrue(listResult.success)
        assertTrue(listResult.message.contains("Low Battery Saver"))
        assertTrue(listResult.message.contains("Bedtime Wind-Down"))

        // Run
        val runResult = routineManageTool.execute(mapOf("action" to "run", "name" to "bedtime"))
        assertTrue(runResult.success)
        assertTrue(runResult.message.contains("Triggered routine 'Bedtime Wind-Down'"))

        // Disable
        val disableResult = routineManageTool.execute(mapOf("action" to "disable", "name" to "routine_bedtime"))
        assertTrue(disableResult.success)
        assertFalse(routineEngine.getRoutine("routine_bedtime")!!.isEnabled)
    }

    @Test
    fun testRoutineSkillBilingualMatching() = runBlocking {
        val ctx1 = SkillContext("run bedtime routine", AgentWorkingMemory("run bedtime routine"))
        val ctx2 = SkillContext("bedtime routine chalu karo", AgentWorkingMemory("bedtime routine chalu karo"))
        val ctx3 = SkillContext("work routine run karo", AgentWorkingMemory("work routine run karo"))
        val ctx4 = SkillContext("kya automations hain", AgentWorkingMemory("kya automations hain"))
        val ctx5 = SkillContext("list routines", AgentWorkingMemory("list routines"))

        assertTrue(routineSkill.canHandle(ctx1.goal, ctx1))
        assertTrue(routineSkill.canHandle(ctx2.goal, ctx2))
        assertTrue(routineSkill.canHandle(ctx3.goal, ctx3))
        assertTrue(routineSkill.canHandle(ctx4.goal, ctx4))
        assertTrue(routineSkill.canHandle(ctx5.goal, ctx5))

        // Negative
        val neg = SkillContext("take a picture", AgentWorkingMemory("take a picture"))
        assertFalse(routineSkill.canHandle(neg.goal, neg))

        // Execution
        val r1 = routineSkill.execute(ctx1.goal, ctx1)
        assertTrue(r1.handled)
        assertEquals("ROUTINE_MANAGE", r1.proposedAction?.type)
        assertEquals("routine_bedtime", r1.proposedAction?.params?.get("routine_id"))

        val r4 = routineSkill.execute(ctx4.goal, ctx4)
        assertTrue(r4.handled)
        assertEquals("list", r4.proposedAction?.params?.get("action"))
    }

    @Test
    fun testIntentResolverFastPathForRoutines() {
        val r1 = IntentResolver.resolve("run bedtime routine")
        assertEquals(AssistantIntent.ROUTINE_RUN, r1?.intent)
        assertEquals("routine_bedtime", r1?.params?.get("routine_id"))

        val r2 = IntentResolver.resolve("start work mode")
        assertEquals(AssistantIntent.ROUTINE_RUN, r2?.intent)
        assertEquals("routine_work_mode", r2?.params?.get("routine_id"))

        val r3 = IntentResolver.resolve("list automations")
        assertEquals(AssistantIntent.ROUTINE_LIST, r3?.intent)

        val r4 = IntentResolver.resolve("kya automations hain")
        assertEquals(AssistantIntent.ROUTINE_LIST, r4?.intent)
    }

    @Test
    fun testToolRegistryAliases() {
        val registry = ToolRegistry()
        registry.register(routineManageTool)

        assertNotNull(registry.get("ROUTINE_MANAGE"))
        assertNotNull(registry.get("RUN_ROUTINE"))
        assertNotNull(registry.get("LIST_ROUTINES"))
        assertNotNull(registry.get("TRIGGER_ROUTINE"))
        assertNotNull(registry.get("ENABLE_ROUTINE"))
        assertNotNull(registry.get("DISABLE_ROUTINE"))
        assertNotNull(registry.get("ROUTINES"))
    }
}
