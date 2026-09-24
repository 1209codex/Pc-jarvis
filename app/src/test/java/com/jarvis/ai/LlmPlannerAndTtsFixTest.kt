package com.jarvis.ai

import com.jarvis.agent.DecisionType
import com.jarvis.tools.SpeakTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmPlannerAndTtsFixTest {

    private class MockLlmClient(private val reply: String) : LlmClient {
        override suspend fun chat(messages: List<Message>, onToken: (String) -> Unit): Result<String> {
            return Result.success(reply)
        }
        override fun cancel() {}
    }

    @Test
    fun testParseDecision_ValidJson() {
        val planner = LlmPlanner(MockLlmClient(""))
        val json = """
            {
              "decision": "ACT",
              "reason_code": "play_music",
              "action": {
                "type": "PLAY_MEDIA",
                "params": { "track": "believer" }
              },
              "confidence": 0.95
            }
        """.trimIndent()

        val decision = planner.parseDecision(json)
        assertEquals(DecisionType.ACT, decision.type)
        assertNotNull(decision.action)
        assertEquals("PLAY_MEDIA", decision.action?.type)
        assertEquals("believer", decision.action?.params?.get("track"))
    }

    @Test
    fun testParseDecision_MarkdownCodeFencedJson() {
        val planner = LlmPlanner(MockLlmClient(""))
        val fenced = """
            ```json
            {
              "decision": "ACT",
              "reason_code": "open_app",
              "action": {
                "type": "OPEN_APP",
                "params": { "app": "youtube" }
              }
            }
            ```
        """.trimIndent()

        val decision = planner.parseDecision(fenced)
        assertEquals(DecisionType.ACT, decision.type)
        assertEquals("OPEN_APP", decision.action?.type)
        assertEquals("youtube", decision.action?.params?.get("app"))
    }

    @Test
    fun testParseDecision_ConversationalPlainTextFallback() {
        val planner = LlmPlanner(MockLlmClient(""))
        val conversationalText = "I can help you check your battery, control playback, set alarms, and open apps."

        val decision = planner.parseDecision(conversationalText)
        assertEquals(DecisionType.ACT, decision.type)
        assertNotNull(decision.action)
        assertEquals("SPEAK", decision.action?.type)
        assertEquals(conversationalText, decision.action?.params?.get("text"))
        assertEquals("llm_conversational_text", decision.reasonCode)
    }

    @Test
    fun testParseDecision_LowConfidenceActionMustAskInsteadOfExecuting() {
        val planner = LlmPlanner(MockLlmClient(""))
        val decision = planner.parseDecision(
            """{"decision":"ACT","action":{"type":"WHATSAPP","params":{"action":"send_message","recipient":"Rahul","message":"hello"}},"confidence":0.2}""",
            "send a message to Rahul"
        )

        assertEquals(DecisionType.ASK_USER, decision.type)
        assertEquals("low_confidence_action", decision.reasonCode)
    }

    @Test
    fun testSpeakTool_PolicyTimeoutIs60s() {
        val speakTool = SpeakTool()
        assertEquals(60_000L, speakTool.policy.timeoutMs)
    }
}
