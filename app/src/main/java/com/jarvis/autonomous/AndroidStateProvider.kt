package com.jarvis.autonomous

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import com.jarvis.foundation.TaskStateManager
import com.jarvis.foundation.worldstate.StateProvider
import com.jarvis.foundation.worldstate.WorldStateSnapshot
import com.jarvis.media.MediaSessionManager

/** Real device-state provider composed from the runtime subsystems already present. */
class AndroidStateProvider(
    private val context: Context,
    private val ambientEngine: AmbientContextEngine,
    private val mediaManager: MediaSessionManager,
    private val taskStateManager: TaskStateManager
) : StateProvider {

    override fun snapshot(): WorldStateSnapshot {
        val ambient = ambientEngine.inferCurrentContext()
        val activeTask = taskStateManager.getRecentTasks(10).firstOrNull { it.status in RUNNING_STATES }
        return WorldStateSnapshot(
            batteryPercent = ambient.batteryPercent,
            isCharging = ambient.isCharging,
            ambientMode = ambient.state.displayName,
            activeMeetingTitle = ambient.activeEventTitle,
            notificationCount = activeNotificationCount(),
            mediaPlaying = mediaManager.isCurrentlyPlaying(),
            mediaTitle = mediaManager.getCurrentTrack()?.let { c ->
                if (c.artist.isBlank()) c.title else "${c.title} by ${c.artist}"
            },
            activeTaskTitle = activeTask?.goal,
            activeTaskStatus = activeTask?.status,
            missingPermissions = missingPermissions()
        )
    }

    private fun activeNotificationCount(): Int {
        return try {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.activeNotifications?.size ?: 0
        } catch (_: Throwable) {
            0
        }
    }

    private fun missingPermissions(): List<String> {
        val wanted = listOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.POST_NOTIFICATIONS,
            Manifest.permission.CAMERA,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        return try {
            wanted.filter { p ->
                context.checkSelfPermission(p) != android.content.pm.PackageManager.PERMISSION_GRANTED
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        private val RUNNING_STATES = setOf("RUNNING", "THINKING", "EXECUTING", "VERIFYING", "WAITING_FOR_USER")
    }
}