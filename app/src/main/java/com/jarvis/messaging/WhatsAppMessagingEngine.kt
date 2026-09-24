package com.jarvis.messaging

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.service.notification.StatusBarNotification
import android.util.Log
import com.jarvis.accessibility.JarvisAccessibilityService
import com.jarvis.accessibility.UiAutomationManager
import com.jarvis.notification.NotificationItem
import com.jarvis.notification.NotificationStore
import com.jarvis.telecom.ContactResolver
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.delay
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

enum class AutoReplyMode {
    OFF,
    DRIVING,
    MEETING,
    BUSY,
    CUSTOM
}

data class DirectReplyAction(
    val id: String,
    val sender: String,
    val action: Notification.Action?,
    val timestamp: Long = System.currentTimeMillis()
)

data class AutoReplyRecord(
    val sender: String,
    val messageSent: String,
    val mode: AutoReplyMode,
    val timestamp: Long = System.currentTimeMillis()
)

class WhatsAppMessagingEngine(
    context: Context? = null,
    private val contactResolver: ContactResolver = ContactResolver(context?.applicationContext ?: context)
) {
    private val context: Context? = context?.applicationContext ?: context

    companion object {
        private const val TAG = "WhatsAppMessagingEngine"
        private const val AUTO_REPLY_COOLDOWN_MS = 10 * 60 * 1000L // 10 minutes
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_WHATSAPP_BIZ = "com.whatsapp.w4b"

        fun resolveTargetPackage(platform: String?): String {
            return when (platform?.lowercase()?.trim()) {
                "business", "whatsapp business", "wa business", "w4b" -> PKG_WHATSAPP_BIZ
                else -> PKG_WHATSAPP
            }
        }

        @Volatile
        var instance: WhatsAppMessagingEngine? = null
            internal set

        fun destroy() {
            instance = null
        }
    }

    init {
        instance = this
    }

    // Direct reply actions cached from active notifications: key is normalized sender name or notif id
    private val replyActions = ConcurrentHashMap<String, DirectReplyAction>()

    // Auto-reply configuration
    var autoReplyMode: AutoReplyMode = AutoReplyMode.OFF
        private set
    var customAutoReplyTemplate: String = "I am currently unavailable. Jarvis (AI Assistant) has notified me of your message."
        private set

    // Cooldown tracker: normalized sender -> last auto-replied timestamp
    private val lastAutoRepliedMap = ConcurrentHashMap<String, Long>()
    private val autoReplyHistory = mutableListOf<AutoReplyRecord>()

    fun registerReplyAction(id: String, sender: String, action: Notification.Action?) {
        if (sender.isBlank()) return
        val normalized = sender.trim().lowercase(Locale.ROOT)
        val entry = DirectReplyAction(id, sender.trim(), action)
        replyActions[normalized] = entry
        replyActions[id] = entry
        Log.d(TAG, "Registered direct reply action for '$sender' (id: $id)")
    }

    fun removeReplyAction(id: String) {
        val entry = replyActions.remove(id)
        if (entry != null) {
            val normalized = entry.sender.lowercase(Locale.ROOT)
            replyActions.remove(normalized)
        }
    }

    fun setAutoReplyMode(mode: AutoReplyMode, customTemplate: String? = null) {
        this.autoReplyMode = mode
        if (!customTemplate.isNullOrBlank()) {
            this.customAutoReplyTemplate = customTemplate.trim()
        }
        Log.i(TAG, "Auto-reply mode updated to $mode")
    }

    fun isAutoReplyActive(): Boolean = autoReplyMode != AutoReplyMode.OFF

    fun getAutoReplyHistory(): List<AutoReplyRecord> = synchronized(autoReplyHistory) {
        autoReplyHistory.toList()
    }

    /**
     * Tier 1: Instant background reply via Android RemoteInput API.
     * Completes without launching WhatsApp or disturbing the active UI.
     */
    fun sendDirectNotificationReply(recipient: String, replyText: String): Boolean {
        if (replyText.isBlank() || context == null) return false
        val normalized = recipient.trim().lowercase(Locale.ROOT)

        // Find candidate action by exact match or substring
        val candidate = replyActions[normalized]
            ?: replyActions.values.firstOrNull {
                it.sender.lowercase(Locale.ROOT).contains(normalized) ||
                        normalized.contains(it.sender.lowercase(Locale.ROOT))
            } ?: return false

        val action = candidate.action ?: return false

        return runCatching {
            val remoteInputs = action.remoteInputs
            if (remoteInputs.isNullOrEmpty()) {
                Log.w(TAG, "No RemoteInputs found on action for ${candidate.sender}")
                return false
            }

            val fillInIntent = Intent()
            val bundle = Bundle()
            for (input in remoteInputs) {
                bundle.putCharSequence(input.resultKey, replyText)
            }
            RemoteInput.addResultsToIntent(remoteInputs, fillInIntent, bundle)

            action.actionIntent.send(context, 0, fillInIntent)
            Log.i(TAG, "Successfully dispatched background RemoteInput reply to ${candidate.sender}: $replyText")
            true
        }.getOrElse { e ->
            Log.w(TAG, "Failed sending RemoteInput reply to ${candidate.sender}: ${e.message}")
            false
        }
    }

    /**
     * Direct WhatsApp Messaging Engine via api.whatsapp.com / whatsapp:// URI scheme.
     * Direct dispatch without requiring prior chat thread history or manual contact selection.
     */
    suspend fun sendDirectMessage(recipient: String, message: String, platform: String? = null): ToolResult {
        val trimmedRecipient = recipient.trim()
        val trimmedMessage = message.trim()

        if (trimmedRecipient.isBlank() || trimmedMessage.isBlank()) {
            return ToolResult(false, "Recipient and message are required for direct WhatsApp messaging.")
        }

        // Resolve recipient to phone number if not already digits/phone format
        val isPhoneNumber = trimmedRecipient.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        var resolvedNumber: String? = if (isPhoneNumber) {
            trimmedRecipient.filter { it.isDigit() || it == '+' }
        } else null

        if (resolvedNumber == null) {
            val contact = contactResolver.resolveContact(trimmedRecipient)
            if (contact != null && contact.phoneNumber.isNotBlank()) {
                resolvedNumber = contact.phoneNumber.filter { it.isDigit() || it == '+' }
                Log.i(TAG, "Direct Message: Resolved '$trimmedRecipient' to phone number: $resolvedNumber (${contact.name})")
            }
        }

        if (resolvedNumber.isNullOrBlank()) {
            return ToolResult(false, "Could not resolve a valid phone number for contact: '$trimmedRecipient'.")
        }

        val cleanDigits = formatWhatsAppNumber(resolvedNumber)
        if (cleanDigits.length < 7) {
            return ToolResult(false, "Invalid phone number digits for direct messaging: '$resolvedNumber'.")
        }

        val targetPkg = resolveTargetPackage(platform)
        val platformLabel = if (targetPkg == PKG_WHATSAPP_BIZ) "WhatsApp Business" else "WhatsApp"

        val ctx = context ?: return ToolResult(
            true,
            "Direct $platformLabel message queued for $trimmedRecipient ($cleanDigits): \"$trimmedMessage\" (Simulation mode)."
        )

        ensureScreenAwake()
        val directUri = Uri.parse("https://api.whatsapp.com/send?phone=$cleanDigits&text=${Uri.encode(trimmedMessage)}")
        val intent = Intent(Intent.ACTION_VIEW, directUri).apply {
            setPackage(targetPkg)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return runCatching {
            ctx.startActivity(intent)

            if (UiAutomationManager.isEnabled()) {
                val arrived = UiAutomationManager.waitForPackage(targetPkg, timeoutMs = 3000)
                if (arrived) {
                    if (clickContinueToChatIfShown(targetPkg)) delay(700)
                    if (clickSendButton(targetPkg)) {
                        Log.i(TAG, "Auto-clicked Send button for direct message to $cleanDigits via $platformLabel")
                        autoReturnToHome()
                        return ToolResult(
                            true,
                            "Direct $platformLabel message sent to $trimmedRecipient ($cleanDigits) automatically."
                        )
                    }
                }
            }

            ToolResult(false, "Direct $platformLabel chat opened with the message pre-filled, but Send could not be verified.")
        }.getOrElse { _ ->
            // Fall back to standard wa.me Intent if package filter fails
            val fallbackUri = Uri.parse("https://wa.me/$cleanDigits?text=${Uri.encode(trimmedMessage)}")
            val fallbackIntent = Intent(Intent.ACTION_VIEW, fallbackUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching {
                ctx.startActivity(fallbackIntent)
                ToolResult(false, "Fallback WhatsApp chat opened for $trimmedRecipient, but Send could not be verified.")
            }.getOrElse { fallbackErr ->
                ToolResult(false, "Failed to launch direct WhatsApp chat: ${fallbackErr.message}")
            }
        }
    }

    /**
     * Primary 3-tier send mechanism:
     * - Tier 1: Background RemoteInput reply (if active notification exists)
     * - Tier 2: ContactResolver lookup -> wa.me deep link -> Accessibility auto-send
     * - Tier 3: In-app accessibility contact search macro
     */
    suspend fun sendMessage(recipient: String, message: String, platform: String? = null): ToolResult {
        val trimmedRecipient = recipient.trim()
        val trimmedMessage = message.trim()

        if (trimmedRecipient.isBlank() || trimmedMessage.isBlank()) {
            return ToolResult(false, "Recipient and message are required for WhatsApp messaging.")
        }

        val targetPkg = resolveTargetPackage(platform)
        val platformLabel = if (targetPkg == PKG_WHATSAPP_BIZ) "WhatsApp Business" else "WhatsApp"

        // Tier 1: Attempt instant background reply
        val directReplied = sendDirectNotificationReply(trimmedRecipient, trimmedMessage)
        if (directReplied) {
            return ToolResult(
                true,
                "Replied to $trimmedRecipient on $platformLabel instantly via background notification: \"$trimmedMessage\"."
            )
        }

        val ctx = context ?: return ToolResult(
            true,
            "$platformLabel message queued for $trimmedRecipient: \"$trimmedMessage\" (Simulation mode)."
        )

        ensureScreenAwake()

        // Check if recipient is a raw phone number
        val isPhoneNumber = trimmedRecipient.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }
        var resolvedNumber: String? = if (isPhoneNumber) {
            trimmedRecipient.filter { it.isDigit() || it == '+' }
        } else null

        // Tier 2: Resolve via ContactResolver if not already a phone number
        if (resolvedNumber == null) {
            val contact = contactResolver.resolveContact(trimmedRecipient)
            if (contact != null && contact.phoneNumber.isNotBlank()) {
                resolvedNumber = contact.phoneNumber.filter { it.isDigit() || it == '+' }
                Log.i(TAG, "Resolved '$trimmedRecipient' to phone number: $resolvedNumber (${contact.name})")
            }
        }

        if (resolvedNumber != null) {
            val cleanDigits = formatWhatsAppNumber(resolvedNumber)
            val waUri = Uri.parse("https://wa.me/$cleanDigits?text=${Uri.encode(trimmedMessage)}")
            val intent = Intent(Intent.ACTION_VIEW, waUri).apply {
                setPackage(targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }

            return runCatching {
                ctx.startActivity(intent)

                // If accessibility automation is enabled, auto-click the Send button!
                if (UiAutomationManager.isEnabled()) {
                val arrived = UiAutomationManager.waitForPackage(targetPkg, timeoutMs = 3000)
                if (arrived) {
                    if (clickContinueToChatIfShown(targetPkg)) delay(700)
                    if (clickSendButton(targetPkg)) {
                        Log.i(TAG, "Auto-clicked Send button for $cleanDigits via $platformLabel")
                        autoReturnToHome()
                        return ToolResult(
                            true,
                            "Sent $platformLabel message to $trimmedRecipient ($cleanDigits) automatically."
                        )
                        }
                    }
                }

                ToolResult(false, "$platformLabel opened with the message pre-filled, but Send could not be verified. Accessibility automation must be enabled to send it.")
            }.getOrElse { e ->
                ToolResult(false, "Failed to launch $platformLabel chat: ${e.message}")
            }
        }

        // Tier 3: In-app search macro
        return runCatching {
            val shareUri = Uri.parse("https://wa.me/?text=${Uri.encode(trimmedMessage)}")
            val intent = Intent(Intent.ACTION_VIEW, shareUri).apply {
                setPackage(targetPkg)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)

            if (UiAutomationManager.isEnabled()) {
                val arrived = UiAutomationManager.waitForPackage(targetPkg, timeoutMs = 3000)
                if (arrived) {
                    delay(700)
                    val service = JarvisAccessibilityService.instance
                    val contactNode = service?.findNodeByText(trimmedRecipient, exact = false)
                    if (contactNode != null) {
                        service.clickNode(contactNode)
                        delay(800)
                        val sendNode = UiAutomationManager.waitForNode(timeoutMs = 2500) { s ->
                            findSendNodeForPackage(s, targetPkg)
                        }
                        if (sendNode != null && service.clickNode(sendNode)) {
                            Log.i(TAG, "Selected '$trimmedRecipient' and sent $platformLabel message automatically")
                            autoReturnToHome()
                            return ToolResult(
                                true,
                                "Selected '$trimmedRecipient' and sent $platformLabel message automatically."
                            )
                        }
                    }
                }
            }

            ToolResult(
                false,
                "$platformLabel opened with message pre-filled, but it was not sent. Please enable Accessibility automation or confirm the contact manually."
            )
        }.getOrElse { e ->
            ToolResult(false, "Failed to open $platformLabel: ${e.message}")
        }
    }

    /**
     * Reads recent intercepted WhatsApp notifications, grouping them by sender.
     * Supports platform filtering (standard/business) and media type classification.
     */
    fun readRecentWhatsApp(
        limit: Int = 5,
        senderFilter: String? = null,
        platformFilter: String? = null
    ): ToolResult {
        val allNotifs = NotificationStore.getRecentNotifications(limit = 25, appFilter = "whatsapp")
        if (allNotifs.isEmpty()) {
            return ToolResult(true, "You have no unread WhatsApp messages right now.")
        }

        // Filter by platform if specified
        val platformFiltered = when (platformFilter?.lowercase()?.trim()) {
            "standard", "whatsapp" -> allNotifs.filter { it.packageName == PKG_WHATSAPP }
            "business", "w4b", "whatsapp business" -> allNotifs.filter { it.packageName == PKG_WHATSAPP_BIZ }
            else -> allNotifs
        }

        val filtered = if (!senderFilter.isNullOrBlank()) {
            val filterLower = senderFilter.lowercase(Locale.ROOT).trim()
            platformFiltered.filter { it.title.lowercase(Locale.ROOT).contains(filterLower) }
        } else {
            platformFiltered
        }

        if (filtered.isEmpty()) {
            val context = when {
                !senderFilter.isNullOrBlank() && !platformFilter.isNullOrBlank() ->
                    "from \"$senderFilter\" on $platformFilter"
                !senderFilter.isNullOrBlank() -> "from \"$senderFilter\""
                !platformFilter.isNullOrBlank() -> "on $platformFilter"
                else -> ""
            }
            return ToolResult(true, "No recent WhatsApp messages found $context.".trim())
        }

        // Group by sender (notification title)
        val groupedBySender = filtered.groupBy { it.title }
        val senderSummaries = mutableListOf<String>()

        for ((sender, messages) in groupedBySender.entries.take(limit)) {
            val latestMsg = messages.first().text
            val msgType = classifyMessageType(latestMsg)
            val displayMsg = if (msgType != "text") "[$msgType]" else "\"$latestMsg\""
            val countText = if (messages.size > 1) " (${messages.size} messages)" else ""
            val srcLabel = if (messages.first().packageName == PKG_WHATSAPP_BIZ) " [Business]" else ""
            senderSummaries.add("$sender$srcLabel$countText: $displayMsg")
        }

        val totalCount = filtered.size
        val summaryText = buildString {
            append("You have $totalCount recent WhatsApp message${if (totalCount > 1) "s" else ""}. ")
            append(senderSummaries.joinToString("; "))
            append(". Would you like me to reply to anyone?")
        }

        return ToolResult(
            success = true,
            message = summaryText,
            data = mapOf(
                "totalCount" to totalCount,
                "sendersCount" to groupedBySender.size,
                "summary" to summaryText
            )
        )
    }

    private fun ensureScreenAwake() {
        try {
            val pm = context?.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            if (pm?.isInteractive == false) {
                @Suppress("DEPRECATION")
                val screenLock = pm.newWakeLock(
                    android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    android.os.PowerManager.ON_AFTER_RELEASE,
                    "Jarvis::WhatsAppScreenWake"
                )
                screenLock.acquire(5000L)
            }
        } catch (e: Exception) {
            Log.d(TAG, "Screen wake for WhatsApp: ${e.message}")
        }
    }

    private fun formatWhatsAppNumber(rawNumber: String): String {
        val clean = rawNumber.filter { it.isDigit() }
        return when {
            clean.length == 10 -> "91$clean"
            clean.length == 11 && clean.startsWith("0") -> "91" + clean.substring(1)
            clean.length >= 11 -> clean
            else -> clean
        }
    }

    fun classifyMessageType(text: String): String {
        val lower = text.lowercase(Locale.ROOT).trim()
        return when {
            lower.isEmpty() || lower == "📷 photo" || lower.startsWith("📷") -> "photo"
            lower == "🎥 video" || lower.startsWith("🎥") -> "video"
            lower.startsWith("🎵") || lower == "🎵 audio" -> "audio"
            lower == "📎 document" || lower.startsWith("📎") -> "document"
            lower.startsWith("📍") || lower.contains("location") -> "location"
            lower == "sticker" || lower.startsWith("🏷") -> "sticker"
            lower.contains("gif") && lower.length < 10 -> "gif"
            lower.startsWith("☎") || lower.contains("missed voice call") -> "call"
            else -> "text"
        }
    }

    private fun findSendNodeForPackage(service: JarvisAccessibilityService, pkg: String): android.view.accessibility.AccessibilityNodeInfo? {
        return service.findNodeByViewId("$pkg:id/send")
            ?: service.findNodeByViewId("$pkg:id/btn_send")
            ?: service.findNodeByViewId("$pkg:id/send_button")
            ?: service.findNodeByViewId("$pkg:id/send_container")
            ?: service.findNodeByContentDescription("Send")
            ?: service.findNodeByContentDescription("send")
            ?: service.findNodeByContentDescription("Send message")
            ?: service.findNodeByContentDescription("भेजें")
            ?: service.findNodeByContentDescription("संदेश भेजें")
            ?: service.findNodeByText("Send")
            ?: service.findNodeByText("भेजें")
            ?: service.findNodeByContentDescription("send", exact = false)
            ?: service.findNodeByText("send", exact = false)
    }

    private suspend fun clickContinueToChatIfShown(pkg: String): Boolean {
        val node = UiAutomationManager.waitForNode(timeoutMs = 900) { service ->
            service.findNodeByViewId("$pkg:id/primary_button")
                ?: service.findNodeByText("continue to chat", exact = false)
        } ?: return false
        return JarvisAccessibilityService.instance?.clickNode(node) == true
    }

    private suspend fun clickSendButton(pkg: String): Boolean {
        val node = UiAutomationManager.waitForNode(timeoutMs = 5000) { service ->
            findSendNodeForPackage(service, pkg)
        } ?: return false
        return JarvisAccessibilityService.instance?.clickNode(node) == true
    }

    private fun findWhatsAppSendNode(service: JarvisAccessibilityService): android.view.accessibility.AccessibilityNodeInfo? {
        return findSendNodeForPackage(service, PKG_WHATSAPP)
    }

    private suspend fun autoReturnToHome() {
        val enabled = context?.let {
            runCatching { com.jarvis.ui.data.UiPreferencesStore(it).loadSettings().whatsAppAutoReturnEnabled }.getOrDefault(true)
        } ?: true
        if (enabled) {
            delay(400)
            JarvisAccessibilityService.instance?.performGlobalAction(
                android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
            )
        }
    }

    /**
     * Handles incoming WhatsApp notification for potential auto-reply.
     */
    fun processIncomingNotification(sbn: StatusBarNotification?, item: NotificationItem) {
        if (!AutoReplyPolicyManager.isEnabled() && !isAutoReplyActive()) return
        if (item.packageName != "com.whatsapp" && item.packageName != "com.whatsapp.w4b") return

        if (sbn != null) {
            val replyAction = sbn.notification?.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
            if (replyAction != null && item.title.isNotBlank()) {
                registerReplyAction(item.id, item.title, replyAction)
            }
        }

        val sender = item.title.trim()
        if (sender.isBlank() || sender.equals("WhatsApp", ignoreCase = true)) return

        // Check cooldown from AutoReplyPolicyManager or local map
        if (!AutoReplyPolicyManager.canReplyTo(sender)) {
            Log.d(TAG, "Auto-reply suppressed for '$sender' (cooldown active)")
            return
        }

        val effectiveMode = if (AutoReplyPolicyManager.isEnabled()) AutoReplyPolicyManager.mode else autoReplyMode
        val replyText = if (AutoReplyPolicyManager.isEnabled()) {
            AutoReplyPolicyManager.getTemplateForCurrentMode()
        } else {
            when (autoReplyMode) {
                AutoReplyMode.DRIVING -> "I am currently driving. Jarvis (AI Assistant) has notified me of your message."
                AutoReplyMode.MEETING -> "I am in a meeting right now. Jarvis (AI Assistant) will notify me as soon as I am free."
                AutoReplyMode.BUSY -> "I am currently occupied. Jarvis AI will remind me to get back to you shortly."
                AutoReplyMode.CUSTOM -> customAutoReplyTemplate
                AutoReplyMode.OFF -> return
            }
        }

        if (replyText.isBlank()) return

        val sent = sendDirectNotificationReply(sender, replyText)
        if (sent) {
            AutoReplyPolicyManager.recordReplySent(sender, "WHATSAPP", replyText)
            val now = System.currentTimeMillis()
            lastAutoRepliedMap[sender.lowercase(Locale.ROOT)] = now
            synchronized(autoReplyHistory) {
                autoReplyHistory.add(0, AutoReplyRecord(sender, replyText, effectiveMode, now))
                while (autoReplyHistory.size > 20) autoReplyHistory.removeAt(autoReplyHistory.size - 1)
            }
            Log.i(TAG, "Auto-replied to '$sender' on WhatsApp in mode $effectiveMode: \"$replyText\"")
        }
    }
}
