package com.jarvis.overlay

import android.content.Context
import com.jarvis.ui.JarvisOrbView
import com.jarvis.voice.VoiceState
import org.junit.Assert.*
import org.junit.Test

class JarvisFloatingOverlayTest {

    @Test
    fun testSnapTargetLeftEdge() {
        val screenWidth = 1080
        val orbWidth = 200
        val margin = 24

        // Orb on left side (x = 100, center = 200 < 540)
        val snapLeft = JarvisFloatingOverlayService.calculateSnapTargetX(
            currentX = 100,
            orbWidthPx = orbWidth,
            screenWidthPx = screenWidth,
            marginPx = margin
        )
        assertEquals(24, snapLeft)
    }

    @Test
    fun testSnapTargetRightEdge() {
        val screenWidth = 1080
        val orbWidth = 200
        val margin = 24

        // Orb on right side (x = 800, center = 900 > 540)
        val snapRight = JarvisFloatingOverlayService.calculateSnapTargetX(
            currentX = 800,
            orbWidthPx = orbWidth,
            screenWidthPx = screenWidth,
            marginPx = margin
        )
        // Expected: 1080 - 200 - 24 = 856
        assertEquals(856, snapRight)
    }

    @Test
    fun testOverlayActionConstants() {
        assertEquals("com.jarvis.action.START_OVERLAY", JarvisFloatingOverlayService.ACTION_START)
        assertEquals("com.jarvis.action.STOP_OVERLAY", JarvisFloatingOverlayService.ACTION_STOP)
        assertEquals("com.jarvis.action.OVERLAY_TALK", JarvisFloatingOverlayService.ACTION_TALK)
    }

    @Test
    fun testOrbViewStateStringMapping() {
        val dummyContext = object : android.content.ContextWrapper(null) {
            override fun getApplicationContext(): Context = this
        }
        val orb = JarvisOrbView(dummyContext)

        orb.setState("idle")
        assertEquals(VoiceState.IDLE, orb.getCurrentState())
        assertEquals("idle", orb.getStateName())

        orb.setState("listening")
        assertEquals(VoiceState.LISTENING, orb.getCurrentState())
        assertEquals("listening", orb.getStateName())

        orb.setState("thinking")
        assertEquals(VoiceState.THINKING, orb.getCurrentState())

        orb.setState("working")
        assertEquals(VoiceState.THINKING, orb.getCurrentState())

        orb.setState("speaking")
        assertEquals(VoiceState.SPEAKING, orb.getCurrentState())
        assertEquals("speaking", orb.getStateName())

        orb.setState("error")
        assertEquals(VoiceState.ERROR, orb.getCurrentState())
        assertEquals("error", orb.getStateName())

        orb.setState("unknown_state")
        assertEquals(VoiceState.IDLE, orb.getCurrentState())
    }
}
