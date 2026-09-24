package com.jarvis

import com.jarvis.messaging.WhatsAppMessagingEngine
import com.jarvis.telecom.ContactInfo
import com.jarvis.telecom.ContactResolver
import com.jarvis.tools.WhatsAppTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WhatsAppDirectMessagingTest {

    private lateinit var contactResolver: ContactResolver
    private lateinit var messagingEngine: WhatsAppMessagingEngine
    private lateinit var whatsAppTool: WhatsAppTool

    @Before
    fun setUp() {
        contactResolver = ContactResolver(null)
        contactResolver.addCachedContact(ContactInfo("c_dollar", "Dollar", "+919876543210", "Mobile"))
        contactResolver.addCachedContact(ContactInfo("c_doller", "Doller", "+919876543210", "Mobile"))

        messagingEngine = WhatsAppMessagingEngine(context = null, contactResolver = contactResolver)
        whatsAppTool = WhatsAppTool(context = null, messagingEngine = messagingEngine)
    }

    @Test
    fun testDirectMessagingContactResolution() = runBlocking {
        val result = messagingEngine.sendDirectMessage("Doller", "Hello")
        assertTrue(result.success)
        assertTrue(result.message.contains("Doller"))
        assertTrue(result.message.contains("Hello"))
        assertTrue(result.message.contains("Simulation mode"))
    }

    @Test
    fun testDirectMessagingRawPhoneNumber() = runBlocking {
        val result = messagingEngine.sendDirectMessage("+919876543210", "Hello from Jarvis")
        assertTrue(result.success)
        assertTrue(result.message.contains("+919876543210"))
    }

    @Test
    fun testWhatsAppToolDirectAction() = runBlocking {
        val params = mapOf(
            "action" to "send_direct_message",
            "recipient" to "Dollar",
            "message" to "Hello Dollar!"
        )
        val result = whatsAppTool.execute(params)
        assertTrue(result.success)
        assertTrue(result.message.contains("Dollar"))
    }

    @Test
    fun testDirectMessageEmptyValidation() = runBlocking {
        val emptyRecipient = messagingEngine.sendDirectMessage("", "Hello")
        assertFalse(emptyRecipient.success)

        val emptyMessage = messagingEngine.sendDirectMessage("Dollar", "")
        assertFalse(emptyMessage.success)
    }

    @Test
    fun testUnresolvableContactDirectMessage() = runBlocking {
        val result = messagingEngine.sendDirectMessage("NonExistentUser12345", "Test message")
        assertFalse(result.success)
        assertTrue(result.message.contains("Could not resolve"))
    }
}
