package com.jarvis

import com.jarvis.agent.AgentKernel
import com.jarvis.agent.AgentLoopGuard
import com.jarvis.agent.AgentObservation
import com.jarvis.agent.AgentState
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.ExecutedAct
import com.jarvis.agent.PendingApproval
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression tests for the repeated-action bug family reported in
 * AGENT_SESSION_LOG.md ("Agent detected infinite repetitive action loop on:
 * YOUTUBE_PLAY" + actions re-firing after clarify resumes).
 */
class RepeatLoopRegressionTest {

    @Test
    fun testCheckpointRoundTripPreservesExecutedActs() {
        val goal = "YouTube per gana chalao"
        val state = AgentState(taskId = 7, goal = goal)
        val act = ExecutedAct(
            result = ToolResult.Success("Playback intent dispatched for 'trending hindi top songs'"),
            observation = AgentObservation("TOOL", true, "Playback intent dispatched for 'trending hindi top songs'"),
            verification = VerificationResult(VerificationStatus.UNKNOWN, "could not be confirmed")
        )
        state.executedActs["YOUTUBE_PLAY:{query=trending hindi top songs}"] = act

        val json = AgentKernel.serializeState(state, AgentWorkingMemory(goal))
        val (restoredState, _) = AgentKernel.restoreState(json)!!

        val restored = restoredState.executedActs["YOUTUBE_PLAY:{query=trending hindi top songs}"]
        assertNotNull("executedActs must survive the ASK_USER checkpoint round-trip", restored)
        assertTrue(restored!!.result.success)
        assertTrue(restored.result.message.contains("trending hindi top songs"))
        assertTrue(restored.observation.success)
        assertEquals(VerificationStatus.UNKNOWN, restored.verification.status)
        assertFalse("UNKNOWN verification must stay unverified after restore", restored.verification.verified)
    }

    @Test
    fun testCheckpointRoundTripPreservesFailedAct() {
        val goal = "torch on karo"
        val state = AgentState(taskId = 8, goal = goal)
        state.executedActs["FLASHLIGHT:{mode=on}"] = ExecutedAct(
            result = ToolResult.Failed("Torch service unavailable"),
            observation = AgentObservation("TOOL", false, "Torch service unavailable"),
            verification = VerificationResult(VerificationStatus.FAILED, "flashlight could not be confirmed on")
        )

        val json = AgentKernel.serializeState(state, AgentWorkingMemory(goal))
        val (restoredState, _) = AgentKernel.restoreState(json)!!

        val restored = restoredState.executedActs["FLASHLIGHT:{mode=on}"]
        assertNotNull(restored)
        assertFalse("A failed act must stay failed after restore", restored!!.result.success)
        assertEquals(VerificationStatus.FAILED, restored.verification.status)
    }

    @Test
    fun testCheckpointRoundTripPreservesPendingApproval() {
        val goal = "Doller ko hello bhejo"
        val state = AgentState(taskId = 9, goal = goal)
        state.pendingApproval = PendingApproval("WHATSAPP", mapOf("recipient" to "Doller", "message" to "hello"))

        val json = AgentKernel.serializeState(state, AgentWorkingMemory(goal))
        val (restoredState, _) = AgentKernel.restoreState(json)!!

        val pending = restoredState.pendingApproval
        assertNotNull("pendingApproval must survive the checkpoint round-trip", pending)
        assertEquals("WHATSAPP", pending!!.type)
        assertEquals("Doller", pending.params["recipient"])
        assertEquals("hello", pending.params["message"])
    }

    @Test
    fun testCheckpointRoundTripBackwardCompatibleWithoutExecutedActs() {
        // A state with no executed acts / pending approval must round-trip cleanly.
        val goal = "open youtube"
        val legacy = AgentState(taskId = 10, goal = goal)
        val json = AgentKernel.serializeState(legacy, AgentWorkingMemory(goal))
        val (restoredState, _) = AgentKernel.restoreState(json)!!
        assertEquals(10, restoredState.taskId)
        assertEquals(goal, restoredState.goal)
        assertTrue(restoredState.executedActs.isEmpty())
        assertNull(restoredState.pendingApproval)
    }

    @Test
    fun testRestoreStateReturnsNullOnCorruptJson() {
        assertNull("Corrupt checkpoint JSON must fall back to a fresh state", AgentKernel.restoreState("not json at all"))
    }

    @Test
    fun testCheckToolCallNeverThrowsAtRepetitionLimit() {
        // The kernel's honest repeat handling relies on checkToolCall returning
        // Exceeded instead of throwing the way recordToolCall does.
        val guard = AgentLoopGuard(maxIterations = 50, maxToolCalls = 50, maxSameActionRepetitions = 3)
        repeat(3) { guard.recordToolCall("YOUTUBE_PLAY", mapOf("query" to "trending hindi top songs")) }
        val check = guard.checkToolCall("YOUTUBE_PLAY", mapOf("query" to "trending hindi top songs"))
        val exceeded = check as AgentLoopGuard.GuardCheckResult.Exceeded
        assertTrue("4th identical call must report Exceeded, not throw", exceeded.reason.contains("infinite repetitive action loop"))
    }

    @Test
    fun testRepeatAfterRetriesStaysAllowedWithoutPhantomRecordings() {
        // ACT (real execution) -> RETRY x2 (no recording in the kernel anymore) ->
        // ACT again: with the old phantom RETRY/prior-act recordings the same-action
        // counter hit the require() and crashed with "infinite repetitive action
        // loop". Without them the repeat is Allowed and handled honestly by the
        // prior-act branch.
        val guard = AgentLoopGuard(maxIterations = 50, maxToolCalls = 50, maxSameActionRepetitions = 3)
        guard.recordToolCall("YOUTUBE_PLAY", mapOf("query" to "q")) // real execution: check+record
        guard.checkToolCall("YOUTUBE_PLAY", mapOf("query" to "q")) // RETRY: check only
        guard.checkToolCall("YOUTUBE_PLAY", mapOf("query" to "q")) // RETRY: check only
        val allowed = guard.checkToolCall("YOUTUBE_PLAY", mapOf("query" to "q"))
        assertTrue("Repeat after retries must stay Allowed for honest prior-act handling", allowed is AgentLoopGuard.GuardCheckResult.Allowed)
        assertEquals("RETRY must not record phantom tool calls", 1, guard.currentToolCalls())
    }
}

