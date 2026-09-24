package com.jarvis.messaging

import android.content.Context
import android.telephony.SmsManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class AutoReplyLogEntry(
    val recipient: String,
    val channel: String, // "SMS" or "WHATSAPP"
    val messageSent: String,
    val mode: AutoReplyMode,
    val timestamp: Long = System.currentTimeMillis()
)

object AutoReplyPolicyManager {
    private const val TAG = "AutoReplyPolicyManager"
    const val DEFAULT_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes

    @Volatile
    var mode: AutoReplyMode = AutoReplyMode.OFF
        private set

    @Volatile
    var customTemplate: String = "I am currently occupied. Jarvis AI has recorded your message and notified me."
        private set

    @Volatile
    var isSmsEnabled: Boolean = true

    @Volatile
    var isWhatsAppEnabled: Boolean = true

    // Cooldown map: normalized contact identifier -> last auto-reply timestamp
    private val lastReplyTimeMap = ConcurrentHashMap<String, Long>()
    private val historyList = mutableListOf<AutoReplyLogEntry>()

    fun setMode(newMode: AutoReplyMode, template: String? = null) {
        mode = newMode
        if (!template.isNullOrBlank()) {
            customTemplate = template.trim()
        }
        Log.i(TAG, "Unified Auto-Reply mode changed to: $newMode")
    }

    fun isEnabled(): Boolean = mode != AutoReplyMode.OFF

    fun getTemplateForCurrentMode(): String {
        return when (mode) {
            AutoReplyMode.DRIVING -> "I am currently driving. Jarvis AI will notify me of your message safely."
            AutoReplyMode.MEETING -> "I am in a meeting right now. Jarvis AI will remind me to respond once I am free."
            AutoReplyMode.BUSY -> "I am currently occupied. Jarvis AI will remind me to get back to you shortly."
            AutoReplyMode.CUSTOM -> customTemplate
            AutoReplyMode.OFF -> ""
        }
    }

    fun canReplyTo(identifier: String, cooldownMs: Long = DEFAULT_COOLDOWN_MS): Boolean {
        if (!isEnabled()) return false
        val clean = identifier.trim().lowercase(Locale.ROOT)
        if (clean.isBlank()) return false

        val now = System.currentTimeMillis()
        val last = lastReplyTimeMap[clean] ?: 0L
        return (now - last) >= cooldownMs
    }

    fun recordReplySent(identifier: String, channel: String, message: String) {
        val clean = identifier.trim().lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        lastReplyTimeMap[clean] = now

        synchronized(historyList) {
            historyList.add(0, AutoReplyLogEntry(clean, channel, message, mode, now))
            while (historyList.size > 50) {
                historyList.removeAt(historyList.size - 1)
            }
        }
        Log.i(TAG, "Recorded auto-reply via $channel to '$clean': \"$message\"")
    }

    fun clearCooldowns() {
        lastReplyTimeMap.clear()
    }

    fun getHistory(): List<AutoReplyLogEntry> = synchronized(historyList) {
        historyList.toList()
    }

    /**
     * Executes auto-reply via SMS if permitted and in cooldown window.
     */
    fun handleIncomingSmsAutoReply(context: Context?, senderNumber: String): Boolean {
        if (!isSmsEnabled || !canReplyTo(senderNumber)) return false
        val message = getTemplateForCurrentMode()
        if (message.isBlank()) return false

        if (context == null) {
            recordReplySent(senderNumber, "SMS", message)
            return true
        }

        return runCatching {
            val smsManager = context.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
            smsManager.sendTextMessage(senderNumber, null, message, null, null)
            recordReplySent(senderNumber, "SMS", message)
            true
        }.getOrElse { e ->
            Log.w(TAG, "Failed sending SMS auto-reply to $senderNumber: ${e.message}")
            false
        }
    }
}
