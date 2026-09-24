package com.jarvis.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Monitors device hardware sensors:
 * 1. Proximity sensor to detect if phone is inside pocket or face down (suppressing accidental wakeups).
 * 2. Accelerometer to detect face-down flip-to-mute gestures and pick-up actions.
 */
class PocketAndMotionManager(
    private val context: Context?,
    private val sensorManager: SensorManager? = context?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
) : SensorEventListener {

    private val TAG = "PocketAndMotionMgr"

    private val isRunning = AtomicBoolean(false)
    private var proximitySensor: Sensor? = null
    private var accelerometer: Sensor? = null

    @Volatile
    var isPocketed: Boolean = false
        private set

    @Volatile
    var isFaceDown: Boolean = false
        private set

    var onPocketStateChanged: ((isPocketed: Boolean) -> Unit)? = null
    var onFlipMuteTriggered: (() -> Unit)? = null

    private var lastFlipMuteTimestamp = 0L

    init {
        proximitySensor = sensorManager?.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    }

    fun start(): Boolean {
        if (sensorManager == null) {
            Log.w(TAG, "SensorManager not available")
            return false
        }
        if (!isRunning.compareAndSet(false, true)) return true

        try {
            proximitySensor?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
                Log.i(TAG, "Proximity sensor registered: ${it.name}")
            }
            accelerometer?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                Log.i(TAG, "Accelerometer registered: ${it.name}")
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Error registering motion sensors: ${e.message}")
            return false
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            sensorManager?.unregisterListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Error unregistering sensors: ${e.message}")
        }
        isPocketed = false
        isFaceDown = false
        Log.i(TAG, "Pocket and motion sensors stopped")
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || !isRunning.get()) return

        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                val distance = event.values.firstOrNull() ?: return
                val maxRange = proximitySensor?.maximumRange ?: 5.0f
                // If distance < maxRange or distance < 3cm, device is covered/in pocket
                val newPocketState = distance < maxRange && distance < 4.0f
                if (isPocketed != newPocketState) {
                    isPocketed = newPocketState
                    Log.i(TAG, "Pocket state changed: isPocketed=$isPocketed (distance=${distance}cm)")
                    onPocketStateChanged?.invoke(isPocketed)
                }
            }

            Sensor.TYPE_ACCELEROMETER -> {
                val z = event.values.getOrNull(2) ?: return
                // When phone is placed face-down on a flat surface, z-axis gravity is ~ -9.8 m/s²
                val newFaceDown = z < -8.2f
                if (newFaceDown && !isFaceDown) {
                    val now = System.currentTimeMillis()
                    if (now - lastFlipMuteTimestamp > 2000L) {
                        lastFlipMuteTimestamp = now
                        Log.i(TAG, "Flip-to-mute detected (z=$z m/s²)")
                        onFlipMuteTriggered?.invoke()
                    }
                }
                isFaceDown = newFaceDown
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // Testing helper to simulate sensor events in tests
    fun simulatePocketStateForTest(pocketed: Boolean) {
        isPocketed = pocketed
        onPocketStateChanged?.invoke(pocketed)
    }

    fun simulateFlipMuteForTest() {
        isFaceDown = true
        onFlipMuteTriggered?.invoke()
    }
}
