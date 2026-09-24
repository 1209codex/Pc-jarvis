package com.jarvis

import com.jarvis.agent.skills.AppControlSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

private class AutoStubTool(
    override val name: String,
    override val description: String = ""
) : Tool {
    override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult(true, "ok")
}

class DeviceAutomationTest {

    @Test
    fun testToolRegistryResolvesUiAutomationAliases() {
        val registry = ToolRegistry()
        registry.register(AutoStubTool("UI_CLICK"))
        registry.register(AutoStubTool("UI_SCROLL"))
        registry.register(AutoStubTool("UI_TYPE"))
        registry.register(AutoStubTool("UI_INSPECT"))
        registry.register(AutoStubTool("UI_GLOBAL"))

        assertEquals("UI_CLICK", registry.resolveCanonicalToolName("CLICK"))
        assertEquals("UI_CLICK", registry.resolveCanonicalToolName("TAP"))
        assertEquals("UI_SCROLL", registry.resolveCanonicalToolName("SCROLL"))
        assertEquals("UI_SCROLL", registry.resolveCanonicalToolName("SWIPE"))
        assertEquals("UI_SCROLL", registry.resolveCanonicalToolName("SCROLL_DOWN"))
        assertEquals("UI_SCROLL", registry.resolveCanonicalToolName("SCROLL_UP"))
        assertEquals("UI_TYPE", registry.resolveCanonicalToolName("TYPE"))
        assertEquals("UI_TYPE", registry.resolveCanonicalToolName("INPUT"))
        assertEquals("UI_INSPECT", registry.resolveCanonicalToolName("INSPECT"))
        assertEquals("UI_GLOBAL", registry.resolveCanonicalToolName("GLOBAL_ACTION"))
        assertEquals("UI_GLOBAL", registry.resolveCanonicalToolName("BACK"))
        assertEquals("UI_GLOBAL", registry.resolveCanonicalToolName("HOME"))
        assertEquals("UI_GLOBAL", registry.resolveCanonicalToolName("RECENTS"))
        assertEquals("UI_GLOBAL", registry.resolveCanonicalToolName("SCREENSHOT"))
    }

    @Test
    fun testAppControlSkillHandlesScrollAndNavigationCommands() = runBlocking {
        val skill = AppControlSkill()
        val context = SkillContext(goal = "", workingMemory = com.jarvis.agent.AgentWorkingMemory(goal = ""))

        // 1. Scroll down
        assertTrue(skill.canHandle("scroll down", context))
        val scrollDownResult = skill.execute("scroll down", context)
        assertEquals("UI_SCROLL", scrollDownResult.proposedAction?.type)
        assertEquals("down", scrollDownResult.proposedAction?.params?.get("direction"))

        // 2. Scroll up in Hindi
        assertTrue(skill.canHandle("uper scroll karo", context))
        val scrollUpResult = skill.execute("uper scroll karo", context)
        assertEquals("UI_SCROLL", scrollUpResult.proposedAction?.type)
        assertEquals("up", scrollUpResult.proposedAction?.params?.get("direction"))

        // 3. Screenshot
        assertTrue(skill.canHandle("screenshot lo", context))
        val screenshotResult = skill.execute("screenshot lo", context)
        assertEquals("UI_GLOBAL", screenshotResult.proposedAction?.type)
        assertEquals("screenshot", screenshotResult.proposedAction?.params?.get("action"))

        // 4. Back navigation
        assertTrue(skill.canHandle("back jao", context))
        val backResult = skill.execute("back jao", context)
        assertEquals("UI_GLOBAL", backResult.proposedAction?.type)
        assertEquals("back", backResult.proposedAction?.params?.get("action"))

        // 5. Home navigation
        assertTrue(skill.canHandle("home screen", context))
        val homeResult = skill.execute("home screen", context)
        assertEquals("UI_GLOBAL", homeResult.proposedAction?.type)
        assertEquals("home", homeResult.proposedAction?.params?.get("action"))

        // 6. Recent apps
        assertTrue(skill.canHandle("recent apps dikhao", context))
        val recentsResult = skill.execute("recent apps dikhao", context)
        assertEquals("UI_GLOBAL", recentsResult.proposedAction?.type)
        assertEquals("recents", recentsResult.proposedAction?.params?.get("action"))
    }
}
