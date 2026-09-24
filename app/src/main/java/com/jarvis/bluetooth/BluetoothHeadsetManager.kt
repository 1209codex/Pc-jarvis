package com.jarvis.bluetooth

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Manages Bluetooth headset/earbud lifecycle, connection states, and low-latency
 * SCO (Synchronous Connection-Oriented) audio routing for bidirectional voice.
 */
class BluetoothHeadsetManager(
    private val context: Context?,
    private val audioManager: AudioManager? = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
) {
    private val TAG = "BluetoothHeadsetManager"

    var isHeadsetConnected: Boolean = false
        private set

    var connectedDeviceName: String? = null
        private set

    var isScoActive: Boolean = false
        private set

    var onHeadsetStateChanged: ((connected: Boolean, deviceName: String?) -> Unit)? = null

    private val isRunning = AtomicBoolean(false)

    private var previousAudioMode: Int? = null
    private var previousScoOn: Boolean = false
    private var ownedCommunicationDeviceId: Int? = null

    private fun deviceName(device: BluetoothDevice?, fallback: String): String {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            context?.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) return fallback
        return runCatching { device?.name }.getOrNull() ?: fallback
    }

    private val headsetReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = deviceName(device, "Bluetooth Headset")

                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Bluetooth Headset connected: $name")
                            isHeadsetConnected = true
                            connectedDeviceName = name
                            onHeadsetStateChanged?.invoke(true, name)
                            com.jarvis.controlplane.JarvisEventBus.shared.post(
                                com.jarvis.controlplane.JarvisEvent.BluetoothStateChanged(name, true, isScoActive)
                            )
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "Bluetooth Headset disconnected")
                            queryInitialConnectionState()
                        }
                    }
                }
                BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothProfile.STATE_DISCONNECTED)
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = deviceName(device, "Bluetooth Audio")

                    when (state) {
                        BluetoothProfile.STATE_CONNECTED -> {
                            Log.i(TAG, "Bluetooth A2DP device connected: $name")
                            isHeadsetConnected = true
                            connectedDeviceName = name
                            onHeadsetStateChanged?.invoke(true, name)
                            com.jarvis.controlplane.JarvisEventBus.shared.post(
                                com.jarvis.controlplane.JarvisEvent.BluetoothStateChanged(name, true, isScoActive)
                            )
                        }
                        BluetoothProfile.STATE_DISCONNECTED -> {
                            Log.i(TAG, "Bluetooth A2DP device disconnected: $name")
                            queryInitialConnectionState()
                        }
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = deviceName(device, "Bluetooth Device")
                    Log.i(TAG, "Bluetooth ACL connected: $name (querying audio profiles...)")
                    queryInitialConnectionState()
                }
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    val name = deviceName(device, "Bluetooth Device")
                    Log.i(TAG, "Bluetooth ACL disconnected: $name")
                    queryInitialConnectionState()
                }
                AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                    val scoState = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                    isScoActive = (scoState == AudioManager.SCO_AUDIO_STATE_CONNECTED)
                    Log.d(TAG, "SCO audio state updated: isScoActive=$isScoActive")
                    if (isScoActive) {
                        onHeadsetStateChanged?.invoke(true, connectedDeviceName)
                    }
                    com.jarvis.controlplane.JarvisEventBus.shared.post(
                        com.jarvis.controlplane.JarvisEvent.BluetoothStateChanged(connectedDeviceName, isHeadsetConnected, isScoActive)
                    )
                }
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED -> {
                    val audioState = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, BluetoothHeadset.STATE_AUDIO_DISCONNECTED)
                    val isAudioConnected = (audioState == BluetoothHeadset.STATE_AUDIO_CONNECTED)
                    Log.d(TAG, "Bluetooth Headset audio state changed: isAudioConnected=$isAudioConnected")
                    if (isAudioConnected) {
                        isScoActive = true
                        onHeadsetStateChanged?.invoke(true, connectedDeviceName)
                    }
                }
                Intent.ACTION_HEADSET_PLUG -> {
                    val state = intent.getIntExtra("state", 0)
                    val name = intent.getStringExtra("name") ?: "Wired Headset"
                    if (state == 1) {
                        Log.i(TAG, "Wired Headset plugged in: $name")
                        isHeadsetConnected = true
                        connectedDeviceName = name
                        onHeadsetStateChanged?.invoke(true, name)
                    } else if (state == 0 && connectedDeviceName?.contains("Wired") == true) {
                        Log.i(TAG, "Wired Headset unplugged")
                        queryInitialConnectionState()
                    }
                }
            }
        }
    }

    private var audioDeviceCallback: android.media.AudioDeviceCallback? = null

    private fun isHeadsetDevice(dev: AudioDeviceInfo): Boolean {
        return dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
               dev.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
               dev.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
               dev.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
               dev.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
               dev.type == AudioDeviceInfo.TYPE_HEARING_AID ||
               (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && dev.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
    }

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        val ctx = context ?: return

        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
                addAction(Intent.ACTION_HEADSET_PLUG)
            }
            ctx.registerReceiver(headsetReceiver, filter)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register headset receiver: ${e.message}")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                audioDeviceCallback = object : android.media.AudioDeviceCallback() {
                    override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                        val hasHeadset = addedDevices?.any { isHeadsetDevice(it) } == true
                        if (hasHeadset) {
                            Log.i(TAG, "AudioDeviceCallback: Headset device connected")
                            queryInitialConnectionState()
                        }
                    }

                    override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                        val hasHeadset = removedDevices?.any { isHeadsetDevice(it) } == true
                        if (hasHeadset) {
                            Log.i(TAG, "AudioDeviceCallback: Headset device removed")
                            queryInitialConnectionState()
                        }
                    }
                }
                audioManager?.registerAudioDeviceCallback(audioDeviceCallback, null)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register AudioDeviceCallback: ${e.message}")
            }
        }

        queryInitialConnectionState()
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        val ctx = context
        try {
            ctx?.unregisterReceiver(headsetReceiver)
        } catch (_: Exception) {}

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && audioDeviceCallback != null) {
            try {
                audioManager?.unregisterAudioDeviceCallback(audioDeviceCallback)
            } catch (_: Exception) {}
            audioDeviceCallback = null
        }

        stopScoRouting()
    }

    fun queryInitialConnectionState() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS or AudioManager.GET_DEVICES_INPUTS)
                val headsetDevice = devices.firstOrNull { isHeadsetDevice(it) }

                if (headsetDevice != null) {
                    isHeadsetConnected = true
                    connectedDeviceName = runCatching { headsetDevice.productName?.toString() }.getOrNull() ?: "Connected Headset"
                    Log.i(TAG, "Headset active: $connectedDeviceName (type ${headsetDevice.type})")
                    onHeadsetStateChanged?.invoke(true, connectedDeviceName)
                    return
                } else {
                    if (isHeadsetConnected) {
                        isHeadsetConnected = false
                        connectedDeviceName = null
                        isScoActive = false
                        onHeadsetStateChanged?.invoke(false, null)
                        com.jarvis.controlplane.JarvisEventBus.shared.post(
                            com.jarvis.controlplane.JarvisEvent.BluetoothStateChanged(null, false, false)
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Initial headset check: ${e.message}")
        }
    }

    /**
     * Activates Bluetooth SCO audio routing so speech input and output route directly
     * through the connected Bluetooth earbud / headset.
     */
    @Synchronized
    fun startScoRouting(): Boolean {
        if (!isHeadsetConnected) {
            Log.d(TAG, "startScoRouting skipped: headset not connected")
            return false
        }
        if (isScoActive) {
            return true
        }
        val am = audioManager ?: return false
        return try {
            if (previousAudioMode == null) {
                previousAudioMode = am.mode
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = am.availableCommunicationDevices
                val targetDevice = commDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_HEARING_AID
                }
                if (targetDevice == null) {
                    Log.i(TAG, "No SCO-capable communication device available; leaving Bluetooth media routing untouched")
                    return false
                }
                val success = am.setCommunicationDevice(targetDevice)
                Log.i(TAG, "Set communication device to ${targetDevice.productName} (type ${targetDevice.type}): $success")
                if (!success) {
                    return false
                }
                ownedCommunicationDeviceId = targetDevice.id
            } else {
                try {
                    am.mode = AudioManager.MODE_IN_COMMUNICATION
                } catch (e: Exception) {
                    Log.d(TAG, "Audio mode configuration notice: ${e.message}")
                }
                @Suppress("DEPRECATION")
                previousScoOn = am.isBluetoothScoOn
                @Suppress("DEPRECATION")
                am.startBluetoothSco()
                @Suppress("DEPRECATION")
                am.isBluetoothScoOn = true
            }

            isScoActive = true
            Log.i(TAG, "Activated Bluetooth communication routing")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error starting SCO routing: ${e.message}")
            false
        }
    }

    /**
     * Stops Bluetooth SCO routing and restores default audio path (phone mic / speaker).
     */
    @Synchronized
    fun stopScoRouting() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val ownedId = ownedCommunicationDeviceId
                val currentCommDevice = am.communicationDevice
                if (ownedId != null && currentCommDevice?.id == ownedId) {
                    am.clearCommunicationDevice()
                    Log.i(TAG, "Cleared owned communication device ${currentCommDevice.productName}")
                }
                ownedCommunicationDeviceId = null
            } else {
                try {
                    @Suppress("DEPRECATION")
                    am.isBluetoothScoOn = previousScoOn
                    @Suppress("DEPRECATION")
                    am.stopBluetoothSco()
                } catch (e: Exception) {
                    Log.d(TAG, "stopBluetoothSco notice: ${e.message}")
                }
            }

            previousAudioMode?.let { oldMode ->
                try {
                    am.mode = oldMode
                    Log.i(TAG, "Restored previous audio mode ($oldMode)")
                } catch (e: Exception) {
                    Log.d(TAG, "Audio mode restore notice: ${e.message}")
                }
                previousAudioMode = null
            }

            isScoActive = false
            Log.i(TAG, "Cleared Bluetooth SCO routing")
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping SCO routing: ${e.message}")
        }
    }

    // Helper for testing state changes directly
    fun updateStateForTest(connected: Boolean, name: String?) {
        isHeadsetConnected = connected
        connectedDeviceName = name
        onHeadsetStateChanged?.invoke(connected, name)
    }
}
