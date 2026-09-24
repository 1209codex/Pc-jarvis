package com.jarvis

import com.jarvis.automation.DailyBriefingScheduler
import com.jarvis.ui.model.AppSettings
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.util.Calendar
import java.util.Locale

class SettingsAutomationPreferencesTest {

    @Test
    fun testAppSettingsDefaultValues() {
        val settings = AppSettings()
        assertTrue("Daily briefing should be enabled by default", settings.dailyBriefingEnabled)
        assertEquals("Default daily briefing time should be 08:00", "08:00", settings.dailyBriefingTime)
        assertTrue("Lock-screen voice should be enabled by default", settings.lockScreenVoiceEnabled)
        assertTrue("WhatsApp auto-return should be enabled by default", settings.whatsAppAutoReturnEnabled)
    }

    @Test
    fun testAppSettingsCustomValues() {
        val custom = AppSettings(
            dailyBriefingEnabled = false,
            dailyBriefingTime = "06:30",
            lockScreenVoiceEnabled = false,
            whatsAppAutoReturnEnabled = false
        )
        assertFalse(custom.dailyBriefingEnabled)
        assertEquals("06:30", custom.dailyBriefingTime)
        assertFalse(custom.lockScreenVoiceEnabled)
        assertFalse(custom.whatsAppAutoReturnEnabled)
    }

    @Test
    fun testJsonSerializationAndDeserializationRoundTrip() {
        val custom = AppSettings(
            dailyBriefingEnabled = false,
            dailyBriefingTime = "19:45",
            lockScreenVoiceEnabled = false,
            whatsAppAutoReturnEnabled = false
        )

        val json = JSONObject().apply {
            put("dailyBriefingEnabled", custom.dailyBriefingEnabled)
            put("dailyBriefingTime", custom.dailyBriefingTime)
            put("lockScreenVoiceEnabled", custom.lockScreenVoiceEnabled)
            put("whatsAppAutoReturnEnabled", custom.whatsAppAutoReturnEnabled)
        }

        val defaultSettings = AppSettings()
        val restored = AppSettings(
            dailyBriefingEnabled = json.optBoolean("dailyBriefingEnabled", defaultSettings.dailyBriefingEnabled),
            dailyBriefingTime = json.optString("dailyBriefingTime", defaultSettings.dailyBriefingTime),
            lockScreenVoiceEnabled = json.optBoolean("lockScreenVoiceEnabled", defaultSettings.lockScreenVoiceEnabled),
            whatsAppAutoReturnEnabled = json.optBoolean("whatsAppAutoReturnEnabled", defaultSettings.whatsAppAutoReturnEnabled)
        )

        assertFalse(restored.dailyBriefingEnabled)
        assertEquals("19:45", restored.dailyBriefingTime)
        assertFalse(restored.lockScreenVoiceEnabled)
        assertFalse(restored.whatsAppAutoReturnEnabled)
    }

    @Test
    fun testTimeSplittingAndFallback() {
        fun parseTime(timeStr: String): Pair<Int, Int> {
            val parts = timeStr.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            return h to m
        }

        assertEquals(8 to 0, parseTime("08:00"))
        assertEquals(7 to 15, parseTime("07:15"))
        assertEquals(23 to 59, parseTime("23:59"))
        assertEquals(0 to 0, parseTime("00:00"))
        assertEquals(8 to 0, parseTime("invalid"))
        assertEquals(8 to 0, parseTime(""))
    }

    @Test
    fun testDisplayTimeFormatting() {
        fun formatTimeForDisplay(timeStr: String): String {
            val parts = timeStr.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            val amPm = if (h < 12) "AM" else "PM"
            val displayHour = when {
                h == 0 -> 12
                h > 12 -> h - 12
                else -> h
            }
            return String.format(Locale.US, "%02d:%02d %s", displayHour, m, amPm)
        }

        assertEquals("08:00 AM", formatTimeForDisplay("08:00"))
        assertEquals("12:00 AM", formatTimeForDisplay("00:00"))
        assertEquals("12:30 PM", formatTimeForDisplay("12:30"))
        assertEquals("07:45 PM", formatTimeForDisplay("19:45"))
        assertEquals("11:59 PM", formatTimeForDisplay("23:59"))
    }

    @Test
    fun testSchedulerCustomTimeCalculation() {
        // Current time: 2026-09-13 06:00:00
        val baseTime = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 13, 6, 0, 0)
        }.timeInMillis

        // Target: 07:30 (today)
        val triggerToday = DailyBriefingScheduler.calculateNextTriggerMillis(hour = 7, minute = 30, nowMillis = baseTime)
        val expectedToday = Calendar.getInstance().apply {
            timeInMillis = baseTime
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("Target 07:30 should trigger today", expectedToday, triggerToday)

        // Current time: 2026-09-13 08:30:00
        val afterTime = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 13, 8, 30, 0)
        }.timeInMillis

        // Target: 07:30 (should be tomorrow)
        val triggerTomorrow = DailyBriefingScheduler.calculateNextTriggerMillis(hour = 7, minute = 30, nowMillis = afterTime)
        val expectedTomorrow = Calendar.getInstance().apply {
            timeInMillis = afterTime
            set(Calendar.HOUR_OF_DAY, 7)
            set(Calendar.MINUTE, 30)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        assertEquals("Target 07:30 should trigger tomorrow when past time", expectedTomorrow, triggerTomorrow)
    }
}
