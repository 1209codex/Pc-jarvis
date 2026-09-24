package com.jarvis

import android.content.Intent
import android.view.KeyEvent
import com.jarvis.bluetooth.BluetoothHeadsetManager
import com.jarvis.bluetooth.HeadsetMediaButtonManager
import com.jarvis.service.JarvisForegroundService
import com.jarvis.service.ServiceStartRequest
import com.jarvis.ui.model.AppSettings
import com.jarvis.wear.WearableCompanionReceiver
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class BluetoothAndWearableCompanionTest {

    @Test
    fun testAppSettingsBluetoothAndWearableDefaults() {
        val settings = AppSettings()
        assertTrue("Bluetooth SCO routing should be enabled by default", settings.bluetoothScoRoutingEnabled)
        assertTrue("Headset hook activation should be enabled by default", settings.headsetHookActivationEnabled)
        assertTrue("Wearable synchronization should be enabled by default", settings.wearableSyncEnabled)
    }

    @Test
    fun testAppSettingsBluetoothAndWearableCustomValues() {
        val custom = AppSettings(
            bluetoothScoRoutingEnabled = false,
            headsetHookActivationEnabled = false,
            wearableSyncEnabled = false
        )
        assertFalse(custom.bluetoothScoRoutingEnabled)
        assertFalse(custom.headsetHookActivationEnabled)
        assertFalse(custom.wearableSyncEnabled)
    }

    @Test
    fun testJsonSerializationDeserializationRoundtrip() {
        val original = AppSettings(
            bluetoothScoRoutingEnabled = false,
            headsetHookActivationEnabled = false,
            wearableSyncEnabled = false
        )

        val json = JSONObject().apply {
            put("bluetoothScoRoutingEnabled", original.bluetoothScoRoutingEnabled)
            put("headsetHookActivationEnabled", original.headsetHookActivationEnabled)
            put("wearableSyncEnabled", original.wearableSyncEnabled)
        }

        val d = AppSettings()
        val restored = AppSettings(
            bluetoothScoRoutingEnabled = json.optBoolean("bluetoothScoRoutingEnabled", d.bluetoothScoRoutingEnabled),
            headsetHookActivationEnabled = json.optBoolean("headsetHookActivationEnabled", d.headsetHookActivationEnabled),
            wearableSyncEnabled = json.optBoolean("wearableSyncEnabled", d.wearableSyncEnabled)
        )

        assertFalse(restored.bluetoothScoRoutingEnabled)
        assertFalse(restored.headsetHookActivationEnabled)
        assertFalse(restored.wearableSyncEnabled)
    }

    @Test
    fun testHeadsetMediaButtonHookTriggersCallback() {
        val triggered = AtomicBoolean(false)
        val manager = HeadsetMediaButtonManager(null) {
            triggered.set(true)
        }

        val consumed = manager.processKeyCode(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN)

        assertTrue("Headset hook down event should be consumed", consumed)
        assertTrue("Headset hook should invoke trigger callback", triggered.get())
    }

    @Test
    fun testHeadsetMediaButtonPlayPauseTriggersCallback() {
        val triggered = AtomicBoolean(false)
        val manager = HeadsetMediaButtonManager(null) {
            triggered.set(true)
        }

        val consumed = manager.processKeyCode(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.ACTION_DOWN)

        assertTrue("Play/Pause down event should be consumed", consumed)
        assertTrue("Play/Pause should invoke trigger callback", triggered.get())
    }

    @Test
    fun testHeadsetMediaButtonActionUpIgnored() {
        val triggered = AtomicBoolean(false)
        val manager = HeadsetMediaButtonManager(null) {
            triggered.set(true)
        }

        val consumed = manager.processKeyCode(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_UP)

        assertFalse("Action UP event should not be consumed", consumed)
        assertFalse("Action UP should not invoke callback", triggered.get())
    }

    @Test
    fun testHeadsetMediaButtonDisabledSetting() {
        val triggered = AtomicBoolean(false)
        val manager = HeadsetMediaButtonManager(null) {
            triggered.set(true)
        }
        manager.isHookActivationEnabled = false

        val consumed = manager.processKeyCode(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN)

        assertFalse("Event should not be consumed when hook activation is disabled", consumed)
        assertFalse("Callback should not fire when disabled", triggered.get())
    }

    @Test
    fun testHeadsetMediaButtonDebounceProtection() {
        var currentTime = 1000L
        val triggerCount = AtomicInteger(0)
        val manager = HeadsetMediaButtonManager(null, timeProvider = { currentTime }) {
            triggerCount.incrementAndGet()
        }

        val consumed1 = manager.processKeyCode(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN)
        // Immediate second event within 400ms debounce
        currentTime = 1100L
        val consumed2 = manager.processKeyCode(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN)

        assertTrue(consumed1)
        assertTrue(consumed2)
        assertEquals("Rapid back-to-back key events within 400ms should be debounced to 1 trigger", 1, triggerCount.get())

        // Event after debounce window (450ms later)
        currentTime = 1600L
        val consumed3 = manager.processKeyCode(KeyEvent.KEYCODE_HEADSETHOOK, KeyEvent.ACTION_DOWN)
        assertTrue(consumed3)
        assertEquals("Event after debounce expires should trigger callback", 2, triggerCount.get())
    }

    @Test
    fun testWearableCompanionCommandParsing() {
        val command = WearableCompanionReceiver.parseCommand(
            WearableCompanionReceiver.ACTION_WEAR_COMMAND,
            "turn on the flashlight"
        )
        assertEquals("turn on the flashlight", command)

        // Blank command
        assertNull(
            WearableCompanionReceiver.parseCommand(
                WearableCompanionReceiver.ACTION_WEAR_COMMAND,
                "   "
            )
        )

        // Different action
        assertNull(
            WearableCompanionReceiver.parseCommand(
                WearableCompanionReceiver.ACTION_WEAR_WAKE,
                "some command"
            )
        )
    }

    @Test
    fun testWearableBroadcastsCarryActionsIntoServiceStartup() {
        assertEquals(
            ServiceStartRequest(JarvisForegroundService.ACTION_MANUAL_LISTEN),
            WearableCompanionReceiver.startRequestForBroadcast(WearableCompanionReceiver.ACTION_WEAR_WAKE, null)
        )
        assertEquals(
            ServiceStartRequest(JarvisForegroundService.ACTION_EXECUTE_COMMAND, "turn on the flashlight"),
            WearableCompanionReceiver.startRequestForBroadcast(WearableCompanionReceiver.ACTION_WEAR_COMMAND, " turn on the flashlight ")
        )
        assertEquals(
            null,
            WearableCompanionReceiver.startRequestForBroadcast(WearableCompanionReceiver.ACTION_WEAR_STOP, null)
        )
        assertEquals(
            ServiceStartRequest(JarvisForegroundService.ACTION_EXECUTE_COMMAND, "run diagnostics"),
            JarvisForegroundService.commandStartRequest(" run diagnostics ")
        )
    }

    @Test
    fun testBluetoothHeadsetManagerConnectionStateUpdates() {
        var callbackName: String? = null
        var callbackConnected = false

        val manager = BluetoothHeadsetManager(null, null)
        manager.onHeadsetStateChanged = { connected, name ->
            callbackConnected = connected
            callbackName = name
        }

        // Test connected
        manager.updateStateForTest(true, "Sony WH-1000XM5")
        assertTrue(manager.isHeadsetConnected)
        assertEquals("Sony WH-1000XM5", manager.connectedDeviceName)
        assertTrue(callbackConnected)
        assertEquals("Sony WH-1000XM5", callbackName)

        // Test disconnected
        manager.updateStateForTest(false, null)
        assertFalse(manager.isHeadsetConnected)
        assertNull(manager.connectedDeviceName)
        assertFalse(callbackConnected)
        assertNull(callbackName)
    }
}
