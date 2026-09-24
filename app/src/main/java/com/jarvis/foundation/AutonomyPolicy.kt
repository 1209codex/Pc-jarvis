package com.jarvis.foundation

/**
 * 4-Dimensional Autonomy Risk Framework.
 *
 * Rather than binary full-auto or coarse risk levels, actions are evaluated along:
 * 1. Reversibility (Can the state change be effortlessly undone?)
 * 2. SideEffectScope (Is the effect internal, hardware radio, local storage, or external communication?)
 * 3. PrivacySensitivity (Does this touch public info, contextual telemetry, or highly sensitive credentials/OTPs?)
 * 4. Destructiveness (Does this modify or permanently erase data?)
 */
enum class Reversibility {
    HIGH,          // Flashlight, volume, media pause, reading state
    MEDIUM,        // Calendar event creation, local note, alarm set
    IRREVERSIBLE   // Sending WhatsApp/SMS, placing phone call, deleting files
}

enum class SideEffectScope {
    INTERNAL_READ,           // Battery, logs, device status, calculations
    DEVICE_HARDWARE,         // Bluetooth, Wi-Fi, volume, flashlight, ringer
    LOCAL_STORAGE,           // Notes, reminders, local cache
    EXTERNAL_COMMUNICATION,  // WhatsApp messages, SMS, phone calls
    FINANCIAL_SECURITY       // Payments, credentials, system permissions, factory reset
}

enum class PrivacySensitivity {
    PUBLIC,            // General knowledge, math, system time
    CONTEXTUAL,        // Calendar agenda, battery, device network
    HIGHLY_SENSITIVE   // OTPs, banking SMS, passwords, personal contacts
}

enum class Destructiveness {
    ZERO,      // No data modification
    MINOR,     // Overwriting non-critical local settings
    CRITICAL   // Data deletion, factory reset, account changes
}

enum class AutonomyTier {
    /** Auto-approved immediately without requiring confirmation. */
    AUTO,
    /** Auto-approved in autonomous mode with preconditions and post-verification. */
    AUTO_GUARDED,
    /** External side-effects requiring user confirmation unless running under active autopilot (e.g. driving mode). */
    CONFIRM_EXTERNAL,
    /** High-risk or catastrophic actions blocked by default without strict authorization. */
    BLOCK_OR_STRICT
}

data class ActionRiskProfile(
    val reversibility: Reversibility,
    val scope: SideEffectScope,
    val privacy: PrivacySensitivity,
    val destructiveness: Destructiveness,
    val tier: AutonomyTier,
    val rationale: String
)

