package com.jarvis.calendar

import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.provider.CalendarContract
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

data class CalendarEvent(
    val id: String,
    val title: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val location: String = "",
    val description: String = ""
)

class CalendarManager(private val context: Context? = null) {

    private val localEvents = mutableListOf<CalendarEvent>()

    init {
        // Preload sample events for offline predictability and testing
        val now = System.currentTimeMillis()
        val oneHour = 3600_000L
        localEvents.add(
            CalendarEvent(
                id = "sample_daily_standup",
                title = "Team Standup",
                startTimeMillis = now + (2 * oneHour),
                endTimeMillis = now + (2 * oneHour) + (30 * 60_000L),
                location = "Google Meet",
                description = "Daily sprint status check"
            )
        )
    }

    fun addEvent(title: String, startTimeMillis: Long, durationMinutes: Int = 60, location: String = "", description: String = ""): CalendarEvent {
        val endTimeMillis = startTimeMillis + (durationMinutes * 60_000L)
        val event = CalendarEvent(
            id = "evt_${System.currentTimeMillis()}",
            title = title.trim(),
            startTimeMillis = startTimeMillis,
            endTimeMillis = endTimeMillis,
            location = location.trim(),
            description = description.trim()
        )
        localEvents.add(event)
        return event
    }

    fun getEventsForRange(startMillis: Long, endMillis: Long): List<CalendarEvent> {
        val result = mutableListOf<CalendarEvent>()

        // 1. Device calendar query if permitted
        result.addAll(queryDeviceCalendar(startMillis, endMillis))

        // 2. Local events
        for (e in localEvents) {
            if (result.none { it.title.equals(e.title, ignoreCase = true) && it.startTimeMillis == e.startTimeMillis }) {
                val overlaps = (e.startTimeMillis <= endMillis) && (e.endTimeMillis >= startMillis)
                if (overlaps) {
                    result.add(e)
                }
            }
        }

        return result.sortedBy { it.startTimeMillis }
    }

    fun getTodayAgenda(): List<CalendarEvent> {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        val startOfDay = cal.timeInMillis

        cal.set(Calendar.HOUR_OF_DAY, 23)
        cal.set(Calendar.MINUTE, 59)
        cal.set(Calendar.SECOND, 59)
        val endOfDay = cal.timeInMillis

        return getEventsForRange(startOfDay, endOfDay)
    }

    fun checkConflicts(proposedStart: Long, proposedEnd: Long): List<CalendarEvent> {
        return getEventsForRange(proposedStart - 3600_000L, proposedEnd + 3600_000L).filter { existing ->
            (proposedStart < existing.endTimeMillis && proposedEnd > existing.startTimeMillis)
        }
    }

    fun deleteEvent(eventId: String): Boolean {
        return localEvents.removeIf { it.id == eventId }
    }

    fun parseNaturalDateTime(query: String): Long {
        val lower = query.lowercase(Locale.ROOT)
        val cal = Calendar.getInstance()

        if (lower.contains("tomorrow") || lower.contains("kal")) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }

        // Time regex: e.g. "at 4 pm", "4:30 pm", "10 am", "15:00"
        val timeRegex = Regex("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?")
        val match = timeRegex.find(lower)

        if (match != null) {
            var hour = match.groupValues[1].toIntOrNull() ?: 9
            val minute = match.groupValues[2].toIntOrNull() ?: 0
            val ampm = match.groupValues[3].lowercase(Locale.ROOT)

            if (ampm == "pm" && hour < 12) hour += 12
            if (ampm == "am" && hour == 12) hour = 0

            cal.set(Calendar.HOUR_OF_DAY, hour)
            cal.set(Calendar.MINUTE, minute)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        // Default to next hour if no explicit time detected
        cal.add(Calendar.HOUR_OF_DAY, 1)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        return cal.timeInMillis
    }

    fun formatEvent(event: CalendarEvent): String {
        val sdf = SimpleDateFormat("h:mm a", Locale.getDefault())
        val startStr = sdf.format(Date(event.startTimeMillis))
        val endStr = sdf.format(Date(event.endTimeMillis))
        val loc = if (event.location.isNotBlank()) " at ${event.location}" else ""
        return "• $startStr - $endStr: ${event.title}$loc"
    }

    private fun queryDeviceCalendar(startMillis: Long, endMillis: Long): List<CalendarEvent> {
        val ctx = context ?: return emptyList()
        if (ctx.checkSelfPermission(android.Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) {
            return emptyList()
        }

        val results = mutableListOf<CalendarEvent>()
        var cursor: Cursor? = null
        try {
            val uri = CalendarContract.Events.CONTENT_URI
            val projection = arrayOf(
                CalendarContract.Events._ID,
                CalendarContract.Events.TITLE,
                CalendarContract.Events.DTSTART,
                CalendarContract.Events.DTEND,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DESCRIPTION
            )
            val selection = "(${CalendarContract.Events.DTSTART} >= ?) AND (${CalendarContract.Events.DTSTART} <= ?)"
            val selectionArgs = arrayOf(startMillis.toString(), endMillis.toString())

            cursor = ctx.contentResolver.query(uri, projection, selection, selectionArgs, "${CalendarContract.Events.DTSTART} ASC")
            cursor?.let {
                val idIdx = it.getColumnIndex(CalendarContract.Events._ID)
                val titleIdx = it.getColumnIndex(CalendarContract.Events.TITLE)
                val startIdx = it.getColumnIndex(CalendarContract.Events.DTSTART)
                val endIdx = it.getColumnIndex(CalendarContract.Events.DTEND)
                val locIdx = it.getColumnIndex(CalendarContract.Events.EVENT_LOCATION)
                val descIdx = it.getColumnIndex(CalendarContract.Events.DESCRIPTION)

                while (it.moveToNext() && results.size < 20) {
                    val id = if (idIdx >= 0) it.getString(idIdx) else ""
                    val title = if (titleIdx >= 0) it.getString(titleIdx) else ""
                    val start = if (startIdx >= 0) it.getLong(startIdx) else 0L
                    val end = if (endIdx >= 0) it.getLong(endIdx) else start + 3600_000L
                    val loc = if (locIdx >= 0) it.getString(locIdx) ?: "" else ""
                    val desc = if (descIdx >= 0) it.getString(descIdx) ?: "" else ""

                    if (title.isNotBlank() && start > 0) {
                        results.add(CalendarEvent(id = "sys_$id", title = title, startTimeMillis = start, endTimeMillis = end, location = loc, description = desc))
                    }
                }
            }
        } catch (_: Throwable) {
            // Graceful fallback
        } finally {
            cursor?.close()
        }
        return results
    }
}
