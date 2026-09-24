package com.jarvis.reminder

import java.util.Calendar
import java.util.Locale
import java.util.UUID

/**
 * Pure natural-language parsing for timers, alarms and reminders. No Android
 * APIs inside so it is unit-testable on the JVM. Android scheduling lives in
 * ReminderScheduler; this only turns an utterance into a ScheduledItem.
 */
object ReminderParser {

    private val DURATION_SECONDS = mapOf(
        "s" to 1L, "sec" to 1L, "secs" to 1L, "second" to 1L, "seconds" to 1L,
        "m" to 60L, "min" to 60L, "mins" to 60L, "minute" to 60L, "minutes" to 60L,
        "h" to 3600L, "hr" to 3600L, "hrs" to 3600L, "hour" to 3600L, "hours" to 3600L
    )

    private val DAY_NAMES = mapOf(
        "sunday" to Calendar.SUNDAY, "sun" to Calendar.SUNDAY, "ravivar" to Calendar.SUNDAY,
        "monday" to Calendar.MONDAY, "mon" to Calendar.MONDAY, "somvar" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY, "tue" to Calendar.TUESDAY, "tues" to Calendar.TUESDAY, "mangalvar" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "wed" to Calendar.WEDNESDAY, "budhvar" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY, "thu" to Calendar.THURSDAY, "thurs" to Calendar.THURSDAY, "guruvar" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY, "fri" to Calendar.FRIDAY, "shukravar" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY, "sat" to Calendar.SATURDAY, "shanivar" to Calendar.SATURDAY
    )

    private val WEEKDAY_BY_NUMBER = mapOf(
        "mon" to Calendar.MONDAY, "tue" to Calendar.TUESDAY, "wed" to Calendar.WEDNESDAY,
        "thu" to Calendar.THURSDAY, "fri" to Calendar.FRIDAY, "sat" to Calendar.SATURDAY, "sun" to Calendar.SUNDAY
    )

    fun parse(input: String, now: Long = System.currentTimeMillis()): ReminderParseResult? {
        val text = input.lowercase(Locale.ROOT).trim()
        if (text.isBlank()) return null

        val isTimer = text.contains("timer")
        val isAlarm = text.contains("alarm") || text.contains("alarm at")
        val isReminder = text.contains("remind")
        val isRoutineJob = text.contains("routine") && (text.contains("at") || text.contains("baje") || text.contains("every"))

        if (!isTimer && !isAlarm && !isReminder && !isRoutineJob) return null

        val durationSeconds = parseDurationSeconds(text)
        val (hour, minute) = parseTimeOfDay(text)
        val label = extractLabel(text, isReminder)

        val item = if (isRoutineJob) {
            val routineName = extractRoutineName(text)
            val days = extractDays(text)
            ScheduledItem(
                id = UUID.randomUUID().toString(),
                kind = ScheduledKind.ROUTINE_JOB,
                hour = if (hour >= 0) hour else 7,
                minute = if (minute >= 0) minute else 0,
                daysOfWeek = days,
                routineId = routineName
            )
        } else if (isTimer && durationSeconds != null) {
            ScheduledItem(
                id = UUID.randomUUID().toString(),
                kind = ScheduledKind.TIMER,
                fireAtMillis = now + durationSeconds * 1000L,
                label = label
            )
        } else if (isReminder) {
            val at = if (durationSeconds != null) now + durationSeconds * 1000L
            else if (hour >= 0) nextOccurrence(hour, minute, now)
            else now + 10 * 60 * 1000L
            ScheduledItem(
                id = UUID.randomUUID().toString(),
                kind = ScheduledKind.REMINDER,
                fireAtMillis = at,
                hour = if (hour >= 0) hour else -1,
                minute = if (minute >= 0) minute else -1,
                label = label
            )
        } else {
            // Alarm (absolute time) or a timer without a parseable duration.
            if (hour < 0) return null
            ScheduledItem(
                id = UUID.randomUUID().toString(),
                kind = ScheduledKind.ALARM,
                fireAtMillis = nextOccurrence(hour, minute, now),
                hour = hour,
                minute = minute,
                label = label
            )
        }

        val spoken = when (item.kind) {
            ScheduledKind.TIMER -> "Timer set for ${formatDuration(durationSeconds ?: 0)}. ${if (label.isNotBlank()) "Note: $label" else ""}"
            ScheduledKind.ALARM -> "Alarm set for ${formatTime(hour, minute)}. ${if (label.isNotBlank()) "Note: $label" else ""}"
            ScheduledKind.REMINDER ->
                if (label.isNotBlank()) "Reminder set for $label" else "Reminder set."
            ScheduledKind.ROUTINE_JOB ->
                "Scheduled routine '${item.routineId}' at ${formatTime(item.hour, item.minute)} on ${formatDays(item.daysOfWeek)}."
        }
        return ReminderParseResult(item, spoken.trim())
    }

