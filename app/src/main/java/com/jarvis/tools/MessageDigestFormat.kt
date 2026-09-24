package com.jarvis.tools

/**
 * Pure digest builder for unread messages, so the spoken summary rules are
 * unit-testable without any device state.
 */
object MessageDigestFormat {

    fun truncate(text: String, max: Int = 140): String =
        if (text.length <= max) text else text.take(max - 1) + "…"

    fun smsLine(sender: String, body: String): String = "$sender: ${truncate(body)}"

    /** Whole digest; empty input yields the "nothing" line so reads are never mysteriously blank. */
    fun buildDigest(smsLines: List<String>, whatsAppLines: List<String>, nothingMessage: String = "You have no unread messages."): String {
        val parts = mutableListOf<String>()
        if (smsLines.isNotEmpty()) {
            parts.add("SMS:")
            parts.addAll(smsLines)
        }
        if (whatsAppLines.isNotEmpty()) {
            if (smsLines.isNotEmpty()) parts.add("")
            parts.add("WhatsApp:")
            parts.addAll(whatsAppLines)
        }
        return if (parts.isEmpty()) nothingMessage else parts.joinToString("\n")
    }
}