package com.jarvis

import com.jarvis.messaging.AutoReplyMode
import com.jarvis.messaging.WhatsAppMessagingEngine
import com.jarvis.telecom.ContactInfo
import com.jarvis.telecom.ContactResolver
import com.jarvis.tools.WhatsAppTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WhatsAppBackgroundMacroTest {

    private lateinit var contactResolver: ContactResolver
    private lateinit var messagingEngine: WhatsAppMessagingEngine
    private lateinit var whatsAppTool: WhatsAppTool

    @Before
    fun setUp() {
        contactResolver = ContactResolver(null)
        contactResolver.addCachedContact(ContactInfo("c_rahul", "Rahul Sharma", "+919876543210", "Mobile"))
        contactResolver.addCachedContact(ContactInfo("c_alex", "Alex Mercer", "+14155552671", "Work"))

        messagingEngine = WhatsAppMessagingEngine(context = null, contactResolver = contactResolver)
        whatsAppTool = WhatsAppTool(context = null, messagingEngine = messagingEngine)
    }

    @Test
    fun testAutoReplyModesAndTemplates() {
        messagingEngine.setAutoReplyMode(AutoReplyMode.DRIVING)
        assertEquals(AutoReplyMode.DRIVING, messagingEngine.autoReplyMode)
        assertTrue(messagingEngine.isAutoReplyActive())

        messagingEngine.setAutoReplyMode(AutoReplyMode.CUSTOM, "Custom busy template")
        assertEquals(AutoReplyMode.CUSTOM, messagingEngine.autoReplyMode)
        assertEquals("Custom busy template", messagingEngine.customAutoReplyTemplate)

        messagingEngine.setAutoReplyMode(AutoReplyMode.OFF)
        assertEquals(AutoReplyMode.OFF, messagingEngine.autoReplyMode)
        assertFalse(messagingEngine.isAutoReplyActive())
    }

    @Test
    fun testDirectMessageWithResolvedContact() = runBlocking {
        val result = messagingEngine.sendDirectMessage("Rahul Sharma", "Hey Rahul, see you at 5")
        assertTrue("Message should succeed in simulation mode", result.success)
        assertTrue("Message should include recipient", result.message.contains("Rahul Sharma"))
        assertTrue("Message should include phone number", result.message.contains("919876543210"))
    }

    @Test
    fun testDirectMessageWithRawPhoneNumber() = runBlocking {
        val result = messagingEngine.sendDirectMessage("+14155552671", "Testing WhatsApp background macro")
        assertTrue("Message should succeed", result.success)
        assertTrue("Message should contain phone number", result.message.contains("+14155552671") || result.message.contains("14155552671"))
    }

    @Test
    fun testSendMessageFallbackValidation() = runBlocking {
        val emptyRecipient = messagingEngine.sendMessage("", "Hello")
        assertFalse("Empty recipient should fail", emptyRecipient.success)

        val emptyMsg = messagingEngine.sendMessage("Rahul", "")
        assertFalse("Empty message should fail", emptyMsg.success)
    }

    @Test
    fun testWhatsAppToolAutoReplyAction() = runBlocking {
        val params = mapOf(
            "action" to "auto_reply",
            "mode" to "meeting"
        )
        val result = whatsAppTool.execute(params)
        assertTrue(result.success)
        assertEquals(AutoReplyMode.MEETING, messagingEngine.autoReplyMode)
        assertTrue(result.message.contains("MEETING"))
    }

    @Test
    fun testWhatsAppToolReadUnreadAction() = runBlocking {
        val params = mapOf(
            "action" to "read_messages",
            "limit" to "3"
        )
        val result = whatsAppTool.execute(params)
        assertTrue(result.success)
    }
}
