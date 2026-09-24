package com.jarvis.habit

enum class HabitCategory {
    APP_USAGE,
    COMMUNICATION,
    MEDIA_PLAYBACK,
    SYSTEM_CONTROL,
    CUSTOM_ROUTINE
}

enum class TimeOfDay {
    MORNING,    // 05:00 - 11:59
    AFTERNOON,  // 12:00 - 16:59
    EVENING,    // 17:00 - 21:59
    NIGHT,      // 22:00 - 04:59
    ANYTIME
}

data class HabitItem(
    val id: String,
    val category: HabitCategory,
    val target: String,
    val timeOfDay: TimeOfDay,
    val frequency: Int,
    val confidenceScore: Float,
    val lastOccurredAt: Long,
    val description: String
)
