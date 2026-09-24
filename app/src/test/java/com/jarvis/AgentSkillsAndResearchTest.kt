package com.jarvis

import com.jarvis.agent.AgentAction
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.*
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentSkillsAndResearchTest {

    @Test
    fun testMediaSkillPersonalization() = runBlocking {
        val skill = MediaSkill()
        val context = SkillContext(
            goal = "mere liye koi badiya gana chalao",
            workingMemory = AgentWorkingMemory("mere liye koi badiya gana chalao"),
            userPreferences = mapOf("favorite_artist" to "Arijit Singh")
        )

        assertTrue(skill.canHandle(context.goal, context))
        val result = skill.execute(context.goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        assertEquals("YOUTUBE_PLAY", result.proposedAction?.type)
        assertTrue(result.proposedAction?.params?.get("query")?.contains("Arijit Singh") == true)
    }

    @Test
    fun testMediaSkillComedy() = runBlocking {
        val skill = MediaSkill()
        val context = SkillContext(
            goal = "mere liye ek comedy video chalao",
            workingMemory = AgentWorkingMemory("mere liye ek comedy video chalao"),
            userPreferences = mapOf("comedy_preference" to "standup comedy")
        )

        assertTrue(skill.canHandle(context.goal, context))
        val result = skill.execute(context.goal, context)
        assertTrue(result.handled)
        assertEquals("YOUTUBE_PLAY", result.proposedAction?.type)
        assertTrue(result.proposedAction?.params?.get("query")?.contains("standup comedy") == true)
    }

    @Test
    fun testSkillRegistryRouting() = runBlocking {
        val registry = SkillRegistry()
        registry.register(MediaSkill())
        registry.register(AppControlSkill())
        registry.register(CommunicationSkill())

        val mediaContext = SkillContext("play music", AgentWorkingMemory("play music"))
        val appControlContext = SkillContext("open chrome", AgentWorkingMemory("open chrome"))
        val commContext = SkillContext("Rahul ko whatsapp msg bhejo", AgentWorkingMemory("Rahul ko whatsapp msg bhejo"))

        val mediaSkill = registry.findSkill("play music", mediaContext)
        val appSkill = registry.findSkill("open chrome", appControlContext)
        val commSkill = registry.findSkill("Rahul ko whatsapp msg bhejo", commContext)

        assertTrue(mediaSkill is MediaSkill)
        assertTrue(appSkill is AppControlSkill)
        assertTrue(commSkill is CommunicationSkill)
    }

    @Test
    fun testDynamicCommunicationSkillExtraction() = runBlocking {
        val skill = CommunicationSkill()
        val context = SkillContext("dollar ko WhatsApp per hello send karo", AgentWorkingMemory("dollar ko WhatsApp per hello send karo"))

        assertTrue(skill.canHandle(context.goal, context))
        val result = skill.execute(context.goal, context)
        assertTrue(result.handled)
        assertEquals("WHATSAPP_SEND", result.proposedAction?.type)
        assertEquals("Dollar", result.proposedAction?.params?.get("recipient"))
        assertEquals("Hello!", result.proposedAction?.params?.get("message"))
    }

    @Test
    fun testIncompleteSendRequestMustAskInsteadOfOpeningOrInventingMessage() = runBlocking {
        val skill = CommunicationSkill()
        val result = skill.execute("send message to Rahul", SkillContext("send message to Rahul", AgentWorkingMemory("send message to Rahul")))

        assertTrue(result.handled)
        assertNull("An incomplete send request must not become an executable action", result.proposedAction)
    }

    @Test
    fun testAppControlSkillYieldsOnCompoundCommands() = runBlocking {
        val skill = AppControlSkill()
        val context = SkillContext("Open instagram and check unread massages", AgentWorkingMemory("Open instagram and check unread massages"))

        // Should NOT handle compound commands like "open X and check Y"
        assertFalse("AppControlSkill must yield on compound command", skill.canHandle(context.goal, context))

        val singleContext = SkillContext("open chrome", AgentWorkingMemory("open chrome"))
        assertTrue("AppControlSkill must handle simple open command", skill.canHandle(singleContext.goal, singleContext))
    }
}
