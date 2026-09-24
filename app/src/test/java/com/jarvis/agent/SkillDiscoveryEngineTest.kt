package com.jarvis.agent

import com.jarvis.agent.skills.SkillRegistry
import org.junit.Assert.*
import org.junit.Test

class SkillDiscoveryEngineTest {

    private val skillRegistry = SkillRegistry()
    private val discoveryEngine = SkillDiscoveryEngine(skillRegistry = skillRegistry)

    @Test
    fun testDiscoverExplicitSkillTeachingIntent() {
        val utterance = "teach jarvis a new skill called Workout Mode to open spotify, play phonk, and turn on dnd"
        val parsed = ComplexCommandParser.parse(utterance)

        val proposal = discoveryEngine.discoverProposedSkill(parsed)

        assertNotNull(proposal)
        assertEquals("Workout Mode", proposal?.proposedName)
        assertTrue(proposal!!.steps.size >= 2)
        assertTrue(proposal.clarificationPrompt.contains("Workout Mode"))

        // Simulate user confirming skill
        val persisted = discoveryEngine.persistDiscoveredSkill(proposal, userApproved = true)
        assertNotNull(persisted)
        assertEquals("Workout Mode", persisted?.name)

        // Check it's live in SkillRegistry
        val allSkills = skillRegistry.all()
        assertTrue(allSkills.any { it.name == "Workout Mode" })
    }

    @Test
    fun testDiscoverLongCompoundWorkflow() {
        val longWorkflow = "open settings and check battery health and turn on power saver mode then close settings"
        val parsed = ComplexCommandParser.parse(longWorkflow)

        val proposal = discoveryEngine.discoverProposedSkill(parsed)
        assertNotNull(proposal)
        assertTrue(proposal!!.steps.size >= 3)
    }

    @Test
    fun testDeclineSkillProposal() {
        val utterance = "teach jarvis a new skill called TestRoutine to open chrome"
        val parsed = ComplexCommandParser.parse(utterance)
        val proposal = discoveryEngine.discoverProposedSkill(parsed)
        assertNotNull(proposal)

        val result = discoveryEngine.persistDiscoveredSkill(proposal!!, userApproved = false)
        assertNull(result)
    }
}
