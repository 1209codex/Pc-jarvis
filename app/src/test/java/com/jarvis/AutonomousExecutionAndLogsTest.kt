package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.LogSkill
import com.jarvis.agent.skills.SkillContext
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.foundation.PolicyEngine
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.logs.LogReaderEngine
import com.jarvis.tools.LogsTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AutonomousExecutionAndLogsTest {

    private lateinit var policyEngine: PolicyEngine
    private lateinit var logReaderEngine: LogReaderEngine
    private lateinit var logsTool: LogsTool
    private lateinit var logSkill: LogSkill

    private fun createContext(goal: String = ""): SkillContext =
        SkillContext(goal = goal, workingMemory = AgentWorkingMemory(goal = goal))

    @Before
    fun setUp() {
        policyEngine = PolicyEngine(autonomousFullAuto = true)
        logReaderEngine = LogReaderEngine()
        logsTool = LogsTool(logReaderEngine)
        logSkill = LogSkill()
    }

    @Test
    fun testAutonomousFullAutoPolicyEvaluation() {
        // High risk tool metadata (e.g. WhatsApp send, file write)
        val highRiskTool = ToolMetadata(
            name = "WHATSAPP",
            description = "Send message",
            parameters = emptyList(),
            riskLevel = RiskLevel.HIGH
        )

        val evalResult = policyEngine.evaluate(highRiskTool, mapOf("action" to "send_message", "recipient" to "Mom", "message" to "Hi"))
        assertTrue("High risk action must be allowed in full-auto mode", evalResult.allowed)
        assertFalse("High risk action must NOT require approval in full-auto mode", evalResult.requiresApproval)

        // Action evaluation
        val actionEval = policyEngine.evaluateAction("WHATSAPP_SEND", mapOf("recipient" to "Boss", "message" to "Done"))
        assertTrue(actionEval.allowed)
        assertFalse(actionEval.requiresApproval)

        // Destructive wipe actions must still be blocked
        val destructiveEval = policyEngine.evaluateAction("FACTORY_RESET", emptyMap())
        assertFalse("Destructive reset must be blocked", destructiveEval.allowed)
        assertTrue(destructiveEval.requiresApproval)
    }

    @Test
    fun testLegacyPolicyEvaluationRequiresApprovalWhenNotFullAuto() {
        policyEngine.autonomousFullAuto = false

        val highRiskTool = ToolMetadata(
            name = "WHATSAPP",
            description = "Send message",
            parameters = emptyList(),
            riskLevel = RiskLevel.HIGH
        )
        val evalResult = policyEngine.evaluate(highRiskTool, mapOf("action" to "send_message", "recipient" to "Mom", "message" to "Hi"))
        assertTrue(evalResult.allowed)
        assertTrue("Legacy mode should require approval for high risk actions", evalResult.requiresApproval)
    }

    @Test
    fun testLogReaderEngineInMemoryTelemetry() {
        logReaderEngine.recordLog("VoiceEngine", "INFO", "Listening started")
        logReaderEngine.recordLog("AndroidSpeechEngine", "INFO", "Speech recognized: play song")
        logReaderEngine.recordLog("ToolExecutor", "INFO", "Executing tool YOUTUBE_PLAY")
        logReaderEngine.recordLog("GroqLlm", "WARN", "HTTP 429 rate limit")

        val logs = logReaderEngine.readLogcat(maxLines = 10, filter = null)
        assertTrue(logs.isNotEmpty())

        val summary = logReaderEngine.getFormattedSummary(maxLines = 10)
        assertTrue(summary.contains("Telemetry"))

        val errors = logReaderEngine.getRecentErrors(maxLines = 5)
        assertTrue(errors.any { it.contains("429") || it.contains("WARN") })
    }

    @Test
    fun testLogsToolExecution() = runBlocking {
        logReaderEngine.recordLog("AgentKernel", "INFO", "Task 1 completed successfully")

        val result = logsTool.execute(mapOf("lines" to "10", "mode" to "summary"))
        assertTrue(result.success)
        assertTrue(result.message.contains("Telemetry") || result.message.contains("Autonomous"))
    }

    @Test
    fun testLogSkillTriggersAndExecution() = runBlocking {
        assertTrue(logSkill.canHandle("read logs", createContext("read logs")))
        assertTrue(logSkill.canHandle("logs padho", createContext("logs padho")))
        assertTrue(logSkill.canHandle("check system logs", createContext("check system logs")))
        assertTrue(logSkill.canHandle("kya error aaya", createContext("kya error aaya")))
        assertTrue(logSkill.canHandle("show recent logs", createContext("show recent logs")))

        val skillResult = logSkill.execute("kya error aaya", createContext("kya error aaya"))
        assertTrue(skillResult.handled)
        assertEquals("LOGS_READ", skillResult.proposedAction?.type)
        assertEquals("error", skillResult.proposedAction?.params?.get("filter"))
    }

    @Test
    fun testIntentResolverLogsFastPath() {
        val res1 = IntentResolver.resolve("read logs")
        assertNotNull(res1)
        assertEquals(AssistantIntent.READ_LOGS, res1?.intent)

        val res2 = IntentResolver.resolve("logs padho")
        assertNotNull(res2)
        assertEquals(AssistantIntent.READ_LOGS, res2?.intent)

        val res3 = IntentResolver.resolve("check error logs")
        assertNotNull(res3)
        assertEquals(AssistantIntent.READ_LOGS, res3?.intent)
        assertEquals("error", res3?.params?.get("filter"))
    }
}
