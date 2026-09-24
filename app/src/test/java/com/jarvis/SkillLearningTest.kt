package com.jarvis

import com.jarvis.agent.AgentAction
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.RiskLevel
import com.jarvis.skilllearning.LearnedSkill
import com.jarvis.skilllearning.LearnedSkillStore
import com.jarvis.skilllearning.SkillStep
import com.jarvis.skilllearning.SkillStatus
import com.jarvis.skilllearning.WorkflowMiner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillLearningTest {

    private fun verified(type: String, params: Map<String, String> = emptyMap()) =
        AgentAction(type, params) to VerificationResult.success("ok", mapOf("outcome" to "READBACK"))

    private fun unknown(type: String) = AgentAction(type) to VerificationResult.unknown("cannot confirm")

    @Test
    fun learnsOnlyFromFullyVerifiedRuns() {
        val store = LearnedSkillStore()
        val learned = store.learnFromRun(
            "play music arijit singh",
            listOf(
                verified("YMUSIC_PLAY", mapOf("query" to "arijit singh")),
                verified("MEDIA_STOP", emptyMap())
            ),
            riskOf = { RiskLevel.LOW }
        )
        assertNotNull(learned)
        assertEquals(2, learned!!.steps.size)
        assertEquals("YMUSIC_PLAY", learned.requiredTools.first())
        assertEquals(SkillStatus.DRAFT, learned.status)
    }

    @Test
    fun unverifiedStepVoidsWholeRun() {
        val store = LearnedSkillStore()
        val learned = store.learnFromRun(
            "play music arijit singh",
            listOf(verified("YMUSIC_PLAY"), unknown("MEDIA_STOP")),
            riskOf = { RiskLevel.LOW }
        )
        assertNull("A run with any unverified step must not produce a skill", learned)
    }

    @Test
    fun emptyRunProducesNothing() {
        val store = LearnedSkillStore()
        assertNull(store.learnFromRun("anything", emptyList()) { RiskLevel.LOW })
    }

    @Test
    fun repeatedRunsPromoteDraftToVerifiedToStable() {
        val store = LearnedSkillStore()
        val run = listOf(verified("BATTERY_STATUS", mapOf("action" to "check")))
        store.learnFromRun("check battery level", run) { RiskLevel.LOW }
        val second = store.learnFromRun("check battery level", run) { RiskLevel.LOW }
        assertEquals(SkillStatus.VERIFIED, second!!.status)
        val third = store.learnFromRun("check battery level", run) { RiskLevel.LOW }
        assertEquals(SkillStatus.STABLE, third!!.status)
        assertEquals(3, third.verifiedRuns)
        assertTrue(third.replayable)
    }

    @Test
    fun changedWorkflowBumpsVersionAndResetsRuns() {
        val store = LearnedSkillStore()
        val a = listOf(verified("OPEN_APP", mapOf("app" to "youtube")))
        store.learnFromRun("open youtube", a) { RiskLevel.LOW }
        store.learnFromRun("open youtube", a) { RiskLevel.LOW }
        // Workflow changes -> new version, fresh verification count.
        val changed = store.learnFromRun(
            "open youtube",
            listOf(verified("OPEN_APP", mapOf("app" to "vanced")), verified("UI_SCROLL", mapOf("direction" to "down"))),
        ) { RiskLevel.LOW }
        assertEquals(2, changed!!.version)
        assertEquals(1, changed.verifiedRuns)
    }

    @Test
    fun stableSkillIsNeverOverwrittenByWeakerRelearn() {
        val store = LearnedSkillStore()
        val run = listOf(verified("BATTERY_STATUS", mapOf("action" to "check")))
        store.learnFromRun("check battery", run) { RiskLevel.LOW }
        store.learnFromRun("check battery", run) { RiskLevel.LOW }
        val stable = store.learnFromRun("check battery", run) { RiskLevel.LOW }
        assertEquals(SkillStatus.STABLE, stable!!.status)

        val weakerRun = listOf(verified("BATTERY_STATUS", mapOf("action" to "check", "extra" to "param")))
        val relearn = store.learnFromRun("check battery", weakerRun) { RiskLevel.LOW }
        assertTrue("STABLE skill must be returned unchanged", relearn!!.version == stable.version && relearn.status == SkillStatus.STABLE)
    }

    @Test
    fun repeatedFailuresDeprecateStableSkill() {
        val store = LearnedSkillStore()
        val run = listOf(verified("BATTERY_STATUS", mapOf("action" to "check")))
        store.learnFromRun("check battery", run) { RiskLevel.LOW }
        store.learnFromRun("check battery", run) { RiskLevel.LOW }
        val stable = store.learnFromRun("check battery", run) { RiskLevel.LOW }
        assertEquals(SkillStatus.STABLE, stable!!.status)

        repeat(6) { store.recordFailure(stable.id, "battery check failed") }
        assertEquals(SkillStatus.DEPRECATED, store.get(stable.id)!!.status)
        assertFalse(store.get(stable.id)!!.replayable)
    }

    @Test
    fun invalidationByToolDeprecatesSkillsUsingIt() {
        val store = LearnedSkillStore()
        store.learnFromRun("send whatsapp to mom", listOf(verified("WHATSAPP_SEND", mapOf("contact" to "mom", "text" to "hi")))) { RiskLevel.MEDIUM }
        val invalidated = store.invalidateByTool("whatsapp_send")
        assertEquals(1, invalidated.size)
        assertEquals(SkillStatus.DEPRECATED, invalidated[0].status)
        assertFalse(invalidated[0].replayable)
    }

    @Test
    fun highRiskWorkflowCarriesRiskClass() {
        val store = LearnedSkillStore()
        val learned = store.learnFromRun(
            "send a message",
            listOf(verified("WHATSAPP_SEND", mapOf("contact" to "mom", "text" to "h"))),
            riskOf = { type -> if (type == "WHATSAPP_SEND") RiskLevel.MEDIUM else RiskLevel.LOW }
        )
        assertEquals(RiskLevel.MEDIUM, learned!!.riskClass)
    }

    @Test
    fun replayWalksStepsOnlyAfterEachIsVerified() {
        val store = LearnedSkillStore()
        val facts = mutableMapOf<String, String>()
        store.learnFromRun("open and scroll", listOf(verified("OPEN_APP"), verified("UI_SCROLL", mapOf("direction" to "down")))) { RiskLevel.LOW }
        store.learnFromRun("open and scroll", listOf(verified("OPEN_APP"), verified("UI_SCROLL", mapOf("direction" to "down")))) { RiskLevel.LOW }

        val first = store.takeStep("open and scroll", facts)
        assertNotNull(first)
        assertEquals("OPEN_APP", first!!.action.type)

        // Unverified previous step: replay halts, normal planning takes over.
        assertNull(store.takeStep("open and scroll", facts))

        store.confirmStepVerified(facts)
        val second = store.takeStep("open and scroll", facts)
        assertNotNull(second)
        assertEquals("UI_SCROLL", second!!.action.type)

        // No more steps -> session clears.
        facts["_learned_ok"] = "1"
        assertNull(store.takeStep("open and scroll", facts))
        assertFalse(facts.containsKey("_learned_skill"))
    }

    @Test
    fun replayDoesNotEngageUntilSkillIsVerified() {
        val store = LearnedSkillStore()
        val facts = mutableMapOf<String, String>()
        store.learnFromRun("check battery", listOf(verified("BATTERY_STATUS"))) { RiskLevel.LOW }
        // DRAFT skills are not replayable yet.
        assertNull(store.takeStep("check battery", facts))
        store.learnFromRun("check battery", listOf(verified("BATTERY_STATUS"))) { RiskLevel.LOW }
        assertNotNull(store.takeStep("check battery", facts))
    }

    @Test
    fun matchesSimilarGoalByEmbedding() {
        val store = LearnedSkillStore()
        val run = listOf(verified("BATTERY_STATUS", mapOf("action" to "check")))
        store.learnFromRun("what is my battery status", run) { RiskLevel.LOW }
        store.learnFromRun("what is my battery status", run) { RiskLevel.LOW }

        assertNotNull(store.takeStep("battery status check karo", mutableMapOf()))
        assertNull(store.takeStep("please cook biryani", mutableMapOf()))
    }

    @Test
    fun workflowMinerSlugsGoalIntoId() {
        assertEquals("open.youtube.and.play", WorkflowMiner.slug("Open YouTube   and play!!"))
    }
}