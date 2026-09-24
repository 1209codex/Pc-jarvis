package com.jarvis.controlplane

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque

/**
 * Immutable snapshot of the device, environment, and user state.
 */
data class WorldState(
    val timestamp: Long = System.currentTimeMillis(),
    val batteryPercent: Int = -1,
    val isCharging: Boolean = false,
    val isNetworkOnline: Boolean = true,
    val isMeteredNetwork: Boolean = false,
    val isMediaPlaying: Boolean = false,
    val currentMediaTitle: String? = null,
    val ringerMode: String = "normal",
    val volumePercent: Int = 50,
    val isBluetoothScoConnected: Boolean = false,
    val ambientMode: String = "IDLE",
    val isScreenLocked: Boolean = false,
    val activeCaller: String? = null,
    val unreadSmsCount: Int = 0,
    val unreadWhatsAppCount: Int = 0,
    val latestOtp: String? = null,
    val activeTaskId: String? = null,
    val activeTaskStage: String = "IDLE",
    val lastVerifiedAction: String? = null
)

/**
 * Thread-safe single source of truth for the autonomous control plane.
 * Maintains real-time reactive state and a ring buffer of historical states for
 * verified before/after diffing.
 */
class WorldStateStore(initial: WorldState = WorldState()) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<WorldState> = _state.asStateFlow()

    private val historyLock = Any()
    private val history = ArrayDeque<WorldState>(MAX_HISTORY)

    init {
        synchronized(historyLock) {
            history.addLast(initial)
        }
    }

    val current: WorldState
        get() = _state.value

    fun update(transform: (WorldState) -> WorldState): WorldState {
        val updated = synchronized(historyLock) {
            val next = transform(_state.value).copy(timestamp = System.currentTimeMillis())
            _state.value = next
            history.addLast(next)
            while (history.size > MAX_HISTORY) {
                history.removeFirst()
            }
            next
        }
        return updated
    }

    fun getPriorSnapshot(): WorldState? {
        synchronized(historyLock) {
            return if (history.size >= 2) {
                val list = history.toList()
                list[list.size - 2]
            } else {
                null
            }
        }
    }

    fun getRecentSnapshots(count: Int = 10): List<WorldState> {
        synchronized(historyLock) {
            return history.toList().takeLast(count)
        }
    }

    companion object {
        const val MAX_HISTORY = 50
        val shared: WorldStateStore by lazy { WorldStateStore() }
    }
}
