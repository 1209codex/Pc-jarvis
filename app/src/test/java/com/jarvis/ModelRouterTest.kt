package com.jarvis

import com.jarvis.ai.LlmClient
import com.jarvis.ai.LlmPlanner
import com.jarvis.ai.Message
import com.jarvis.ai.ModelRouter
import com.jarvis.ai.ModelTier
import com.jarvis.agent.AgentDecision
import com.jarvis.agent.AgentState
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.DecisionType
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class FakeLlm(private val reply: String) : LlmClient {
    override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> =
        Result.success(reply)

    override fun cancel() {}
}

class ModelRouterTest {

    private val router = ModelRouter(defaultModel = "standard-model", strongModel = "strong-model")

    @Test
    fun standardComplexityRoutesToDefaultModel() {
        val route = router.route("play music on spotify")
        assertEquals(ModelTier.STANDARD, route.tier)
        assertEquals("standard-model", route.model)
        assertTrue(route.usesCloud)
    }

    @Test
    fun multiStepRequestRoutesToStrongModel() {
        val route = router.route("book an uber and then set a reminder in the morning")
        assertEquals(ModelTier.STRONG, route.tier)
        assertEquals("strong-model", route.model)
    }

    @Test
    fun planningVerbsRouteToStrongModel() {
        assertEquals(ModelTier.STRONG, router.classifyComplexity("organize my tomorrow"))
    }

    @Test
    fun verboseInputRoutesToStrongModel() {
        val long = "Please take a look at all of these notifications from whatsapp telegram and gmail " +
            "and then summarize which ones need my attention right now and which ones can wait until evening"
        assertEquals(ModelTier.STRONG, router.classifyComplexity(long))
    }

    @Test
    fun privacyTermsRouteLocalWithoutCloud() {
        val route = router.route("can you read the otp from my messages")
        assertEquals(ModelTier.LOCAL, route.tier)
        assertTrue(route.privacySensitive)
        assertFalse(route.usesCloud)
    }

    @Test
    fun nonSensitiveTextIsNotPrivacyFlagged() {
        assertFalse(router.privacySensitive("play some music"))
        assertTrue(router.privacySensitive("show my debit card details"))
    }

    @Test
    fun plannerRefusesCloudForPrivacySensitiveGoal() = runBlocking {
        val planner = LlmPlanner(
            FakeLlm("""{"decision":"ACT","reason_code":"x","action":{"type":"BATTERY","params":{}},"success_criteria":["done"],"confidence":0.9}"""),
            router = ModelRouter(),
            strongLlm = FakeLlm("should-not-be-called")
        )
        val result = planner.decide(
            history = emptyList(),
            goal = "what is my upi pin",
            state = AgentState(taskId = 1, goal = "what is my upi pin"),
            workingMemory = AgentWorkingMemory(goal = "what is my upi pin"),
            context = "no context",
            availableTools = "BATTERY"
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("privacy", ignoreCase = true))
    }

    @Test
    fun plannerDelegatesStrongGoalsToStrongClient() = runBlocking {
        val canned = """{"decision":"ACT","reason_code":"x","action":{"type":"BATTERY","params":{}},"success_criteria":["done"],"confidence":0.9}"""
        var strongCalls = 0
        val strong = object : LlmClient {
            override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> {
                strongCalls++
                return Result.success(canned)
            }

            override fun cancel() {}
        }
        val planner = LlmPlanner(FakeLlm(""), router = ModelRouter(), strongLlm = strong)

        val result = planner.decide(
            history = emptyList(),
            goal = "book a cab and then remind me",
            state = AgentState(taskId = 1, goal = "book a cab and then remind me"),
            workingMemory = AgentWorkingMemory(goal = "book a cab and then remind me"),
            context = "ctx",
            availableTools = "BATTERY"
        )

        assertTrue(result.isSuccess)
        assertEquals(DecisionType.ACT, result.getOrThrow().type)
        assertEquals(1, strongCalls)
    }
}