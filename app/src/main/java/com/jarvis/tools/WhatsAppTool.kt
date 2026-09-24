package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.messaging.AutoReplyMode
import com.jarvis.messaging.WhatsAppMessagingEngine

class WhatsAppTool(
    private val context: Context? = null,
    private val messagingEngine: WhatsAppMessagingEngine? = null
) : Tool {
    override val name: String = "WHATSAPP"
    override val description: String = "Autonomous WhatsApp & WhatsApp Business messaging tool. Actions: 'send_message' (sends to recipient with text), 'send_direct_message' (sends directly via phone/contact URI), 'read_messages' (reads and summarizes recent unread WhatsApp/WhatsApp Business messages), 'reply' (interactively drafts and replies to a contact), 'auto_reply' (configures driving/meeting/off auto-reply mode), 'open' (opens WhatsApp or WhatsApp Business)."

    private val TAG = "WhatsAppTool"

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val recipient = params["recipient"]?.trim()
        val message = params["message"]?.trim()
        val platform = params["platform"]?.trim()
        val rawAction = params["action"]?.trim()?.lowercase()

        val action = rawAction ?: when {
            params.containsKey("reply") || params.containsKey("respond") -> "reply"
            !recipient.isNullOrBlank() || !message.isNullOrBlank() -> "send_message"
            params.containsKey("mode") -> "auto_reply"
            else -> "open"
        }

        return try {
            val engine = messagingEngine ?: WhatsAppMessagingEngine.instance ?: WhatsAppMessagingEngine(context)

            when (action) {
                "open" -> {
                    val targetPkg = WhatsAppMessagingEngine.resolveTargetPackage(platform)
                    val pkgLabel = if (targetPkg == "com.whatsapp.w4b") "WhatsApp Business" else "WhatsApp"
                    val ctx = context ?: return ToolResult.Success("$pkgLabel opened (Simulation mode).")
                    val intent = ctx.packageManager.getLaunchIntentForPackage(targetPkg)
                        ?: return ToolResult.Failed("$pkgLabel is not installed on this device.")
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    ctx.startActivity(intent)
                    ToolResult.Success("$pkgLabel opened.")
                }

                "send_message", "send" -> {
                    if (recipient.isNullOrBlank() || message.isNullOrBlank()) {
                        return ToolResult.Failed("Recipient and message are required to send a WhatsApp message.")
                    }
                    engine.sendMessage(recipient, message, platform)
                }

                "send_direct_message", "direct_send", "direct_message", "send_direct" -> {
                    if (recipient.isNullOrBlank() || message.isNullOrBlank()) {
                        return ToolResult.Failed("Recipient and message are required to send a direct WhatsApp message.")
                    }
                    engine.sendDirectMessage(recipient, message, platform)
                }

                "reply", "reply_message", "respond" -> {
                    if (recipient.isNullOrBlank()) {
                        val recent = engine.readRecentWhatsApp(limit = 5, platformFilter = platform)
                        ToolResult.Success("Who would you like me to reply to? ${recent.message}")
                    } else if (message.isNullOrBlank()) {
                        ToolResult.Success("What would you like me to say to $recipient?")
                    } else {
                        engine.sendMessage(recipient, message, platform)
                    }
                }

                "read_messages", "read_unread", "check_unread", "read" -> {
                    val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 10) ?: 5
                    val senderFilter = params["sender"]?.trim()
                    val platformFilter = params["platform"]?.trim()
                    engine.readRecentWhatsApp(limit = limit, senderFilter = senderFilter, platformFilter = platformFilter)
                }

                "auto_reply" -> {
                    val modeStr = params["mode"]?.trim()?.lowercase() ?: "driving"
                    val template = params["template"]?.trim()

                    val targetMode = mapOf(
                        "off" to AutoReplyMode.OFF, "disable" to AutoReplyMode.OFF, "stop" to AutoReplyMode.OFF,
                        "driving" to AutoReplyMode.DRIVING, "drive" to AutoReplyMode.DRIVING,
                        "meeting" to AutoReplyMode.MEETING, "meet" to AutoReplyMode.MEETING,
                        "busy" to AutoReplyMode.BUSY,
                        "custom" to AutoReplyMode.CUSTOM,
                        "on" to AutoReplyMode.DRIVING, "enable" to AutoReplyMode.DRIVING
                    )[modeStr] ?: AutoReplyMode.OFF

                    engine.setAutoReplyMode(targetMode, template)

                    if (targetMode == AutoReplyMode.OFF)
                        ToolResult.Success("WhatsApp auto-reply has been turned off.")
                    else
                        ToolResult.Success("WhatsApp auto-reply active in ${targetMode.name} mode (10-min per-contact cooldown).")
                }

                else -> ToolResult.Failed("Unknown WhatsApp action: $action")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error executing WhatsApp action: ${e.message}", e)
            ToolResult.Failed("WhatsApp execution failed: ${e.message}")
        }
    }
}

