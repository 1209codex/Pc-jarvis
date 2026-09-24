package com.jarvis.tools

import android.content.Context
import com.jarvis.reminder.ReminderParser
import com.jarvis.reminder.ReminderScheduler
import com.jarvis.reminder.ReminderStore
import com.jarvis.reminder.ScheduledItem
import com.jarvis.reminder.ScheduledKind

/**
 * Schedules timers, alarms, reminders and recurring routine jobs via the
 * system AlarmManager. All utterance parsing is delegated to the pure
 * ReminderParser; this tool only marshals params, persists and schedules.
 */
class ReminderSchedulerTool(private val context: Context) : Tool {
    override val name: String = "REMINDER_SCHEDULE"
    override val description: String =
        "Schedules timers, alarms, reminders and recurring routine jobs. Parameters: action ('set_timer', 'set_alarm', 'remind_me', 'schedule_routine', 'cancel', 'list'), what (label/note), minutes, time, routine, days."

    val metadata = com.jarvis.foundation.ToolMetadata(
        name = "REMINDER_SCHEDULE",
        description = "Sets one-off and recurring reminders, timers, alarms and timed routine runs on the device.",
        parameters = emptyList(),
        riskLevel = com.jarvis.foundation.RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "remind_me"
        return when (action) {
            "set_timer", "timer" -> scheduleTimer(params)
            "set_alarm", "alarm" -> scheduleAlarm(params)
            "remind_me", "reminder", "remind" -> scheduleReminder(params)
            "schedule_routine" -> scheduleRoutine(params)
            "cancel" -> cancelItem(params)
            "list" -> listItems()
            "list_routines" -> listRoutines()
            else -> ToolResult.Failed("Unknown reminder action: '$action'. Supported: set_timer, set_alarm, remind_me, schedule_routine, cancel, list.")
        }
    }

    private fun scheduleTimer(params: Map<String, String>): ToolResult {
        val minutes = params["minutes"]?.toLongOrNull()
        val what = params["what"] ?: params["label"] ?: params["note"] ?: ""
        val whenText = params["when"]
        val now = System.currentTimeMillis()

        val parsed = if (minutes != null) {
            com.jarvis.reminder.ReminderParseResult(
                ScheduledItem(
                    id = java.util.UUID.randomUUID().toString(),
                    kind = ScheduledKind.TIMER,
                    fireAtMillis = now + minutes * 60_000L,
                    label = what
                ),
                "Timer set for $minutes minute(s)."
            )
        } else if (whenText != null) {
            ReminderParser.parse("set a timer for $whenText", now)
        } else {
            ReminderParser.parse("set a timer for $what", now)
        } ?: return ToolResult.Failed("Could not parse the timer duration. Try 'set timer for 5 minutes'.")

        val item = if (what.isNotBlank() && parsed.item.label.isBlank()) parsed.item.copy(label = what) else parsed.item
        ReminderStore(context).add(item)
        ReminderScheduler.schedule(context, item)
        return ToolResult.Success(parsed.spoken, mapOf("id" to item.id, "fire_at" to item.fireAtMillis))
    }

    private fun scheduleAlarm(params: Map<String, String>): ToolResult {
        val time = params["time"] ?: return ToolResult.Failed("Alarm time missing. Try 'set alarm 7 am'.")
        val what = params["what"] ?: params["label"] ?: ""
        val parsed = ReminderParser.parse("set alarm $time ${if (what.isNotBlank()) "to $what" else ""}")
            ?: return ToolResult.Failed("Could not parse alarm time '$time'.")
        ReminderStore(context).add(parsed.item)
        ReminderScheduler.schedule(context, parsed.item)
        return ToolResult.Success(parsed.spoken, mapOf("id" to parsed.item.id, "fire_at" to parsed.item.fireAtMillis))
    }

    private fun scheduleReminder(params: Map<String, String>): ToolResult {
        val what = params["what"] ?: params["label"] ?: ""
        val whenText = params["when"]
        val input = buildString {
            append("remind me")
            if (whenText != null && whenText.isNotBlank()) append(" $whenText")
            if (what.isNotBlank()) append(" to $what")
        }
        val parsed = ReminderParser.parse(input)
            ?: return ToolResult.Failed("Could not parse the reminder. Try 'remind me in 20 minutes to take medicine'.")
        ReminderStore(context).add(parsed.item)
        ReminderScheduler.schedule(context, parsed.item)
        return ToolResult.Success(parsed.spoken, mapOf("id" to parsed.item.id, "fire_at" to parsed.item.fireAtMillis))
    }

    private fun scheduleRoutine(params: Map<String, String>): ToolResult {
        val routine = params["routine"] ?: return ToolResult.Failed("Routine name missing.")
        val time = params["time"] ?: params["at"] ?: return ToolResult.Failed("Time missing. Try 'schedule_routine' with time '07:30'.")
        val days = ReminderParser.parseDayList(params["days"] ?: "")
        val matches = ReminderParser.parse(
            "run $routine routine at $time${if (days.isNotEmpty()) " every day" else ""}"
        ) ?: return ToolResult.Failed("Could not parse routine schedule '$time'.")
        val item = matches.item.copy(routineId = routine, daysOfWeek = days, fireAtMillis = System.currentTimeMillis())
        ReminderStore(context).add(item)
        ReminderScheduler.schedule(context, item)
        return ToolResult.Success(
            "Scheduled routine '$routine' at ${ReminderParser.formatTime(item.hour, item.minute)}.",
            mapOf("id" to item.id, "routine" to routine)
        )
    }

    private fun cancelItem(params: Map<String, String>): ToolResult {
        val id = params["id"] ?: return ToolResult.Failed("Reminder id missing.")
        val store = ReminderStore(context)
        val found = store.load().any { it.id == id }
        if (!found) return ToolResult.Failed("No reminder with id '$id'.")
        ReminderScheduler.cancel(context, id)
        store.remove(id)
        return ToolResult.Success("Reminder cancelled.")
    }

    private fun listItems(): ToolResult {
        val items = ReminderStore(context).load().filter { it.kind != ScheduledKind.ROUTINE_JOB }
        if (items.isEmpty()) return ToolResult.Success("No reminders or timers scheduled.")
        val fmt = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.US)
        val text = items.sortedBy { it.fireAtMillis }.joinToString("\n") {
            val whenStr = if (it.fireAtMillis > 0) fmt.format(java.util.Date(it.fireAtMillis)) else "${oClock(it)}"
            "$whenStr - ${it.label.ifBlank { it.kind.name }} (#${it.id.take(8)})"
        }
        return ToolResult.Success(text, mapOf("reminders" to items.joinToString(",") { it.id }))
    }

    private fun oClock(item: ScheduledItem): String =
        if (item.hour >= 0) ReminderParser.formatTime(item.hour, item.minute) else "soon"

    private fun listRoutines(): ToolResult {
        val items = ReminderStore(context).load().filter { it.kind == ScheduledKind.ROUTINE_JOB }
        if (items.isEmpty()) return ToolResult.Success("No scheduled routine jobs.")
        val text = items.joinToString("\n") {
            "${it.routineId} @ ${ReminderParser.formatTime(it.hour, it.minute)} on ${ReminderParser.formatDays(it.daysOfWeek)}"
        }
        return ToolResult.Success(text)
    }
}