    private fun parseDurationSeconds(text: String): Long? {
        if (text.contains("half hour")) return 1800L
        if (text.contains("an hour")) return 3600L

        val match = Regex("(\\d+)\\s*(sec|secs|second|seconds|s|min|mins|minute|minutes|m|hour|hours|hr|hrs|h)\\b").find(text)
        if (match != null) {
            val amount = match.groupValues[1].toLong()
            val unitKey = match.groupValues[2].lowercase()
            return amount * (DURATION_SECONDS[unitKey] ?: 60L)
        }
        return null
    }

    private fun parseTimeOfDay(text: String): Pair<Int, Int> {
        val match = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?").find(text)
            ?: return Pair(-1, -1)
        var hour = match.groupValues[1].toIntOrNull() ?: return Pair(-1, -1)
        if (hour > 23) return Pair(-1, -1)
        val minute = match.groupValues[2].toIntOrNull() ?: 0
        val ampm = match.groupValues[3].lowercase()
        if (ampm == "pm" && hour < 12) hour += 12
        if (ampm == "am" && hour == 12) hour = 0
        return Pair(hour, minute)
    }

    private fun extractLabel(text: String, isReminder: Boolean): String {
        if (!isReminder) return ""
        val toIndex = text.indexOf(" to ")
        return if (toIndex >= 0) text.substring(toIndex + 4).trim() else ""
    }

    private fun extractRoutineName(text: String): String {
        val runIndex = text.indexOf(" run ")
        val chalaoIndex = text.indexOf(" chalao ")
        val fromIndex = maxOf(runIndex, chalaoIndex)
        if (fromIndex >= 0) {
            return text.substring(fromIndex + 5).trim().ifBlank { "unknown_routine" }
        }
        val atIndex = text.indexOf(" at ")
        if (atIndex >= 0) {
            return text.substring(0, atIndex).trim().ifBlank { "unknown_routine" }
        }
        return "unknown_routine"
    }

    private fun extractDays(text: String): List<Int> {
        val days = mutableListOf<Int>()
        for (name in DAY_NAMES.keys) {
            if (text.contains(name)) {
                days.add(DAY_NAMES.getValue(name))
            }
        }
        if (days.isEmpty() && (text.contains("morning") || text.contains("roz") || text.contains("daily") || text.contains("every day"))) {
            days.addAll(Calendar.MONDAY..Calendar.SUNDAY)
        }
        return days
    }

    fun nextOccurrence(hour: Int, minute: Int, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        cal.set(Calendar.HOUR_OF_DAY, hour)
        cal.set(Calendar.MINUTE, minute)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        if (cal.timeInMillis <= now) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun nextWeekdayOccurrence(hour: Int, minute: Int, days: List<Int>, now: Long): Long {
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        val usableDays = days.ifEmpty { listOf(Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY) }
        for (dayOffset in 0..7) {
            val candidate = cal.clone() as Calendar
            candidate.add(Calendar.DAY_OF_YEAR, dayOffset)
            val dow = candidate.get(Calendar.DAY_OF_WEEK)
            if (dow in usableDays) {
                candidate.set(Calendar.HOUR_OF_DAY, hour)
                candidate.set(Calendar.MINUTE, minute)
                candidate.set(Calendar.SECOND, 0)
                candidate.set(Calendar.MILLISECOND, 0)
                if (candidate.timeInMillis <= now) continue
                return candidate.timeInMillis
            }
        }
        return now
    }

    fun formatTime(hour: Int, minute: Int): String {
        val h = if (hour == 12 || hour == 0) 12 else hour % 12
        val suffix = if (hour >= 12) "pm" else "am"
        return String.format(Locale.US, "%d:%02d %s", h, minute, suffix)
    }

    fun formatDuration(seconds: Long): String {
        if (seconds >= 3600) return "${seconds / 3600} hour(s)"
        if (seconds >= 60) return "${seconds / 60} minute(s)"
        return "$seconds second(s)"
    }

    fun formatDays(days: List<Int>): String {
        if (days.isEmpty()) return "weekdays"
        if (days.size == 7) return "every day"
        val names = listOf(
            "Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"
        )
        return days.sorted().joinToString(", ") { names[it - Calendar.SUNDAY] }
    }

    fun parseDayList(raw: String): List<Int> {
        val out = mutableListOf<Int>()
        if (raw.isBlank()) return out
        for (token in raw.split(",", " ", ";")) {
            val t = token.trim().lowercase()
            if (t.isEmpty()) continue
            WEEKDAY_BY_NUMBER[t]?.let { out.add(it) }
        }
        return out
    }
}