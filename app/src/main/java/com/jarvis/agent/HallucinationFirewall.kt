package com.jarvis.agent

import com.jarvis.execution.VerificationResult
import com.jarvis.execution.VerificationStatus
import com.jarvis.foundation.ToolMetadata
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult

data class FirewallValidationResult(
    val isValid: Boolean,
    val canonicalToolName: String?,
    val sanitizedParams: Map<String, String>,
    val rejectionReason: String? = null
)

/**
 * Firewall that intercepts all LLM proposed actions before execution and
 * all execution results before spoken TTS response.
 *
 * Guarantees:
 * 1. LLM cannot call un-registered or unknown tools.
 * 2. Missing required schema parameters are caught immediately.
 * 3. LLM cannot claim success for actions whose OS verification failed or is UNKNOWN.
 */
class HallucinationFirewall(
    private val toolRegistry: ToolRegistry
) {

    /**
     * Pre-execution gateway check on LLM generated tool calls.
     */
    fun validateToolCall(toolName: String, rawParams: Map<String, String>): FirewallValidationResult {
        val canonical = toolRegistry.resolveCanonicalToolName(toolName)
            ?: return FirewallValidationResult(
                isValid = false,
                canonicalToolName = null,
                sanitizedParams = rawParams,
                rejectionReason = "Tool '$toolName' is not supported or registered in system capabilities."
            )

        val metadata: ToolMetadata? = toolRegistry.getMetadata(canonical)
        if (metadata != null) {
            val missing = metadata.parameters
                .filter { it.required && rawParams[it.name].isNullOrBlank() }
                .map { it.name }

            if (missing.isNotEmpty()) {
                return FirewallValidationResult(
                    isValid = false,
                    canonicalToolName = canonical,
                    sanitizedParams = rawParams,
                    rejectionReason = "Missing required parameter(s): ${missing.joinToString(", ")} for '$canonical'."
                )
            }
        }

        return FirewallValidationResult(
            isValid = true,
            canonicalToolName = canonical,
            sanitizedParams = rawParams
        )
    }

    /**
     * Post-execution verification gate.
     * Replaces fabricated LLM confirmations with honest OS observation status.
     */
    fun sanitizeSpokenResponse(
        actionType: String,
        toolResult: ToolResult,
        verification: VerificationResult,
        proposedResponse: String
    ): String {
        if (!toolResult.success) {
            return "Could not complete $actionType: ${toolResult.message}"
        }

        return when (verification.status) {
            VerificationStatus.VERIFIED -> {
                if (proposedResponse.isNotBlank()) proposedResponse else toolResult.message
            }
            VerificationStatus.UNKNOWN -> {
                "${toolResult.message} (Action dispatched; playback or delivery confirmation pending)."
            }
            VerificationStatus.FAILED -> {
                "Action was dispatched, but verification failed: ${verification.message}"
            }
        }
    }
}
