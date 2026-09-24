package com.jarvis.controlplane

import com.jarvis.wakeword.WakeDetectorMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * Unified reactive event hierarchy for J.A.R.V.I.S.
 * All system triggers (wake word, telephony, messaging, battery, calendar, bluetooth, timers)
 * emit events through the central bus instead of isolated 60s polling.
 */
sealed class JarvisEvent(val timestamp: Long = System.currentTimeMillis()) {
    data class WakeWordTriggered(val phrase: String, val mode: WakeDetectorMode) : JarvisEvent()
    data class VoiceCommandReceived(val utterance: String) : JarvisEvent()
    data class IncomingCall(val phoneNumber: String, val callerName: String?) : JarvisEvent()
    data class CallStateChanged(val state: String, val number: String?) : JarvisEvent()
    data class SmsReceived(val sender: String, val body: String, val detectedOtp: String?) : JarvisEvent()
    data class WhatsAppNotification(
        val sender: String,
        val message: String,
        val isDirectReplyAvailable: Boolean,
        val platform: String = "WhatsApp"
    ) : JarvisEvent()
    data class BatteryStateChanged(val level: Int, val isCharging: Boolean) : JarvisEvent()
    data class CalendarUpcoming(val eventId: String, val title: String, val startMillis: Long) : JarvisEvent()
    data class BluetoothStateChanged(
        val deviceName: String?,
        val isConnected: Boolean,
        val isScoReady: Boolean
    ) : JarvisEvent()
    data class NetworkStateChanged(val isOnline: Boolean, val isMetered: Boolean) : JarvisEvent()
    data class MediaStateChanged(val isPlaying: Boolean, val title: String?, val artist: String?) : JarvisEvent()
    data class ScheduledTrigger(val triggerId: String, val action: String) : JarvisEvent()
    data class HeadsetHookClicked(val clickCount: Int = 1) : JarvisEvent()
    data class TaskCompleted(val taskId: String, val verified: Boolean, val message: String) : JarvisEvent()
    data class SystemAlert(val level: String, val message: String) : JarvisEvent()
    data class ClipboardSuggestion(
        val type: String,
        val content: String,
        val suggestedAction: String,
        val promptText: String
    ) : JarvisEvent()
}

/**
 * Thread-safe asynchronous Kotlin Flow event bus for the autonomous control plane.
 */
class JarvisEventBus(bufferCapacity: Int = 64) {
    private val _events = MutableSharedFlow<JarvisEvent>(
        replay = 16,
        extraBufferCapacity = bufferCapacity
    )
    val events = _events.asSharedFlow()

    fun post(event: JarvisEvent): Boolean {
        return _events.tryEmit(event)
    }

    fun emit(event: JarvisEvent): Boolean {
        return post(event)
    }

    inline fun <reified T : JarvisEvent> subscribe(): Flow<T> {
        return events.filterIsInstance<T>()
    }

    companion object {
        val shared: JarvisEventBus by lazy { JarvisEventBus() }
    }
}
