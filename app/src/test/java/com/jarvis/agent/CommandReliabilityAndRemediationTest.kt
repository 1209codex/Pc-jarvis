package com.jarvis.agent

import com.jarvis.ai.LlmClient
import com.jarvis.ai.LlmPlanner
import com.jarvis.ai.Message
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class CommandReliabilityAndRemediationTest {

    private class TestMockLlm : LlmClient {
        override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> {
            return Result.success("{}")
        }
        override fun cancel() {}
    }

    @Test
    fun testCompoundTaskSplittingVsNounPhraseProtection_BUG_CC_001() {
        // Noun phrases containing 'and' / '&' must not be split into multiple sub-goals
        val tomAndJerry = MultiTaskDecomposer.decompose("play tom and jerry on youtube")
        assertEquals("Noun phrase 'tom and jerry' must not be split", 1, tomAndJerry.size)
        assertEquals("play tom and jerry on youtube", tomAndJerry.first())

        val rockAndRoll = MultiTaskDecomposer.decompose("play rock and roll music")
        assertEquals("Noun phrase 'rock and roll' must not be split", 1, rockAndRoll.size)

        val fastAndFurious = MultiTaskDecomposer.decompose("search for fast and furious trailer")
        assertEquals("Noun phrase 'fast and furious' must not be split", 1, fastAndFurious.size)

        // Real compound requests with action verbs in each part must split correctly
        val realCompound = MultiTaskDecomposer.decompose("turn on torch and play believer")
        assertEquals("Compound command must be decomposed into 2 sub-goals", 2, realCompound.size)
        assertEquals("turn on torch", realCompound[0])
        assertEquals("play believer", realCompound[1])

        val hindiCompound = MultiTaskDecomposer.decompose("torch on karo aur kesariya gana bajao")
        assertEquals("Hindi compound command must be decomposed into 2 sub-goals", 2, hindiCompound.size)
    }

    @Test
    fun testFallbackDecisionSubstringBoundaryChecks_BUG_FK_002() {
        // 1. "stopwatch start karo" must NOT trigger MEDIA_STOP
        val stopwatchDecision = AgentKernel.matchFallbackRules("stopwatch start karo")
        assertNotEquals("stopwatch must not trigger MEDIA_STOP", "MEDIA_STOP", stopwatchDecision.action?.type)

        // 2. "bus stop near me" must NOT trigger MEDIA_STOP
        val busStopDecision = AgentKernel.matchFallbackRules("bus stop near me")
        assertNotEquals("bus stop must not trigger MEDIA_STOP", "MEDIA_STOP", busStopDecision.action?.type)

        // 3. "stop music" MUST trigger MEDIA_STOP
        val stopMusicDecision = AgentKernel.matchFallbackRules("stop music")
        assertEquals(DecisionType.ACT, stopMusicDecision.type)
        assertEquals("MEDIA_STOP", stopMusicDecision.action?.type)

        // 4. "flash sale" must NOT trigger FLASHLIGHT
        val flashSaleDecision = AgentKernel.matchFallbackRules("flash sale")
        assertNotEquals("flash sale must not trigger FLASHLIGHT", "FLASHLIGHT", flashSaleDecision.action?.type)

        // 5. "turn on torch" MUST trigger FLASHLIGHT
        val torchDecision = AgentKernel.matchFallbackRules("turn on torch")
        assertEquals("FLASHLIGHT", torchDecision.action?.type)

        // 6. "instant noodles" must NOT trigger INSTAGRAM
        val instantDecision = AgentKernel.matchFallbackRules("instant noodles")
        assertNotEquals("instant noodles must not trigger INSTAGRAM", "INSTAGRAM", instantDecision.action?.type)

        // 7. "aaj ka mausam kaisa hai" must NOT trigger CALENDAR_MANAGE
        val weatherDecision = AgentKernel.matchFallbackRules("aaj ka mausam kaisa hai")
        assertNotEquals("weather query must not trigger CALENDAR_MANAGE", "CALENDAR_MANAGE", weatherDecision.action?.type)
    }

    @Test
    fun testLlmPlannerConversationalTextVsActionableGoal_BUG_LLM_003() {
        val mockClient = TestMockLlm()
        val planner = LlmPlanner(mockClient)

        // Case A: For an actionable goal ("turn on flashlight"), conversational plain text must throw IllegalStateException
        // so it replans / uses rule fallback rather than executing a blind SPEAK action.
        try {
            planner.parseDecision("Sure, I am turning on the flashlight for you now!", "turn on flashlight")
            fail("Expected IllegalStateException when LLM provides conversational text for an actionable goal")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("actionable goal"))
        }

        // Case B: For an informational non-actionable goal ("what is the capital of France"), plain text is accepted as SPEAK
        val infoDecision = planner.parseDecision("The capital of France is Paris.", "what is the capital of France")
        assertEquals(DecisionType.ACT, infoDecision.type)
        assertEquals("SPEAK", infoDecision.action?.type)
        assertEquals("The capital of France is Paris.", infoDecision.action?.params?.get("text"))

        // Case C: Canonical action alias resolution in structured JSON
        val rawJson = """
            {
              "decision": "ACT",
              "action": {
                "type": "TORCH",
                "params": {"mode": "on"}
              }
            }
        """.trimIndent()
        val parsed = planner.parseDecision(rawJson, "turn on torch")
        assertEquals("FLASHLIGHT", parsed.action?.type)
    }

    @Test
    fun testToolRegistryStrictSchemaValidation_BUG_TR_004() = runBlocking {
        val registry = ToolRegistry()
        val dummyTool = object : Tool {
            override val name: String = "SAMPLE_TOOL"
            override val description: String = "Sample tool for validation testing"
            override suspend fun execute(params: Map<String, String>): ToolResult {
                return ToolResult.Success("Executed successfully with params: $params")
            }
        }

        val metadata = ToolMetadata(
            name = "SAMPLE_TOOL",
            description = "Sample tool",
            parameters = listOf(
                ParameterSchema(name = "count", type = "integer", description = "Number of items", required = true),
                ParameterSchema(name = "enabled", type = "boolean", description = "Flag enabled", required = false)
            ),
            riskLevel = RiskLevel.LOW
        )

        registry.register(dummyTool, metadata)
        val executor = ToolExecutor(registry)

        // 1. Missing required parameter
        val resMissing = executor.execute("SAMPLE_TOOL", emptyMap())
        assertFalse(resMissing.success)
        assertTrue(resMissing.message.contains("Missing required parameter"))

        // 2. Invalid integer parameter
        val resInvalidInt = executor.execute("SAMPLE_TOOL", mapOf("count" to "not_a_number"))
        assertFalse(resInvalidInt.success)
        assertTrue(resInvalidInt.message.contains("must be an integer"))

        // 3. Invalid boolean parameter
        val resInvalidBool = executor.execute("SAMPLE_TOOL", mapOf("count" to "10", "enabled" to "maybe"))
        assertFalse(resInvalidBool.success)
        assertTrue(resInvalidBool.message.contains("must be a boolean"))

        // 4. Valid parameters
        val resValid = executor.execute("SAMPLE_TOOL", mapOf("count" to "10", "enabled" to "true"))
        assertTrue(resValid.success)
        assertTrue(resValid.message.contains("Executed successfully"))
    }

    @Test
    fun testAgentResultVerifiedIntegrity_BUG_RT_006() {
        val completedResult = AgentResult(
            status = AgentResultStatus.COMPLETED,
            response = "Message dispatched.",
            taskId = 1L,
            iterations = 1,
            verified = true
        )
        assertTrue(completedResult.success)
        assertTrue(completedResult.verified)

        val unverifiedCompletedResult = AgentResult(
            status = AgentResultStatus.COMPLETED,
            response = "Dispatched via background intent (unverified).",
            taskId = 2L,
            iterations = 1,
            verified = false
        )
        assertTrue(unverifiedCompletedResult.success)
        assertFalse("Unverified completed result must have verified=false", unverifiedCompletedResult.verified)
    }
}
