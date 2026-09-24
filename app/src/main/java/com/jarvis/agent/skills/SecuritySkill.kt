package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Locale

class SecuritySkill : Skill {
    override val id: String = "security_guardian"
    override val name: String = "Device Security & Privacy Skill"
    override val description: String = "Audits permissions, scans camera/mic access, and analyzes phishing threats in English and Hindi."
    override val triggers: List<String> = listOf(
        "security", "privacy", "permission", "permissions",
        "camera access", "mic access", "microphone access", "access to my camera", "access to camera", "access to mic",
        "phishing", "scam", "spam message", "fraud",
        "privacy score", "audit"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()
        if (triggers.any { lower.contains(it) }) return true
        if ((lower.contains("camera") || lower.contains("mic")) && (lower.contains("access") || lower.contains("permission") || lower.contains("who"))) return true
        return false
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        // 1. Phishing / Scam Message Scan
        if (lower.contains("phishing") || lower.contains("scam") || lower.contains("spam") || lower.contains("fraud") || lower.contains("suspicious")) {
            val content = lower.replace(Regex("\\b(check|is|this|message|sms|a|scam|phishing|spam|fraud|suspicious|analyze|scan)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("SECURITY_AUDIT", mapOf("action" to "analyze_message", "message" to content)),
                explanation = "Analyzing message for phishing and fraud indicators"
            )
        }

        // 2. Camera & Mic Access
        if (lower.contains("camera") || lower.contains("mic") || lower.contains("microphone")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("SECURITY_AUDIT", mapOf("action" to "scan_camera_mic_apps")),
                explanation = "Scanning apps with camera and microphone access"
            )
        }

        // 3. Privacy Score
        if (lower.contains("score") || lower.contains("grade") || lower.contains("status")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("SECURITY_AUDIT", mapOf("action" to "privacy_score")),
                explanation = "Calculating device privacy score"
            )
        }

        // 4. General Permission Audit
        return SkillResult(
            handled = true,
            proposedAction = AgentAction("SECURITY_AUDIT", mapOf("action" to "audit_permissions")),
            explanation = "Auditing installed app permissions"
        )
    }
}
