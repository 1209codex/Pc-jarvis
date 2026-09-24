package com.jarvis.ai

import com.jarvis.agent.AgentAction
import com.jarvis.agent.AgentDecision
import com.jarvis.agent.AgentObservation
import com.jarvis.agent.AgentState
import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.DecisionType
import com.jarvis.agent.GoalEvaluator
import com.jarvis.agent.MultiTaskDecomposer
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiTaskIntentTest {

    private val evaluator = GoalEvaluator()

    @Test
    fun testDecomposerSplitsEnglishCompoundTasks() {
        val result1 = MultiTaskDecomposer.decompose("turn on flashlight and play believer")
        assertEquals(listOf("turn on flashlight", "play believer"), result1)

        val result2 = MultiTaskDecomposer.decompose("open settings then stop music")
        assertEquals(listOf("open settings", "stop music"), result2)

        val result3 = MultiTaskDecomposer.decompose("open youtube, search coding tutorials")
        assertEquals(listOf("open youtube", "search coding tutorials"), result3)

        val result4 = MultiTaskDecomposer.decompose("open whatsapp and turn on torch and play kesariya")
        assertEquals(listOf("open whatsapp", "turn on torch", "play kesariya"), result4)
    }

    @Test
    fun testDecomposerSplitsHindiHinglishCompoundTasks() {
        val result1 = MultiTaskDecomposer.decompose("torch on karo aur believer bajao")
        assertEquals(listOf("torch on karo", "believer bajao"), result1)

        val result2 = MultiTaskDecomposer.decompose("kholo settings fir torch on karo")
        assertEquals(listOf("kholo settings", "torch on karo"), result2)

        val result3 = MultiTaskDecomposer.decompose("torch band karo aur music pause karo")
        assertEquals(listOf("torch band karo", "music pause karo"), result3)

        val result4 = MultiTaskDecomposer.decompose("YouTube kholo aur scroll karo")
        assertEquals(listOf("YouTube kholo", "scroll karo"), result4)
    }

    @Test
    fun testDecomposerGuardsAgainstFalseSplits() {
        // Noun phrases and media titles should NOT be split
        val result1 = MultiTaskDecomposer.decompose("play rock and roll")
        assertEquals(listOf("play rock and roll"), result1)

        val result2 = MultiTaskDecomposer.decompose("search fast and furious")
        assertEquals(listOf("search fast and furious"), result2)

        val result3 = MultiTaskDecomposer.decompose("watch tom and jerry")
        assertEquals(listOf("watch tom and jerry"), result3)

        val result4 = MultiTaskDecomposer.decompose("single command")
        assertEquals(listOf("single command"), result4)
    }

    @Test
    fun testIntentResolverFastPathCompoundTask() {
        // Simple offline commands without verification need fast path
        val resolved = IntentResolver.resolve("open settings and stop music")
        assertNotNull(resolved)
        assertEquals(AssistantIntent.COMPOUND_TASK, resolved!!.intent)
        assertNotNull(resolved.directPlan)
        assertEquals(2, resolved.directPlan!!.actions.size)
        assertEquals("OPEN_APP", resolved.directPlan!!.actions[0].type)
        assertEquals("MEDIA_STOP", resolved.directPlan!!.actions[1].type)
    }

    @Test
    fun testAgentWorkingMemorySubGoalProgression() {
        val compoundGoal = "torch on karo aur believer bajao"
        val memory = AgentWorkingMemory(goal = compoundGoal)
        val subGoals = MultiTaskDecomposer.decompose(compoundGoal)
        memory.subGoals.addAll(subGoals)

        assertEquals(2, memory.subGoals.size)
        assertEquals(0, memory.currentPendingSubGoalIndex())
        assertEquals("torch on karo", memory.currentPendingSubGoal())
        assertFalse(memory.isAllSubGoalsCompleted())

        // Complete sub-goal 0
        memory.completedSubGoals.add(0)
        assertEquals(1, memory.currentPendingSubGoalIndex())
        assertEquals("believer bajao", memory.currentPendingSubGoal())
        assertFalse(memory.isAllSubGoalsCompleted())

        // Complete sub-goal 1
        memory.completedSubGoals.add(1)
        assertEquals(-1, memory.currentPendingSubGoalIndex())
        assertNull(memory.currentPendingSubGoal())
        assertTrue(memory.isAllSubGoalsCompleted())
    }

    @Test
    fun testMultiTaskSequentialGoalEvaluation() {
        val goal1 = "torch on karo"
        val state1 = AgentState(
            taskId = 201,
            goal = goal1,
            iteration = 1,
            lastAction = AgentAction("FLASHLIGHT", mapOf("mode" to "on")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("FLASHLIGHT", mapOf("mode" to "on")),
                successCriteria = listOf("flashlight on")
            )
        )
        val obs1 = AgentObservation("TOOL", true, "Flashlight is ON")
        val verif1 = VerificationResult(VerificationStatus.VERIFIED, "Flashlight verified ON")
        val eval1 = evaluator.evaluate(goal1, state1, obs1, verif1)
        assertTrue("Sub-goal 1 must be satisfied", eval1.satisfied)

        val goal2 = "believer bajao"
        val state2 = AgentState(
            taskId = 201,
            goal = goal2,
            iteration = 2,
            lastAction = AgentAction("YOUTUBE_PLAY", mapOf("query" to "believer")),
            lastDecision = AgentDecision(
                type = DecisionType.ACT,
                action = AgentAction("YOUTUBE_PLAY", mapOf("query" to "believer")),
                successCriteria = listOf("video playing")
            )
        )
        val obs2 = AgentObservation("TOOL", true, "Playing believer on YouTube")
        val verif2 = VerificationResult(VerificationStatus.VERIFIED, "Media playback active on device: believer")
        val eval2 = evaluator.evaluate(goal2, state2, obs2, verif2)
        assertTrue("Sub-goal 2 must be satisfied", eval2.satisfied)
    }
}
