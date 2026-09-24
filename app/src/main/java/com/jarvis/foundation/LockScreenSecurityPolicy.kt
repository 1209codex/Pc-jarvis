package com.jarvis.foundation

/**
 * LockScreenSecurityPolicy
 *
 * Enforces privacy and security boundaries when Jarvis is invoked while the device
 * is locked (Keyguard active). Safe queries and hardware actions (e.g. flashlight,
 * time, weather, volume) are permitted, while sensitive operations (WhatsApp, SMS,
 * contacts, system modifications, app autopilot) require unlocking the device first.
 */
object LockScreenSecurityPolicy {

    private val SAFE_ACTIONS = setOf(
        "SPEAK",
        "FLASHLIGHT",
        "TORCH",
        "TIME",
        "CLOCK",
        "WEATHER",
        "BATTERY",
        "DEVICE_STATUS",
        "SYSTEM_INFO",
        "CALCULATOR",
        "UNIT_CONVERTER",
        "CURRENCY_CONVERTER",
        "MEDIA_CONTROL",
        "SCREEN_LOCK",
        "LOCK_SCREEN",
        "SCREEN_UNLOCK",
        "UNLOCK_SCREEN"
    )

    private val SAFE_SWITCHBOARD_ACTIONS = setOf(
        "flashlight_on",
        "flashlight_off",
        "toggle_flashlight",
        "volume_up",
        "volume_down",
        "set_volume",
        "get_status"
    )

    /**
     * Determines whether an action is safe to execute on the secure lock screen.
     */
    fun isActionAllowedOnLockScreen(
        actionType: String,
        params: Map<String, String>,
        riskLevel: RiskLevel = RiskLevel.LOW
    ): Boolean {
        // High or critical risk actions are never allowed on the lock screen
        if (riskLevel == RiskLevel.HIGH || riskLevel == RiskLevel.CRITICAL) {
            return false
        }

        val upper = actionType.trim().uppercase()
        if (upper in SAFE_ACTIONS) {
            return true
        }

        if (upper == "SYSTEM_SWITCHBOARD") {
            val action = params["action"]?.lowercase() ?: ""
            return action in SAFE_SWITCHBOARD_ACTIONS
        }

        if (upper == "WEB_SEARCH") {
            return true
        }

        // WhatsApp, SMS, Telephony, App Autopilot, File operations, notifications, etc. are gated
        return false
    }
}
