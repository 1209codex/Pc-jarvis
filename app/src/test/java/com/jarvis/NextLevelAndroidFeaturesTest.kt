package com.jarvis

import com.jarvis.ai.AssistantIntent
import com.jarvis.ai.IntentResolver
import com.jarvis.battery.BatterySample
import com.jarvis.battery.BatteryThermalAutopilotEngine
import com.jarvis.context.AmbientContext
import com.jarvis.context.AmbientContextEngine
import com.jarvis.macro.MacroWorkflowEngine
import com.jarvis.notification.NotificationCategory
import com.jarvis.notification.NotificationClassifier
import com.jarvis.notification.NotificationItem
import com.jarvis.notification.NotificationStore
import com.jarvis.rag.DocumentChunker
import com.jarvis.rag.InMemoryRagIndexStore
import com.jarvis.tools.BatteryStatusTool
import com.jarvis.tools.LocationTool
import com.jarvis.tools.NotificationsTool
import com.jarvis.tools.QuickNotesTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NextLevelAndroidFeaturesTest {

    @Before
    fun setUp() {
        NotificationStore.clear()
        AmbientContextEngine.instance?.clearGeofenceReminders()
    }

    // ==========================================
    // 1. Notification Digest & Spam Triage Tests
    // ==========================================

    @Test
    fun testNotificationClassifier_CategoriesAndOtpOverride() {
        // Promotional notifications
        val promoCategory = NotificationClassifier.classify(
            packageName = "in.swiggy.android",
            title = "Super Discount Today!",
            text = "Flat 50% off on your next burger order. Use coupon FOODIE50."
        )
        assertEquals(NotificationCategory.PROMOTION, promoCategory)

        // Personal / Priority notifications
        val chatCategory = NotificationClassifier.classify(
            packageName = "com.whatsapp",
            title = "Mom",
            text = "Please pick up the prescription on your way back."
        )
        assertEquals(NotificationCategory.PRIORITY, chatCategory)

        // OTP override: Even if from a shopping app with offer words, OTP must remain PRIORITY
        val otpCategory = NotificationClassifier.classify(
            packageName = "com.flipkart.android",
            title = "Login verification",
            text = "Your OTP for login is 492018. Valid for 10 minutes. Do not share."
        )
        assertEquals(NotificationCategory.PRIORITY, otpCategory)

        // System notification
        val systemCategory = NotificationClassifier.classify(
            packageName = "android.systemui",
            title = "System Update",
            text = "Security patch update ready to install."
        )
        assertEquals(NotificationCategory.SYSTEM, systemCategory)
    }

    @Test
    fun testNotificationDigest_GenerationAndSpamClearing() {
        val n1 = NotificationItem(
            id = "1",
            packageName = "com.whatsapp",
            appName = "WhatsApp",
            title = "Alice",
            text = "Project sync moved to 4 PM.",
            timestamp = System.currentTimeMillis(),
            isOngoing = false,
            category = NotificationCategory.PRIORITY
        )
        val n2 = NotificationItem(
            id = "2",
            packageName = "com.google.android.gm",
            appName = "Gmail",
            title = "Boss",
            text = "Urgent: Q3 Roadmap review required.",
            timestamp = System.currentTimeMillis(),
            isOngoing = false,
            category = NotificationCategory.PRIORITY
        )
        val n3 = NotificationItem(
            id = "3",
            packageName = "com.application.zomato",
            appName = "Zomato",
            title = "Craving biryani?",
            text = "Grab free delivery on all orders today!",
            timestamp = System.currentTimeMillis(),
            isOngoing = false,
            category = NotificationCategory.PROMOTION
        )

        NotificationStore.addNotification(n1)
        NotificationStore.addNotification(n2)
        NotificationStore.addNotification(n3)

        val digest = NotificationStore.getDigestSummary()
        assertEquals(2, digest.priorityCount)
        assertEquals(1, digest.promotionCount)
        assertTrue(digest.conversationalSummary.contains("WhatsApp"))
        assertTrue(digest.conversationalSummary.contains("Zomato"))

        // Clear spam/promotions
        val cleared = NotificationStore.clearPromotions()
        assertEquals(1, cleared)
        assertEquals(2, NotificationStore.getRecentNotifications(10).size)
        assertTrue(NotificationStore.getRecentNotifications(10).none { it.category == NotificationCategory.PROMOTION })
    }

    @Test
    fun testNotificationsTool_DigestAndClearSpamActions() = runBlocking {
        val tool = NotificationsTool(context = null)

        NotificationStore.addNotification(
            NotificationItem(
                id = "10",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Bob",
                text = "Meeting is starting now.",
                timestamp = System.currentTimeMillis(),
                isOngoing = false,
                category = NotificationCategory.PRIORITY
            )
        )
        NotificationStore.addNotification(
            NotificationItem(
                id = "11",
                packageName = "com.myntra.android",
                appName = "Myntra",
                title = "Mega Sale",
                text = "Save up to 70% off shoes.",
                timestamp = System.currentTimeMillis(),
                isOngoing = false,
                category = NotificationCategory.PROMOTION
            )
        )

        val digestResult = tool.execute(mapOf("action" to "digest"))
        assertTrue(digestResult.success)
        assertTrue(digestResult.message.contains("Bob") || digestResult.message.contains("WhatsApp"))

        val clearResult = tool.execute(mapOf("action" to "clear_spam"))
        assertTrue(clearResult.success)
        assertTrue(clearResult.message.contains("1 promotional"))
    }

    // ==========================================
    // 2. Battery & Thermal Autopilot Tests
    // ==========================================

    @Test
    fun testBatteryThermalAutopilot_VelocityAndThresholds() {
        val engine = BatteryThermalAutopilotEngine(context = null)

        val now = System.currentTimeMillis()
        // Simulate 2% battery drop over 30 minutes
        val samples = listOf(
            BatterySample(percentage = 80, timestamp = now - 30 * 60 * 1000L, temperatureC = 32.0f, isCharging = false),
            BatterySample(percentage = 78, timestamp = now, temperatureC = 33.0f, isCharging = false)
        )

        val rate = engine.calculateRatePerHour(samples)
        // 2% drop in 0.5hr = -4.0%/hr
        assertEquals(-4.0f, rate, 0.1f)

        // Time to empty at 78% with -4%/hr drain = 78 / 4 * 60 = 1170 minutes
        val minsEmpty = engine.estimateMinutesRemaining(78, isCharging = false, ratePerHour = rate)
        assertEquals(1170, minsEmpty)

        // Check thermal threshold
        val normalState = engine.evaluateHealth(level = 80, isCharging = false, tempC = 35.0f)
        assertFalse(normalState.isOverheated)

        val hotState = engine.evaluateHealth(level = 80, isCharging = false, tempC = 42.5f)
        assertTrue(hotState.isOverheated)
        assertTrue(hotState.advisory.contains("Overheating"))

        // Check overcharge threshold
        val overcharged = engine.evaluateHealth(level = 95, isCharging = true, tempC = 36.0f)
        assertTrue(overcharged.isOvercharged)
        assertTrue(overcharged.advisory.contains("95%"))
    }

    @Test
    fun testBatteryStatusTool_EnrichedOutput() = runBlocking {
        val tool = BatteryStatusTool(context = null)
        val result = tool.execute(emptyMap())
        assertTrue(result.success)
        assertTrue(result.message.contains("Battery is at"))
    }

    // ==========================================
    // 3. Voice Quick Note & RAG Vault Tests
    // ==========================================

    @Test
    fun testQuickNotesTool_AddAndRagAutoIndexing() = runBlocking {
        val ragStore = InMemoryRagIndexStore()
        val tool = QuickNotesTool(context = null, customIndexStore = ragStore)

        val addResult = tool.execute(mapOf("action" to "add", "note" to "Flight to Bengaluru is 6E 521 at terminal 2 gate 14"))
        assertTrue(addResult.success)
        assertTrue(addResult.message.contains("Indexed in RAG vault"))

        // Verify it was indexed in RAG store
        val allDocs = ragStore.getAllDocuments()
        assertEquals(1, allDocs.size)
        val chunks = ragStore.getAllChunks()
        assertTrue(chunks.isNotEmpty())
        assertTrue(chunks[0].content.contains("6E 521"))

        // Verify list action
        val listResult = tool.execute(mapOf("action" to "list"))
        assertTrue(listResult.success)
        assertTrue(listResult.message.contains("Flight to Bengaluru"))
    }

    // ==========================================
    // 4. Ambient Context & Geofence Tests
    // ==========================================

    @Test
    fun testAmbientContextEngine_ContextDetectionAndGeofenceReminders() {
        val engine = AmbientContextEngine(context = null)
        engine.setHomeSsid("Home_Mesh_5G")
        engine.setWorkSsid("Office_Corporate_WiFi")

        // Test SSID Home evaluation
        val homeContext = engine.evaluateContext(
            currentSsid = "Home_Mesh_5G",
            isDrivingAudio = false,
            isNightHour = false,
            isCharging = false
        )
        assertEquals(AmbientContext.HOME, homeContext)

        // Test SSID Work evaluation
        val workContext = engine.evaluateContext(
            currentSsid = "Office_Corporate_WiFi",
            isDrivingAudio = false,
            isNightHour = false,
            isCharging = false
        )
        assertEquals(AmbientContext.WORK, workContext)

        // Test Driving takes precedence
        val drivingContext = engine.evaluateContext(
            currentSsid = "Home_Mesh_5G",
            isDrivingAudio = true,
            isNightHour = false,
            isCharging = false
        )
        assertEquals(AmbientContext.DRIVING, drivingContext)

        // Test Night Rest evaluation
        val nightContext = engine.evaluateContext(
            currentSsid = "Unknown_Network",
            isDrivingAudio = false,
            isNightHour = true,
            isCharging = true
        )
        assertEquals(AmbientContext.NIGHT_REST, nightContext)

        // Test Geofence Reminders
        engine.addGeofenceReminder(AmbientContext.HOME, "Water the indoor bonsai plants")
        val remindersAtWork = engine.checkAndPopGeofenceReminders(AmbientContext.WORK)
        assertTrue(remindersAtWork.isEmpty())

        val remindersAtHome = engine.checkAndPopGeofenceReminders(AmbientContext.HOME)
        assertEquals(1, remindersAtHome.size)
        assertEquals("Water the indoor bonsai plants", remindersAtHome[0])

        // After popping, reminder queue must be empty for HOME
        val remindersAtHomeSecondTime = engine.checkAndPopGeofenceReminders(AmbientContext.HOME)
        assertTrue(remindersAtHomeSecondTime.isEmpty())
    }

    @Test
    fun testLocationTool_GeofenceAndContextActions() = runBlocking {
        val tool = LocationTool(context = null)

        val setHomeResult = tool.execute(mapOf("action" to "set_home", "ssid" to "MyHomeRouter"))
        assertTrue(setHomeResult.success)
        assertTrue(setHomeResult.message.contains("MyHomeRouter"))

        val remindResult = tool.execute(mapOf("action" to "remind_at", "target" to "home", "reminder" to "Turn on geyser"))
        assertTrue(remindResult.success)
        assertTrue(remindResult.message.contains("Turn on geyser"))

        val checkResult = tool.execute(mapOf("action" to "check_context"))
        assertTrue(checkResult.success)
        assertTrue(checkResult.message.contains("Current ambient context"))
    }

    // ==========================================
    // 5. UI Macro Workflows & Deep App Automations
    // ==========================================

    @Test
    fun testMacroWorkflowEngine_StorageAndWhatsAppStatus() = runBlocking {
        val engine = MacroWorkflowEngine(context = null, simulateForTesting = true)

        val storageMacro = engine.getMacro("macro_device_storage")
        assertNotNull(storageMacro)
        assertEquals("Open Storage & Device Care", storageMacro?.name)
        val storageExec = engine.executeMacro(storageMacro!!)
        assertTrue(storageExec.success)
        assertEquals("macro_device_storage", storageExec.macroId)

        val statusMacro = engine.getMacro("macro_whatsapp_status")
        assertNotNull(statusMacro)
        assertEquals("Open WhatsApp Status / Updates", statusMacro?.name)
        val statusExec = engine.executeMacro(statusMacro!!)
        assertTrue(statusExec.success)
        assertEquals("macro_whatsapp_status", statusExec.macroId)
    }

    // ==========================================
    // 6. Intent Resolution for All 5 Features
    // ==========================================

    @Test
    fun testIntentResolver_AllNewFeatures() {
        // 1. Notification Digest & Spam Triage
        val digestIntent = IntentResolver.resolve("summarize my notifications")
        assertNotNull(digestIntent)
        assertEquals(AssistantIntent.READ_NOTIFICATIONS, digestIntent?.intent)
        assertEquals("digest", digestIntent?.params?.get("action"))

        val spamIntent = IntentResolver.resolve("clear spam notifications")
        assertNotNull(spamIntent)
        assertEquals(AssistantIntent.READ_NOTIFICATIONS, spamIntent?.intent)
        assertEquals("clear_spam", spamIntent?.params?.get("action"))

        // 2. Quick Note & RAG Vault
        val noteIntent = IntentResolver.resolve("take a note buy organic milk and honey")
        assertNotNull(noteIntent)
        assertEquals(AssistantIntent.REMEMBER_FACT, noteIntent?.intent)
        assertEquals("add", noteIntent?.params?.get("action"))
        assertTrue(noteIntent?.params?.get("content")?.contains("organic milk") == true)

        // 3. Geofence Reminder & Ambient Context
        val geofenceIntent = IntentResolver.resolve("remind me to turn off heater when I reach home")
        assertNotNull(geofenceIntent)
        assertEquals(AssistantIntent.WHERE_AM_I, geofenceIntent?.intent)
        assertEquals("remind_at", geofenceIntent?.params?.get("action"))
        assertEquals("home", geofenceIntent?.params?.get("target"))

        val contextIntent = IntentResolver.resolve("check context")
        assertNotNull(contextIntent)
        assertEquals(AssistantIntent.WHERE_AM_I, contextIntent?.intent)
        assertEquals("check_context", contextIntent?.params?.get("action"))

        // 4. UI Macro Shortcuts
        val storageIntent = IntentResolver.resolve("open storage care")
        assertNotNull(storageIntent)
        assertEquals(AssistantIntent.MACRO_RUN, storageIntent?.intent)
        assertEquals("macro_device_storage", storageIntent?.params?.get("macro_id"))

        val waStatusIntent = IntentResolver.resolve("open whatsapp status")
        assertNotNull(waStatusIntent)
        assertEquals(AssistantIntent.MACRO_RUN, waStatusIntent?.intent)
        assertEquals("macro_whatsapp_status", waStatusIntent?.params?.get("macro_id"))
    }
}
