package com.jarvis.messaging

import com.jarvis.notification.NotificationItem
import com.jarvis.notification.NotificationStore
import com.jarvis.notification.WhatsAppPlatform
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.WhatsAppTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WhatsAppAutomationTest {

    private lateinit var engine: WhatsAppMessagingEngine
    private lateinit var tool: WhatsAppTool
    private lateinit var registry: ToolRegistry

    @Before
    fun setup() {
        NotificationStore.clear()
        engine = WhatsAppMessagingEngine(null)
        tool = WhatsAppTool(context = null, messagingEngine = engine)
        registry = ToolRegistry()
    }

    @Test
    fun testPlatformPackageResolution() {
        assertEquals("com.whatsapp", WhatsAppMessagingEngine.resolveTargetPackage(null))
        assertEquals("com.whatsapp", WhatsAppMessagingEngine.resolveTargetPackage("standard"))
        assertEquals("com.whatsapp", WhatsAppMessagingEngine.resolveTargetPackage("whatsapp"))
        assertEquals("com.whatsapp", WhatsAppMessagingEngine.resolveTargetPackage("other"))

        assertEquals("com.whatsapp.w4b", WhatsAppMessagingEngine.resolveTargetPackage("business"))
        assertEquals("com.whatsapp.w4b", WhatsAppMessagingEngine.resolveTargetPackage("whatsapp business"))
        assertEquals("com.whatsapp.w4b", WhatsAppMessagingEngine.resolveTargetPackage("wa business"))
        assertEquals("com.whatsapp.w4b", WhatsAppMessagingEngine.resolveTargetPackage("w4b"))
        assertEquals("com.whatsapp.w4b", WhatsAppMessagingEngine.resolveTargetPackage("  BUSINESS  "))
    }

    @Test
    fun testMessageTypeClassification() {
        assertEquals("text", engine.classifyMessageType("Hello, how are you?"))
        assertEquals("photo", engine.classifyMessageType("📷 Photo"))
        assertEquals("photo", engine.classifyMessageType("📷"))
        assertEquals("photo", engine.classifyMessageType(""))
        assertEquals("video", engine.classifyMessageType("🎥 Video"))
        assertEquals("audio", engine.classifyMessageType("🎵 Audio file"))
        assertEquals("document", engine.classifyMessageType("📎 Invoice.pdf"))
        assertEquals("location", engine.classifyMessageType("📍 Live location shared"))
        assertEquals("sticker", engine.classifyMessageType("sticker"))
        assertEquals("sticker", engine.classifyMessageType("🏷 Animated sticker"))
        assertEquals("call", engine.classifyMessageType("Missed voice call"))
    }

    @Test
    fun testNotificationItemPlatformDistinction() {
        val standardItem = NotificationItem(
            id = "com.whatsapp:101",
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            title = "Alice",
            text = "Hey there",
            timestamp = System.currentTimeMillis(),
            isOngoing = false,
            platform = WhatsAppPlatform.STANDARD
        )

        val bizItem = NotificationItem(
            id = "com.whatsapp.w4b:102",
            packageName = "com.whatsapp.w4b",
            appName = "WhatsApp Business",
            title = "Tech Store",
            text = "Your order has been confirmed",
            timestamp = System.currentTimeMillis(),
            isOngoing = false,
            platform = WhatsAppPlatform.BUSINESS
        )

        NotificationStore.addNotification(standardItem)
        NotificationStore.addNotification(bizItem)

        // Reading without filter returns both
        val allResult = engine.readRecentWhatsApp(limit = 10)
        assertTrue(allResult.success)
        assertTrue(allResult.message.contains("Alice"))
        assertTrue(allResult.message.contains("Tech Store"))
        assertTrue(allResult.message.contains("[Business]"))

        // Reading with business filter returns only business
        val bizResult = engine.readRecentWhatsApp(limit = 10, platformFilter = "business")
        assertTrue(bizResult.success)
        assertTrue(bizResult.message.contains("Tech Store"))
        assertFalse(bizResult.message.contains("Alice"))

        // Reading with standard filter returns only standard
        val stdResult = engine.readRecentWhatsApp(limit = 10, platformFilter = "standard")
        assertTrue(stdResult.success)
        assertTrue(stdResult.message.contains("Alice"))
        assertFalse(stdResult.message.contains("Tech Store"))
    }

    @Test
    fun testMediaFormatHandlingInNotifications() {
        val mediaItem = NotificationItem(
            id = "com.whatsapp:201",
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            title = "Bob",
            text = "📷 Photo",
            timestamp = System.currentTimeMillis(),
            isOngoing = false,
            platform = WhatsAppPlatform.STANDARD
        )
        NotificationStore.addNotification(mediaItem)

        val res = engine.readRecentWhatsApp(limit = 5)
        assertTrue(res.success)
        assertTrue(res.message.contains("Bob: [photo]"))
    }

    @Test
    fun testInteractiveReplyWorkflow() = runBlocking {
        // Case 1: Reply action with no recipient specified asks who to reply to
        val resNoRecipient = tool.execute(mapOf("action" to "reply"))
        assertTrue(resNoRecipient.success)
        assertTrue(resNoRecipient.message.contains("Who would you like me to reply to?"))

        // Case 2: Reply action with recipient but no message asks what to say
        val resNoMessage = tool.execute(mapOf("action" to "reply", "recipient" to "Alice"))
        assertTrue(resNoMessage.success)
        assertEquals("What would you like me to say to Alice?", resNoMessage.message)

        // Case 3: Reply action with both recipient and message dispatches (simulation mode)
        val resFull = tool.execute(mapOf(
            "action" to "reply",
            "recipient" to "Alice",
            "message" to "On my way",
            "platform" to "business"
        ))
        assertTrue(resFull.success)
        assertTrue(resFull.message.contains("WhatsApp Business message queued for Alice"))
    }

    @Test
    fun testToolRegistryAliasesAndAdaptations() {
        val replyAdapted = registry.adaptParamsForAlias("REPLY_WHATSAPP", mapOf("recipient" to "Charlie"))
        assertEquals("reply", replyAdapted["action"])

        val bizSendAdapted = registry.adaptParamsForAlias("WHATSAPP_BUSINESS_SEND", mapOf("recipient" to "Supplier", "message" to "Stock update"))
        assertEquals("send_message", bizSendAdapted["action"])
        assertEquals("business", bizSendAdapted["platform"])

        val bizReadAdapted = registry.adaptParamsForAlias("WHATSAPP_BUSINESS_READ", emptyMap())
        assertEquals("read_messages", bizReadAdapted["action"])
        assertEquals("business", bizReadAdapted["platform"])

        val bizReplyAdapted = registry.adaptParamsForAlias("WHATSAPP_BUSINESS_REPLY", mapOf("recipient" to "Client"))
        assertEquals("reply", bizReplyAdapted["action"])
        assertEquals("business", bizReplyAdapted["platform"])
    }
}
