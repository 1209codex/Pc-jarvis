package com.jarvis

import com.jarvis.foundation.MetricsCollector
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolExecutor
import com.jarvis.tools.ToolPolicy
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

private class CountingStubTool(
    override val name: String,
    override val policy: ToolPolicy = ToolPolicy(riskLevel = RiskLevel.HIGH),
    var calls: Int = 0
) : Tool {
    override suspend fun execute(params: Map<String, String>): ToolResult {
        calls++
        return ToolResult(true, "ok")
    }
}

class ToolCallAndTimeoutTest {

    @Test
    fun recordsToolCallMetricPerActionType() = runBlocking {
        val metrics = MetricsCollector()
        val registry = ToolRegistry()
        val tool = CountingStubTool("NOTIFICATIONS_READ", policy = ToolPolicy(riskLevel = RiskLevel.LOW))
        registry.register(tool)
        val executor = ToolExecutor(registry, metrics = metrics)

        executor.execute("NOTIFICATIONS_READ", mapOf())
        executor.execute("NOTIFICATIONS_READ", mapOf())

        val point = metrics.point("tool", "notifications_read")
        assertEquals(2L, point.calls)
        assertEquals(2L, point.okCalls)
        assertEquals(2, tool.calls)
    }

    @Test
    fun failedToolCountsAsUnsuccessful() = runBlocking {
        val metrics = MetricsCollector()
        val registry = ToolRegistry()
        registry.register(object : Tool {
            override val name: String = "SHOW_ERROR"
            override suspend fun execute(params: Map<String, String>): ToolResult =
                ToolResult.Failed("boom")
        })
        val executor = ToolExecutor(registry, metrics = metrics)

        executor.execute("SHOW_ERROR", mapOf())

        val point = metrics.point("tool", "SHOW_ERROR")
        assertEquals(1L, point.calls)
        assertEquals(0L, point.okCalls)
        assertEquals(1L, point.failureCalls)
    }

    @Test
    fun toolPolicyRiskIsUsedWhenMetadataAbsent() = runBlocking {
        val metrics = MetricsCollector()
        val registry = ToolRegistry()
        // No registry metadata -> ToolExecutor must fall back to ToolPolicy.riskLevel (HIGH).
        registry.register(CountingStubTool("WHATSAPP"))
        val executor = ToolExecutor(registry, metrics = metrics)

        val result = executor.execute("WHATSAPP", mapOf("recipient" to "Mom", "message" to "hi"))

        assertEquals(ToolResult.NeedsConfirmation("WHATSAPP", "").success, result.success)
        assertEquals(true, result.needsConfirmation)
        assertEquals(0, metrics.point("tool", "WHATSAPP").okCalls)
    }

    @Test
    fun metadataRiskWinsOverToolPolicyRisk() = runBlocking {
        val metrics = MetricsCollector()
        val registry = ToolRegistry()
        val tool = CountingStubTool("LOW_RISK_ACTION", policy = ToolPolicy(riskLevel = RiskLevel.HIGH))
        registry.register(
            tool,
            ToolMetadata(
                name = "LOW_RISK_ACTION",
                description = "test",
                parameters = emptyList(),
                riskLevel = RiskLevel.LOW
            )
        )
        val executor = ToolExecutor(registry, metrics = metrics)

        val result = executor.execute("LOW_RISK_ACTION", mapOf())

        assertEquals(true, result.success)
        assertEquals(1, tool.calls)
    }
}