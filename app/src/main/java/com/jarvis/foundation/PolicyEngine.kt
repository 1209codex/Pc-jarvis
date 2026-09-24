package com.jarvis.foundation

enum class RiskLevel {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

data class ParameterSchema(
    val name: String,
    val type: String,
    val description: String,
    val required: Boolean = true
)

data class ToolMetadata(
    val name: String,
    val description: String,
    val parameters: List<ParameterSchema>,
    val riskLevel: RiskLevel,
    val outputSchema: String = "text"
)

data class PolicyEvaluationResult(
    val allowed: Boolean,
    val requiresApproval: Boolean,
    val reason: String
)

class PolicyEngine(
    var autonomousFullAuto: Boolean = false,
    val autonomyEngine: AutonomyPolicyEngine = AutonomyPolicyEngine(autonomousFullAuto = autonomousFullAuto),
    var isDeviceLockedProvider: () -> Boolean = { false },
    var allowLockScreenSafeActions: Boolean = true
) {
    init {
        autonomyEngine.autonomousFullAuto = autonomousFullAuto
    }

    fun setAutonomousMode(enabled: Boolean) {
        autonomousFullAuto = enabled
        autonomyEngine.autonomousFullAuto = enabled
    }

    fun setDrivingMode(active: Boolean) {
        autonomyEngine.drivingModeActive = active
    }

    fun evaluate(toolMetadata: ToolMetadata, params: Map<String, String>): PolicyEvaluationResult {
        autonomyEngine.autonomousFullAuto = autonomousFullAuto

        // Critical safety invariant: never permit destructive actions regardless of flags
        if (toolMetadata.riskLevel == RiskLevel.CRITICAL) {
            return PolicyEvaluationResult(
                allowed = false,
                requiresApproval = true,
                reason = "Critical destructive action blocked by security policy"
            )
        }

        // Lock-screen security gatekeeper: gate private/sensitive actions when device is locked
        if (isDeviceLockedProvider()) {
            val safe = allowLockScreenSafeActions &&
                    LockScreenSecurityPolicy.isActionAllowedOnLockScreen(toolMetadata.name, params, toolMetadata.riskLevel)
            if (!safe) {
                return PolicyEvaluationResult(
                    allowed = false,
                    requiresApproval = true,
                    reason = "Device is locked. Please unlock your device to perform this action."
                )
            }
        }

        // In manual mode (autonomousFullAuto = false), HIGH risk requires user approval
        if (!autonomousFullAuto && toolMetadata.riskLevel == RiskLevel.HIGH) {
            return PolicyEvaluationResult(
                allowed = true,
                requiresApproval = true,
                reason = "High impact action requires user confirmation in manual mode"
            )
        }

        return autonomyEngine.evaluate(toolMetadata.name, params)
    }

    fun evaluateAction(actionType: String, params: Map<String, String>): PolicyEvaluationResult {
        autonomyEngine.autonomousFullAuto = autonomousFullAuto

        // Lock-screen security gatekeeper: gate private/sensitive actions when device is locked
        if (isDeviceLockedProvider()) {
            val safe = allowLockScreenSafeActions &&
                    LockScreenSecurityPolicy.isActionAllowedOnLockScreen(actionType, params)
            if (!safe) {
                return PolicyEvaluationResult(
                    allowed = false,
                    requiresApproval = true,
                    reason = "Device is locked. Please unlock your device to perform this action."
                )
            }
        }

        return autonomyEngine.evaluate(actionType, params)
    }
}
