package com.jarvis.network

import android.content.Context
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class JarvisLanServerTest {

    @Test
    fun testPairingPinFormat() {
        val server = JarvisLanServer(context = ContextWrapperDummy(), port = 8888)
        val pin = server.pairingPin
        assertNotNull(pin)
        assertEquals(4, pin.length)
        assertTrue("PIN must be 4 digits", pin.all { it.isDigit() })
    }

    @Test
    fun testLocalIpAddressResolution() {
        val server = JarvisLanServer(context = ContextWrapperDummy(), port = 8888)
        val ip = server.getLocalIpAddress()
        assertNotNull(ip)
        assertTrue(ip.isNotEmpty())
    }

    @Test
    fun testServerLifecycleStartStop() {
        val server = JarvisLanServer(context = ContextWrapperDummy(), port = 8889)
        assertFalse(JarvisLanServer.isServerRunning)

        server.start()
        assertTrue(JarvisLanServer.isServerRunning)

        server.stop()
        assertFalse(JarvisLanServer.isServerRunning)
    }

    // Dummy context for unit testing without full Android system service mocks
    private class ContextWrapperDummy : android.content.ContextWrapper(null) {
        override fun getApplicationContext(): Context = this
    }
}
