package com.jarvis.agent.skills

import com.jarvis.agent.AgentWorkingMemory
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAutopilotSkillTest {

    private val skill = AppAutopilotSkill()
    private val context = SkillContext(
        goal = "click submit button",
        workingMemory = AgentWorkingMemory(goal = "click submit button")
    )

    @Test
    fun testCanHandle_clickTriggers() = runBlocking {
        assertTrue(skill.canHandle("click on submit", context))
        assertTrue(skill.canHandle("tap button continue", context))
        assertTrue(skill.canHandle("submit button dabao", context))
        assertTrue(skill.canHandle("click karo next", context))
    }

    @Test
    fun testCanHandle_longPressAndDoubleTap() = runBlocking {
        assertTrue(skill.canHandle("long press on the message", context))
        assertTrue(skill.canHandle("double tap on photo", context))
        assertTrue(skill.canHandle("der tak dabao icon", context))
    }

    @Test
    fun testCanHandle_switchNeighbor() = runBlocking {
        assertTrue(skill.canHandle("toggle switch next to dark mode", context))
        assertTrue(skill.canHandle("turn on switch for bluetooth", context))
        assertTrue(skill.canHandle("switch dabao wifi", context))
    }

    @Test
    fun testExecute_clickAction() = runBlocking {
        val result = skill.execute("click on submit button", context)
        assertTrue(result.handled)
        assertEquals("APP_AUTOPILOT", result.proposedAction!!.type)
        assertEquals("click", result.proposedAction!!.params["action"])
        assertTrue(result.proposedAction!!.params["target"]?.contains("submit") == true)
    }

    @Test
    fun testExecute_longPressAction() = runBlocking {
        val result = skill.execute("long press on profile image", context)
        assertTrue(result.handled)
        assertEquals("APP_AUTOPILOT", result.proposedAction!!.type)
        assertEquals("long_press", result.proposedAction!!.params["action"])
        assertTrue(result.proposedAction!!.params["target"]?.contains("profile image") == true)
    }

    @Test
    fun testExecute_toggleNeighborAction() = runBlocking {
        val result = skill.execute("toggle switch next to Dark theme", context)
        assertTrue(result.handled)
        assertEquals("APP_AUTOPILOT", result.proposedAction!!.type)
        assertEquals("toggle_neighbor", result.proposedAction!!.params["action"])
        assertEquals("dark theme", result.proposedAction!!.params["target"]?.lowercase())
    }

    @Test
    fun testExecute_scrollAction() = runBlocking {
        val result = skill.execute("scroll down in app", context)
        assertTrue(result.handled)
        assertEquals("APP_AUTOPILOT", result.proposedAction!!.type)
        assertEquals("scroll", result.proposedAction!!.params["action"])
        assertEquals("down", result.proposedAction!!.params["direction"])
    }

    @Test
    fun testExecute_dismissPopupAction() = runBlocking {
        val result = skill.execute("dismiss popup", context)
        assertTrue(result.handled)
        assertEquals("APP_AUTOPILOT", result.proposedAction!!.type)
        assertEquals("dismiss_popup", result.proposedAction!!.params["action"])
    }
}
