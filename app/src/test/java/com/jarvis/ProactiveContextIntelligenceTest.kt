package com.jarvis

import com.jarvis.calendar.CalendarManager
import com.jarvis.context.ProactiveContextEngine
import com.jarvis.device.BatteryState
import com.jarvis.ui.model.AppSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ProactiveContextIntelligenceTest {

    private lateinit var calendarManager: CalendarManager
    private lateinit var contextEngine: ProactiveContextEngine

    @Before
    fun setUp() {
        calendarManager = CalendarManager(null)
        contextEngine = ProactiveContextEngine(
            context = null,
            voiceEngine = null,
            calendarManager = calendarManager
        )
    }

    @Test
    fun testAppSettingsDefaultProactiveValues() {
        val settings = AppSettings()
        assertTrue("Proactive calendar alerts should be enabled by default", settings.proactiveCalendarAlertsEnabled)
        assertEquals("Default pre-briefing lead time should be 15 minutes", 15, settings.proactivePreBriefingMinutes)
        assertTrue("Proactive battery alerts should be enabled by default", settings.proactiveBatteryAlertsEnabled)
        assertTrue("Battery full-charge announcements should be enabled by default", settings.batteryAnnouncementsEnabled)
    }

    @Test
    fun testAppSettingsCustomProactiveValues() {
        val custom = AppSettings(
            proactiveCalendarAlertsEnabled = false,
            proactivePreBriefingMinutes = 30,
            proactiveBatteryAlertsEnabled = false,
            batteryAnnouncementsEnabled = false
        )
        assertFalse(custom.proactiveCalendarAlertsEnabled)
        assertEquals(30, custom.proactivePreBriefingMinutes)
        assertFalse(custom.proactiveBatteryAlertsEnabled)
        assertFalse(custom.batteryAnnouncementsEnabled)
    }

    @Test
    fun testJsonSerializationDeserializationRoundtrip() {
        val original = AppSettings(
            proactiveCalendarAlertsEnabled = false,
            proactivePreBriefingMinutes = 10,
            proactiveBatteryAlertsEnabled = false
        )

        val json = JSONObject().apply {
            put("proactiveCalendarAlertsEnabled", original.proactiveCalendarAlertsEnabled)
            put("proactivePreBriefingMinutes", original.proactivePreBriefingMinutes)
            put("proactiveBatteryAlertsEnabled", original.proactiveBatteryAlertsEnabled)
        }

        val d = AppSettings()
        val restored = AppSettings(
            proactiveCalendarAlertsEnabled = json.optBoolean("proactiveCalendarAlertsEnabled", d.proactiveCalendarAlertsEnabled),
            proactivePreBriefingMinutes = json.optInt("proactivePreBriefingMinutes", d.proactivePreBriefingMinutes),
            proactiveBatteryAlertsEnabled = json.optBoolean("proactiveBatteryAlertsEnabled", d.proactiveBatteryAlertsEnabled)
        )

        assertFalse(restored.proactiveCalendarAlertsEnabled)
        assertEquals(10, restored.proactivePreBriefingMinutes)
        assertFalse(restored.proactiveBatteryAlertsEnabled)
    }

    @Test
    fun testCalendarPreBriefingWindowTrigger() {
        val now = System.currentTimeMillis()
        // Event starts in 10 minutes (within 15-minute pre-briefing window)
        val eventStart = now + TimeUnit.MINUTES.toMillis(10)
        calendarManager.addEvent(
            title = "Quarterly Business Review",
            startTimeMillis = eventStart,
            durationMinutes = 45,
            location = "Board Room"
        )

        val triggered = contextEngine.evaluateCalendarEvents(now, preBriefingMinutes = 15)
        assertEquals(1, triggered.size)
        assertTrue(triggered[0].contains("Quarterly Business Review"))
        assertTrue(triggered[0].contains("starting in 10 minutes"))
        assertTrue(triggered[0].contains("Board Room"))
    }

    @Test
    fun testCalendarPreBriefingOutsideWindowNotTriggered() {
        val now = System.currentTimeMillis()
        // Event starts in 60 minutes (outside 15-minute window)
        val eventStart = now + TimeUnit.MINUTES.toMillis(60)
        calendarManager.addEvent(
            title = "Late Evening Sync",
            startTimeMillis = eventStart,
            durationMinutes = 30
        )

        val triggered = contextEngine.evaluateCalendarEvents(now, preBriefingMinutes = 15)
        assertEquals("Events far in the future should not trigger pre-briefings", 0, triggered.size)
    }

    @Test
    fun testCalendarDuplicateSuppression() {
        val now = System.currentTimeMillis()
        val eventStart = now + TimeUnit.MINUTES.toMillis(12)
        calendarManager.addEvent(
            title = "Sprint Planning",
            startTimeMillis = eventStart,
            durationMinutes = 60
        )

        // First evaluation: triggers pre-briefing
        val firstRun = contextEngine.evaluateCalendarEvents(now, preBriefingMinutes = 15)
        assertEquals(1, firstRun.size)
        assertTrue(firstRun[0].contains("Sprint Planning"))

        // Second evaluation (e.g. 1 minute later): duplicate suppressed
        val secondRun = contextEngine.evaluateCalendarEvents(now + 60_000L, preBriefingMinutes = 15)
        assertEquals("Duplicate calendar alerts should be suppressed", 0, secondRun.size)
    }

    @Test
    fun testCriticalLowBatteryAlertDischarging() {
        val now = System.currentTimeMillis()
        val lowBatteryState = BatteryState(percentage = 12, isCharging = false)

        val alert = contextEngine.evaluateBatteryAlert(lowBatteryState, now)
        assertNotNull(alert)
        assertTrue(alert!!.contains("critically low at 12%"))
        assertTrue(alert.contains("connect your charger"))
    }

    @Test
    fun testLowBatteryCooldownProtection() {
        val now = System.currentTimeMillis()
        val lowBatteryState = BatteryState(percentage = 10, isCharging = false)

        // 1. First trigger fires alert
        val alert1 = contextEngine.evaluateBatteryAlert(lowBatteryState, now)
        assertNotNull(alert1)

        // 2. Immediate second trigger (within 30-minute cooldown) is suppressed
        val alert2 = contextEngine.evaluateBatteryAlert(lowBatteryState, now + TimeUnit.MINUTES.toMillis(5))
        assertNull("Subsequent low battery alert within cooldown should be suppressed", alert2)

        // 3. After 31 minutes, cooldown expires and alert triggers again
        val alert3 = contextEngine.evaluateBatteryAlert(lowBatteryState, now + TimeUnit.MINUTES.toMillis(31))
        assertNotNull("Alert should trigger after 30-min cooldown expires", alert3)
    }

    @Test
    fun testNormalBatteryProducesNoAlert() {
        val now = System.currentTimeMillis()
        // 50% battery discharging - healthy
        val normalState = BatteryState(percentage = 50, isCharging = false)
        val alert = contextEngine.evaluateBatteryAlert(normalState, now)
        assertNull("Normal battery state should produce no alert", alert)

        // 12% battery but charging - user is already charging, no alert needed
        val lowChargingState = BatteryState(percentage = 12, isCharging = true)
        val alertCharging = contextEngine.evaluateBatteryAlert(lowChargingState, now)
        assertNull("Low battery while charging should not produce warning", alertCharging)
    }

    @Test
    fun testFullChargeAnnouncementAndUnplugReset() {
        val now = System.currentTimeMillis()
        val fullState = BatteryState(percentage = 100, isCharging = true)

        // 1. Initial 100% full charge triggers alert
        val alert1 = contextEngine.evaluateBatteryAlert(fullState, now)
        assertNotNull(alert1)
        assertTrue(alert1!!.contains("fully charged at 100%"))

        // 2. Next check while still plugged in does NOT repeat
        val alert2 = contextEngine.evaluateBatteryAlert(fullState, now + 60_000L)
        assertNull("100% full charge should not spam repeatedly while plugged in", alert2)

        // 3. Disconnect charger
        val unpluggedState = BatteryState(percentage = 99, isCharging = false)
        contextEngine.evaluateBatteryAlert(unpluggedState, now + 120_000L)
        assertFalse(contextEngine.fullChargedAnnounced)

        // 4. Plug back in later to 100% triggers again
        val rePluggedFull = BatteryState(percentage = 100, isCharging = true)
        val alert3 = contextEngine.evaluateBatteryAlert(rePluggedFull, now + 180_000L)
        assertNotNull("Re-plugged full charge should trigger alert anew", alert3)
    }

    @Test
    fun testBatteryAlertDisabledSettings() {
        val now = System.currentTimeMillis()
        val fullState = BatteryState(percentage = 100, isCharging = true)
        val disabledSettings = AppSettings(batteryAnnouncementsEnabled = false)

        val alert = contextEngine.evaluateBatteryAlert(fullState, now, settingsOverride = disabledSettings)
        assertNull("Disabled battery announcements setting should prevent alert", alert)
    }
}
