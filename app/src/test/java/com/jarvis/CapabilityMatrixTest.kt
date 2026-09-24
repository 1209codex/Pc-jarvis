package com.jarvis

import com.jarvis.tools.SystemCapability
import com.jarvis.tools.API_ANDROID_13
import com.jarvis.tools.API_ANDROID_14
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Surface the API constants used above.
@Suppress("unused")
private val api13 = API_ANDROID_13
private val api14 = API_ANDROID_14

class CapabilityMatrixTest {

    @Test
    fun wifiOpenableBelowAndroid13() {
        assertTrue(SystemCapability.wifiToggle(32).canAttempt)
        assertTrue(SystemCapability.wifiToggle(API_ANDROID_13 - 1).canAttempt)
    }

    @Test
    fun wifiBlockedOnAndroid13PlusWithPanel() {
        val d = SystemCapability.wifiToggle(API_ANDROID_13)
        assertFalse(d.canAttempt)
        assertNotNull(d.panelAction)
        assertEquals("android.settings.WIFI_SETTINGS", d.panelAction)
        assertFalse(d.reason.isNullOrBlank())
    }

    @Test
    fun hotspotAlwaysOpensTetheringPanel() {
        val d = SystemCapability.hotspotToggle(API_ANDROID_13)
        assertFalse(d.canAttempt)
        assertEquals("android.settings.WIRELESS_SETTINGS", d.panelAction)
    }

    @Test
    fun bluetoothAttemptedButRequiresVerification() {
        val d = SystemCapability.bluetoothToggle(API_ANDROID_13)
        assertTrue(d.canAttempt)
        assertNotNull(d.reason)
    }

    @Test
    fun dndBlockedOnAndroid14NeedsSpecialAccess() {
        val d = SystemCapability.dndToggle(API_ANDROID_14)
        assertFalse(d.canAttempt)
        assertEquals(SystemCapability.ACTION_NOTIFICATION_POLICY_ACCESS, d.panelAction)
    }

    @Test
    fun dndAttemptedBefore14() {
        assertTrue(SystemCapability.dndToggle(33).canAttempt)
    }

    @Test
    fun flashlightAlwaysAttempted() {
        assertTrue(SystemCapability.flashlightToggle(API_ANDROID_13).canAttempt)
    }

    @Test
    fun unknownToggleIsHonestlyUnknown() {
        val d = SystemCapability.decide(35, "teleport")
        assertFalse(d.canAttempt)
        assertNotNull(d.reason)
    }

    @Test
    fun decideRoutesToCorrectMatrix() {
        assertFalse(SystemCapability.decide(API_ANDROID_13, "wifi").canAttempt)
        assertFalse(SystemCapability.decide(API_ANDROID_13, "hotspot").canAttempt)
        assertTrue(SystemCapability.decide(API_ANDROID_13, "dnd").canAttempt)
    }
}