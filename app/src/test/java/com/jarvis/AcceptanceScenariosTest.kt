package com.jarvis

import com.jarvis.agent.*
import com.jarvis.agent.skills.MediaSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AcceptanceScenariosTest {

    private val evaluator = GoalEvaluator()
    private val recovery = AgentRecoveryManager()

    /**
     * Test Case 1: "Mere liye koi badiya song chalao"
     * -> Song playback intent dispatched
     * -> If unverified (audio stream inactive): Goal MUST NOT be satisfied
     * -> If verified (audio active): Goal satisfied
     */
    @Test
    fun testScenario1_SongPlaybackVerificationGating() = runBlocking {
        val goal = "Mere liye koi badiya song chalao"
        val state = AgentState(
            taskId = 101,
            goal = goal,
            iteration = 1,
            lastAction = AgentAction("MUSIC_PLAY", mapOf("query" to "Arijit Singh top hits"))
        )

        val toolResult = ToolResult.Success("Searching and playing 'Arijit Singh top hits'")
        val observation = AgentObservation("TOOL", true, toolResult.message)

        // Case 1A: Audio not active yet -> UNVERIFIED
        val unverifiedResult = VerificationResult(
            status = VerificationStatus.UNKNOWN,
            message = "Playback intent dispatched for 'Arijit Singh top hits', but audio stream is not active yet"
        )
        val evaluationUnverified = evaluator.evaluate(goal, state, observation, unverifiedResult)
        assertFalse("Goal MUST NOT be satisfied if playback verification is false", evaluationUnverified.satisfied)
        assertTrue(evaluationUnverified.missingRequirements.any { it.contains("Media playback audio stream unconfirmed") })

        // Case 1B: Audio active on device -> VERIFIED
        val verifiedResult = VerificationResult(
            status = VerificationStatus.VERIFIED,
            message = "Media playback active on device: Arijit Singh top hits"
        )
        val evaluationVerified = evaluator.evaluate(goal, state, observation, verifiedResult)
        assertTrue("Goal MUST be satisfied only when playback is verified active", evaluationVerified.satisfied)
    }

    /**
     * Test Case 2: "Mere liye ek comedy video chalao"
     * -> Candidate research / skill gathers comedy video candidates
     * -> Top candidate selected and played
     * -> If playback unverified -> recovery selects alternative candidate
     * -> Playback verified -> goal completed
     */
    @Test
    fun testScenario2_ComedyVideoResearchAndAlternativeRecovery() = runBlocking {
        val goal = "Mere liye ek comedy video chalao"
        val workingMemory = AgentWorkingMemory(goal)
        val mediaSkill = MediaSkill()

        // 1. Skill handles goal and provides ranked comedy candidates
        val skillContext = SkillContext(
            goal = goal,
            workingMemory = workingMemory,
            userPreferences = mapOf("comedy_preference" to "standup comedy")
        )
        assertTrue(mediaSkill.canHandle(goal, skillContext))
        val skillResult = mediaSkill.execute(goal, skillContext)
        assertTrue(skillResult.handled)
        assertTrue(skillResult.candidateQueries.isNotEmpty())

        workingMemory.candidates.addAll(skillResult.candidateQueries)

        // 2. Select first candidate
        val firstCandidate = workingMemory.candidates.first()

        val state = AgentState(
            taskId = 102,
            goal = goal,
            iteration = 1,
            lastAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to firstCandidate)),
            failureCount = 1
        )

        // 3. First candidate playback fails verification
        val failedObs = AgentObservation("TOOL", false, "Playback intent dispatched, but audio stream is not active yet")
        val recoveryPlan = recovery.determineRecovery(state.lastAction!!, failedObs, state, workingMemory)

        assertEquals("Recovery MUST switch to alternative candidate", RecoveryStrategy.SELECT_ALTERNATIVE, recoveryPlan.strategy)
        assertNotNull(recoveryPlan.modifiedAction)
        val altQuery = recoveryPlan.modifiedAction?.params?.get("query").orEmpty()
        assertNotEquals("Alternative query must be different from failed query", firstCandidate, altQuery)

        // 4. Alternative candidate succeeds and verifies
        state.lastAction = recoveryPlan.modifiedAction
        val successObservation = AgentObservation("TOOL", true, "Playing $altQuery on YouTube")
        val verifiedResult = VerificationResult(
            status = VerificationStatus.VERIFIED,
            message = "Media playback active on device: $altQuery"
        )
        val evaluation = evaluator.evaluate(goal, state, successObservation, verifiedResult)
        assertTrue("Goal satisfied after alternative candidate playback is verified", evaluation.satisfied)
    }

    /**
     * Test Case 3: "YouTube kholo aur comedy video chalao"
     * -> Compound / multi-step goal ("kholo" + "chalao")
     * -> Step 1: OPEN_APP alone MUST NOT complete goal
     * -> Step 2: YOUTUBE_PLAY + Verification = COMPLETE
     */
    @Test
    fun testScenario3_MultiStepCompoundGoalEvaluation() = runBlocking {
        val goal = "YouTube kholo aur comedy video chalao"

        // Step 1: OPEN_APP executed and app opened
        val step1State = AgentState(
            taskId = 103,
            goal = goal,
            iteration = 1,
            lastAction = AgentAction("OPEN_APP", mapOf("app" to "youtube")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("OPEN_APP", mapOf("app" to "youtube")),
                successCriteria = listOf("YouTube open", "comedy video playing")
            )
        )
        val step1Obs = AgentObservation("TOOL", true, "Opened youtube successfully")
        val step1Verif = VerificationResult(VerificationStatus.VERIFIED, "Target app 'youtube' verified installed and launched")

        val step1Eval = evaluator.evaluate(goal, step1State, step1Obs, step1Verif)
        assertFalse("OPEN_APP step alone MUST NOT satisfy multi-step goal", step1Eval.satisfied)
        assertTrue("Must indicate missing comedy video playback requirement", step1Eval.missingRequirements.any { it.contains("comedy video playing") || it.contains("Subsequent") })

        // Step 2: YOUTUBE_PLAY executed and verified. The kernel marks the OPEN_APP
        // criterion satisfied in step 1 via AgentState.satisfiedCriteria; both
        // requirements must be independently verified before the goal completes.
        val step2State = AgentState(
            taskId = 103,
            goal = goal,
            iteration = 2,
            completedSteps = 1,
            lastAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
                successCriteria = listOf("YouTube open", "comedy video playing")
            )
        ).apply { satisfiedCriteria.add("YouTube open".lowercase().trim()) }
        val step2Obs = AgentObservation("TOOL", true, "Playing bassi standup comedy on YouTube")
        val step2Verif = VerificationResult(VerificationStatus.VERIFIED, "Media playback active on device: bassi standup comedy")

        val step2Eval = evaluator.evaluate(goal, step2State, step2Obs, step2Verif)
        assertTrue("Goal satisfied when both app open and playback are verified", step2Eval.satisfied)
    }
}
