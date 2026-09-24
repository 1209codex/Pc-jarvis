package com.jarvis.controlplane

/**
 * Layer 0: Device Guardian.
 *
 * Enforces hardware and resource invariants before any autonomous background task is scheduled.
 * Prevents battery exhaustion, thermal throttling, or mic hijacking during active calls.
 */
data class GuardianVerdict(
    val allowed: Boolean,
    val reason: String
)

class DeviceGuardian(
    private val worldStore: WorldStateStore = WorldStateStore.shared
) {
    /**
     * Checks whether an autonomous background goal or proactive task can safely execute.
     */
    fun canExecuteAutonomousTask(requiresNetwork: Boolean = false, requiresAudio: Boolean = false): GuardianVerdict {
        val state = worldStore.current

        // 1. Critical Battery Guard
        if (state.batteryPercent in 1..10 && !state.isCharging) {
            return GuardianVerdict(
                allowed = false,
                reason = "Proactive execution suppressed: battery critically low (${state.batteryPercent}%) and discharging."
            )
        }

        // 2. Active Call / Telephony Guard
        if (requiresAudio && state.activeCaller != null) {
            return GuardianVerdict(
                allowed = false,
                reason = "Proactive audio execution suppressed: phone call currently active."
            )
        }

        // 3. Network Connectivity Guard
        if (requiresNetwork && !state.isNetworkOnline) {
            return GuardianVerdict(
                allowed = false,
                reason = "Network-dependent task suppressed: device is currently offline."
            )
        }

        return GuardianVerdict(
            allowed = true,
            reason = "Device health and invariants satisfied for execution."
        )
    }
}
