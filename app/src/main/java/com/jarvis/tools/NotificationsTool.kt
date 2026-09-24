package com.jarvis.tools

import android.content.Context
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.notification.JarvisNotificationListenerService
import com.jarvis.notification.NotificationStore

class NotificationsTool(private val context: Context? = null) : Tool {
    override val name: String = "NOTIFICATIONS_READ"
    override val description: String = "Reads and summarizes incoming device notifications (WhatsApp, SMS, Email, etc.)."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "NOTIFICATIONS_READ",
        description = "Fetches recent intercepted notifications from apps like WhatsApp, Messages, etc.",
        parameters = listOf(
            ParameterSchema("app", "string", "Optional app filter, e.g. 'whatsapp' or 'messages'", required = false),
            ParameterSchema("limit", "string", "Maximum notifications to return (default 5)", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        if (context != null && !JarvisNotificationListenerService.isNotificationAccessGranted(context)) {
            return ToolResult.Success(
                "Notification access is not enabled. Please enable Notification Access in Jarvis Settings so I can read your messages."
            )
        }

        val action = params["action"]?.trim()?.lowercase() ?: "read"
        val appFilter = params["app"]?.trim()?.ifBlank { null }
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 15) ?: 5

        when (action) {
            "digest", "summary", "triage", "brief" -> {
                val digest = NotificationStore.getDigestSummary()
                return ToolResult.Success(
                    message = digest.conversationalSummary,
                    data = mapOf(
                        "action" to "digest",
                        "priority_count" to digest.priorityCount,
                        "promotion_count" to digest.promotionCount,
                        "total_count" to digest.totalCount
                    )
                )
            }
            "clear_spam", "clear_promotions", "dismiss_spam" -> {
                val cleared = NotificationStore.clearPromotions()
                return ToolResult.Success(
                    message = "Cleared $cleared promotional notification${if (cleared == 1) "" else "s"}.",
                    data = mapOf("action" to "clear_spam", "cleared_count" to cleared)
                )
            }
            "priority" -> {
                val items = NotificationStore.getPriorityNotifications(limit = limit)
                if (items.isEmpty()) {
                    return ToolResult.Success("You have no priority or personal notifications right now.")
                }
                val summary = buildString {
                    append("You have ${items.size} priority notification${if (items.size > 1) "s" else ""}: ")
                    items.forEachIndexed { idx, item ->
                        val sender = if (item.title.isNotBlank()) "${item.title} on ${item.appName}" else item.appName
                        val body = item.text.ifBlank { "Notification posted" }
                        append("${idx + 1}. $sender: \"$body\"${if (idx < items.size - 1) ". " else "."}")
                    }
                }
                return ToolResult.Success(
                    message = summary,
                    data = mapOf(
                        "action" to "priority",
                        "count" to items.size,
                        "notifications" to items.map { mapOf("app" to it.appName, "title" to it.title, "text" to it.text) }
                    )
                )
            }
            else -> {
                val items = NotificationStore.getRecentNotifications(limit = limit, appFilter = appFilter)
                if (items.isEmpty()) {
                    val filterNote = if (appFilter != null) " from $appFilter" else ""
                    return ToolResult.Success("You have no new notifications$filterNote right now.")
                }

                val summary = buildString {
                    append("You have ${items.size} recent notification${if (items.size > 1) "s" else ""}: ")
                    items.forEachIndexed { idx, item ->
                        val sender = if (item.title.isNotBlank()) "${item.title} on ${item.appName}" else item.appName
                        val body = item.text.ifBlank { "Notification posted" }
                        append("${idx + 1}. $sender: \"$body\"${if (idx < items.size - 1) ". " else "."}")
                    }
                }

                return ToolResult.Success(
                    message = summary,
                    data = mapOf(
                        "action" to "read",
                        "count" to items.size,
                        "notifications" to items.map { mapOf("app" to it.appName, "title" to it.title, "text" to it.text) }
                    )
                )
            }
        }
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Notification read failed: ${result.message}")
        }
        val text = result.message
        return when {
            result.data.containsKey("notifications") -> VerificationResult.success(
                "Notifications read (${result.data["count"]})",
                mapOf("count" to "${result.data["count"]}", "outcome" to "READBACK")
            )
            text.contains("no new notifications", ignoreCase = true) -> VerificationResult.success(
                "Notification store queried; none to read",
                mapOf("count" to "0", "outcome" to "EMPTY")
            )
            text.contains("access is not enabled", ignoreCase = true) -> VerificationResult.unknown(
                "Notification access not enabled; nothing could be read",
                mapOf("outcome" to "ACCESS_DENIED")
            )
            else -> VerificationResult.unknown("Notification read returned an unexpected response", mapOf("outcome" to "UNRECOGNIZED"))
        }
    }
}
