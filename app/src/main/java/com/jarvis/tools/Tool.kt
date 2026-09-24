package com.jarvis.tools

import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel

open class ToolResult(
    open val success: Boolean,
    open val message: String,
    open val data: Map<String, Any> = emptyMap(),
    open val interrupted: Boolean = false,
    open val recoverable: Boolean = false,
    open val needsPermission: String? = null,
    open val needsConfirmation: Boolean = false
) {
    data class Success(
        override val message: String,
        override val data: Map<String, Any> = emptyMap(),
        override val interrupted: Boolean = false
    ) : ToolResult(true, message, data, interrupted)

    data class Failed(
        val error: String,
        override val recoverable: Boolean = false,
        override val interrupted: Boolean = false
    ) : ToolResult(false, error, emptyMap(), interrupted, recoverable)

    data class Partial(
        override val message: String,
        val pendingStep: String,
        override val interrupted: Boolean = false
    ) : ToolResult(true, message, emptyMap(), interrupted)

    data class NeedsPermission(
        val permission: String,
        val explanation: String
    ) : ToolResult(false, "Permission required: $permission ($explanation)", emptyMap(), false, false, permission)

    data class NeedsConfirmation(
        val action: String,
        val prompt: String
    ) : ToolResult(false, prompt, emptyMap(), false, false, null, true)
}

data class ToolPolicy(
    val idempotent: Boolean = true,
    val retryable: Boolean = true,
    val requiresConfirmation: Boolean = false,
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val timeoutMs: Long = 20_000L
)

interface Tool {
    val name: String
    val description: String get() = ""
    val policy: ToolPolicy get() = ToolPolicy()
    suspend fun execute(params: Map<String, String>): ToolResult

    /**
     * Optional per-tool outcome verification. Override to turn the tool's own
     * reported evidence into a definitive result; the default is UNKNOWN so an
     * unverified dispatch can never claim success. When a tool returns UNKNOWN
     * here, ToolExecutor falls back to the central VerificationEngine.
     */
    fun verify(params: Map<String, String>, result: ToolResult): VerificationResult =
        VerificationResult.unknown("No specialized verifier for '${name}'")
}
