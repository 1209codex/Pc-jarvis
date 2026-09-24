package com.jarvis.tools

import android.content.Context
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.security.SecurityAuditor

class SecurityAuditorTool(
    private val context: Context? = null,
    private val auditor: SecurityAuditor = SecurityAuditor(context)
) : Tool {

    override val name: String = "SECURITY_AUDIT"
    override val description: String =
        "Audits device permissions, checks camera/microphone access, scans for phishing/SMS scams, and calculates privacy scores. Actions: audit_permissions, scan_camera_mic_apps, analyze_message, privacy_score. Parameters: action, message/text."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "SECURITY_AUDIT",
        description = "Monitors device privacy, permissions, and anti-phishing security.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "audit_permissions"
        val messageText = params["message"] ?: params["text"] ?: params["content"] ?: ""

        when (action) {
            "audit_permissions", "audit", "scan_permissions" -> {
                val audits = auditor.auditInstalledApps()
                val nonSystem = audits.filter { !it.isSystemApp }
                val cameraCount = nonSystem.count { it.hasCamera }
                val micCount = nonSystem.count { it.hasMic }
                val locCount = nonSystem.count { it.hasLocation }

                val summary = "Device Privacy Audit:\n" +
                        "• ${nonSystem.size} third-party apps analyzed\n" +
                        "• Camera Access: $cameraCount apps\n" +
                        "• Microphone Access: $micCount apps\n" +
                        "• Location Access: $locCount apps\n" +
                        "• Overall Privacy Score: ${auditor.calculatePrivacyScore()}/100"

                return ToolResult.Success(
                    message = summary,
                    data = mapOf("total_analyzed" to nonSystem.size, "score" to auditor.calculatePrivacyScore())
                )
            }

            "scan_camera_mic_apps", "camera_mic", "sensor_apps" -> {
                val sensorApps = auditor.getCameraAndMicApps()
                if (sensorApps.isEmpty()) {
                    return ToolResult.Success(
                        message = "No third-party apps found with camera or microphone permissions.",
                        data = mapOf("count" to 0)
                    )
                }
                val formatted = sensorApps.take(8).joinToString("\n") {
                    val sensors = mutableListOf<String>().apply {
                        if (it.hasCamera) add("Camera")
                        if (it.hasMic) add("Mic")
                    }.joinToString(" & ")
                    "• ${it.appName} ($sensors)"
                }
                return ToolResult.Success(
                    message = "Apps with Camera/Mic Access (${sensorApps.size} total):\n$formatted",
                    data = mapOf("count" to sensorApps.size)
                )
            }

            "analyze_message", "scan_scam", "phishing" -> {
                if (messageText.isBlank()) {
                    return ToolResult.Failed("Please provide the message text to analyze for security threats.")
                }
                val result = auditor.analyzeMessageSecurity(messageText)
                val msg = if (result.isSuspicious) {
                    "⚠️ Threat Level: ${result.threatLevel}\nRisk Factors:\n" +
                            result.riskFactors.joinToString("\n") { "• $it" } +
                            "\nRecommendation: Do not click any links or share credentials."
                } else {
                    "✓ Threat Analysis: SAFE. No common phishing or scam patterns detected in this message."
                }
                return ToolResult.Success(
                    message = msg,
                    data = mapOf("threat_level" to result.threatLevel, "suspicious" to result.isSuspicious)
                )
            }

            "privacy_score", "score" -> {
                val score = auditor.calculatePrivacyScore()
                val grade = when {
                    score >= 90 -> "EXCELLENT"
                    score >= 75 -> "GOOD"
                    score >= 60 -> "MODERATE"
                    else -> "NEEDS_ATTENTION"
                }
                return ToolResult.Success(
                    message = "Current Device Privacy Shield Score: $score/100 ($grade).",
                    data = mapOf("score" to score, "grade" to grade)
                )
            }

            else -> return ToolResult.Failed("Unknown security action: '$action'. Supported: audit_permissions, scan_camera_mic_apps, analyze_message, privacy_score.")
        }
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Security audit action failed: ${result.message}")
        }
        val action = params["action"]?.trim()?.lowercase() ?: "audit_permissions"
        return when (action) {
            "audit_permissions", "audit", "scan_permissions" ->
                if (result.data.containsKey("total_analyzed")) VerificationResult.success("Privacy audit generated", mapOf("outcome" to "REPORT_GENERATED"))
                else VerificationResult.unknown("Audit ran but totals not reported", mapOf("outcome" to "UNPARSED"))

            "scan_camera_mic_apps", "camera_mic", "sensor_apps" ->
                if (result.data.containsKey("count")) VerificationResult.success("Sensor-permission scan generated", mapOf("outcome" to "REPORT_GENERATED"))
                else VerificationResult.unknown("Sensor scan ran but count not reported", mapOf("outcome" to "UNPARSED"))

            "analyze_message", "scan_scam", "phishing" ->
                if (result.data.containsKey("threat_level")) VerificationResult.success("Message threat analysis complete", mapOf("outcome" to "ANALYZED"))
                else VerificationResult.unknown("Message analyzed but threat level not reported", mapOf("outcome" to "UNPARSED"))

            "privacy_score", "score" ->
                if (result.data.containsKey("score")) VerificationResult.success("Privacy score computed", mapOf("outcome" to "SCORE_READ"))
                else VerificationResult.unknown("Score action ran but value not reported", mapOf("outcome" to "UNPARSED"))

            else -> VerificationResult.unknown("No verifier for security action '$action'", mapOf("outcome" to "UNKNOWN_ACTION"))
        }
    }
}
