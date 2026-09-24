package com.jarvis

import com.jarvis.agent.AgentState
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.ai.LlmClient
import com.jarvis.ai.LlmPlanner
import com.jarvis.ai.Message
import com.jarvis.conversation.ConversationManager
import com.jarvis.voice.VoiceState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class ContinuousConversationTest {

    @Test
    fun testExitUtterancesDetection() {
        val manager = ConversationManager()

        // English exits
        assertTrue(manager.isExitUtterance("bye"))
        assertTrue(manager.isExitUtterance("goodbye"))
        assertTrue(manager.isExitUtterance("Hey Jarvis, bye"))
        assertTrue(manager.isExitUtterance("thank you"))
        assertTrue(manager.isExitUtterance("thanks"))
        assertTrue(manager.isExitUtterance("that's all"))
        assertTrue(manager.isExitUtterance("thats it"))
        assertTrue(manager.isExitUtterance("stop listening"))

        // Hindi / Hinglish exits
        assertTrue(manager.isExitUtterance("bas"))
        assertTrue(manager.isExitUtterance("alvida"))
        assertTrue(manager.isExitUtterance("shukriya"))
        assertTrue(manager.isExitUtterance("dhanyawad"))
        assertTrue(manager.isExitUtterance("bas itna hi"))
        assertTrue(manager.isExitUtterance("kuch nahi"))

        // Custom wake word exits
        assertTrue(manager.isExitUtterance("Hey Friday, bye", "Friday"))
        assertTrue(manager.isExitUtterance("Friday, thank you", "Friday"))
        assertTrue(manager.isExitUtterance("Ok Computer, that's all", "Computer"))
        assertTrue(manager.isExitUtterance("Computer stop listening", "Computer"))

        // Non-exit commands
        assertFalse(manager.isExitUtterance("open chrome"))
        assertFalse(manager.isExitUtterance("send whatsapp message to John"))
        assertFalse(manager.isExitUtterance("what time is it"))
        assertFalse(manager.isExitUtterance("turn on basement light"))
    }

    @Test
    fun testExitResponses() {
        val manager = ConversationManager()

        val thankResp = manager.getExitResponse("thank you jarvis")
        assertTrue(thankResp.contains("welcome", ignoreCase = true))

        val nightResp = manager.getExitResponse("good night")
        assertTrue(nightResp.contains("night", ignoreCase = true))

        val byeResp = manager.getExitResponse("bye")
        assertTrue(byeResp.contains("goodbye", ignoreCase = true))
    }

    @Test
    fun testConversationHistoryRecordingAndEviction() {
        val manager = ConversationManager()

        manager.addMessage("user", "Hello Jarvis")
        manager.addMessage("assistant", "Hello boss, how can I help you?")

        val history = manager.getHistory()
        assertEquals(2, history.size)
        assertEquals("user", history[0].role)
        assertEquals("Hello Jarvis", history[0].content)
        assertEquals("assistant", history[1].role)
        assertEquals("Hello boss, how can I help you?", history[1].content)

        // Test FIFO limit (max 10)
        for (i in 1..15) {
            manager.addMessage("user", "Message $i")
        }

        val limitedHistory = manager.getHistory()
        assertEquals(10, limitedHistory.size)
        assertEquals("Message 15", limitedHistory.last().content)

        manager.clearHistory()
        assertTrue(manager.getHistory().isEmpty())
    }

    @Test
    fun testStateTransitionsForContinuousConversationAndBargeIn() {
        val manager = ConversationManager()

        // Start engine
        assertTrue(manager.transitionTo(VoiceState.WAITING_FOR_WAKE))
        assertTrue(manager.transitionTo(VoiceState.WAKE_DETECTED))
        assertTrue(manager.transitionTo(VoiceState.LISTENING))
        assertTrue(manager.transitionTo(VoiceState.THINKING))
        assertTrue(manager.transitionTo(VoiceState.SPEAKING))

        // 1. Follow-up continuous window: SPEAKING -> LISTENING
        assertTrue(manager.transitionTo(VoiceState.LISTENING))

        // Back to SPEAKING for next turn
        assertTrue(manager.transitionTo(VoiceState.THINKING))
        assertTrue(manager.transitionTo(VoiceState.SPEAKING))

        // 2. Barge-in interruption: SPEAKING -> INTERRUPTED -> LISTENING
        assertTrue(manager.transitionTo(VoiceState.INTERRUPTED))
        assertEquals(VoiceState.INTERRUPTED, manager.currentStateValue)
        assertTrue(manager.transitionTo(VoiceState.LISTENING))
        assertEquals(VoiceState.LISTENING, manager.currentStateValue)

        // 3. Timeout / User exits: LISTENING -> WAITING_FOR_WAKE
        assertTrue(manager.transitionTo(VoiceState.WAITING_FOR_WAKE))
        assertEquals(VoiceState.WAITING_FOR_WAKE, manager.currentStateValue)
    }

    @Test
    fun testLlmPlannerReceivesHistoryContext() = runBlocking {
        val capturedMessages = mutableListOf<Message>()

        val fakeLlmClient = object : LlmClient {
            override suspend fun chat(
                messages: List<Message>,
                onToken: (String) -> Unit
            ): Result<String> {
                capturedMessages.addAll(messages)
                val jsonReply = """
                    {
                      "decision": "COMPLETE",
                      "reason_code": "DONE",
                      "confidence": 0.99
                    }
                """.trimIndent()
                return Result.success(jsonReply)
            }

            override fun cancel() {}
        }

        val planner = LlmPlanner(fakeLlmClient)
        val history = listOf(
            Message("user", "What's the weather today?"),
            Message("assistant", "It is 28 degrees and sunny.")
        )

        val state = AgentState(taskId = 1L, goal = "Should I carry an umbrella?")
        val memory = AgentWorkingMemory(goal = state.goal)

        val decision = planner.decide(
            history = history,
            goal = state.goal,
            state = state,
            workingMemory = memory,
            context = "Context info",
            availableTools = "TOOLS"
        )

        assertTrue(decision.isSuccess)
        // Verify history was included in the LLM payload
        assertTrue(capturedMessages.any { it.role == "user" && it.content == "What's the weather today?" })
        assertTrue(capturedMessages.any { it.role == "assistant" && it.content == "It is 28 degrees and sunny." })
        assertTrue(capturedMessages.any { it.role == "user" && it.content.contains("Should I carry an umbrella?") })
    }
}