class AutonomyPolicyEngine(
    var autonomousFullAuto: Boolean = false,
    var drivingModeActive: Boolean = false,
    var allowAutonomousMessaging: Boolean = false
) {
    /**
     * Evaluates the 4-dimensional risk profile for an action and parameter set.
     */
    fun classifyAction(actionType: String, params: Map<String, String>): ActionRiskProfile {
        val upper = actionType.trim().uppercase()

        // 1. Destructive Operations -> BLOCK_OR_STRICT
        if (upper in listOf("FACTORY_RESET", "DELETE_DATA", "WIPE_STORAGE", "UNINSTALL_APP", "SYSTEM_WIPE")) {
            return ActionRiskProfile(
                reversibility = Reversibility.IRREVERSIBLE,
                scope = SideEffectScope.FINANCIAL_SECURITY,
                privacy = PrivacySensitivity.HIGHLY_SENSITIVE,
                destructiveness = Destructiveness.CRITICAL,
                tier = AutonomyTier.BLOCK_OR_STRICT,
                rationale = "Critical destructive operations are strictly blocked by safety invariants."
            )
        }

        // 2. External Communications (WhatsApp Send, SMS Send, Outgoing Calls)
        val isWhatsApp = upper in listOf("WHATSAPP", "WHATSAPP_SEND", "SEND_WHATSAPP")
        val isWhatsAppSend = isWhatsApp && (
            params["action"]?.equals("send_message", ignoreCase = true) == true ||
            (params["action"] == null && (!params["recipient"].isNullOrBlank() || !params["message"].isNullOrBlank()))
        )

        val isSmsSend = upper in listOf("SEND_SMS", "SMS_SEND") ||
            (upper == "TELEPHONY_CONTROL" && params["action"]?.equals("send_sms", ignoreCase = true) == true)

        val isOutgoingCall = upper in listOf("CALL", "DIAL", "PLACE_CALL") ||
            (upper == "TELEPHONY_CONTROL" && params["action"]?.equals("call", ignoreCase = true) == true)

        if (isWhatsAppSend || isSmsSend || isOutgoingCall) {
            // External communications must never be auto-approved silently:
            // require explicit drivingModeActive or allowAutonomousMessaging setting
            val autoApproved = autonomousFullAuto || drivingModeActive || allowAutonomousMessaging
            val tier = if (autoApproved) AutonomyTier.AUTO_GUARDED else AutonomyTier.CONFIRM_EXTERNAL
            return ActionRiskProfile(
                reversibility = Reversibility.IRREVERSIBLE,
                scope = SideEffectScope.EXTERNAL_COMMUNICATION,
                privacy = PrivacySensitivity.CONTEXTUAL,
                destructiveness = Destructiveness.ZERO,
                tier = tier,
                rationale = if (autoApproved)
                    "External transmission auto-approved under active autopilot policy."
                else
                    "External transmission requires confirmation to prevent unintended communications."
            )
        }

        // 3. Destructive File Operations
        if (upper in listOf("FILE_DELETE", "DELETE_FILE") ||
            (upper == "FILE_WRITE" && params["overwrite"]?.equals("true", ignoreCase = true) == true)
        ) {
            return ActionRiskProfile(
                reversibility = Reversibility.IRREVERSIBLE,
                scope = SideEffectScope.LOCAL_STORAGE,
                privacy = PrivacySensitivity.CONTEXTUAL,
                destructiveness = Destructiveness.MINOR,
                tier = if (autonomousFullAuto && params["safe_temp"] == "true") AutonomyTier.AUTO_GUARDED else AutonomyTier.CONFIRM_EXTERNAL,
                rationale = "Overwriting or deleting local files requires confirmation."
            )
        }

        // 4. Safe Local Storage & System Planning (Notes, Calendar, Alarms) -> AUTO_GUARDED
        if (upper in listOf("NOTE", "SAVE_NOTE", "REMINDER", "SET_ALARM", "SET_TIMER", "CALENDAR_MANAGE", "ROUTINE_MANAGE", "MACRO_WORKFLOW")) {
            return ActionRiskProfile(
                reversibility = Reversibility.MEDIUM,
                scope = SideEffectScope.LOCAL_STORAGE,
                privacy = PrivacySensitivity.CONTEXTUAL,
                destructiveness = Destructiveness.ZERO,
                tier = AutonomyTier.AUTO_GUARDED,
                rationale = "Local structured planning operations are auto-approved with state checkpointing."
            )
        }

        // 5. Hardware Radios & Device Settings -> AUTO
        if (upper in listOf("DEVICE_SETTINGS", "FLASHLIGHT", "MEDIA_CONTROL", "MEDIA_STOP", "MUSIC_PLAY", "YOUTUBE_PLAY", "SPOTIFY_PLAY")) {
            return ActionRiskProfile(
                reversibility = Reversibility.HIGH,
                scope = SideEffectScope.DEVICE_HARDWARE,
                privacy = PrivacySensitivity.PUBLIC,
                destructiveness = Destructiveness.ZERO,
                tier = AutonomyTier.AUTO,
                rationale = "Reversible hardware toggles execute autonomously without latency."
            )
        }

        // 6. Read-Only / Information Retrieval / Telemetry -> AUTO
        return ActionRiskProfile(
            reversibility = Reversibility.HIGH,
            scope = SideEffectScope.INTERNAL_READ,
            privacy = PrivacySensitivity.PUBLIC,
            destructiveness = Destructiveness.ZERO,
            tier = AutonomyTier.AUTO,
            rationale = "Read-only inspection and query tools are safe for instantaneous autonomous execution."
        )
    }

    /**
     * Legacy evaluation bridge returning PolicyEvaluationResult.
     */
    fun evaluate(actionType: String, params: Map<String, String>): PolicyEvaluationResult {
        val profile = classifyAction(actionType, params)
        return when (profile.tier) {
            AutonomyTier.AUTO -> PolicyEvaluationResult(
                allowed = true,
                requiresApproval = false,
                reason = profile.rationale
            )
            AutonomyTier.AUTO_GUARDED -> PolicyEvaluationResult(
                allowed = true,
                requiresApproval = false,
                reason = profile.rationale
            )
            AutonomyTier.CONFIRM_EXTERNAL -> PolicyEvaluationResult(
                allowed = true,
                requiresApproval = true,
                reason = profile.rationale
            )
            AutonomyTier.BLOCK_OR_STRICT -> PolicyEvaluationResult(
                allowed = false,
                requiresApproval = true,
                reason = profile.rationale
            )
        }
    }
}
