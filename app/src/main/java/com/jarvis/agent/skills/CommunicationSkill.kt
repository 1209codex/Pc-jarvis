package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Locale

enum class AttachmentType {
    IMAGE,
    DOCUMENT,
    AUDIO,
    VIDEO,
    SCREENSHOT,
    NONE
}

data class AttachmentInfo(
    val attachmentType: AttachmentType,
    val fileFilter: String,
    val recipient: String,
    val caption: String
)

open class CommunicationSkill(private val actionTypePrefix: String = "WHATSAPP_SEND") : Skill {
    override val id: String = "communication"
    override val name: String = "Communication Skill"
    override val description: String = "Manages WhatsApp messaging and social communication."
    override val triggers: List<String> = listOf(
        "whatsapp", "wa", "message", "msg", "bhejo", "chat", "dm", "send",
        "reply", "auto reply", "autoreply", "unread", "padho", "jawab", "kya aaya",
        "send photo", "send picture", "send image", "send document", "send file", "send pdf", "send screenshot", "send video", "send audio"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()
        if (lower.contains("instagram") || lower.contains("insta") || lower.contains("twitter")) return false

        // Do not intercept local file creation or directory listing unless explicit messaging requested
        if (lower.contains("make ") || lower.contains("create ") || lower.contains("write ") || lower.contains("list ") || lower.contains("show file") || lower.contains("show folder")) {
            if (!lower.contains("whatsapp") && !lower.contains("message") && !lower.contains("send to") && !lower.contains("bhejo")) {
                return false
            }
        }

        if (isReadRequest(lower)) return true

        val readTriggers = listOf(
            "read whatsapp", "check whatsapp", "read my whatsapp", "whatsapp padho",
            "whatsapp messages padho", "koi whatsapp aaya", "kisi ka whatsapp aaya",
            "unread whatsapp", "what did i get on whatsapp", "whatsapp par kya aaya",
            "check unread whatsapp", "whatsapp unread", "whatsapp check"
        )
        if (readTriggers.any { lower.contains(it) }) return true

        val autoReplyTriggers = listOf(
            "auto reply", "autoreply", "driving mode reply", "meeting mode reply",
            "auto reply on", "auto reply off", "auto reply band", "auto reply chalu"
        )
        if (autoReplyTriggers.any { lower.contains(it) }) return true

        if (lower.startsWith("reply to ") || lower.contains(" ko reply karo") || lower.startsWith("reply ")) return true

        return triggers.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        // 1. Read messages / unread check
        val readTriggers = listOf(
            "read whatsapp", "check whatsapp", "read my whatsapp", "whatsapp padho",
            "whatsapp messages padho", "koi whatsapp aaya", "kisi ka whatsapp aaya",
            "unread whatsapp", "what did i get on whatsapp", "whatsapp par kya aaya",
            "check unread whatsapp", "whatsapp unread", "whatsapp check"
        )
        if (readTriggers.any { lower.contains(it) } || (isReadRequest(lower) && lower.contains("whatsapp"))) {
            val senderMatch = Regex("(?:from|ka|ke)\\s+([a-zA-Z0-9_+]+)").find(lower)
            val senderFilter = senderMatch?.groupValues?.get(1)?.trim()
            val params = mutableMapOf<String, String>("action" to "read_messages")
            if (!senderFilter.isNullOrBlank() && senderFilter !in listOf("me", "my", "whatsapp")) {
                params["sender"] = senderFilter
            }
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "WHATSAPP",
                    params = params,
                    expectedOutcome = "Reading recent WhatsApp notifications${if (params.containsKey("sender")) " from ${params["sender"]}" else ""}"
                ),
                explanation = "Checking and summarizing recent WhatsApp messages"
            )
        }

        // Generic SMS read
        if (isReadRequest(lower)) {
            val action = if (lower.contains("sms") || lower.contains("text")) "read_sms" else "read_all"
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "MESSAGE_READER",
                    params = mapOf("action" to action)
                ),
                explanation = "Reading recent messages"
            )
        }

        // 2. Auto-reply configuration
        if (lower.contains("auto reply") || lower.contains("autoreply")) {
            val mode = when {
                lower.contains("off") || lower.contains("stop") || lower.contains("disable") || lower.contains("band") -> "off"
                lower.contains("drive") || lower.contains("driving") -> "driving"
                lower.contains("meet") || lower.contains("meeting") -> "meeting"
                lower.contains("busy") -> "busy"
                else -> "driving"
            }
            val params = mapOf("action" to "auto_reply", "mode" to mode)
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "WHATSAPP",
                    params = params,
                    expectedOutcome = "Setting WhatsApp auto-reply mode to $mode"
                ),
                explanation = "Configuring WhatsApp auto-reply policy"
            )
        }

        // 3. Quick Reply pattern
        val replyMatchA = Regex("^reply\\s+to\\s+([a-zA-Z0-9_+]+)\\s+(?:saying|that)?\\s*(.+)$").find(lower)
        if (replyMatchA != null) {
            val recipient = replyMatchA.groupValues[1].trim().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
            val message = replyMatchA.groupValues[2].trim()
            val params = mapOf("action" to "send_message", "recipient" to recipient, "message" to message)
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "WHATSAPP",
                    params = params,
                    expectedOutcome = "Sending reply to $recipient on WhatsApp"
                ),
                explanation = "Prepared direct reply for $recipient"
            )
        }

        val replyMatchB = Regex("^(.+?)\\s+ko\\s+reply\\s+karo\\s*(?:ki)?\\s*(.+)$").find(lower)
        if (replyMatchB != null) {
            val recipient = replyMatchB.groupValues[1].trim().replaceFirstChar {
                if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString()
            }
            val message = replyMatchB.groupValues[2].trim()
            val params = mapOf("action" to "send_message", "recipient" to recipient, "message" to message)
            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "WHATSAPP",
                    params = params,
                    expectedOutcome = "Sending reply to $recipient on WhatsApp"
                ),
                explanation = "Prepared direct reply for $recipient"
            )
        }

        // 4. Media / Document Attachment goal
        val attachmentInfo = extractAttachmentInfo(goal)
        if (attachmentInfo.attachmentType != AttachmentType.NONE) {
            val params = mutableMapOf(
                "action" to "send_media",
                "recipient" to attachmentInfo.recipient,
                "attachmentType" to attachmentInfo.attachmentType.name,
                "fileFilter" to attachmentInfo.fileFilter
            )
            if (attachmentInfo.caption.isNotBlank()) {
                params["caption"] = attachmentInfo.caption
            }

            return SkillResult(
                handled = true,
                proposedAction = AgentAction(
                    type = "WHATSAPP",
                    params = params,
                    expectedOutcome = "Sending ${attachmentInfo.attachmentType.name.lowercase(Locale.ROOT)} (${attachmentInfo.fileFilter}) to ${attachmentInfo.recipient} on WhatsApp"
                ),
                explanation = "Prepared WhatsApp ${attachmentInfo.attachmentType.name.lowercase(Locale.ROOT)} attachment for ${attachmentInfo.recipient}"
            )
        }

        // 5. Standard Message sending
        val extracted = extractRecipientAndMessage(goal)
        val recipient = extracted.first
        val message = extracted.second

        // Never turn an incomplete "send message" request into a guessed payload
        // (the old fallback used the literal word "Message" as the message).
        val sendRequested = lower.contains("send") || lower.contains("bhejo") || lower.contains("bhej do") ||
                lower.contains("message") || lower.contains("msg")
        if (sendRequested && (recipient.isBlank() || message.isBlank() ||
                    message.equals("message", ignoreCase = true) || message.equals("msg", ignoreCase = true))) {
            return SkillResult(
                handled = true,
                proposedAction = null,
                explanation = "Recipient and exact message text are required before sending."
            )
        }

        val actionType = if (recipient.isNotBlank() || message.isNotBlank()) "send_message" else "open"

        return SkillResult(
            handled = true,
            proposedAction = AgentAction(
                type = actionTypePrefix,
                params = mapOf(
                    "recipient" to recipient,
                    "message" to message,
                    "action" to actionType
                ),
                expectedOutcome = if (recipient.isNotBlank()) "Sending WhatsApp message to $recipient" else "Opening WhatsApp"
            ),
            explanation = "Prepared WhatsApp communication for ${if (recipient.isNotBlank()) recipient else "contact"}"
        )
    }

    companion object {
        private fun isReadRequest(lower: String): Boolean =
            (lower.contains("read") || lower.contains("check") || lower.contains("padho") || lower.contains("pata karo")) &&
                (lower.contains("message") || lower.contains("msg") || lower.contains("sms") || lower.contains("whatsapp") || lower.contains("text"))

        fun extractRecipientAndMessage(goal: String): Pair<String, String> {
            val trimmed = goal.trim()
            val lower = trimmed.lowercase(Locale.ROOT)

            // Strip leading wake words or politeness words
            val cleaned = lower
                .replace(Regex("^(?:hey|hi|hello|ok|okay)?\\s*jarvis\\s*[,:]?\\s*", RegexOption.IGNORE_CASE), "")
                .trim()

            // 0. Pattern: "<recipient> ko <msg> (send karo|bhejo|send|karo)? whatsapp (pe|par|per)?"
            // e.g. "Doller ko hello send karo whatsapp pe", "Doller ko hello bhejo whatsapp par"
            val hindiPatternEndWa = Regex(
                "^(?:send\\s+)?(.+?)\\s+ko\\s+(.+?)\\s*(?:send\\s*karo|bhejo|send|karo|likho|message\\s*karo|msg\\s*karo)?\\s*(?:whatsapp|wa)\\s*(?:\\b(?:per|par|pe|me|in)\\b)?$",
                RegexOption.IGNORE_CASE
            )
            val matchEndWa = hindiPatternEndWa.find(cleaned)
            if (matchEndWa != null) {
                val r = cleanRecipient(matchEndWa.groupValues[1])
                val m = cleanMessage(matchEndWa.groupValues[2])
                if (r.isNotBlank()) return Pair(r, m)
            }

            // 1. Pattern: "<recipient> ko whatsapp (per|par|pe)? (message)? <msg> (bhejo|send karo|karo|likho)"
            // e.g. "dollar ko whatsapp per hello send karo"
            val hindiPatternA = Regex(
                "^(?:send\\s+)?(.+?)\\s+ko\\s+whatsapp\\s*(?:\\b(?:per|par|pe)\\b\\s*)?(?:message|msg)?\\s*(.+?)\\s*(?:send\\s*karo|bhejo|send|karo|likho)?$",
                RegexOption.IGNORE_CASE
            )
            val matchA = hindiPatternA.find(cleaned)
            if (matchA != null) {
                val r = cleanRecipient(matchA.groupValues[1])
                val m = cleanMessage(matchA.groupValues[2])
                if (r.isNotBlank()) return Pair(r, m)
            }

            // 2. Pattern: "whatsapp (per|par|pe)? <recipient> ko (message)? <msg> (bhejo|send karo|karo|bolo)"
            // e.g. "whatsapp par dollar ko bolo hello"
            val hindiPatternB = Regex(
                "^whatsapp\\s*(?:\\b(?:per|par|pe)\\b\\s*)?(.+?)\\s+ko\\s*(?:message|msg|bolo|send)?\\s*(.+?)\\s*(?:send\\s*karo|bhejo|send|karo|likho)?$",
                RegexOption.IGNORE_CASE
            )
            val matchB = hindiPatternB.find(cleaned)
            if (matchB != null) {
                val r = cleanRecipient(matchB.groupValues[1])
                val m = cleanMessage(matchB.groupValues[2])
                if (r.isNotBlank()) return Pair(r, m)
            }

            // 3. Pattern: "<recipient> ko (message)? <msg> (bhejo|send karo|karo)"
            // e.g. "dollar ko hello send karo", "rahul ko message bhejo kal aana"
            val hindiPatternC = Regex(
                "^(.+?)\\s+ko\\s+(?:message|msg)?\\s*(.+?)\\s*(?:send\\s*karo|bhejo|send|karo|likho)$",
                RegexOption.IGNORE_CASE
            )
            val matchC = hindiPatternC.find(cleaned)
            if (matchC != null) {
                val r = cleanRecipient(matchC.groupValues[1])
                val m = cleanMessage(matchC.groupValues[2])
                if (r.isNotBlank()) return Pair(r, m)
            }

            // 4. Pattern: "<recipient> ko whatsapp karo" (no explicit message)
            val hindiPatternD = Regex(
                "^(.+?)\\s+ko\\s+whatsapp\\s*(?:karo|message|msg)?$",
                RegexOption.IGNORE_CASE
            )
            val matchD = hindiPatternD.find(cleaned)
            if (matchD != null) {
                val r = cleanRecipient(matchD.groupValues[1])
                if (r.isNotBlank()) return Pair(r, "Hello!")
            }

            // 5. English pattern: "send <message> to <recipient> (on whatsapp)?"
            val engPatternA = Regex(
                "^send\\s+(?:a\\s+)?(?:whatsapp\\s+)?(?:message\\s+)?(.+?)\\s+to\\s+(.+?)(?:\\s+on\\s+whatsapp)?$",
                RegexOption.IGNORE_CASE
            )
            val matchEngA = engPatternA.find(cleaned)
            if (matchEngA != null) {
                val m = cleanMessage(matchEngA.groupValues[1])
                val r = cleanRecipient(matchEngA.groupValues[2])
                if (r.isNotBlank()) return Pair(r, m)
            }

            // 6. English pattern: "send <recipient> (a message|message) (on whatsapp)? saying <message>"
            val engPatternB = Regex(
                "^send\\s+(.+?)\\s+(?:a\\s+)?(?:message|msg|whatsapp)?(?:\\s+on\\s+whatsapp)?\\s+(?:saying|that)\\s+(.+)$",
                RegexOption.IGNORE_CASE
            )
            val matchEngB = engPatternB.find(cleaned)
            if (matchEngB != null) {
                val r = cleanRecipient(matchEngB.groupValues[1])
                val m = cleanMessage(matchEngB.groupValues[2])
                if (r.isNotBlank()) return Pair(r, m)
            }

            // 7. English pattern: "whatsapp <recipient> <message>"
            val engPatternC = Regex(
                "^whatsapp\\s+(?:message\\s+to\\s+|to\\s+)?([a-zA-Z0-9_+]+)\\s+(.+)$",
                RegexOption.IGNORE_CASE
            )
            val matchEngC = engPatternC.find(cleaned)
            if (matchEngC != null) {
                val r = cleanRecipient(matchEngC.groupValues[1])
                val m = cleanMessage(matchEngC.groupValues[2])
                if (r.isNotBlank()) return Pair(r, m)
            }

            // Fallback: check for standard contact names or words
            val fallbackRecipient = when {
                lower.contains("rahul") -> "Rahul"
                lower.contains("mummy") || lower.contains("mom") || lower.contains("maa") -> "Mummy"
                lower.contains("papa") || lower.contains("dad") -> "Papa"
                lower.contains("bhai") || lower.contains("brother") -> "Bhai"
                lower.contains("dollar") || lower.contains("doller") -> "Doller"
                else -> ""
            }

            val fallbackMessage = when {
                lower.contains("hello") -> "Hello!"
                lower.contains("kaise ho") -> "Kaise ho?"
                lower.contains("good morning") -> "Good morning!"
                lower.contains("good night") -> "Good night!"
                else -> "Hello!"
            }

            return Pair(fallbackRecipient, fallbackMessage)
        }

        fun extractAttachmentInfo(goal: String): AttachmentInfo {
            val lower = goal.lowercase(Locale.ROOT).trim()

            val attachmentType = when {
                lower.contains("screenshot") || lower.contains("screen recording") -> AttachmentType.SCREENSHOT
                lower.contains("photo") || lower.contains("picture") || lower.contains("image") || lower.contains("pic") || lower.contains("camera") -> AttachmentType.IMAGE
                lower.contains("document") || lower.contains("file") || lower.contains("pdf") || lower.contains("doc") || lower.contains("docx") || lower.contains("excel") || lower.contains("sheet") -> AttachmentType.DOCUMENT
                lower.contains("audio") || lower.contains("voice note") || lower.contains("recording") -> AttachmentType.AUDIO
                lower.contains("video") || lower.contains("clip") -> AttachmentType.VIDEO
                else -> AttachmentType.NONE
            }

            if (attachmentType == AttachmentType.NONE) {
                val (rec, msg) = extractRecipientAndMessage(goal)
                return AttachmentInfo(AttachmentType.NONE, "", rec, msg)
            }

            val fileMatch = Regex("([a-zA-Z0-9_-]+\\.(?:pdf|doc|docx|png|jpg|jpeg|txt|csv))").find(lower)
            val fileFilter = fileMatch?.groupValues?.get(1) ?: when (attachmentType) {
                AttachmentType.SCREENSHOT -> "latest_screenshot"
                AttachmentType.IMAGE -> "latest_photo"
                AttachmentType.DOCUMENT -> "latest_document"
                AttachmentType.AUDIO -> "latest_audio"
                AttachmentType.VIDEO -> "latest_video"
                else -> ""
            }

            val (recipient, caption) = extractRecipientAndMessage(goal)

            return AttachmentInfo(
                attachmentType = attachmentType,
                fileFilter = fileFilter,
                recipient = recipient,
                caption = if (caption == "Hello!") "" else caption
            )
        }

        private fun cleanRecipient(raw: String): String {
            val stopWords = setOf("ek", "yeh", "please", "kripya", "to", "the", "a", "contact")
            val tokens = raw.trim().split(Regex("\\s+")).filter { it.lowercase(Locale.ROOT) !in stopWords }
            val joined = tokens.joinToString(" ").trim()
            if (joined.isBlank()) return ""
            return if (joined.all { it.isDigit() || it == '+' || it == ' ' || it == '-' }) {
                joined
            } else {
                joined.split(" ").joinToString(" ") { word ->
                    word.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
                }
            }
        }

        private fun cleanMessage(raw: String): String {
            var msg = raw.trim()
            val trailingVerbs = listOf("send karo", "bhejo", "karo", "likho", "send", "bolo")
            for (verb in trailingVerbs) {
                if (msg.lowercase(Locale.ROOT).endsWith(verb)) {
                    msg = msg.substring(0, msg.length - verb.length).trim()
                }
            }
            if (msg.isBlank() || msg.equals("hello", ignoreCase = true)) return "Hello!"
            if (msg.equals("kaise ho", ignoreCase = true)) return "Kaise ho?"
            return msg.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.ROOT) else it.toString() }
        }
    }
}

class WhatsAppMessagingSkill : CommunicationSkill(actionTypePrefix = "WHATSAPP")
