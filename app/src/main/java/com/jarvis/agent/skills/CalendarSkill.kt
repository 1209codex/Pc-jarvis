package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CalendarEvent(
    val id: String,
    val title: String,
    val startTimeEpoch: Long,
    val endTimeEpoch: Long
)

data class CalendarConflictResult(
    val hasConflict: Boolean,
    val conflictingEvent: CalendarEvent? = null,
    val suggestedFreeSlotEpoch: Long? = null
)

class CalendarSkill : Skill {
    override val id: String = "calendar_intelligence"
    override val name: String = "Calendar & Agenda Skill"
    override val description: String = "Schedules events, checks calendar conflicts, and presents daily agenda with conflict resolution in English and Hindi."
    override val triggers: List<String> = listOf(
        "calendar", "schedule", "agenda", "meeting", "event",
        "aaj ka schedule", "aaj ki meeting", "meeting schedule karo", "meeting rakho",
        "check conflict", "schedule conflict", "upcoming events"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()
        return triggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        // 1. Check Conflict Explicit Query
        if (lower.contains("conflict")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("CALENDAR_MANAGE", mapOf("action" to "check_conflict", "query" to lower)),
                explanation = "Checking calendar schedule for potential conflicts"
            )
        }

        // 2. Today's Agenda / Daily Schedule
        if ((lower.contains("today") || lower.contains("aaj") || lower.contains("agenda") || lower.contains("upcoming")) &&
            !lower.contains("schedule") && !lower.contains("add") && !lower.contains("karo") && !lower.contains("rakho")) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("CALENDAR_MANAGE", mapOf("action" to "today_agenda")),
                explanation = "Retrieving today's agenda"
            )
        }

        // 3. Add Event / Schedule Meeting with Duration & Conflict Analysis
        val parsedTitle = extractTitle(goal)
        val startTime = parseStartTime(goal)
        val durationMs = parseDurationMs(goal)
        val endTime = startTime + durationMs

        val params = mutableMapOf(
            "action" to "add_event",
            "title" to parsedTitle,
            "startTime" to startTime.toString(),
            "endTime" to endTime.toString(),
            "durationMinutes" to TimeUnit.MILLISECONDS.toMinutes(durationMs).toString()
        )

        return SkillResult(
            handled = true,
            proposedAction = AgentAction("CALENDAR_MANAGE", params),
            explanation = "Scheduling calendar event '$parsedTitle' starting at ${formatEpoch(startTime)}"
        )
    }

    companion object {
        fun detectConflict(existingEvents: List<CalendarEvent>, proposed: CalendarEvent): CalendarConflictResult {
            val conflicting = existingEvents.firstOrNull { existing ->
                proposed.startTimeEpoch < existing.endTimeEpoch && proposed.endTimeEpoch > existing.startTimeEpoch
            }

            if (conflicting == null) {
                return CalendarConflictResult(hasConflict = false)
            }

            val suggestedSlot = findNextFreeSlot(existingEvents, proposed)
            return CalendarConflictResult(
                hasConflict = true,
                conflictingEvent = conflicting,
                suggestedFreeSlotEpoch = suggestedSlot
            )
        }

        fun findNextFreeSlot(existingEvents: List<CalendarEvent>, proposed: CalendarEvent): Long {
            val duration = proposed.endTimeEpoch - proposed.startTimeEpoch
            val sorted = existingEvents.sortedBy { it.startTimeEpoch }

            var candidateStart = proposed.startTimeEpoch

            for (event in sorted) {
                if (candidateStart < event.endTimeEpoch && (candidateStart + duration) > event.startTimeEpoch) {
                    candidateStart = event.endTimeEpoch
                }
            }
            return candidateStart
        }

        fun extractTitle(goal: String): String {
            val lower = goal.lowercase(Locale.ROOT).trim()
            val cleaned = lower
                .replace(Regex("\\b(schedule|meeting|event|karo|rakho|with|add|set|tomorrow|kal|today|aaj|at|baje|am|pm|for|hour|hours|mins|minutes)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            return if (cleaned.isBlank()) "Scheduled Meeting" else cleaned.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.titlecase(Locale.ROOT) } }
        }

        fun parseStartTime(goal: String, now: Long = System.currentTimeMillis()): Long {
            val cal = Calendar.getInstance().apply { timeInMillis = now }
            val lower = goal.lowercase(Locale.ROOT)

            if (lower.contains("tomorrow") || lower.contains("kal")) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
            }

            val hourMatch = Regex("(\\d{1,2})\\s*(?:baje|pm|am)?").find(lower)
            if (hourMatch != null) {
                var hour = hourMatch.groupValues[1].toIntOrNull() ?: 10
                if (lower.contains("pm") && hour < 12) hour += 12
                cal.set(Calendar.HOUR_OF_DAY, hour)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
            } else {
                cal.add(Calendar.HOUR_OF_DAY, 1)
            }
            return cal.timeInMillis
        }

        fun parseDurationMs(goal: String): Long {
            val lower = goal.lowercase(Locale.ROOT)
            val hourMatch = Regex("(\\d+)\\s*(?:hour|hr|hours)").find(lower)
            if (hourMatch != null) {
                val hours = hourMatch.groupValues[1].toLongOrNull() ?: 1
                return TimeUnit.HOURS.toMillis(hours)
            }

            val minMatch = Regex("(\\d+)\\s*(?:min|mins|minute|minutes)").find(lower)
            if (minMatch != null) {
                val mins = minMatch.groupValues[1].toLongOrNull() ?: 30
                return TimeUnit.MINUTES.toMillis(mins)
            }

            return TimeUnit.MINUTES.toMillis(60)
        }

        private fun formatEpoch(epoch: Long): String {
            val cal = Calendar.getInstance().apply { timeInMillis = epoch }
            return String.format(Locale.US, "%02d:%02d", cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        }
    }
}
