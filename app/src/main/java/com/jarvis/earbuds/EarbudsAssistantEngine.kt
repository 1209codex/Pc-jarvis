package com.jarvis.earbuds

import android.content.Context
import android.util.Log
import com.jarvis.bluetooth.BluetoothHeadsetManager
import com.jarvis.controlplane.JarvisEvent
import com.jarvis.controlplane.JarvisEventBus
import com.jarvis.messaging.WhatsAppMessagingEngine
import com.jarvis.telecom.CallAndSmsAgent
import com.jarvis.telecom.ContactResolver
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.voice.AsrEngine
import com.jarvis.voice.TtsEngine
import com.jarvis.voice.VoiceEngine
import com.jarvis.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Smart Bluetooth Earbuds Assistant & Hands-Free Screen Call Control:
 * Intercepts incoming messaging events (WhatsApp / SMS) and phone calls,
 * announcing them privately in the user's earbuds (or speaker) with immediate
 * hands-free voice dictation and call control.
 */
class EarbudsAssistantEngine(
    private val context: Context,
    private val bluetoothHeadsetManager: BluetoothHeadsetManager,
    private val voiceEngine: VoiceEngine? = null,
    private val ttsEngine: TtsEngine? = voiceEngine?.ttsEngine,
    private val asrEngine: AsrEngine? = voiceEngine?.asrEngine
) {
    private val TAG = "EarbudsAssistantEngine"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var eventCollectorJob: Job? = null
    private val isRunning = AtomicBoolean(false)
    private val isProcessingNotification = AtomicBoolean(false)
    private val isProcessingCall = AtomicBoolean(false)

    var isEnabled: Boolean = true

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        Log.i(TAG, "Starting EarbudsAssistantEngine...")

        eventCollectorJob = scope.launch {
            JarvisEventBus.shared.events.collect { event ->
                if (!isEnabled) return@collect

                when (event) {
                    is JarvisEvent.WhatsAppNotification -> {
                        handleIncomingWhatsApp(event)
                    }
                    is JarvisEvent.IncomingCall -> {
                        handleIncomingCall(event)
                    }
                    else -> {}
                }
            }
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        eventCollectorJob?.cancel()
        eventCollectorJob = null
        scope.cancel()
        Log.i(TAG, "EarbudsAssistantEngine stopped")
    }

    private suspend fun handleIncomingWhatsApp(event: JarvisEvent.WhatsAppNotification) {
        // Only announce via Bluetooth earbuds to preserve privacy and user intent
        val isHeadset = bluetoothHeadsetManager.isHeadsetConnected
        if (!isHeadset) {
            Log.d(TAG, "Ignoring incoming WhatsApp announcement: no Bluetooth headset connected")
            return
        }

        // Don't interrupt an active speech command turn or conversation
        val currentState = voiceEngine?.conversationManager?.currentStateValue
        if (currentState != null && currentState != VoiceState.WAITING_FOR_WAKE && currentState != VoiceState.IDLE) {
            Log.d(TAG, "Ignoring notification announcement: VoiceEngine is in active state $currentState")
            return
        }

        if (!isProcessingNotification.compareAndSet(false, true)) return

        try {
            val sender = event.sender.ifBlank { "Someone" }
            val messageSnippet = event.message.take(120)
            val announcement = "Boss, message from $sender: '$messageSnippet'. Reply?"

            Log.i(TAG, "Announcing incoming message over earbuds: $announcement")
            ttsEngine?.speak(announcement)
            delay(150)

            if (event.isDirectReplyAvailable && asrEngine != null) {
                promptForVoiceReply(sender)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error in earbuds message announcement: ${e.message}")
        } finally {
            isProcessingNotification.set(false)
        }
    }

    private suspend fun promptForVoiceReply(sender: String) {
        var replyTranscript: String? = null

        asrEngine?.startListening(
            onSpeechStarted = {
                Log.d(TAG, "Speech started for notification reply")
            },
            onPartial = {},
            onFinal = { text ->
                if (text.isNotBlank()) {
                    replyTranscript = text.trim()
                }
            },
            onError = { _, _ -> },
            onEmpty = {}
        )

        // Wait up to 5 seconds for user reply
        var waited = 0L
        while (waited < 5000L && replyTranscript == null) {
            delay(200)
            waited += 200
        }

        runCatching { asrEngine?.stopListening() }

        val reply = replyTranscript
        if (!reply.isNullOrBlank()) {
            if (isNegativeResponse(reply)) {
                Log.i(TAG, "User declined to reply to $sender")
                ttsEngine?.speak("Okay, skipped.")
                return
            }

            val cleanReply = extractCleanReply(reply)
            Log.i(TAG, "Dispatching voice reply to $sender: '$cleanReply'")
            val engine = WhatsAppMessagingEngine.instance
            val dispatched = engine?.sendDirectNotificationReply(sender, cleanReply) == true

            if (dispatched) {
                ttsEngine?.speak("Reply sent to $sender.")
            } else {
                ttsEngine?.speak("Could not dispatch direct reply.")
            }
        }
    }

    private fun isNegativeResponse(text: String): Boolean {
        val lower = text.lowercase().trim()
        return lower in listOf("no", "nope", "cancel", "don't reply", "dont reply", "skip", "dismiss", "nah")
    }

    private suspend fun handleIncomingCall(event: JarvisEvent.IncomingCall) {
        val settings = runCatching { UiPreferencesStore(context).loadSettings() }.getOrNull()
        if (settings?.callScreeningEnabled == false) {
            Log.d(TAG, "Call screening is disabled in settings")
            return
        }

        val isHeadset = bluetoothHeadsetManager.isHeadsetConnected
        // If neither headset nor speaker screening is active, return
        if (!isHeadset && settings == null) {
            Log.d(TAG, "No Bluetooth headset connected for incoming call announcement")
            return
        }

        if (!isProcessingCall.compareAndSet(false, true)) return

        try {
            val number = event.phoneNumber.trim()
            val resolvedName = event.callerName?.ifBlank { null }
                ?: runCatching { ContactResolver(context).resolveContact(number)?.name }.getOrNull()
                ?: number.ifBlank { "Unknown Caller" }

            val announcement = "Incoming call from $resolvedName. Say answer or reject."
            Log.i(TAG, "Screening call: $announcement (headset=$isHeadset)")
            ttsEngine?.speak(announcement)
            delay(200)

            if (asrEngine != null) {
                promptForCallAction(number, resolvedName)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error screening incoming call: ${e.message}")
        } finally {
            isProcessingCall.set(false)
        }
    }

    private suspend fun promptForCallAction(number: String, callerName: String) {
        var responseTranscript: String? = null

        asrEngine?.startListening(
            onSpeechStarted = {
                Log.d(TAG, "Speech detected during call screening prompt")
            },
            onPartial = {},
            onFinal = { text ->
                if (text.isNotBlank()) {
                    responseTranscript = text.trim()
                }
            },
            onError = { _, _ -> },
            onEmpty = {}
        )

        // Listen for up to 6 seconds or until call stops ringing
        var waited = 0L
        while (waited < 6000L && responseTranscript == null && CallAndSmsAgent.instance?.activeCallSession?.isRinging != false) {
            delay(200)
            waited += 200
        }

        runCatching { asrEngine?.stopListening() }

        val response = responseTranscript
        if (!response.isNullOrBlank()) {
            val lower = response.lowercase().trim()
            val agent = CallAndSmsAgent.instance ?: CallAndSmsAgent(context)

            when {
                isRejectWithSms(lower) -> {
                    val smsMsg = extractCustomSmsMessage(lower)
                    Log.i(TAG, "User commanded reject call with SMS: '$smsMsg'")
                    agent.rejectCall(autoReplySms = smsMsg)
                    ttsEngine?.speak("Call rejected and SMS sent.")
                }
                isAnswerResponse(lower) -> {
                    Log.i(TAG, "User commanded answer call")
                    val answered = agent.answerCall()
                    if (answered) {
                        ttsEngine?.speak("Call connected.")
                    } else {
                        ttsEngine?.speak("Could not answer call.")
                    }
                }
                isRejectResponse(lower) -> {
                    Log.i(TAG, "User commanded reject call")
                    agent.rejectCall(null)
                    ttsEngine?.speak("Call rejected.")
                }
                lower.contains("who") || lower.contains("kaun") || lower.contains("caller") -> {
                    ttsEngine?.speak("Call is from $callerName.")
                    // Listen again for second chance
                    promptForCallAction(number, callerName)
                }
                else -> {
                    Log.d(TAG, "Unrecognized call response: '$response'")
                }
            }
        }
    }

    fun isAnswerResponse(text: String): Boolean {
        val lower = text.lowercase().trim()
        val words = listOf("answer", "pick up", "pickup", "accept", "receive", "uthao", "haan uthao", "haan", "connect")
        return words.any { lower.contains(it) }
    }

    fun isRejectResponse(text: String): Boolean {
        val lower = text.lowercase().trim()
        val words = listOf("reject", "decline", "cut", "kat do", "hang up", "dismiss", "no", "nahi")
        return words.any { lower.contains(it) }
    }

    fun isRejectWithSms(text: String): Boolean {
        val lower = text.lowercase().trim()
        return (lower.contains("busy") || lower.contains("message") || lower.contains("sms") ||
                lower.contains("text") || lower.contains("meeting") || lower.contains("driving") || lower.contains("later")) &&
                (lower.contains("send") || lower.contains("say") || lower.contains("reject") || lower.contains("tell") || lower.contains("bol"))
    }

    fun extractCustomSmsMessage(text: String): String {
        val lower = text.lowercase().trim()
        return when {
            lower.contains("driving") -> "I am driving right now, will call you later."
            lower.contains("meeting") -> "I am in a meeting, will get back to you soon."
            lower.contains("call later") || lower.contains("call you later") -> "Can't talk right now. Will call you later."
            else -> "I am currently busy, will call you back later."
        }
    }

    fun extractCleanReply(text: String): String {
        var res = text.trim()
        val prefixes = listOf(
            "yes tell him", "yes tell her", "yes tell them",
            "tell him", "tell her", "tell them",
            "reply that", "reply with", "reply",
            "say that", "say", "send"
        )
        for (p in prefixes) {
            if (res.startsWith(p, ignoreCase = true)) {
                res = res.substring(p.length).trim()
                break
            }
        }
        return res.ifBlank { text }
    }
}
