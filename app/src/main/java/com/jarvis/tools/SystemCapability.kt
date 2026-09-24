package com.jarvis.tools

/**
 * Pure capability matrix for device toggles. Codifies what Android allows an
 * app to change on each API level, so SystemSwitchboardTool can be honest:
 * try real toggles, verify, and open the right Settings panel only when the
 * OS genuinely blocks programmatic change.
 */
data class CapabilityDecision(
    val canAttempt: Boolean,
    val panelAction: String? = null,
    val reason: String? = null
)

const val API_ANDROID_13 = 33
const val API_ANDROID_14 = 34

object SystemCapability {

    val ACTION_WIFI_SETTINGS = android.provider.Settings.ACTION_WIFI_SETTINGS
    val ACTION_BLUETOOTH_SETTINGS = android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
    val ACTION_NOTIFICATION_POLICY_ACCESS = android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
    val ACTION_WIRELESS_SETTINGS = android.provider.Settings.ACTION_WIRELESS_SETTINGS
    val ACTION_FLASHLIGHT_SETTINGS = "android.settings.FLASHLIGHT_SETTINGS"

    /** API >= 33 removed WifiManager#setWifiEnabled for third-party apps. */
    fun wifiToggle(api: Int): CapabilityDecision =
        if (api < API_ANDROID_13) CapabilityDecision(canAttempt = true)
        else CapabilityDecision(
            canAttempt = false,
            panelAction = ACTION_WIFI_SETTINGS,
            reason = "Android 13+ no longer lets apps turn Wi-Fi on or off."
        )

    /** Hotspot toggling needs system privileges on every version. */
    fun hotspotToggle(@Suppress("UNUSED_PARAMETER") api: Int): CapabilityDecision = CapabilityDecision(
        canAttempt = false,
        panelAction = ACTION_WIRELESS_SETTINGS,
        reason = "Apps can't switch the hotspot programmatically; open the tethering settings to change it."
    )

    fun bluetoothToggle(api: Int): CapabilityDecision =
        if (api < API_ANDROID_13) CapabilityDecision(canAttempt = true)
        else CapabilityDecision(
            canAttempt = true,
            reason = "BluetoothAdapter.enable() is deprecated; it may silently no-op and will be verified."
        )

    fun dndToggle(api: Int): CapabilityDecision =
        if (api >= API_ANDROID_14) CapabilityDecision(
            canAttempt = false,
            panelAction = ACTION_NOTIFICATION_POLICY_ACCESS,
            reason = "Android 14 requires special access for Do Not Disturb; open settings to allow it."
        )
        else CapabilityDecision(canAttempt = true)

    fun flashlightToggle(@Suppress("UNUSED_PARAMETER") api: Int): CapabilityDecision = CapabilityDecision(canAttempt = true)

    fun decide(apiLevel: Int, action: String): CapabilityDecision = when (action) {
        "wifi" -> wifiToggle(apiLevel)
        "hotspot" -> hotspotToggle(apiLevel)
        "bluetooth", "bt" -> bluetoothToggle(apiLevel)
        "dnd" -> dndToggle(apiLevel)
        "led", "flashlight" -> flashlightToggle(apiLevel)
        else -> CapabilityDecision(canAttempt = false, reason = "Unknown toggle '$action'.")
    }
}