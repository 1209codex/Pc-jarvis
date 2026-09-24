package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.ResearchSkill
import com.jarvis.agent.skills.SkillContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ResearchAndReasoningSkillTest {

    @Test
    fun testResearchSkillTypoAndPrefixMatching() = runBlocking {
        val skill = ResearchSkill()
        val testGoal = "sesarch for android architecture"
        val context = SkillContext(
            goal = testGoal,
            workingMemory = AgentWorkingMemory(goal = testGoal)
        )

        // 1. Check intent recognition on typos & variants
        assertTrue("Must handle 'reseach'", skill.canHandle("reseach quantum computing", context))
        assertTrue("Must handle 'sesarch'", skill.canHandle("sesarch latest tech news", context))
        assertTrue("Must handle 'search for'", skill.canHandle("search for electric cars", context))
        assertTrue("Must handle 'sesarch for'", skill.canHandle("sesarch for space exploration", context))
        assertTrue("Must handle 'look up'", skill.canHandle("look up python tutorials", context))
        assertTrue("Must handle 'find out'", skill.canHandle("find out about mars rover", context))

        // 2. Check quick search query extraction
        val searchResult = skill.execute("sesarch for electric vehicles in 2026", context)
        assertTrue(searchResult.handled)
        assertEquals("SEARCH_WEB", searchResult.proposedAction?.type)
        assertEquals("electric vehicles in 2026", searchResult.proposedAction?.params?.get("query"))

        // 3. Check deep research report routing
        val reportResult = skill.execute("make a report on artificial intelligence ethics", context)
        assertTrue(reportResult.handled)
        assertEquals("RESEARCH_DEEP", reportResult.proposedAction?.type)
        assertEquals("artificial intelligence ethics", reportResult.proposedAction?.params?.get("topic"))
    }
}
