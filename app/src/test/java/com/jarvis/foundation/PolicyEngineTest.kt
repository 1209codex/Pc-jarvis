package com.jarvis.foundation

import org.junit.Assert.*
import org.junit.Test

class PolicyEngineTest {
    @Test fun highRiskRequiresApprovalInManualMode() {
        val result = PolicyEngine(autonomousFullAuto = false).evaluate(
            ToolMetadata("test", "test", emptyList(), RiskLevel.HIGH),
            emptyMap()
        )
        assertTrue(result.allowed)
        assertTrue(result.requiresApproval)
    }

    @Test fun criticalRiskIsBlocked() {
        val result = PolicyEngine().evaluateAction("DELETE_DATA", emptyMap())
        assertFalse(result.allowed)
        assertTrue(result.requiresApproval)
    }

    @Test fun lockScreenAllowsSafeToolsWhenLocked() {
        val policyEngine = PolicyEngine(
            autonomousFullAuto = true,
            isDeviceLockedProvider = { true }
        )

        // Safe query: Time / Clock
        val timeResult = policyEngine.evaluateAction("CLOCK", mapOf("action" to "get_time"))
        assertTrue(timeResult.allowed)

        // Safe query: Weather
        val weatherResult = policyEngine.evaluateAction("WEATHER", mapOf("action" to "current"))
        assertTrue(weatherResult.allowed)

        // Safe hardware switch: Flashlight
        val flashlightResult = policyEngine.evaluateAction("FLASHLIGHT", emptyMap())
        assertTrue(flashlightResult.allowed)

        // Safe hardware switch: Volume
        val volumeResult = policyEngine.evaluateAction("SYSTEM_SWITCHBOARD", mapOf("action" to "volume_up"))
        assertTrue(volumeResult.allowed)
    }

    @Test fun lockScreenGatesSensitiveToolsWhenLocked() {
        val policyEngine = PolicyEngine(
            autonomousFullAuto = true,
            isDeviceLockedProvider = { true }
        )

        // Sensitive: WhatsApp
        val waResult = policyEngine.evaluateAction("WHATSAPP", mapOf("action" to "send_message", "recipient" to "Alice"))
        assertFalse(waResult.allowed)
        assertTrue(waResult.requiresApproval)
        assertTrue(waResult.reason.contains("Device is locked"))

        // Sensitive: Telephony
        val callResult = policyEngine.evaluateAction("TELEPHONY_CONTROL", mapOf("action" to "call", "number" to "12345"))
        assertFalse(callResult.allowed)
        assertTrue(callResult.requiresApproval)
        assertTrue(callResult.reason.contains("Device is locked"))

        // Sensitive: App Autopilot
        val autopilotResult = policyEngine.evaluateAction("APP_AUTOPILOT", mapOf("action" to "open_app", "package" to "com.example.bank"))
        assertFalse(autopilotResult.allowed)
        assertTrue(autopilotResult.requiresApproval)
        assertTrue(autopilotResult.reason.contains("Device is locked"))
    }

    @Test fun unlockedDevicePermitsStandardFlow() {
        val policyEngine = PolicyEngine(
            autonomousFullAuto = true,
            isDeviceLockedProvider = { false }
        )

        val timeResult = policyEngine.evaluateAction("CLOCK", mapOf("action" to "get_time"))
        assertTrue(timeResult.allowed)

        // In autonomousFullAuto mode, unlocked device allows WhatsApp auto-guarded
        val waResult = policyEngine.evaluateAction("WHATSAPP", mapOf("action" to "send_message", "recipient" to "Alice"))
        assertTrue(waResult.allowed)
    }
}
