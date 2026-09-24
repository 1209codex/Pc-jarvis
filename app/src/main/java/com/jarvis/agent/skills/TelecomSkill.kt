package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import java.util.Locale

class TelecomSkill : Skill {
    override val id: String = "telecom_intelligence"
    override val name: String = "Context-Aware Telecom & Call/SMS Skill"
    override val description: String = "Handles incoming and outgoing phone calls, hands-free call answering/rejection, contextual SMS, and reading incoming SMS/OTPs in English and Hindi."
    override val triggers: List<String> = listOf(
        "call", "phone", "dial", "ring", "answer", "reject", "decline", "pickup", "pick up",
        "phone uthao", "call uthao", "call kaat", "phone kaat", "phone lagao", "call karo",
        "who is calling", "kiska call", "kiska phone", "sms", "text", "message", "otp", "verification code",
        "search contact", "find contact", "contact number", "number batao",
        "auto reply", "auto-reply", "driving mode auto reply", "meeting mode auto reply", "auto reply band karo", "turn off auto reply"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase(Locale.ROOT).trim()
        // WhatsApp messages should go to WhatsAppMessagingSkill unless phone calls or SMS are explicitly indicated
        if (lower.contains("whatsapp") && !lower.contains("auto reply") && !lower.contains("auto-reply")) return false

        val matched = listOf(
            "answer", "reject", "decline", "phone uthao", "call uthao", "call kaat", "phone kaat",
            "who is calling", "kiska call", "kiska phone", "read sms", "sms padho", "otp", "verification code",
            "call", "phone", "dial", "sms", "text", "message bhejo",
            "search contact", "find contact", "contact number", "number batao",
            "auto reply", "auto-reply"
        )
        return matched.any { lower.contains(it) }
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase(Locale.ROOT).trim()

        // 0. Auto-Reply Configuration
        if (lower.contains("auto reply") || lower.contains("auto-reply")) {
            val mode = when {
                lower.contains("off") || lower.contains("band") || lower.contains("stop") || lower.contains("disable") -> "off"
                lower.contains("meeting") || lower.contains("meet") -> "meeting"
                lower.contains("busy") -> "busy"
                lower.contains("drive") || lower.contains("driving") -> "driving"
                else -> "driving"
            }
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("TELEPHONY_CONTROL", mapOf("action" to "auto_reply", "mode" to mode)),
                explanation = "Configuring unified auto-reply mode to: $mode"
            )
        }

