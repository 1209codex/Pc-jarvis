package com.jarvis.autonomous

import android.util.Log
import com.jarvis.tools.ToolExecutor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

data class DeferredTask(
    val id: String,
    val goal: String,
    val reason: String,
    val timestamp: Long = System.currentTimeMillis()
)

class SelfHealingSupervisor(
    private val toolExecutor: ToolExecutor? = null,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.Default),
    private val onDeferredTaskDue: ((DeferredTask) -> Unit)? = null
) {
    private val TAG = "SelfHealingSupervisor"
    private val deferredQueue = mutableListOf<DeferredTask>()
    private var isOnline: Boolean = true

    fun setNetworkStatus(online: Boolean) {
        val wasOffline = !isOnline
        isOnline = online
        if (wasOffline && online) {
            onNetworkRestored("Connected")
        }
    }

    fun queueDeferredTask(goal: String, reason: String): DeferredTask {
        val task = DeferredTask(id = "def_${System.currentTimeMillis()}", goal = goal, reason = reason)
        synchronized(deferredQueue) {
            deferredQueue.add(task)
        }
        return task
    }

    fun getDeferredTasks(): List<DeferredTask> = synchronized(deferredQueue) { deferredQueue.toList() }

    fun onNetworkRestored(networkLabel: String) {
        if (deferredQueue.isEmpty()) return
        scope.launch {
            replayDeferredTasks(networkLabel)
        }
    }

    /**
     * Replays every queued deferred task through [onDeferredTaskDue] now that the
     * network is back, then clears the queue (the queue is drained first so a
     * handler that re-queues a failed task cannot replay it again in this pass).
     *
     * Returns the tasks that were handed to the replay handler. Replay is
     * best-effort: a handler throwing for one task must not block the others,
     * and if no handler is attached the queue is still flushed (previous
     * semantics — tasks were silently dropped on restore).
     */
    fun replayDeferredTasks(networkLabel: String): List<DeferredTask> {
        val tasks = synchronized(deferredQueue) {
            val copy = deferredQueue.toList()
            deferredQueue.clear()
            copy
        }
        if (tasks.isEmpty()) return tasks

        Log.i(TAG, "Network restored ($networkLabel): replaying ${tasks.size} deferred task(s)")
        val handler = onDeferredTaskDue ?: run {
            Log.w(TAG, "No replay handler attached; ${tasks.size} deferred task(s) dropped on network restore")
            return tasks
        }
        for (task in tasks) {
            try {
                handler.invoke(task)
            } catch (e: Exception) {
                Log.w(TAG, "Deferred task replay failed for '${task.goal}': ${e.message}", e)
            }
        }
        return tasks
    }

    fun clear() {
        synchronized(deferredQueue) {
            deferredQueue.clear()
        }
    }
}
