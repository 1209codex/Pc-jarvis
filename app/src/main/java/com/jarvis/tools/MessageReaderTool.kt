package com.jarvis.tools

import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.messaging.WhatsAppMessagingEngine
import com.jarvis.telecom.CallAndSmsAgent

/**
 * Reads recent SMS and WhatsApp messages into a clean digest. Reuses the live
 * engine singletons; if they aren't running the answer is an honest "not
 * available yet" rather than a fabricated digest.
 */
class MessageReaderTool : Tool {
    override val name: String = "MESSAGE_READER"
    override val description: String =
        "Reads recent SMS and WhatsApp messages. Actions: read_sms, read_whatsapp, read_all. Optional filters: sender, limit."

    val metadata = ToolMetadata(
        name = "MESSAGE_READER",
        description = "Reads recent incoming SMS and WhatsApp messages and summarises them.",
        parameters = emptyList(),
        riskLevel = RiskLevel.MEDIUM
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "read_all"
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val sender = params["sender"]

        val smsLines = mutableListOf<String>()
        val waLines = mutableListOf<String>()

        when (action) {
            "read_sms" -> readSms(limit, sender, smsLines)
            "read_whatsapp" -> readWhatsApp(limit, sender, waLines)
            "read_all" -> {
                readSms(limit, sender, smsLines)
                readWhatsApp(limit, sender, waLines)
            }
            else -> return ToolResult.Failed("Unknown action '$action'. Supported: read_sms, read_whatsapp, read_all.")
        }

        return when {
            action == "read_whatsapp" && waLines.isEmpty() && smsLines.isEmpty() -> {
                val engineErr = if (WhatsAppMessagingEngine.instance == null) " WhatsApp isn't active." else ""
                ToolResult.Failed("No unread WhatsApp messages.$engineErr")
            }
            waLines.isEmpty() && smsLines.isEmpty() -> ToolResult.Failed("No unread messages from that source.")
            else -> {
                val digest = MessageDigestFormat.buildDigest(smsLines, waLines)
                ToolResult.Success(digest, mapOf("sms" to smsLines.size.toString(), "whatsapp" to waLines.size.toString()))
            }
        }
    }

    private fun readSms(limit: Int, sender: String?, out: MutableList<String>) {
        val agent = CallAndSmsAgent.instance ?: return
        agent.readRecentSms(limit = limit, senderFilter = sender).forEach {
            out.add(MessageDigestFormat.smsLine(it.contactName.ifBlank { it.sender }, it.body))
        }
    }

    private fun readWhatsApp(limit: Int, sender: String?, out: MutableList<String>) {
        val engine = WhatsAppMessagingEngine.instance ?: return
        val result = engine.readRecentWhatsApp(limit = limit, senderFilter = sender)
        if (result.success) {
            result.message.split("\n").filter { it.isNotBlank() && !it.startsWith("You have no unread") }.forEach { out.add(it) }
        }
    }
}