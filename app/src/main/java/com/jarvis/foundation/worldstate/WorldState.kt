package com.jarvis.foundation.worldstate

data class WorldStateSnapshot(
    val timestamp: Long = System.currentTimeMillis(),
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val ambientMode: String? = null,
    val activeMeetingTitle: String? = null,
    val notificationCount: Int = 0,
    val mediaPlaying: Boolean = false,
    val mediaTitle: String? = null,
    val activeTaskTitle: String? = null,
    val activeTaskStatus: String? = null,
    val missingPermissions: List<String> = emptyList()
) {
    fun overview(): String = buildString {
        append("Battery: $batteryPercent%")
        if (isCharging) append(" (charging)")
        ambientMode?.let { append(", ambient: $it") }
        activeMeetingTitle?.let { append(", meeting: $it") }
        if (notificationCount > 0) append(", $notificationCount unread notifications")
        if (mediaPlaying) append(", media playing: ${mediaTitle ?: "unknown"}")
        activeTaskTitle?.let { append(", active task: $it [$activeTaskStatus]") }
        if (missingPermissions.isNotEmpty()) append(", missing permissions: ${missingPermissions.joinToString()}")
    }.toString()
}

fun interface StateProvider {
    fun snapshot(): WorldStateSnapshot
}

/** Keeps a small history of device states so a command's before/after state can be observed. */
class WorldStateService(private val provider: StateProvider) {
    private val history = ArrayDeque<WorldStateSnapshot>()

    fun fresh(): WorldStateSnapshot = provider.snapshot().also(::record)

    fun latest(): WorldStateSnapshot = history.lastOrNull() ?: provider.snapshot()

    fun prior(): WorldStateSnapshot? = if (history.size >= 2) history[history.size - 2] else null

    val historySize: Int get() = history.size

    private fun record(snapshot: WorldStateSnapshot) {
        history.addLast(snapshot)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    companion object {
        const val MAX_HISTORY = 50
    }
}