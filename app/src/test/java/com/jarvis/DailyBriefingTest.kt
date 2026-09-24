package com.jarvis

import com.jarvis.automation.DailyBriefingScheduler
import com.jarvis.calendar.CalendarManager
import com.jarvis.notification.NotificationItem
import com.jarvis.notification.NotificationStore
import com.jarvis.tools.DailyBriefingTool
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.Calendar

class DailyBriefingTest {

    private lateinit var briefingTool: DailyBriefingTool

    @Before
    fun setUp() {
        NotificationStore.clear()
        briefingTool = DailyBriefingTool(null)
    }

    @Test
    fun testBriefingGenerationWithAgendaAndNotifications() = runBlocking {
        NotificationStore.addNotification(
            NotificationItem(
                id = "notif_wa_1",
                packageName = "com.whatsapp",
                appName = "WhatsApp",
                title = "Rahul",
                text = "Are we meeting today?",
                timestamp = System.currentTimeMillis(),
                isOngoing = false
            )
        )

        val result = briefingTool.execute(emptyMap())
        assertTrue("Briefing execution should succeed", result.success)
        assertNotNull(result.message)

        val text = result.message
        assertTrue("Briefing should contain date", text.contains("Today is") || text.contains("2026") || text.contains("September") || text.contains("Sunday") || text.contains(","))
        assertTrue("Briefing should contain time", text.contains("time is") || text.contains("AM") || text.contains("PM"))
        assertTrue("Briefing should contain systems operational message", text.contains("systems are operational") || text.contains("operational"))

        val verify = briefingTool.verify(emptyMap(), result)
        assertTrue("Verification should pass", verify.verified)
    }

    @Test
    fun testSchedulerNextTriggerCalculation() {
        val now = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 13, 7, 0, 0)
        }.timeInMillis

        // If current time is 7:00 AM, next 8:00 AM should be today at 8:00 AM
        val nextTriggerToday = DailyBriefingScheduler.calculateNextTriggerMillis(hour = 8, minute = 0, nowMillis = now)
        val expectedToday = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        assertEquals("Should schedule for 8:00 AM today", expectedToday, nextTriggerToday)

        // If current time is 9:00 AM, next 8:00 AM should be tomorrow at 8:00 AM
        val pastTime = Calendar.getInstance().apply {
            set(2026, Calendar.SEPTEMBER, 13, 9, 0, 0)
        }.timeInMillis
        val nextTriggerTomorrow = DailyBriefingScheduler.calculateNextTriggerMillis(hour = 8, minute = 0, nowMillis = pastTime)
        val expectedTomorrow = Calendar.getInstance().apply {
            timeInMillis = pastTime
            set(Calendar.HOUR_OF_DAY, 8)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
        assertEquals("Should schedule for 8:00 AM tomorrow", expectedTomorrow, nextTriggerTomorrow)
    }
}
