package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.MacroSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.macro.MacroActionType
import com.jarvis.macro.MacroStep
import com.jarvis.macro.MacroWorkflowEngine
import com.jarvis.macro.UiMacro
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class CustomMacroRoutineTest {

    private lateinit var macroEngine: MacroWorkflowEngine
    private lateinit var macroSkill: MacroSkill

    @Before
    fun setUp() {
        macroEngine = MacroWorkflowEngine(context = null)
        macroSkill = MacroSkill(macroEngine)
    }

    @Test
    fun testCustomMacroCreationAndTriggerLookup() {
        val customRoutine = UiMacro(
            id = "routine_gaming_mode",
            name = "Extreme Gaming Mode",
            description = "Turns on DND and launches game",
            steps = listOf(
                MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "recents", description = "Recents"),
                MacroStep(2, MacroActionType.CLICK_TEXT, target = "Close all", isOptional = true, description = "Clear apps"),
                MacroStep(3, MacroActionType.LAUNCH_APP, payload = "com.pubg.imobile", description = "Launch Game")
            ),
            triggers = listOf("gaming mode", "khelna hai", "start game session")
        )

        macroEngine.registerMacro(customRoutine)

        // Lookup by exact ID
        val byId = macroEngine.getMacro("routine_gaming_mode")
        assertNotNull(byId)
        assertEquals("Extreme Gaming Mode", byId?.name)
        assertEquals(3, byId?.steps?.size)

        // Lookup by custom voice trigger phrase
        val byTrigger = macroEngine.getMacro("khelna hai")
        assertNotNull(byTrigger)
        assertEquals("routine_gaming_mode", byTrigger?.id)

        // Lookup by name substring
        val byName = macroEngine.getMacro("gaming mode")
        assertNotNull(byName)
        assertEquals("routine_gaming_mode", byName?.id)
    }

    @Test
    fun testMacroSkillHandlesCustomRoutineVoiceTrigger() = runBlocking {
        val routine = UiMacro(
            id = "routine_study",
            name = "Study Protocol",
            description = "Study preparation",
            steps = listOf(
                MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "home")
            ),
            triggers = listOf("padhai shuru", "study time")
        )
        macroEngine.registerMacro(routine)

        val context = SkillContext("padhai shuru", AgentWorkingMemory("padhai shuru"))
        assertTrue(macroSkill.canHandle("padhai shuru", context))

        val result = macroSkill.execute("padhai shuru", context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        assertEquals("MACRO_WORKFLOW", result.proposedAction?.type)
        assertEquals("routine_study", result.proposedAction?.params?.get("macro_id"))
        assertEquals("run", result.proposedAction?.params?.get("action"))
    }

    @Test
    fun testMacroDeletionRemovesFromEngine() {
        val tempRoutine = UiMacro(
            id = "temp_routine",
            name = "Temporary Routine",
            description = "To be deleted",
            steps = emptyList(),
            triggers = listOf("temp trigger")
        )
        macroEngine.registerMacro(tempRoutine)
        assertNotNull(macroEngine.getMacro("temp_routine"))

        val deleted = macroEngine.deleteMacro("temp_routine")
        assertTrue(deleted)
        assertNull(macroEngine.getMacro("temp_routine"))
    }
}
