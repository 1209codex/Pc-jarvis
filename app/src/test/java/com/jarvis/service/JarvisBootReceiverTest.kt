package com.jarvis.service

import android.content.Intent
import org.junit.Assert.*
import org.junit.Test

class JarvisBootReceiverTest {

    @Test
    fun testActionConstants() {
        assertEquals("android.intent.action.QUICKBOOT_POWERON", JarvisBootReceiver.ACTION_QUICKBOOT_POWERON)
        assertEquals("com.htc.intent.action.QUICKBOOT_POWERON", JarvisBootReceiver.ACTION_HTC_QUICKBOOT)
    }

    @Test
    fun testReceiverInstantiation() {
        val receiver = JarvisBootReceiver()
        assertNotNull(receiver)
    }

    @Test
    fun testIsSupportedAction_bootCompleted() {
        assertTrue(JarvisBootReceiver.isSupportedAction(Intent.ACTION_BOOT_COMPLETED))
        assertTrue(JarvisBootReceiver.isSupportedAction(Intent.ACTION_MY_PACKAGE_REPLACED))
        assertTrue(JarvisBootReceiver.isSupportedAction(JarvisBootReceiver.ACTION_QUICKBOOT_POWERON))
        assertTrue(JarvisBootReceiver.isSupportedAction(JarvisBootReceiver.ACTION_HTC_QUICKBOOT))
    }

    @Test
    fun testIsSupportedAction_unsupported() {
        assertFalse(JarvisBootReceiver.isSupportedAction(null))
        assertFalse(JarvisBootReceiver.isSupportedAction("android.intent.action.BATTERY_LOW"))
        assertFalse(JarvisBootReceiver.isSupportedAction("com.example.UNRECOGNIZED_ACTION"))
    }
}
