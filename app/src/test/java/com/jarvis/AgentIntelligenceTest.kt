package com.jarvis

import com.jarvis.agent.*
import com.jarvis.ai.LlmClient
import com.jarvis.ai.LlmPlanner
import com.jarvis.ai.Message
import com.jarvis.ai.ModelRouter
import com.jarvis.execution.VerificationEngine
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.execution.mediaTitleMatches
import com.jarvis.foundation.PolicyEngine
import com.jarvis.foundation.TaskStateManager
import com.jarvis.memory.AugmentedMemoryPipeline
import com.jarvis.memory.MemoryStore
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AgentIntelligenceTest {

    private class MockLlm(private val responseJson: String) : LlmClient {
        override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> {
            return Result.success(responseJson)
        }
        override fun cancel() {}
    }

    private class MockSuccessTool(override val name: String) : Tool {
        override suspend fun execute(params: Map<String, String>): ToolResult {
            return ToolResult.Success("Executed $name with $params")
        }
    }

    @Test
    fun testAgentLoopGuard() {
        val guard = AgentLoopGuard(maxIterations = 5, maxToolCalls = 5, maxSameActionRepetitions = 2)
        guard.nextIteration()
        assertEquals(1, guard.currentIteration())

        val check1 = guard.checkToolCall("OPEN_APP", mapOf("app" to "youtube"))
        assertTrue(check1 is AgentLoopGuard.GuardCheckResult.Allowed)
        guard.recordToolCall("OPEN_APP", mapOf("app" to "youtube"))
        assertEquals(1, guard.currentToolCalls())

        // Same call second time is fine
        val check2 = guard.checkToolCall("OPEN_APP", mapOf("app" to "youtube"))
        assertTrue(check2 is AgentLoopGuard.GuardCheckResult.Allowed)
        guard.recordToolCall("OPEN_APP", mapOf("app" to "youtube"))

        // Third same call should trigger infinite loop guard
        val check3 = guard.checkToolCall("OPEN_APP", mapOf("app" to "youtube"))
        assertTrue(check3 is AgentLoopGuard.GuardCheckResult.Exceeded)
        val exceeded = check3 as AgentLoopGuard.GuardCheckResult.Exceeded
        assertEquals(GuardRecommendation.STOP_AGENT, exceeded.recommendation)
        assertTrue(exceeded.reason.contains("repetitive action loop"))
    }

    @Test
    fun testGoalEvaluator() {
        val evaluator = GoalEvaluator()
        val state = AgentState(
            taskId = 1,
            goal = "stop playback",
            lastAction = AgentAction("MEDIA_STOP")
        )
        val observation = AgentObservation("TOOL", true, "Media stopped")
        val verification = VerificationResult(VerificationStatus.VERIFIED, "Media stopped")

        val evaluation = evaluator.evaluate("stop playback", state, observation, verification)
        assertTrue(evaluation.satisfied)
        assertEquals(1.0f, evaluation.confidence, 0.01f)
    }

    @Test
    fun testGoalEvaluatorRejectsUnverifiedMediaPlayback() {
        val evaluator = GoalEvaluator()
        val state = AgentState(
            taskId = 1,
            goal = "Mere liye koi badiya song chalao",
            lastAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to "Arijit Singh top hits"))
        )
        val observation = AgentObservation("TOOL", true, "Playing Arijit Singh on YouTube")
        val verification = VerificationResult(
            status = VerificationStatus.UNKNOWN,
            message = "Playback intent dispatched, but audio stream is not active yet"
        )

        val evaluation = evaluator.evaluate(state.goal, state, observation, verification)
        // MUST be false because verification failed!
        assertFalse(evaluation.satisfied)
        assertTrue(evaluation.missingRequirements.isNotEmpty())
    }

    @Test
    fun testGoalEvaluatorRejectsOpenAppOnlyForMultiStepGoal() {
        val evaluator = GoalEvaluator()
        val state = AgentState(
            taskId = 1,
            goal = "YouTube kholo aur comedy video chalao",
            lastAction = AgentAction("OPEN_APP", mapOf("app" to "youtube"))
        )
        val observation = AgentObservation("TOOL", true, "Opened youtube successfully")
        val verification = VerificationResult(VerificationStatus.VERIFIED, "Target app 'youtube' verified installed and launched")

        val evaluation = evaluator.evaluate(state.goal, state, observation, verification)
        // Opening YouTube alone is NOT enough to complete a compound goal with comedy playback!
        assertFalse(evaluation.satisfied)
        assertTrue(evaluation.missingRequirements.isNotEmpty())
    }

    @Test
    fun testGoalEvaluatorIndependentSuccessCriteria() {
        val evaluator = GoalEvaluator()
        val state = AgentState(
            taskId = 1,
            goal = "YouTube kholo aur comedy video chalao",
            lastAction = AgentAction("OPEN_APP", mapOf("app" to "youtube")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("OPEN_APP", mapOf("app" to "youtube")),
                successCriteria = listOf("YouTube open", "comedy video playing")
            )
        )
        val observation = AgentObservation("TOOL", true, "Opened youtube successfully")
        val verification = VerificationResult(VerificationStatus.VERIFIED, "Target app 'youtube' verified installed and launched")

        val evaluation = evaluator.evaluate(state.goal, state, observation, verification)
        // Only "YouTube open" is met, "comedy video playing" is not met!
        assertFalse(evaluation.satisfied)
        assertTrue(evaluation.missingRequirements.any { it.contains("comedy video playing") })
    }

    @Test
    fun testGoalEvaluatorCompletedStepsCannotFakeACriterion() {
        val evaluator = GoalEvaluator()
        // completedSteps > 0 must NEVER stand in for an independently-verified
        // requirement. Even after a verified playback step, the "YouTube open"
        // requirement is unmet because it was never satisfied by an OPEN_APP action.
        val state = AgentState(
            taskId = 1,
            goal = "YouTube kholo aur comedy video chalao",
            completedSteps = 1,
            lastAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
                successCriteria = listOf("YouTube open", "comedy video playing")
            )
        )
        val observation = AgentObservation("TOOL", true, "Playing bassi standup comedy")
        val verification = VerificationResult(VerificationStatus.VERIFIED, "Requested media is playing")

        val evaluation = evaluator.evaluate(state.goal, state, observation, verification)
        assertFalse("completedSteps must not satisfy an independent requirement", evaluation.satisfied)
        assertTrue(evaluation.missingRequirements.any { it.contains("YouTube open") })
    }

    @Test
    fun testGoalEvaluatorCrossStepCriteriaCompleteWhenAllVerified() {
        val evaluator = GoalEvaluator()
        // Both requirements independently verified across steps (kernel accumulation).
        val state = AgentState(
            taskId = 1,
            goal = "YouTube kholo aur comedy video chalao",
            completedSteps = 2,
            lastAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
                successCriteria = listOf("YouTube open", "comedy video playing")
            )
        ).apply { satisfiedCriteria.add("YouTube open".lowercase().trim()) }
        val observation = AgentObservation("TOOL", true, "Playing bassi standup comedy")
        val verification = VerificationResult(VerificationStatus.VERIFIED, "Requested media is playing: bassi standup comedy")

        val evaluation = evaluator.evaluate(state.goal, state, observation, verification)
        assertTrue(evaluation.satisfied)
    }

    @Test
    fun testGoalEvaluatorUnverifiedPlaybackCannotSatisfyPlayCriterion() {
        val evaluator = GoalEvaluator()
        val state = AgentState(
            taskId = 1,
            goal = "YouTube kholo aur comedy video chalao",
            lastAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("YOUTUBE_PLAY", mapOf("query" to "bassi standup comedy")),
                successCriteria = listOf("YouTube open", "comedy video playing")
            )
        ).apply { satisfiedCriteria.add("YouTube open".lowercase().trim()) }
        val observation = AgentObservation("TOOL", true, "Opened YouTube search, playback not confirmed")
        val verification = VerificationResult(
            VerificationStatus.UNKNOWN,
            "YouTube search screen opened; no specific video selection was confirmed"
        )

        val evaluation = evaluator.evaluate(state.goal, state, observation, verification)
        assertFalse("UNKNOWN playback must never satisfy the video requirement", evaluation.satisfied)
        assertTrue(evaluation.missingRequirements.any { it.contains("comedy video playing") })
    }

    @Test
    fun testGoalEvaluatorCommunicationDispatchCompletesOnlyOnDispatchOutcome() {
        val evaluator = GoalEvaluator()
        val goal = "Raj ko message bhejo"
        val observation = AgentObservation("TOOL", true, "Opened WhatsApp chat to Raj")

        // Tool succeeded, verifier could not confirm delivery -> UNKNOWN + DISPATCHED.
        val dispatched = VerificationResult(
            VerificationStatus.UNKNOWN,
            "Communication dispatched to 'Raj', delivery cannot be confirmed",
            mapOf("recipient" to "Raj", "send_confirmed" to "false", "outcome" to "DISPATCHED")
        )
        val dispatchedState = AgentState(
            taskId = 1, goal = goal,
            lastAction = AgentAction("WHATSAPP", mapOf("recipient" to "Raj", "message" to "Hello"))
        )
        val dispatchedEval = evaluator.evaluate(goal, dispatchedState, observation, dispatched)
        assertTrue("Dispatch is the observable boundary; delivery is not", dispatchedEval.satisfied)
        assertEquals("Verification must remain UNKNOWN, never VERIFIED", VerificationStatus.UNKNOWN, dispatched.status)

        // Same action but verification is UNKNOWN with no DISPATCHED outcome: unsatisfiable.
        val bareUnknown = VerificationResult(VerificationStatus.UNKNOWN, "No signal available")
        val bareState = dispatchedState.copy(lastDecision = null)
        val bareEval = evaluator.evaluate(goal, bareState, observation, bareUnknown)
        assertFalse("Plain UNKNOWN must not satisfy communication", bareEval.satisfied)
    }

    @Test
    fun testMediaTitleMatching() {
        assertTrue(mediaTitleMatches("bassi comedy", "Anubhav Singh Bassi - Stand Up Comedy"))
        assertTrue(mediaTitleMatches("bassi standup comedy", "bassi standup comedy full show (Official)"))
        assertTrue(mediaTitleMatches("latest hindi song", "Latest Hindi Song 2026 - Dance Mix"))
        assertTrue(mediaTitleMatches("", "anything at all"))
        assertFalse(mediaTitleMatches("q", "anything at all"))
        assertFalse(mediaTitleMatches("bassi standup comedy", "Zakir Khan standup comedy"))
        assertFalse(mediaTitleMatches("comedy video", null))
        assertFalse(mediaTitleMatches("comedy video", ""))
    }

    @Test
    fun testAgentRecoveryManager() {
        val recovery = AgentRecoveryManager()
        val failedObs = AgentObservation("TOOL", false, "App 'ymusic' is not installed")
        val failureType = recovery.classifyFailure(failedObs)
        assertEquals(FailureType.APP_NOT_INSTALLED, failureType)

        val state = AgentState(taskId = 1, goal = "play music")
        val failedAction = AgentAction("OPEN_APP", mapOf("app" to "ymusic", "query" to "kesariya"))
        val plan = recovery.determineRecovery(failedAction, failedObs, state)

        assertEquals(RecoveryStrategy.USE_ALTERNATE_TOOL, plan.strategy)
        assertEquals("YOUTUBE_PLAY", plan.modifiedAction?.type)
    }

    @Test
    fun testAgentRecoverySelectsNextCandidate() {
        val recovery = AgentRecoveryManager()
        val failedObs = AgentObservation("TOOL", false, "Playback intent dispatched, but audio stream is not active yet")
        val state = AgentState(taskId = 1, goal = "Mere liye ek comedy video chalao", failureCount = 1)
        val workingMemory = AgentWorkingMemory("Mere liye ek comedy video chalao").apply {
            candidates.addAll(listOf(
                "Anubhav Singh Bassi standup comedy (bassi comedy)",
                "Zakir Khan standup comedy special (zakir comedy)"
            ))
        }
        val failedAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to "Anubhav Singh Bassi standup comedy"))

        val plan = recovery.determineRecovery(failedAction, failedObs, state, workingMemory)
        assertEquals(RecoveryStrategy.SELECT_ALTERNATIVE, plan.strategy)
        assertTrue(plan.modifiedAction?.params?.get("query")?.contains("Zakir Khan") == true)
    }

    @Test
    fun testLlmPlannerDecisionParsing() {
        val json = """
            {
              "decision": "ACT",
              "reason_code": "DIRECT_PLAY",
              "action": {
                "type": "YOUTUBE_PLAY",
                "params": {
                  "query": "Arijit Singh"
                }
              },
              "confidence": 0.95
            }
        """.trimIndent()

        val planner = LlmPlanner(MockLlm(json))
        val decision = planner.parseDecision(json)

        assertEquals(DecisionType.ACT, decision.type)
        assertEquals("DIRECT_PLAY", decision.reasonCode)
        assertNotNull(decision.action)
        assertEquals("YOUTUBE_PLAY", decision.action?.type)
        assertEquals("Arijit Singh", decision.action?.params?.get("query"))
    }

    @Test
    fun testPrivacyRouterStripsSensitiveHistoryTurns() = runBlocking {
        val sentMessages = mutableListOf<Message>()
        val capturingLlm = object : LlmClient {
            override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> {
                sentMessages.addAll(messages)
                return Result.success(
                    """{"decision":"ACT","reason_code":"CHIME","action":null,"success_criteria":[],"confidence":0.9}"""
                )
            }
            override fun cancel() {}
        }
        val planner = LlmPlanner(capturingLlm, ModelRouter())
        val state = AgentState(taskId = 1L, goal = "play the chime now")
        val wm = AgentWorkingMemory(goal = "play the chime now")
        val history = listOf(
            Message("user", "my bank pin is 482193, never share it"),
            Message("assistant", "Sure, nothing stored."),
            Message("user", "actually play the chime now")
        )

        val result = planner.decide(history, "play the chime now", state, wm, context = "", availableTools = "")

        assertTrue(result.isSuccess)
        val sent = sentMessages.joinToString("\n") { it.content }
        assertFalse("Sensitive history turn leaked to cloud: $sent", sent.contains("482193"))
        assertTrue(sent.contains("chime"))
    }
}
