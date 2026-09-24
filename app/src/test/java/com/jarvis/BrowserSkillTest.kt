package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.BrowserSkill
import com.jarvis.agent.skills.SkillContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class BrowserSkillTest {

    private lateinit var skill: BrowserSkill

    @Before
    fun setUp() {
        skill = BrowserSkill()
    }

    @Test
    fun testCanHandleEnglishWebTriggers() = runBlocking {
        val dummyContext = SkillContext("goal", AgentWorkingMemory("goal"))
        assertTrue(skill.canHandle("search web for quantum computing", dummyContext))
        assertTrue(skill.canHandle("google this topic", dummyContext))
        assertTrue(skill.canHandle("browse to android documentation", dummyContext))
        assertTrue(skill.canHandle("open website wikipedia.org", dummyContext))
        assertTrue(skill.canHandle("https://github.com/torvalds", dummyContext))
    }

    @Test
    fun testCanHandleHinglishWebTriggers() = runBlocking {
        val dummyContext = SkillContext("goal", AgentWorkingMemory("goal"))
        assertTrue(skill.canHandle("internet pe search karo weather today", dummyContext))
        assertTrue(skill.canHandle("online search karo best laptop", dummyContext))
        assertTrue(skill.canHandle("google pe dhoondo prime minister of india", dummyContext))
        assertTrue(skill.canHandle("website kholo google.com", dummyContext))
    }

    @Test
    fun testExecutionWithUrlDirectNavigation() = runBlocking {
        val goal = "please visit https://github.com"
        val context = SkillContext(goal, AgentWorkingMemory(goal))

        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)
        assertEquals("SEARCH_WEB", result.proposedAction?.type)
        assertEquals("https://github.com", result.proposedAction?.params?.get("query"))
        assertEquals("true", result.proposedAction?.params?.get("openBrowser"))
    }

    @Test
    fun testExecutionWithCleanQueryExtraction() = runBlocking {
        val goal = "search web for latest space exploration news"
        val context = SkillContext(goal, AgentWorkingMemory(goal))

        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        val query = result.proposedAction?.params?.get("query").orEmpty()
        assertEquals("latest space exploration news", query)
        assertEquals("false", result.proposedAction?.params?.get("openBrowser"))
    }

    @Test
    fun testExecutionWithBrowserFlag() = runBlocking {
        val goal = "chrome me kholo android architecture guidelines"
        val context = SkillContext(goal, AgentWorkingMemory(goal))

        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertEquals("true", result.proposedAction?.params?.get("openBrowser"))
    }
}
