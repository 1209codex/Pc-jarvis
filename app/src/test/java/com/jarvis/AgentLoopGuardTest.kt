package com.jarvis

import com.jarvis.agent.AgentLoopGuard
import com.jarvis.agent.GuardRecommendation
import org.junit.Assert.*
import org.junit.Test

class AgentLoopGuardTest {

    @Test
    fun testIterationAndToolCallLimits() {
        val guard = AgentLoopGuard(maxIterations = 3, maxToolCalls = 2)

        assertEquals(AgentLoopGuard.GuardCheckResult.Allowed, guard.checkNextIteration())
        guard.nextIteration()
        guard.nextIteration()
        assertEquals(AgentLoopGuard.GuardCheckResult.Allowed, guard.checkNextIteration())

        // 3rd iteration
        guard.nextIteration()
        val overflowCheck = guard.checkNextIteration()
        assertTrue(overflowCheck is AgentLoopGuard.GuardCheckResult.Exceeded)
        val exceeded = overflowCheck as AgentLoopGuard.GuardCheckResult.Exceeded
        assertEquals(GuardRecommendation.FORCE_FINAL_RESPONSE, exceeded.recommendation)
        assertTrue(exceeded.reason.contains("iteration limit reached"))
    }

    @Test
    fun testSingleActionRepetitionDetection() {
        val guard = AgentLoopGuard(maxSameActionRepetitions = 2)
        val params = mapOf("query" to "test")

        guard.recordToolCall("search", params)
        guard.recordToolCall("search", params)

        val check = guard.checkToolCall("search", params)
        assertTrue(check is AgentLoopGuard.GuardCheckResult.Exceeded)
        val exceeded = check as AgentLoopGuard.GuardCheckResult.Exceeded
        assertEquals(GuardRecommendation.STOP_AGENT, exceeded.recommendation)
        assertEquals("search", exceeded.failingTool)
    }

    @Test
    fun testTwoGramCycleDetection() {
        val guard = AgentLoopGuard(maxSameActionRepetitions = 5)
        val actionA = "fetch" to mapOf("id" to "1")
        val actionB = "process" to mapOf("id" to "1")

        // Action sequence: A, B, A
        guard.recordToolCall(actionA.first, actionA.second)
        guard.recordToolCall(actionB.first, actionB.second)
        guard.recordToolCall(actionA.first, actionA.second)

        // Prospective 4th action: B -> sequence becomes (A, B, A, B) forming a 2-gram cycle!
        val check = guard.checkToolCall(actionB.first, actionB.second)
        assertTrue(check is AgentLoopGuard.GuardCheckResult.Exceeded)
        val exceeded = check as AgentLoopGuard.GuardCheckResult.Exceeded
        assertTrue(exceeded.reason.contains("2-gram loop"))
        assertEquals(GuardRecommendation.STOP_AGENT, exceeded.recommendation)
    }

    @Test
    fun testThreeGramCycleDetection() {
        val guard = AgentLoopGuard(maxSameActionRepetitions = 5)
        val actionA = "step1" to mapOf("k" to "v1")
        val actionB = "step2" to mapOf("k" to "v2")
        val actionC = "step3" to mapOf("k" to "v3")

        // Sequence: A, B, C, A, B
        guard.recordToolCall(actionA.first, actionA.second)
        guard.recordToolCall(actionB.first, actionB.second)
        guard.recordToolCall(actionC.first, actionC.second)
        guard.recordToolCall(actionA.first, actionA.second)
        guard.recordToolCall(actionB.first, actionB.second)

        // Prospective 6th action C -> sequence becomes (A, B, C, A, B, C) forming a 3-gram cycle!
        val check = guard.checkToolCall(actionC.first, actionC.second)
        assertTrue(check is AgentLoopGuard.GuardCheckResult.Exceeded)
        val exceeded = check as AgentLoopGuard.GuardCheckResult.Exceeded
        assertTrue(exceeded.reason.contains("3-gram loop"))
    }

    @Test
    fun testConsecutiveFailureCircuitBreaker() {
        val guard = AgentLoopGuard(maxConsecutiveFailures = 3)
        val toolName = "rag_search"

        assertEquals(AgentLoopGuard.GuardCheckResult.Allowed, guard.recordToolFailure(toolName, "Network error 1"))
        assertEquals(AgentLoopGuard.GuardCheckResult.Allowed, guard.recordToolFailure(toolName, "Network error 2"))

        assertFalse(guard.isCircuitOpen(toolName))

        // 3rd failure trips circuit breaker
        val tripped = guard.recordToolFailure(toolName, "Network error 3")
        assertTrue(tripped is AgentLoopGuard.GuardCheckResult.Exceeded)
        val exceeded = tripped as AgentLoopGuard.GuardCheckResult.Exceeded
        assertEquals(GuardRecommendation.CIRCUIT_BREAK_TOOL, exceeded.recommendation)
        assertEquals(toolName, exceeded.failingTool)
        assertTrue(guard.isCircuitOpen(toolName))

        // Subsequent checkToolCall for this tool should be blocked
        val check = guard.checkToolCall(toolName, mapOf("q" to "test"))
        assertTrue(check is AgentLoopGuard.GuardCheckResult.Exceeded)
        val checkExceeded = check as AgentLoopGuard.GuardCheckResult.Exceeded
        assertEquals(GuardRecommendation.CIRCUIT_BREAK_TOOL, checkExceeded.recommendation)
    }

    @Test
    fun testCircuitBreakerRecoveryOnSuccess() {
        val guard = AgentLoopGuard(maxConsecutiveFailures = 3)
        val toolName = "weather_api"

        guard.recordToolFailure(toolName, "Timeout 1")
        guard.recordToolFailure(toolName, "Timeout 2")

        // Success resets failure count
        guard.recordToolSuccess(toolName)

        // Another failure should start count from 1, not 3
        val nextFailure = guard.recordToolFailure(toolName, "Timeout 3")
        assertEquals(AgentLoopGuard.GuardCheckResult.Allowed, nextFailure)
        assertFalse(guard.isCircuitOpen(toolName))
    }
}
