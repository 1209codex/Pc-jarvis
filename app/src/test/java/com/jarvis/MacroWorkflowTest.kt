package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.MacroSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.macro.MacroActionType
import com.jarvis.macro.MacroStep
import com.jarvis.macro.MacroWorkflowEngine
import com.jarvis.macro.UiMacro
import com.jarvis.tools.MacroWorkflowTool
import com.jarvis.tools.ToolRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MacroWorkflowTest {

    private lateinit var macroEngine: MacroWorkflowEngine
    private lateinit var macroTool: MacroWorkflowTool
    private lateinit var macroSkill: MacroSkill

    @Before
    fun setUp() {
        macroEngine = MacroWorkflowEngine(context = null, simulateForTesting = true)
        macroEngine.resetToDefaults()
        macroTool = MacroWorkflowTool(macroEngine)
        macroSkill = MacroSkill()
    }

    @Test
    fun testBuiltinMacroTemplatesInitialization() {
        val all = macroEngine.getAllMacros()
        assertEquals(6, all.size)

        val clearApps = macroEngine.getMacro("macro_clear_apps")
        assertNotNull(clearApps)
        assertEquals("Clear All Background Apps", clearApps?.name)
        assertEquals(4, clearApps?.steps?.size)

        val ytSearch = macroEngine.getMacro("macro_youtube_search")
        assertNotNull(ytSearch)
        assertEquals("YouTube Quick Search", ytSearch?.name)
        assertEquals("com.google.android.youtube", ytSearch?.targetPackage)

        val softwareUpdate = macroEngine.getMacro("macro_software_update")
        assertNotNull(softwareUpdate)
        assertEquals("Check Software Update", softwareUpdate?.name)
        assertEquals(5, softwareUpdate?.steps?.size)

        val quickHome = macroEngine.getMacro("macro_quick_home")
        assertNotNull(quickHome)
        assertEquals(3, quickHome?.steps?.size)

        val storage = macroEngine.getMacro("macro_device_storage")
        assertNotNull(storage)
        assertEquals("Open Storage & Device Care", storage?.name)

        val whatsapp = macroEngine.getMacro("macro_whatsapp_status")
        assertNotNull(whatsapp)
        assertEquals("Open WhatsApp Status / Updates", whatsapp?.name)
    }

    @Test
    fun testMacroExecutionBlockedWithoutAccessibility() = runBlocking {
        // In production mode (simulateForTesting = false), missing accessibility must fail closed
        val productionEngine = MacroWorkflowEngine(context = null, simulateForTesting = false)
        val macro = productionEngine.getMacro("macro_clear_apps")!!
        val result = productionEngine.executeMacro(macro)

        assertFalse("Macro must NOT report success when accessibility is disabled", result.success)
        assertTrue(result.failureReason?.contains("REQUIRES_ACCESSIBILITY") == true)
    }

    @Test
    fun testMacroExecutionSequencingAndLogging() = runBlocking {
        val macro = macroEngine.getMacro("macro_clear_apps")!!
        val initialRunCount = macro.runCount

        val result = macroEngine.executeMacro(macro)
        assertTrue(result.success)
        assertEquals(4, result.completedSteps)
        assertEquals(4, result.totalSteps)
        assertEquals(4, result.stepLogs.size)
        assertEquals(initialRunCount + 1, macro.runCount)
        assertTrue(macro.lastRunAt > 0L)
    }

    @Test
    fun testCustomMacroCreationAndDeletion() = runBlocking {
        val customMacro = UiMacro(
            id = "custom_flow",
            name = "Test Workflow",
            description = "Custom macro for testing",
            targetPackage = "com.test.app",
            steps = listOf(
                MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "home", description = "Home step"),
                MacroStep(2, MacroActionType.DELAY, payload = "100", description = "Small delay"),
                MacroStep(3, MacroActionType.GLOBAL_ACTION, target = "notifications", description = "Notifications step")
            )
        )

        macroEngine.registerMacro(customMacro)
        assertEquals(7, macroEngine.getAllMacros().size)

        val fetched = macroEngine.getMacro("custom_flow")
        assertNotNull(fetched)
        assertEquals("Test Workflow", fetched?.name)

        val execResult = macroEngine.executeMacro(fetched!!)
        assertTrue(execResult.success)
        assertEquals(3, execResult.completedSteps)

        val deleted = macroEngine.deleteMacro("custom_flow")
        assertTrue(deleted)
        assertNull(macroEngine.getMacro("custom_flow"))
    }

    @Test
    fun testMacroWorkflowToolExecution() = runBlocking {
        // List
        val listResult = macroTool.execute(mapOf("action" to "list"))
        assertTrue(listResult.success)
        assertTrue(listResult.message.contains("Clear All Background Apps"))
        assertTrue(listResult.message.contains("YouTube Quick Search"))

        // Info
        val infoResult = macroTool.execute(mapOf("action" to "info", "macro" to "macro_youtube_search"))
        assertTrue(infoResult.success)
        assertTrue(infoResult.message.contains("Steps (4)"))

        // Run
        val runResult = macroTool.execute(mapOf("action" to "run", "macro" to "macro_clear_apps"))
        assertTrue(runResult.success)
        assertTrue(runResult.message.contains("executed successfully"))

        // Delete
        val delResult = macroTool.execute(mapOf("action" to "delete", "macro" to "macro_quick_home"))
        assertTrue(delResult.success)
        assertNull(macroEngine.getMacro("macro_quick_home"))
    }

    @Test
    fun testMacroSkillBilingualMatching() = runBlocking {
        val ctx1 = SkillContext("run clear apps macro", AgentWorkingMemory("run clear apps macro"))
        val ctx2 = SkillContext("youtube search macro", AgentWorkingMemory("youtube search macro"))
        val ctx3 = SkillContext("saari apps clear karne ka macro", AgentWorkingMemory("saari apps clear karne ka macro"))
        val ctx4 = SkillContext("macros dikhao", AgentWorkingMemory("macros dikhao"))
        val ctx5 = SkillContext("list macros", AgentWorkingMemory("list macros"))

        assertTrue(macroSkill.canHandle(ctx1.goal, ctx1))
        assertTrue(macroSkill.canHandle(ctx2.goal, ctx2))
        assertTrue(macroSkill.canHandle(ctx3.goal, ctx3))
        assertTrue(macroSkill.canHandle(ctx4.goal, ctx4))
        assertTrue(macroSkill.canHandle(ctx5.goal, ctx5))

        // Negative match
        val neg = SkillContext("set volume to 50", AgentWorkingMemory("set volume to 50"))
        assertFalse(macroSkill.canHandle(neg.goal, neg))

        // Execution checks
        val r1 = macroSkill.execute(ctx1.goal, ctx1)
        assertTrue(r1.handled)
        assertEquals("MACRO_WORKFLOW", r1.proposedAction?.type)
        assertEquals("macro_clear_apps", r1.proposedAction?.params?.get("macro_id"))

        val r2 = macroSkill.execute(ctx2.goal, ctx2)
        assertTrue(r2.handled)
        assertEquals("macro_youtube_search", r2.proposedAction?.params?.get("macro_id"))

        val r4 = macroSkill.execute(ctx4.goal, ctx4)
        assertTrue(r4.handled)
        assertEquals("list", r4.proposedAction?.params?.get("action"))
    }

    @Test
    fun testIntentResolverFastPathForMacros() {
        val r1 = IntentResolver.resolve("run clear apps macro")
        assertEquals(AssistantIntent.MACRO_RUN, r1?.intent)
        assertEquals("macro_clear_apps", r1?.params?.get("macro_id"))

        val r2 = IntentResolver.resolve("youtube macro")
        assertEquals(AssistantIntent.MACRO_RUN, r2?.intent)
        assertEquals("macro_youtube_search", r2?.params?.get("macro_id"))

        val r3 = IntentResolver.resolve("list macros")
        assertEquals(AssistantIntent.MACRO_LIST, r3?.intent)

        val r4 = IntentResolver.resolve("macros dikhao")
        assertEquals(AssistantIntent.MACRO_LIST, r4?.intent)
    }

    @Test
    fun testToolRegistryAliases() {
        val registry = ToolRegistry()
        registry.register(macroTool)

        assertNotNull(registry.get("MACRO_WORKFLOW"))
        assertNotNull(registry.get("RUN_MACRO"))
        assertNotNull(registry.get("EXECUTE_WORKFLOW"))
        assertNotNull(registry.get("LIST_MACROS"))
        assertNotNull(registry.get("SHOW_MACROS"))
        assertNotNull(registry.get("MACRO"))
        assertNotNull(registry.get("WORKFLOW"))
    }
}
