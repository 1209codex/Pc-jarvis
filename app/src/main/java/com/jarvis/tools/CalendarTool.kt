package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.provider.CalendarContract
import android.util.Log
import com.jarvis.calendar.CalendarManager
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

class CalendarTool(
    private val context: Context? = null,
    private val calendarManager: CalendarManager = CalendarManager(context)
) : Tool {

    override val name: String = "CALENDAR_MANAGE"
    override val description: String =
        "Schedules events, checks calendar conflicts, and lists daily agenda. Actions: add_event, list_events, today_agenda, check_conflict. Parameters: action, title, time/date, duration, location."
    override val policy: ToolPolicy = ToolPolicy(idempotent = false, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "CALENDAR_MANAGE",
        description = "Manages meetings, reminders, and daily calendar schedule.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "today_agenda"
        val title = params["title"] ?: params["event"] ?: params["name"] ?: "Meeting"
        val rawTime = params["time"] ?: params["date"] ?: params["when"] ?: ""
        val duration = params["duration"]?.toIntOrNull() ?: 60
        val location = params["location"] ?: ""

        when (action) {
            "add_event", "schedule", "create_event" -> {
                if (title.isBlank()) {
                    return ToolResult.Failed("Please provide a title for the calendar event.")
                }

                val startTime = calendarManager.parseNaturalDateTime(rawTime)
                val conflicts = calendarManager.checkConflicts(startTime, startTime + (duration * 60_000L))

                val event = calendarManager.addEvent(
                    title = title,
                    startTimeMillis = startTime,
                    durationMinutes = duration,
                    location = location
                )

                // Also launch system calendar insert intent so system calendar stays updated
                try {
                    val intent = Intent(Intent.ACTION_INSERT).apply {
                        data = CalendarContract.Events.CONTENT_URI
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        putExtra(CalendarContract.Events.TITLE, title)
                        putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startTime)
                        putExtra(CalendarContract.EXTRA_EVENT_END_TIME, startTime + (duration * 60_000L))
                        if (location.isNotBlank()) putExtra(CalendarContract.Events.EVENT_LOCATION, location)
                    }
                    context?.startActivity(intent)
                } catch (t: Throwable) {
                    Log.w("CalendarTool", "Failed to launch system calendar intent: ${t.message}")
                }

                val conflictWarning = if (conflicts.isNotEmpty()) {
                    "\n⚠️ Note: Conflicts with '${conflicts.first().title}'."
                } else ""

                return ToolResult.Success(
                    message = "Scheduled '$title' for ${calendarManager.formatEvent(event)}.$conflictWarning",
                    data = mapOf("id" to event.id, "title" to title, "has_conflict" to conflicts.isNotEmpty())
                )
            }

            "today_agenda", "list_today", "agenda" -> {
                val agenda = calendarManager.getTodayAgenda()
                if (agenda.isEmpty()) {
                    return ToolResult.Success(
                        message = "Your schedule is clear for today, sir. No upcoming meetings on your agenda.",
                        data = mapOf("count" to 0)
                    )
                }
                val formatted = agenda.joinToString("\n") { calendarManager.formatEvent(it) }
                return ToolResult.Success(
                    message = "Today's Agenda (${agenda.size} event(s)):\n$formatted",
                    data = mapOf("count" to agenda.size, "first_event" to agenda.first().title)
                )
            }

            "check_conflict", "conflicts" -> {
                val startTime = calendarManager.parseNaturalDateTime(rawTime)
                val conflicts = calendarManager.checkConflicts(startTime, startTime + (duration * 60_000L))
                if (conflicts.isEmpty()) {
                    return ToolResult.Success(
                        message = "No schedule conflicts detected for the specified time.",
                        data = mapOf("conflicts" to 0)
                    )
                }
                val conflictSummary = conflicts.joinToString(", ") { it.title }
                return ToolResult.Success(
                    message = "Conflict detected! You already have: $conflictSummary.",
                    data = mapOf("conflicts" to conflicts.size, "conflicting_events" to conflictSummary)
                )
            }

            "list_events", "upcoming" -> {
                val now = System.currentTimeMillis()
                val nextWeek = now + (7 * 24 * 3600_000L)
                val events = calendarManager.getEventsForRange(now, nextWeek)
                if (events.isEmpty()) {
                    return ToolResult.Success(
                        message = "No upcoming events found for the next 7 days.",
                        data = mapOf("count" to 0)
                    )
                }
                val formatted = events.take(6).joinToString("\n") { calendarManager.formatEvent(it) }
                return ToolResult.Success(
                    message = "Upcoming Schedule:\n$formatted",
                    data = mapOf("count" to events.size)
                )
            }

            else -> return ToolResult.Failed("Unknown calendar action: '$action'. Supported: add_event, today_agenda, check_conflict, list_events.")
        }
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Calendar action failed: ${result.message}")
        }
        val action = params["action"]?.trim()?.lowercase() ?: "today_agenda"
        return when (action) {
            "add_event", "schedule", "create_event" -> {
                val id = result.data["id"] as? Long
                val title = result.data["title"] as? String
                if (id != null && !title.isNullOrBlank()) {
                    VerificationResult.success("Calendar event '$title' scheduled", mapOf("outcome" to "EVENT_ADDED"))
                } else {
                    VerificationResult.unknown("Event creation reported but no event record confirmed", mapOf("outcome" to "NO_RECORD"))
                }
            }
            "today_agenda", "list_today", "agenda", "list_events", "upcoming",
            "check_conflict", "conflicts" -> {
                if (result.data.containsKey("count") || result.data.containsKey("conflicts")) {
                    VerificationResult.success("Calendar $action report generated", mapOf("outcome" to "REPORT_GENERATED"))
                } else {
                    VerificationResult.unknown("Calendar report returned but contents not parsed", mapOf("outcome" to "UNPARSED"))
                }
            }
            else -> VerificationResult.unknown("No verifier for calendar action '$action'", mapOf("outcome" to "UNKNOWN_ACTION"))
        }
    }
}
