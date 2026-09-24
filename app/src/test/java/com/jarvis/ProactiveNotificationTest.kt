package com.jarvis

import com.jarvis.agent.skills.SkillContext
import com.jarvis.agent.skills.ProactiveSkill
import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.device.BatteryMonitor
import com.jarvis.device.BatteryState
import com.jarvis.notification.NotificationItem
import com.jarvis.notification.NotificationStore
import com.jarvis.tools.ToolRegistry
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class ProactiveNotificationTest {

    private val skillContext = SkillContext(
        goal = "proactive query",
        workingMemory = com.jarvis.agent.AgentWorkingMemory("proactive query")
    )

    @Before
    fun setUp() {
        NotificationStore.clear()
    }

    @Test
    fun testNotificationStoreBufferingAndFiltering() {
        // Add multiple notifications
        NotificationStore.addNotification(
            NotificationItem(
                id = "com.whatsapp:1",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Rahul",
                text = "Are you reaching today?",
                timestamp = System.currentTimeMillis(),
                isOngoing = false
            )
        )
        NotificationStore.addNotification(
            NotificationItem(
                id = "com.google.android.gm:2",
                packageName = "com.google.android.gm",
                appName = "Gmail",
                title = "Google Security",
                text = "New login from Linux",
                timestamp = System.currentTimeMillis(),
                isOngoing = false
            )
        )
        NotificationStore.addNotification(
            NotificationItem(
                id = "com.spotify.music:3",
                packageName = "com.spotify.music",
                appName = "Spotify",
                title = "Playing music",
                text = "Song title",
                timestamp = System.currentTimeMillis(),
                isOngoing = true // Ongoing media player notification
            )
        )

        // Retrieve non-ongoing
        val recent = NotificationStore.getRecentNotifications(limit = 10)
        assertEquals(2, recent.size)
        assertEquals("Gmail", recent[0].appName) // newest first
        assertEquals("WhatsApp", recent[1].appName)

        // Filter by app
        val waOnly = NotificationStore.getRecentNotifications(limit = 10, appFilter = "whatsapp")
        assertEquals(1, waOnly.size)
        assertEquals("WhatsApp", waOnly[0].appName)
        assertEquals("Rahul", waOnly[0].title)

        // Remove notification
        NotificationStore.removeNotification("com.whatsapp:1")
        val afterRemove = NotificationStore.getRecentNotifications(limit = 10)
        assertEquals(1, afterRemove.size)
        assertEquals("Gmail", afterRemove[0].appName)
    }

    @Test
    fun testNotificationStoreCapacityLimit() {
        for (i in 1..40) {
            NotificationStore.addNotification(
                NotificationItem(
                    id = "app:$i",
                    packageName = "com.test.app",
                    appName = "TestApp",
                    title = "Title $i",
                    text = "Message $i",
                    timestamp = System.currentTimeMillis(),
                    isOngoing = false
                )
            )
        }

        val all = NotificationStore.getAll()
        assertEquals(30, all.size) // Capped at 30
        assertEquals("Title 40", all.first().title)
    }

    @Test
    fun testBatteryMonitorFormatSummary() {
        val normalState = BatteryState(
            level = 75,
            scale = 100,
            percentage = 75,
            isCharging = false,
            isFull = false,
            pluggedSource = "None",
            temperatureC = 28.5f,
            health = "Good"
        )
        val normalSummary = BatteryMonitor.formatSummary(normalState)
        assertTrue(normalSummary.contains("75%"))
        assertTrue(normalSummary.contains("Discharging normally"))

        val chargingState = BatteryState(
            level = 42,
            scale = 100,
            percentage = 42,
            isCharging = true,
            isFull = false,
            pluggedSource = "AC",
            temperatureC = 30.0f,
            health = "Good"
        )
        val chargingSummary = BatteryMonitor.formatSummary(chargingState)
        assertTrue(chargingSummary.contains("42%"))
        assertTrue(chargingSummary.contains("charging via AC"))

        val lowState = BatteryState(
            level = 10,
            scale = 100,
            percentage = 10,
            isCharging = false,
            isFull = false,
            pluggedSource = "None",
            temperatureC = 25.0f,
            health = "Good"
        )
        val lowSummary = BatteryMonitor.formatSummary(lowState)
        assertTrue(lowSummary.contains("low, please plug in"))
    }

    @Test
    fun testProactiveSkillIntentRecognition() = runBlocking {
        val skill = ProactiveSkill()

        // Battery triggers
        assertTrue(skill.canHandle("battery check", skillContext))
        assertTrue(skill.canHandle("how much battery is left?", skillContext))
        assertTrue(skill.canHandle("battery kitni bachi hai", skillContext))

        // Notification triggers
        assertTrue(skill.canHandle("what notifications do I have?", skillContext))
        assertTrue(skill.canHandle("read my notifications", skillContext))
        assertTrue(skill.canHandle("kiska notification aaya hai", skillContext))
        assertTrue(skill.canHandle("check whatsapp message", skillContext))

        // Briefing triggers
        assertTrue(skill.canHandle("give me a daily briefing", skillContext))
        assertTrue(skill.canHandle("morning briefing", skillContext))
        assertTrue(skill.canHandle("subah ki report", skillContext))
        assertTrue(skill.canHandle("status report", skillContext))

        // Unrelated
        assertFalse(skill.canHandle("open chrome", skillContext))
        assertFalse(skill.canHandle("turn on flashlight", skillContext))
    }

    @Test
    fun testProactiveSkillExecutionActionMapping() = runBlocking {
        val skill = ProactiveSkill()

        val batteryRes = skill.execute("check battery", skillContext)
        assertEquals("BATTERY_CHECK", batteryRes.proposedAction?.type)

        val briefingRes = skill.execute("give me morning briefing", skillContext)
        assertEquals("DAILY_BRIEFING", briefingRes.proposedAction?.type)

        val notifRes = skill.execute("read whatsapp notifications", skillContext)
        assertEquals("NOTIFICATIONS_READ", notifRes.proposedAction?.type)
        assertEquals("whatsapp", notifRes.proposedAction?.params?.get("app"))
    }

    @Test
    fun testIntentResolverProactiveFastPaths() {
        val batteryIntent = IntentResolver.resolve("battery check")
        assertNotNull(batteryIntent)
        assertEquals(AssistantIntent.BATTERY_CHECK, batteryIntent?.intent)

        val hindiBattery = IntentResolver.resolve("battery kitni hai")
        assertNotNull(hindiBattery)
        assertEquals(AssistantIntent.BATTERY_CHECK, hindiBattery?.intent)

        val notifIntent = IntentResolver.resolve("read notifications")
        assertNotNull(notifIntent)
        assertEquals(AssistantIntent.READ_NOTIFICATIONS, notifIntent?.intent)

        val briefingIntent = IntentResolver.resolve("morning briefing")
        assertNotNull(briefingIntent)
        assertEquals(AssistantIntent.DAILY_BRIEFING, briefingIntent?.intent)
    }

    @Test
    fun testToolRegistryProactiveAliases() {
        val registry = ToolRegistry()
        val dummyTool = object : com.jarvis.tools.Tool {
            override val name: String = "NOTIFICATIONS_READ"
            override suspend fun execute(params: Map<String, String>): ToolResult = ToolResult.Success("OK")
        }
        registry.register(dummyTool)

        assertEquals("NOTIFICATIONS_READ", registry.resolveCanonicalToolName("READ_NOTIFICATIONS"))
        assertEquals("NOTIFICATIONS_READ", registry.resolveCanonicalToolName("CHECK_NOTIFICATIONS"))
        assertEquals("NOTIFICATIONS_READ", registry.resolveCanonicalToolName("NOTIFICATIONS"))
    }
}
