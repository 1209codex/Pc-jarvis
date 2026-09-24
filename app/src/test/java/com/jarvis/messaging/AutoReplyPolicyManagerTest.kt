package com.jarvis.messaging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AutoReplyPolicyManagerTest {

    @Before
    fun setup() {
        AutoReplyPolicyManager.clearCooldowns()
        AutoReplyPolicyManager.setMode(AutoReplyMode.OFF)
    }

    @Test
    fun testModeSwitchingAndTemplates() {
        AutoReplyPolicyManager.setMode(AutoReplyMode.DRIVING)
        assertTrue(AutoReplyPolicyManager.isEnabled())
        assertTrue(AutoReplyPolicyManager.getTemplateForCurrentMode().contains("driving", ignoreCase = true))

        AutoReplyPolicyManager.setMode(AutoReplyMode.MEETING)
        assertTrue(AutoReplyPolicyManager.getTemplateForCurrentMode().contains("meeting", ignoreCase = true))

        AutoReplyPolicyManager.setMode(AutoReplyMode.CUSTOM, "Custom busy note from testing")
        assertEquals("Custom busy note from testing", AutoReplyPolicyManager.getTemplateForCurrentMode())

        AutoReplyPolicyManager.setMode(AutoReplyMode.OFF)
        assertFalse(AutoReplyPolicyManager.isEnabled())
    }

    @Test
    fun testCooldownEnforcement() {
        AutoReplyPolicyManager.setMode(AutoReplyMode.DRIVING)
        val contact = "+919876543210"

        // First message allowed
        assertTrue(AutoReplyPolicyManager.canReplyTo(contact))

        // Record reply
        AutoReplyPolicyManager.recordReplySent(contact, "WHATSAPP", "Driving note")

        // Immediate subsequent message should be blocked by cooldown
        assertFalse(AutoReplyPolicyManager.canReplyTo(contact))

        // Different contact should still be allowed
        assertTrue(AutoReplyPolicyManager.canReplyTo("+919876543211"))
    }

    @Test
    fun testHistoryRecording() {
        AutoReplyPolicyManager.setMode(AutoReplyMode.BUSY)
        AutoReplyPolicyManager.recordReplySent("Alice", "SMS", "Occupied note")
        AutoReplyPolicyManager.recordReplySent("Bob", "WHATSAPP", "Occupied note")

        val history = AutoReplyPolicyManager.getHistory()
        assertEquals(2, history.size)
        assertEquals("bob", history[0].recipient)
        assertEquals("WHATSAPP", history[0].channel)
        assertEquals("alice", history[1].recipient)
        assertEquals("SMS", history[1].channel)
    }
}
