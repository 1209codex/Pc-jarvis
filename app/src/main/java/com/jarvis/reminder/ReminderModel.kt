package com.jarvis.reminder

enum class ScheduledKind { TIMER, ALARM, REMINDER, ROUTINE_JOB }

data class ScheduledItem(
    val id: String,
    val kind: ScheduledKind,
    val fireAtMillis: Long = 0L,
    val hour: Int = -1,
    val minute: Int = -1,
    val daysOfWeek: List<Int> = emptyList(),
    val label: String = "",
    val routineId: String = ""
) {
    val isRecurring: Boolean get() = kind == ScheduledKind.ROUTINE_JOB
}

data class ReminderParseResult(
    val item: ScheduledItem,
    val spoken: String
)