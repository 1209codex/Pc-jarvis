package com.jarvis

import com.jarvis.sensors.PocketAndMotionManager
import org.junit.Assert.*
import org.junit.Test

class PocketAndMotionSensorTest {

    @Test
    fun testPocketStateSimulationAndCallback() {
        val manager = PocketAndMotionManager(null, null)
        var callbackInvoked = false
        var lastState = false

        manager.onPocketStateChanged = { pocketed ->
            callbackInvoked = true
            lastState = pocketed
        }

        assertFalse(manager.isPocketed)

        manager.simulatePocketStateForTest(true)
        assertTrue(manager.isPocketed)
        assertTrue(callbackInvoked)
        assertTrue(lastState)

        manager.simulatePocketStateForTest(false)
        assertFalse(manager.isPocketed)
        assertFalse(lastState)
    }

    @Test
    fun testFlipMuteSimulation() {
        val manager = PocketAndMotionManager(null, null)
        var flipMuted = false

        manager.onFlipMuteTriggered = {
            flipMuted = true
        }

        manager.simulateFlipMuteForTest()
        assertTrue(manager.isFaceDown)
        assertTrue(flipMuted)
    }
}