        // 1. Answer Call
        val answerTriggers = listOf("answer call", "answer the call", "answer", "pick up the call", "pick up", "pickup", "phone uthao", "call uthao", "call receive karo", "phone receive karo")
        if (answerTriggers.any { lower == it || lower.startsWith("$it ") || lower.endsWith(" $it") }) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("TELEPHONY_CONTROL", mapOf("action" to "answer_call")),
                explanation = "Answering incoming phone call hands-free"
            )
        }

        // 2. Reject Call (with optional auto-reply SMS)
        val rejectTriggers = listOf("reject call", "reject the call", "reject", "decline call", "decline the call", "decline", "call kaat do", "phone kaat do", "call kato", "phone kato", "call dismiss karo")
        val isReject = rejectTriggers.any { lower.contains(it) }
        if (isReject) {
            val smsPart = when {
                lower.contains("saying") -> lower.substringAfter("saying").trim()
                lower.contains("that") -> lower.substringAfter("that").trim()
                lower.contains("ki") -> lower.substringAfter("ki").trim()
                lower.contains("sms") -> lower.substringAfter("sms").trim()
                lower.contains("message") -> lower.substringAfter("message").trim()
                lower.contains("bol do") -> lower.substringAfter("bol do").trim()
                else -> ""
            }.replace(Regex("^(?:ki|that|saying|bhej do)?\\s*", RegexOption.IGNORE_CASE), "").trim()

            val params = mutableMapOf("action" to "reject_call")
            if (smsPart.isNotBlank()) {
                params["message"] = smsPart
            }

            return SkillResult(
                handled = true,
                proposedAction = AgentAction("TELEPHONY_CONTROL", params),
                explanation = "Rejecting phone call${if (smsPart.isNotBlank()) " with SMS: \"$smsPart\"" else ""}"
            )
        }

        // 3. Who is calling / Caller ID inspection
        val callerInfoTriggers = listOf("who is calling", "who's calling", "kiska call hai", "kiska phone hai", "kiska call aa raha hai", "caller id", "who is on the phone")
        if (callerInfoTriggers.any { lower.contains(it) }) {
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("TELEPHONY_CONTROL", mapOf("action" to "caller_info")),
                explanation = "Checking active incoming caller identity"
            )
        }

        // 4. Read SMS & OTP Extraction
        val smsReadTriggers = listOf("read sms", "read my sms", "sms padho", "sms padh", "check sms", "koi sms aaya", "otp", "verification code", "otp batao", "otp kya aaya", "what is my otp", "what's my otp")
        if (smsReadTriggers.any { lower.contains(it) }) {
            val isOtp = lower.contains("otp") || lower.contains("code") || lower.contains("pin")
            val params = mutableMapOf(
                "action" to "sms_read",
                "otp_only" to isOtp.toString()
            )

            // Optional sender filter e.g. "read sms from mom"
            val senderMatch = Regex("(?:from|ka|ke)\\s+([a-zA-Z0-9_+]+)").find(lower)
            val senderFilter = senderMatch?.groupValues?.get(1)?.trim()
            if (!senderFilter.isNullOrBlank() && senderFilter !in listOf("me", "my", "sms", "phone")) {
                params["sender"] = senderFilter
            }

            return SkillResult(
                handled = true,
                proposedAction = AgentAction("TELEPHONY_CONTROL", params),
                explanation = if (isOtp) "Extracting verification OTP code from SMS" else "Reading recent incoming SMS messages"
            )
        }

        // 5. SMS Sending & Drafting (Outbound)
        if (lower.contains("sms") || lower.contains("text") || lower.contains("message bhejo") || lower.contains("send message")) {
            // A recipient-only send request is incomplete; never turn the contact into the body.
            if (Regex("^send\\s+(?:message|sms|text)\\s+(?:to|ko)\\s+\\S+$").matches(lower)) {
                return SkillResult(
                    handled = true,
                    proposedAction = null,
                    explanation = "Recipient and exact SMS text are required before sending."
                )
            }
            val explicitPrefix = listOf(
                "send message to ", "message to ", "send sms to ", "sms to ", "text to ",
                "send message ko ", "message ko ", "send sms ko ", "sms ko ", "text ko "
            ).firstOrNull { lower.startsWith(it) }
            val explicitRemainder = explicitPrefix?.let { lower.removePrefix(it).trim() }
            var recipient = ""
            var body = ""
            if (!explicitRemainder.isNullOrBlank()) {
                val parts = explicitRemainder.trim().split(Regex("\\s+"), limit = 2)
                recipient = parts.firstOrNull().orEmpty()
                body = parts.getOrNull(1).orEmpty()
                    .replace(Regex("^(?:saying|that|ki|likho)\\s+"), "")
                    .trim()
            } else {
                val contactMatch = Regex("(?:sms|text|message(?:\\s+bhejo)?)(?:\\s+to|\\s+ko)?\\s+([a-zA-Z0-9_+]+)(?:\\s+(?:saying|that|ki|likho)\\s+(.+))?").find(lower)
                recipient = contactMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
                body = contactMatch?.groupValues?.getOrNull(2)?.trim().orEmpty()
            }

            // Also accept natural speech without a "saying/that/ki" marker:
            // "send message to Alex I am late".
            if (body.isBlank() && recipient !in setOf("to", "ko")) {
                val looseMatch = Regex(
                    "(?:sms|text|message(?:\\s+bhejo)?)(?:\\s+to|\\s+ko)?\\s+([a-zA-Z0-9_+]+)\\s+(.+)$"
                ).find(lower)
                if (looseMatch != null) {
                    recipient = looseMatch.groupValues[1].trim()
                    body = looseMatch.groupValues[2].trim()
                }
            }

            if (recipient.isBlank()) {
                val words = lower.split(" ")
                val toIdx = words.indexOf("to").takeIf { it >= 0 } ?: words.indexOf("ko").takeIf { it >= 0 }
                if (toIdx != null && toIdx + 1 < words.size) {
                    recipient = words[toIdx + 1]
                }
            }

            val sendRequested = lower.contains("send") || lower.contains("bhejo") || lower.contains("bhej do")
            if (sendRequested && body.isBlank()) {
                return SkillResult(
                    handled = true,
                    proposedAction = null,
                    explanation = "Recipient and exact SMS text are required before sending."
                )
            }

            val params = mutableMapOf<String, String>()
            if (body.isNotBlank()) {
                params["action"] = "sms_send"
                params["recipient"] = recipient
                params["message"] = body
            } else {
                params["action"] = "sms_draft"
                params["recipient"] = recipient
            }

            return SkillResult(
                handled = true,
                proposedAction = AgentAction("TELEPHONY_CONTROL", params),
                explanation = "Composing SMS to $recipient"
            )
        }

        // 6. Search contact
        if (lower.contains("search contact") || lower.contains("find contact") || lower.contains("contact number") || lower.contains("number batao")) {
            val target = lower.replace(Regex("\\b(search|find|contact|number|batao|ka|ki)\\b", RegexOption.IGNORE_CASE), " ")
                .replace(Regex("\\s+"), " ").trim()
            return SkillResult(
                handled = true,
                proposedAction = AgentAction("TELEPHONY_CONTROL", mapOf("action" to "search_contact", "recipient" to target)),
                explanation = "Searching contact for: $target"
            )
        }

        // 7. Outbound Phone Call / Dial
        val isDial = lower.startsWith("dial")
        val cleanTarget = lower.replace(Regex("\\b(call|phone|dial|karo|lagao|mila|ko|please)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ").trim()

        val action = if (isDial) "dial" else "call"
        return SkillResult(
            handled = true,
            proposedAction = AgentAction("TELEPHONY_CONTROL", mapOf("action" to action, "recipient" to cleanTarget)),
            explanation = "Initiating phone call to: $cleanTarget"
        )
    }
}
