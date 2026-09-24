package com.jarvis.conversation

import android.util.Log
import com.jarvis.ai.Message
import com.jarvis.voice.VoiceState
import com.jarvis.wakeword.RaphaelPhoneticMatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConversationManager {
    private val TAG = "ConversationManager"
    private val _stateFlow = MutableStateFlow(VoiceState.IDLE)
    val stateFlow: StateFlow<VoiceState> = _stateFlow.asStateFlow()

    private val conversationHistory = mutableListOf<Message>()
    private val maxHistoryMessages = 10

    val currentStateValue: VoiceState
        get() = _stateFlow.value

    @Synchronized
    fun transitionTo(newState: VoiceState): Boolean {
        val oldState = _stateFlow.value
        if (isValidTransition(oldState, newState)) {
            _stateFlow.value = newState
            Log.i(TAG, "State transition: $oldState -> $newState")
            return true
        } else {
            Log.w(TAG, "Invalid state transition attempted: $oldState -> $newState")
            return false
        }
    }

    private fun isValidTransition(from: VoiceState, to: VoiceState): Boolean {
        if (to == VoiceState.ERROR || from == VoiceState.ERROR) return true
        if (from == to) return true
        return when (from) {
            VoiceState.IDLE -> to == VoiceState.WAITING_FOR_WAKE || to == VoiceState.OFFLINE
            VoiceState.WAITING_FOR_WAKE -> true
            VoiceState.WAKE_DETECTED -> to == VoiceState.LISTENING || to == VoiceState.WAITING_FOR_WAKE || to == VoiceState.UNDERSTANDING || to == VoiceState.THINKING
            VoiceState.LISTENING -> to == VoiceState.THINKING || to == VoiceState.UNDERSTANDING || to == VoiceState.WAITING_FOR_WAKE
            VoiceState.UNDERSTANDING,
            VoiceState.CHECKING_MEMORY,
            VoiceState.RESEARCHING,
            VoiceState.THINKING,
            VoiceState.EXECUTING,
            VoiceState.VERIFYING,
            VoiceState.RECOVERING,
            VoiceState.WAITING_FOR_USER,
            VoiceState.COMPLETED,
            VoiceState.CANCELLED -> true
            VoiceState.SPEAKING -> to == VoiceState.WAITING_FOR_WAKE || to == VoiceState.INTERRUPTED || to == VoiceState.LISTENING || to == VoiceState.EXECUTING || to == VoiceState.IDLE || to == VoiceState.THINKING
            VoiceState.INTERRUPTED -> to == VoiceState.LISTENING || to == VoiceState.WAITING_FOR_WAKE
            VoiceState.OFFLINE -> to == VoiceState.IDLE || to == VoiceState.WAITING_FOR_WAKE
            VoiceState.ERROR -> to == VoiceState.WAITING_FOR_WAKE || to == VoiceState.IDLE
        }
    }

    /**
     * Checks if the transcript is a wake-word-only utterance ("Jarvis" or custom wake word).
     * Returns cleaned command text, or null if it was wake-only.
     */
    fun processUtterance(rawTranscript: String, customWakeWord: String? = null): String? {
        val clean = rawTranscript.trim()
        if (clean.isBlank()) return null

        val stripped = RaphaelPhoneticMatcher.stripWakeWordPrefix(clean, customWakeWord)
        if (stripped.isBlank() || RaphaelPhoneticMatcher.isWakeWord(clean, customWakeWord)) {
            Log.i(TAG, "Utterance is wake-word-only ('$clean'). Skipping LLM dispatch.")
            return null
        }
        return stripped
    }

    @Synchronized
    fun addMessage(role: String, content: String) {
        conversationHistory.add(Message(role, content))
        // BUG-023 fix: Use while loop instead of if to prevent history overflow
        while (conversationHistory.size > maxHistoryMessages) {
            conversationHistory.removeAt(0)
        }
    }

    @Synchronized
    fun getHistory(): List<Message> {
        return conversationHistory.toList()
    }

    @Synchronized
    fun clearHistory() {
        conversationHistory.clear()
    }

    /**
     * Checks if the user utterance is an explicit conversational closing phrase.
     */
    fun isExitUtterance(rawTranscript: String, customWakeWord: String? = null): Boolean {
        val stripped = RaphaelPhoneticMatcher.stripWakeWordPrefix(rawTranscript, customWakeWord)
        val lower = stripped.lowercase().trim()
            .replace(Regex("^(?:hey|hi|hello|ok|okay)?\\s*[,:]?\\s*", RegexOption.IGNORE_CASE), "")
            .trim()
        val exitPhrases = setOf(
            "bye", "goodbye", "bye bye", "good night", "tata", "alvida",
            "thank you", "thanks", "thank you so much", "thanks jarvis",
            "that's all", "thats all", "that is all", "that's it", "thats it",
            "nothing", "kuch nahi", "kuch nahi chahiye", "bas", "bas itna hi",
            "shukriya", "dhanyawad", "stop listening", "band karo sunna"
        )
        return lower in exitPhrases || exitPhrases.any { lower == it }
    }

    fun getExitResponse(utterance: String): String {
        val lower = utterance.lowercase().trim()
        return when {
            lower.contains("thank") || lower.contains("shukriya") || lower.contains("dhanyawad") ->
                "You're welcome, boss. Let me know if you need anything else."
            lower.contains("night") ->
                "Good night, boss. Sleep well."
            else ->
                "Goodbye boss. Have a great day!"
        }
    }
}
