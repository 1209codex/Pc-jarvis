package com.jarvis.agent

import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HallucinationFirewallTest {

    private class DummyTool : Tool {
        override val name: String = "TEST_TOOL"
        override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult.Success("Ok")
    }

    @Test
    fun testRejectsUnknownTool() {
        val registry = ToolRegistry()
        val firewall = HallucinationFirewall(registry)

        val res = firewall.validateToolCall("NON_EXISTENT_TOOL", emptyMap())
        assertFalse(res.isValid)
        assertTrue(res.rejectionReason!!.contains("not supported"))
    }

    @Test
    fun testValidatesRequiredParameters() {
        val registry = ToolRegistry()
        val tool = DummyTool()
        val meta = ToolMetadata(
            name = "TEST_TOOL",
            description = "Test",
            parameters = listOf(ParameterSchema("target", "string", "target", required = true)),
            riskLevel = RiskLevel.LOW
        )
        registry.register(tool, meta)
        val firewall = HallucinationFirewall(registry)

        // Missing required
        val invalid = firewall.validateToolCall("TEST_TOOL", emptyMap())
        assertFalse(invalid.isValid)
        assertTrue(invalid.rejectionReason!!.contains("Missing required parameter"))

        // Present required
        val valid = firewall.validateToolCall("TEST_TOOL", mapOf("target" to "abc"))
        assertTrue(valid.isValid)
    }

    @Test
    fun testSanitizesUnverifiedSpokenResponse() {
        val registry = ToolRegistry()
        val firewall = HallucinationFirewall(registry)

        val unverified = VerificationResult.unknown("No confirmation")
        val sanitized = firewall.sanitizeSpokenResponse(
            actionType = "WHATSAPP",
            toolResult = ToolResult.Success("Message sent"),
            verification = unverified,
            proposedResponse = "Yes boss, message sent successfully!"
        )

        assertTrue(sanitized.contains("confirmation pending"))
    }
}
