package com.jarvis

import com.jarvis.reminder.ReminderParser
import com.jarvis.reminder.ReminderStore
import com.jarvis.reminder.ScheduledItem
import com.jarvis.reminder.ScheduledKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReminderParserTest {

    @Test
    fun parsesTimerDuration() {
        val now = 1_700_000_000_000L
        val r = ReminderParser.parse("set a timer for 20 minutes", now)
        assertEquals(ScheduledKind.TIMER, r?.item?.kind)
        assertEquals(now + 20 * 60 * 1000L, r?.item?.fireAtMillis)
    }

    @Test
    fun parsesHalfHourAndSeconds() {
        val now = 1_700_000_000_000L
        assertEquals(now + 1_800_000L, ReminderParser.parse("timer for half hour", now)?.item?.fireAtMillis)
        assertEquals(now + 45_000L, ReminderParser.parse("timer for 45 seconds", now)?.item?.fireAtMillis)
    }

    @Test
    fun parsesAlarmTimeToday() {
        val now = 1_700_000_000_000L
        val r = ReminderParser.parse("set alarm 7 am", now)
        assertEquals(ScheduledKind.ALARM, r?.item?.kind)
        assertEquals(7, r?.item?.hour)
        assertEquals(0, r?.item?.minute)
    }

    @Test
    fun alarmAfterNowRollsToNextDay() {
        // now is 11:00; alarm "10 am" must land tomorrow.
        val now = java.util.Calendar.getInstance().apply {
            set(2024, java.util.Calendar.NOVEMBER, 15, 11, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val fireAt = ReminderParser.parse("set alarm 10 am", now)?.item?.fireAtMillis ?: return
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = fireAt }
        assertEquals(10, cal.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun parsesReminderToLabelAndDuration() {
        val now = 1_700_000_000_000L
        val r = ReminderParser.parse("remind me in 30 minutes to take medicine", now)
        assertEquals(ScheduledKind.REMINDER, r?.item?.kind)
        assertEquals(now + 30 * 60 * 1000L, r?.item?.fireAtMillis)
        assertEquals("take medicine", r?.item?.label)
    }

    @Test
    fun reminderDefaultsToTenMinutesWhenNoTime() {
        val now = 1_700_000_000_000L
        val r = ReminderParser.parse("remind me to drink water", now)
        assertEquals(now + 10 * 60 * 1000L, r?.item?.fireAtMillis)
        assertEquals("drink water", r?.item?.label)
    }

    @Test
    fun parsesRoutineJobWithWeekdays() {
        val r = ReminderParser.parse("every morning at 7 run good morning routine")
        assertEquals(ScheduledKind.ROUTINE_JOB, r?.item?.kind)
        assertEquals(7, r?.item?.hour)
        assertEquals(0, r?.item?.minute)
    }

    @Test
    fun nextWeekdayOccurrencePicksNextMatchingDay() {
        // Friday 2024-11-15 18:00; next Monday 2024-11-18.
        val friday = java.util.Calendar.getInstance().apply {
            set(2024, java.util.Calendar.NOVEMBER, 15, 18, 0, 0)
            set(java.util.Calendar.MILLISECOND, 0)
        }.timeInMillis
        val next = ReminderParser.nextWeekdayOccurrence(9, 0, listOf(java.util.Calendar.MONDAY), friday)
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(java.util.Calendar.MONDAY, cal.get(java.util.Calendar.DAY_OF_WEEK))
        assertEquals(9, cal.get(java.util.Calendar.HOUR_OF_DAY))
    }

    @Test
    fun storeRoundTripsJson() {
        val item = ScheduledItem(
            id = "abc", kind = ScheduledKind.REMINDER, fireAtMillis = 42L,
            hour = 7, minute = 30, daysOfWeek = listOf(java.util.Calendar.MONDAY),
            label = "stand up", routineId = ""
        )
        val decoded = ReminderStore.decode(ReminderStore.encode(listOf(item)))
        assertEquals(1, decoded.size)
        assertEquals("abc", decoded[0].id)
        assertEquals(ScheduledKind.REMINDER, decoded[0].kind)
        assertEquals(42L, decoded[0].fireAtMillis)
        assertEquals("stand up", decoded[0].label)
        assertTrue(decoded[0].daysOfWeek.contains(java.util.Calendar.MONDAY))
    }
}