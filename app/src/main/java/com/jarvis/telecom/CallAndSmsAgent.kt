package com.jarvis.telecom

import android.content.Context
import android.content.pm.PackageManager
import android.annotation.SuppressLint
import android.os.Build
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.util.Log
import com.jarvis.accessibility.JarvisAccessibilityService
import com.jarvis.accessibility.UiAutomationManager
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

data class CallSession(
    val number: String,
    val contactName: String,
    val isRinging: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class SmsItem(
    val id: String,
    val sender: String,
    val contactName: String,
    val body: String,
    val otpCode: String?,
    val timestamp: Long = System.currentTimeMillis()
)

class CallAndSmsAgent(
    context: Context? = null,
    private val contactResolver: ContactResolver = ContactResolver(context?.applicationContext ?: context)
) {
    private val context: Context? = context?.applicationContext ?: context

    companion object {
        private const val TAG = "CallAndSmsAgent"

        @Volatile
        var instance: CallAndSmsAgent? = null
            internal set

        fun destroy() {
            instance = null
        }
    }

    init {
        instance = this
    }

    @Volatile
    var activeCallSession: CallSession? = null
        private set

    private val recentSmsList = CopyOnWriteArrayList<SmsItem>()

    fun onIncomingCallRinging(number: String, onAnnounce: ((String) -> Unit)? = null) {
        val cleanNumber = number.trim()
        val contact = if (cleanNumber.isNotBlank()) contactResolver.resolveContact(cleanNumber) else null
        val displayName = contact?.name ?: cleanNumber.ifBlank { "Unknown Caller" }

        val session = CallSession(
            number = cleanNumber,
            contactName = displayName,
            isRinging = true
        )
        activeCallSession = session
        Log.i(TAG, "Incoming call ringing from: $displayName ($cleanNumber)")

        val announcement = "Incoming call from $displayName. Say 'answer', 'reject', or 'send busy SMS'."
        onAnnounce?.invoke(announcement)
    }

    fun onCallEnded() {
        Log.i(TAG, "Call ended / idle. Active session cleared.")
        activeCallSession = null
    }

    fun onSmsReceived(sender: String, body: String): SmsItem {
        val cleanSender = sender.trim()
        val cleanBody = body.trim()
        val contact = if (cleanSender.isNotBlank()) contactResolver.resolveContact(cleanSender) else null
        val displayName = contact?.name ?: cleanSender.ifBlank { "Unknown Sender" }

        val otp = extractOtp(cleanBody)
        val item = SmsItem(
            id = "sms_${System.currentTimeMillis()}",
            sender = cleanSender,
            contactName = displayName,
            body = cleanBody,
            otpCode = otp
        )

        recentSmsList.add(0, item)
        while (recentSmsList.size > 30) {
            recentSmsList.removeAt(recentSmsList.size - 1)
        }

        // Auto-copy OTP to clipboard if detected
        val currentCtx = context
        if (otp != null && currentCtx != null) {
            runCatching {
                val clipboard = currentCtx.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                val clip = android.content.ClipData.newPlainText("JARVIS OTP", otp)
                clipboard?.setPrimaryClip(clip)
                Log.i(TAG, "Auto-copied OTP code ($otp) to clipboard")
            }
        }

        // Evaluate SMS Auto-Reply via AutoReplyPolicyManager
        if (com.jarvis.messaging.AutoReplyPolicyManager.isEnabled()) {
            com.jarvis.messaging.AutoReplyPolicyManager.handleIncomingSmsAutoReply(context, cleanSender)
        }

        Log.i(TAG, "SMS received from $displayName: \"${cleanBody.take(40)}\" (OTP: $otp)")
        return item
    }

    /**
     * Extracts 4 to 8 digit verification codes and OTPs from text.
     */
    fun extractOtp(body: String): String? {
        val lower = body.lowercase(Locale.ROOT)
        val otpKeywords = listOf("otp", "code", "verification", "passcode", "password", "pin", "verify", "auth", "secret", "cvv", "one time password", "login code")
        if (otpKeywords.none { lower.contains(it) }) {
            return null
        }

        // Pattern 1: Keywords preceding digits e.g. "code is 492810" or "OTP: 1234" or "OTP is 591029"
        val pattern1 = Regex("(?:code|otp|verification|passcode|pin|secret|password)[^0-9]{1,15}([0-9]{4,8})\\b", RegexOption.IGNORE_CASE)
        val match1 = pattern1.find(body)
        if (match1 != null) {
            return match1.groupValues[1]
        }

        // Pattern 2: Digits preceding keyword e.g. "492810 is your OTP"
        val pattern2 = Regex("\\b([0-9]{4,8})[^0-9]{1,15}(?:is your|is the|as your|for verification|for login|to verify)", RegexOption.IGNORE_CASE)
        val match2 = pattern2.find(body)
        if (match2 != null) {
            return match2.groupValues[1]
        }

        // Pattern 3: Standalone 4 to 8 digit code surrounded by whitespace/punctuation
        val pattern3 = Regex("\\b([0-9]{4,8})\\b")
        val match3 = pattern3.find(body)
        return match3?.groupValues?.get(1)
    }

    @SuppressLint("MissingPermission")
    private fun attemptCallControl(
        ctx: Context,
        telecomAction: (TelecomManager) -> Boolean,
        candidateTexts: List<String>,
        contentDescription: String
    ): Boolean {
        if (ctx.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED) {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            if (tm != null && runCatching { telecomAction(tm) }.getOrDefault(false)) {
                return true
            }
        }

        if (UiAutomationManager.isEnabled()) {
            val service = JarvisAccessibilityService.instance
            val node = candidateTexts.firstNotNullOfOrNull { text ->
                service?.findNodeByText(text, exact = false)
            } ?: service?.findNodeByContentDescription(contentDescription)

            if (node != null && service?.clickNode(node) == true) {
                return true
            }
        }
        return false
    }

    /**
     * Answers incoming ringing call hands-free.
     */
    fun answerCall(): Boolean {
        val ctx = context ?: run {
            Log.i(TAG, "answerCall: Context null, simulating call answer.")
            activeCallSession = activeCallSession?.copy(isRinging = false)
            return true
        }

        return runCatching {
            val answered = attemptCallControl(
                ctx = ctx,
                telecomAction = { tm ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                        ctx.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                    ) {
                        @Suppress("DEPRECATION")
                        tm.acceptRingingCall()
                        true
                    } else false
                },
                candidateTexts = listOf("Answer", "Accept"),
                contentDescription = "Answer"
            )
            if (answered) {
                activeCallSession = activeCallSession?.copy(isRinging = false)
                Log.i(TAG, "Answered call successfully")
            }
            answered
        }.getOrElse { e ->
            Log.w(TAG, "Failed to answer call: ${e.message}")
            false
        }
    }

    /**
     * Rejects ringing or active call hands-free and optionally sends auto-reply SMS.
     */
    fun rejectCall(autoReplySms: String? = null): Boolean {
        val targetNumber = activeCallSession?.number
        val targetName = activeCallSession?.contactName ?: "Caller"
        val ctx = context ?: run {
            Log.i(TAG, "rejectCall: Context null, simulating call reject.")
            activeCallSession = null
            return true
        }

        val rejected = runCatching {
            attemptCallControl(
                ctx = ctx,
                telecomAction = { tm ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
                        ctx.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) == PackageManager.PERMISSION_GRANTED
                    ) {
                        @Suppress("DEPRECATION")
                        tm.endCall()
                    } else false
                },
                candidateTexts = listOf("Decline", "Reject", "Dismiss"),
                contentDescription = "Decline"
            )
            true
        }.getOrDefault(false)

        activeCallSession = null

        // Send auto-reply SMS if requested and target number is available
        if (!autoReplySms.isNullOrBlank() && !targetNumber.isNullOrBlank()) {
            runCatching {
                val smsManager = ctx.getSystemService(SmsManager::class.java) ?: SmsManager.getDefault()
                smsManager.sendTextMessage(targetNumber, null, autoReplySms, null, null)
                Log.i(TAG, "Auto-reply SMS sent to $targetName ($targetNumber): \"$autoReplySms\"")
            }.onFailure { e ->
                Log.w(TAG, "Failed sending auto-reply SMS: ${e.message}")
            }
        }

        return rejected
    }

    fun readRecentSms(limit: Int = 5, senderFilter: String? = null, otpOnly: Boolean = false): List<SmsItem> {
        var items = recentSmsList.toList()
        if (!senderFilter.isNullOrBlank()) {
            val lowerFilter = senderFilter.lowercase(Locale.ROOT).trim()
            items = items.filter {
                it.sender.lowercase(Locale.ROOT).contains(lowerFilter) ||
                        it.contactName.lowercase(Locale.ROOT).contains(lowerFilter)
            }
        }
        if (otpOnly) {
            items = items.filter { !it.otpCode.isNullOrBlank() }
        }
        return items.take(limit)
    }

    fun formatSmsSummary(limit: Int = 5, senderFilter: String? = null, otpOnly: Boolean = false): String {
        val messages = readRecentSms(limit, senderFilter, otpOnly)
        if (messages.isEmpty()) {
            return if (otpOnly) "No recent OTP or verification codes found." else "You have no new SMS messages."
        }

        return buildString {
            if (otpOnly) {
                append("Found ${messages.size} verification code${if (messages.size > 1) "s" else ""}: ")
                messages.forEachIndexed { idx, item ->
                    append("${idx + 1}. Code is ${item.otpCode} from ${item.contactName}. ")
                }
            } else {
                append("You have ${messages.size} recent SMS message${if (messages.size > 1) "s" else ""}: ")
                messages.forEachIndexed { idx, item ->
                    val otpPart = if (!item.otpCode.isNullOrBlank()) " (OTP Code: ${item.otpCode})" else ""
                    append("${idx + 1}. From ${item.contactName}: \"${item.body}\"$otpPart. ")
                }
            }
        }
    }
}
