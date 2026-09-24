package com.jarvis.routine

enum class TriggerType {
    WIFI_CONNECTED,
    WIFI_DISCONNECTED,
    BATTERY_LEVEL_DROPS_BELOW,
    BATTERY_FULL,
    CHARGER_CONNECTED,
    CHARGER_DISCONNECTED,
    TIME_SCHEDULE,
    MANUAL
}

data class RoutineTrigger(
    val type: TriggerType,
    val parameter: String = "", // e.g. "Office_WiFi", "20", "22:30"
    val daysOfWeek: List<Int>? = null // e.g. Calendar.MONDAY..Calendar.FRIDAY
)

data class RoutineAction(
    val toolName: String,
    val params: Map<String, String> = emptyMap(),
    val description: String = ""
)

data class SmartRoutine(
    val id: String,
    val name: String,
    val description: String,
    val trigger: RoutineTrigger,
    val actions: List<RoutineAction>,
    var isEnabled: Boolean = true,
    var lastTriggeredAt: Long = 0L,
    val cooldownMinutes: Int = 15
)

data class RoutineExecutionResult(
    val routineId: String,
    val routineName: String,
    val executed: Boolean,
    val reason: String,
    val executedActionsCount: Int = 0,
    val errors: List<String> = emptyList()
)
