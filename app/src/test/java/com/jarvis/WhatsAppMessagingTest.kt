package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.SkillContext
import com.jarvis.agent.skills.WhatsAppMessagingSkill
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.messaging.AutoReplyMode
import com.jarvis.messaging.WhatsAppMessagingEngine
import com.jarvis.notification.NotificationItem
import com.jarvis.notification.NotificationStore
import com.jarvis.telecom.ContactInfo
import com.jarvis.telecom.ContactResolver
import com.jarvis.tools.WhatsAppTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class WhatsAppMessagingTest {

    private lateinit var contactResolver: ContactResolver
    private lateinit var messagingEngine: WhatsAppMessagingEngine
    private lateinit var whatsAppTool: WhatsAppTool
    private lateinit var messagingSkill: WhatsAppMessagingSkill

    private fun createContext(goal: String = ""): SkillContext =
        SkillContext(goal = goal, workingMemory = AgentWorkingMemory(goal = goal))

    @Before
    fun setUp() {
        NotificationStore.clear()
        contactResolver = ContactResolver(null)
        contactResolver.addCachedContact(ContactInfo("c_rahul", "Rahul", "+919876543210", "Mobile"))
        contactResolver.addCachedContact(ContactInfo("c_amit", "Amit", "+919876543211", "Mobile"))

        messagingEngine = WhatsAppMessagingEngine(context = null, contactResolver = contactResolver)
        whatsAppTool = WhatsAppTool(context = null, messagingEngine = messagingEngine)
        messagingSkill = WhatsAppMessagingSkill()
    }

    @Test
    fun testAutoReplyModeTransitions() {
        assertEquals(AutoReplyMode.OFF, messagingEngine.autoReplyMode)
        assertFalse(messagingEngine.isAutoReplyActive())

        messagingEngine.setAutoReplyMode(AutoReplyMode.DRIVING)
        assertEquals(AutoReplyMode.DRIVING, messagingEngine.autoReplyMode)
        assertTrue(messagingEngine.isAutoReplyActive())

        messagingEngine.setAutoReplyMode(AutoReplyMode.MEETING)
        assertEquals(AutoReplyMode.MEETING, messagingEngine.autoReplyMode)

        messagingEngine.setAutoReplyMode(AutoReplyMode.CUSTOM, "Be right back, coding with Jarvis.")
        assertEquals(AutoReplyMode.CUSTOM, messagingEngine.autoReplyMode)
        assertEquals("Be right back, coding with Jarvis.", messagingEngine.customAutoReplyTemplate)

        messagingEngine.setAutoReplyMode(AutoReplyMode.OFF)
        assertEquals(AutoReplyMode.OFF, messagingEngine.autoReplyMode)
        assertFalse(messagingEngine.isAutoReplyActive())
    }

    @Test
    fun testReplyActionRegistrationAndRemoval() {
        messagingEngine.registerReplyAction("wa_101", "Rahul", null)
        // Ensure action was registered (null Action is safe)
        messagingEngine.removeReplyAction("wa_101")
        assertFalse(messagingEngine.sendDirectNotificationReply("Rahul", "hello"))
    }

    @Test
    fun testReadRecentWhatsAppNotifications() {
        // Empty state
        val emptyResult = messagingEngine.readRecentWhatsApp()
        assertTrue(emptyResult.success)
        assertTrue(emptyResult.message.contains("no unread WhatsApp messages"))

        // Add dummy notifications
        NotificationStore.addNotification(
            NotificationItem(
                id = "com.whatsapp:1",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Rahul",
                text = "Are we meeting today?",
                timestamp = System.currentTimeMillis() - 60000,
                isOngoing = false
            )
        )
        NotificationStore.addNotification(
            NotificationItem(
                id = "com.whatsapp:2",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Rahul",
                text = "Let me know the time",
                timestamp = System.currentTimeMillis() - 30000,
                isOngoing = false
            )
        )
        NotificationStore.addNotification(
            NotificationItem(
                id = "com.whatsapp:3",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Amit",
                text = "File sent over email",
                timestamp = System.currentTimeMillis() - 10000,
                isOngoing = false
            )
        )

        val readResult = messagingEngine.readRecentWhatsApp()
        assertTrue(readResult.success)
        assertTrue(readResult.message.contains("recent WhatsApp message"))
        assertTrue(readResult.message.contains("Rahul"))
        assertTrue(readResult.message.contains("Amit"))

        // Test with sender filter
        val rahulOnly = messagingEngine.readRecentWhatsApp(senderFilter = "Rahul")
        assertTrue(rahulOnly.success)
        assertTrue(rahulOnly.message.contains("Rahul"))
        assertFalse(rahulOnly.message.contains("Amit"))
    }

    @Test
    fun testSendMessageValidation() = runBlocking {
        // Missing parameters
        val fail1 = messagingEngine.sendMessage("", "Hello")
        assertFalse(fail1.success)

        val fail2 = messagingEngine.sendMessage("Rahul", "")
        assertFalse(fail2.success)

        // With simulation context (context = null)
        val successRes = messagingEngine.sendMessage("Rahul", "I'll be there in 10 minutes")
        assertTrue(successRes.success)
        assertTrue(successRes.message.contains("Rahul"))
    }

    @Test
    fun testWhatsAppMessagingSkillTriggers() = runBlocking {
        assertTrue(messagingSkill.canHandle("read my whatsapp messages", createContext("read my whatsapp messages")))
        assertTrue(messagingSkill.canHandle("whatsapp padho", createContext("whatsapp padho")))
        assertTrue(messagingSkill.canHandle("koi whatsapp message aaya kya", createContext("koi whatsapp message aaya kya")))
        assertTrue(messagingSkill.canHandle("turn on whatsapp auto reply", createContext("turn on whatsapp auto reply")))
        assertTrue(messagingSkill.canHandle("auto reply band karo", createContext("auto reply band karo")))
        assertTrue(messagingSkill.canHandle("reply to Rahul saying I am on the way", createContext("reply to Rahul saying I am on the way")))
        assertTrue(messagingSkill.canHandle("Rahul ko WhatsApp bhejo ki main late hunga", createContext("Rahul ko WhatsApp bhejo ki main late hunga")))

        // Non-whatsapp social shouldn't trigger
        assertFalse(messagingSkill.canHandle("open instagram", createContext("open instagram")))
    }

    @Test
    fun testWhatsAppMessagingSkillExecution() = runBlocking {
        // Read messages
        val readSkill = messagingSkill.execute("read my whatsapp messages", createContext("read my whatsapp messages"))
        assertTrue(readSkill.handled)
        assertEquals("WHATSAPP", readSkill.proposedAction?.type)
        assertEquals("read_messages", readSkill.proposedAction?.params?.get("action"))

        // Auto-reply
        val autoReplySkill = messagingSkill.execute("turn on whatsapp auto reply driving", createContext("turn on whatsapp auto reply driving"))
        assertTrue(autoReplySkill.handled)
        assertEquals("WHATSAPP", autoReplySkill.proposedAction?.type)
        assertEquals("auto_reply", autoReplySkill.proposedAction?.params?.get("action"))
        assertEquals("driving", autoReplySkill.proposedAction?.params?.get("mode"))

        // Direct Quick Reply
        val replySkill = messagingSkill.execute("reply to Rahul saying call you soon", createContext("reply to Rahul saying call you soon"))
        assertTrue(replySkill.handled)
        assertEquals("WHATSAPP", replySkill.proposedAction?.type)
        assertEquals("Rahul", replySkill.proposedAction?.params?.get("recipient"))
        assertEquals("call you soon", replySkill.proposedAction?.params?.get("message"))
    }

    @Test
    fun testIntentResolverWhatsAppFastPath() {
        val res1 = IntentResolver.resolve("read whatsapp")
        assertNotNull(res1)
        assertEquals(AssistantIntent.WHATSAPP_READ, res1?.intent)

        val res2 = IntentResolver.resolve("whatsapp padho")
        assertNotNull(res2)
        assertEquals(AssistantIntent.WHATSAPP_READ, res2?.intent)

        val res3 = IntentResolver.resolve("koi whatsapp aaya")
        assertNotNull(res3)
        assertEquals(AssistantIntent.WHATSAPP_READ, res3?.intent)

        val res4 = IntentResolver.resolve("whatsapp auto reply driving")
        assertNotNull(res4)
        assertEquals(AssistantIntent.WHATSAPP_AUTO_REPLY, res4?.intent)
        assertEquals("driving", res4?.params?.get("mode"))

        val res5 = IntentResolver.resolve("auto reply band")
        assertNotNull(res5)
        assertEquals(AssistantIntent.WHATSAPP_AUTO_REPLY, res5?.intent)
        assertEquals("off", res5?.params?.get("mode"))

        val res6 = IntentResolver.resolve("Doller ko hello send karo whatsapp pe")
        assertNotNull(res6)
        assertEquals(AssistantIntent.WHATSAPP_SEND, res6?.intent)
        assertEquals("doller", res6?.params?.get("recipient"))
        assertEquals("hello", res6?.params?.get("message"))

        val res7 = IntentResolver.resolve("call Doller")
        assertNotNull(res7)
        assertEquals(AssistantIntent.TELEPHONY_CALL, res7?.intent)
        assertEquals("doller", res7?.params?.get("recipient"))

        val res8 = IntentResolver.resolve("open youtube and directly play song")
        assertNotNull(res8)
        assertEquals(AssistantIntent.COMPOUND_TASK, res8?.intent)
        assertEquals(2, res8?.directPlan?.actions?.size)

        val res9 = IntentResolver.resolve("play song on youtube")
        assertNotNull(res9)
        assertEquals(AssistantIntent.PLAY_MEDIA, res9?.intent)
        assertEquals("trending songs", res9?.params?.get("query"))

        val incomplete = IntentResolver.resolve("send message to Rahul on whatsapp")
        assertTrue("An incomplete message request must be clarified", incomplete == null || incomplete.directPlan == null)
    }

    @Test
    fun testWhatsAppToolExecution() = runBlocking {
        // Auto reply config
        val toolResAutoReply = whatsAppTool.execute(mapOf("action" to "auto_reply", "mode" to "meeting"))
        assertTrue(toolResAutoReply.success)
        assertEquals(AutoReplyMode.MEETING, messagingEngine.autoReplyMode)

        // Read messages
        val toolResRead = whatsAppTool.execute(mapOf("action" to "read_messages"))
        assertTrue(toolResRead.success)

        // Send message simulation
        val toolResSend = whatsAppTool.execute(mapOf("recipient" to "Amit", "message" to "Meeting at 4pm"))
        assertTrue(toolResSend.success)
    }
}
