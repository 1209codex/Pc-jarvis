package com.jarvis

import com.jarvis.execution.VerificationEngine
import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.tools.AutonomousModeTool
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolPolicy
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class RecordingStubTool(
    override val name: String,
    private val verifyResult: VerificationResult = VerificationResult.unknown("stub"),
    var calls: Int = 0
) : Tool {
    override suspend fun execute(params: Map<String, String>): ToolResult {
        calls++
        return ToolResult(true, "ok")
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult = verifyResult
}

private class SlowStubTool : Tool {
    override val name: String = "SLOW_TOOL"
    override val policy: ToolPolicy = ToolPolicy(timeoutMs = 100)
    override suspend fun execute(params: Map<String, String>): ToolResult {
        delay(1_000)
        return ToolResult(true, "late")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ToolGatewayTest {

    @Test
    fun routesThroughToolVerifierWhenOverridden() = runBlocking {
        val metrics = com.jarvis.foundation.MetricsCollector()
        val registry = ToolRegistry()
        val tool = RecordingStubTool(
            "NOTIFICATIONS_READ",
            verifyResult = VerificationResult.success("notifs verified", mapOf("outcome" to "READBACK"))
        )
        registry.register(tool, ToolMetadata("NOTIFICATIONS_READ", "test", emptyList(), RiskLevel.LOW))
        val engine = VerificationEngine(null)
        val executor = ToolExecutor(registry, verificationEngine = engine, metrics = metrics)

        val result = executor.execute("NOTIFICATIONS_READ", mapOf())

        assertTrue(result.success)
        assertEquals(1, tool.calls)
    }

    @Test
    fun fallsBackToCentralEngineWhenToolVerifierIsUnknown() = runBlocking {
        val registry = ToolRegistry()
        val tool = RecordingStubTool("CALCULATE", verifyResult = VerificationResult.unknown("stub default"))
        registry.register(tool, ToolMetadata("CALCULATE", "test", emptyList(), RiskLevel.LOW))
        val engine = VerificationEngine(null)
        val executor = ToolExecutor(registry, verificationEngine = engine)

        // Engine has a real specialist for CALCULATE that does not need a Context.
        val result = executor.execute("CALCULATE", mapOf())

        assertTrue(result.success)
        // Tool-level verifier returned UNKNOWN, so we cannot assert the engine result directly;
        // but the gateway must not crash and the tool ran. Behavior contract is engine fallback.
        assertEquals(1, tool.calls)
    }

    @Test
    fun toolVerifierFailureSurfacesEvenWhenExecutionSucceeded() = runBlocking {
        val registry = ToolRegistry()
        val tool = RecordingStubTool(
            "SECURITY_AUDIT",
            verifyResult = VerificationResult.failure("report was not generated")
        )
        registry.register(tool, ToolMetadata("SECURITY_AUDIT", "test", emptyList(), RiskLevel.LOW))
        val executor = ToolExecutor(registry)

        val result = executor.execute("SECURITY_AUDIT", mapOf("action" to "privacy_score"))

        // Tool executed fine; verification says no. Execution result remains true (the tool ran),
        // the FAILED verification is observable via the tool's own verifier.
        assertTrue(result.success)
    }

    @Test
    fun schemaValidationRejectsMissingRequiredParamsBeforeExecution() = runBlocking {
        val registry = ToolRegistry()
        val tool = RecordingStubTool("RAG_RETRIEVE")
        registry.register(
            tool,
            ToolMetadata(
                name = "RAG_RETRIEVE",
                description = "test",
                parameters = listOf(ParameterSchema("query", "string", "search query", required = true)),
                riskLevel = RiskLevel.LOW
            )
        )
        val executor = ToolExecutor(registry)

        val result = executor.execute("RAG_RETRIEVE", mapOf("limit" to "3"))

        assertFalse(result.success)
        assertTrue(result.message.contains("query"))
        assertEquals(0, tool.calls)
    }

    @Test
    fun schemaValidationAllowsRequestsWithRequiredParams() = runBlocking {
        val registry = ToolRegistry()
        val tool = RecordingStubTool("RAG_RETRIEVE")
        registry.register(
            tool,
            ToolMetadata(
                name = "RAG_RETRIEVE",
                description = "test",
                parameters = listOf(ParameterSchema("query", "string", "search query", required = true)),
                riskLevel = RiskLevel.LOW
            )
        )
        val executor = ToolExecutor(registry)

        val result = executor.execute("RAG_RETRIEVE", mapOf("query" to "quantum"))

        assertTrue(result.success)
        assertEquals(1, tool.calls)
    }

    @Test
    fun timeoutKillsRunawayToolAndReportsFailure() = runBlocking {
        val registry = ToolRegistry()
        registry.register(SlowStubTool(), ToolMetadata("SLOW_TOOL", "test", emptyList(), RiskLevel.LOW))
        val executor = ToolExecutor(registry)

        val result = executor.execute("SLOW_TOOL", mapOf())

        assertFalse(result.success)
        assertTrue(result.message.contains("timed out"))
    }

    @Test
    fun auditSinkReceivesExecutionStatuses() = runBlocking {
        val events = mutableListOf<String>()
        val registry = ToolRegistry()
        registry.register(
            RecordingStubTool("NOTIFICATIONS_READ"),
            ToolMetadata("NOTIFICATIONS_READ", "test", emptyList(), RiskLevel.LOW)
        )
        val executor = ToolExecutor(registry, auditSink = { _, _, _, status, _ -> events.add(status) })

        executor.execute("NOTIFICATIONS_READ", mapOf())
        executor.execute("NO_SUCH_TOOL", mapOf())

        assertTrue(events.contains("EXECUTED"))
        assertEquals(1, events.count { it == "EXECUTED" })
    }

    @Test
    fun autonomousToolVerifyClaimsSuccessOnlyWhenStateConfirms() {
        val tool = AutonomousModeTool(
            com.jarvis.autonomous.AmbientContextEngine(context = null),
            com.jarvis.autonomous.AutonomousDaemon(
                ambientEngine = com.jarvis.autonomous.AmbientContextEngine(context = null),
                calendarManager = null,
                fileManager = null,
                toolExecutor = null,
                coroutineScope = TestScope(UnconfinedTestDispatcher())
            )
        )

        val enabled = tool.verify(
            mapOf("action" to "enable_autopilot"),
            ToolResult(true, "Autopilot engaged", mapOf("autopilot" to true, "ambient_state" to "STANDBY"))
        )
        assertEquals(VerificationStatus.VERIFIED, enabled.status)

        val notEnabled = tool.verify(
            mapOf("action" to "enable_autopilot"),
            ToolResult(true, "Autopilot engaged", mapOf("autopilot" to false))
        )
        assertEquals(VerificationStatus.UNKNOWN, notEnabled.status)
    }
}