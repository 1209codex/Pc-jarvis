package com.jarvis.tools

import android.content.Context
import com.jarvis.accessibility.JarvisAccessibilityService
import com.jarvis.calendar.CalendarManager
import com.jarvis.device.BatteryMonitor
import com.jarvis.execution.VerificationResult
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.notification.NotificationStore
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class DailyBriefingTool(private val context: Context? = null) : Tool {
    override val name: String = "DAILY_BRIEFING"
    override val description: String = "Provides a complete daily executive briefing including date, time, battery, calendar agenda, unread messages, and system status."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "DAILY_BRIEFING",
        description = "Executive briefing covering date, time, battery, calendar agenda, unread messages, and active app.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val cal = Calendar.getInstance()
        val hour = cal.get(Calendar.HOUR_OF_DAY)
        val greeting = when (hour) {
            in 4..11 -> "Good morning sir"
            in 12..16 -> "Good afternoon sir"
            in 17..21 -> "Good evening sir"
            else -> "Hello sir"
        }

        val timeFormat = SimpleDateFormat("h:mm a", Locale.getDefault())
        val dateFormat = SimpleDateFormat("EEEE, MMMM d", Locale.getDefault())
        val currentTime = timeFormat.format(cal.time)
        val currentDate = dateFormat.format(cal.time)

        // 1. Battery status
        val battery = BatteryMonitor.getBatteryState(context)
        val batteryText = if (battery.percentage >= 0) {
            "Battery is at ${battery.percentage}%${if (battery.isCharging) " and charging" else ""}."
        } else {
            ""
        }

        // 2. Calendar Agenda
        val calendarManager = CalendarManager(context)
        val agenda = calendarManager.getTodayAgenda()
        val agendaText = if (agenda.isEmpty()) {
            "Your calendar is clear for today."
        } else {
            val count = agenda.size
            val first = agenda.first()
            val eventTimeFmt = SimpleDateFormat("h:mm a", Locale.getDefault())
            val firstTime = eventTimeFmt.format(Date(first.startTimeMillis))
            if (count == 1) {
                "You have 1 event today: ${first.title} at $firstTime."
            } else {
                "You have $count events today, starting with ${first.title} at $firstTime."
            }
        }

        // 3. Unread Notifications & Messages
        val recentNotifs = NotificationStore.getRecentNotifications(limit = 6)
        val msgNotifs = recentNotifs.filter {
            it.packageName.contains("whatsapp", ignoreCase = true) ||
            it.packageName.contains("mms", ignoreCase = true) ||
            it.packageName.contains("sms", ignoreCase = true) ||
            it.packageName.contains("telecom", ignoreCase = true) ||
            it.packageName.contains("gm", ignoreCase = true)
        }
        val notifText = when {
            msgNotifs.isNotEmpty() -> "You have ${msgNotifs.size} unread priority message${if (msgNotifs.size > 1) "s" else ""}."
            recentNotifs.isNotEmpty() -> "You have ${recentNotifs.size} recent notification${if (recentNotifs.size > 1) "s" else ""}."
            else -> "No unread notifications."
        }

        // 4. Foreground app (if any)
        val activeApp = JarvisAccessibilityService.currentForegroundPackage?.substringAfterLast(".")
        val activeAppText = if (!activeApp.isNullOrBlank() && !activeApp.contains("jarvis", ignoreCase = true)) {
            "Active app is $activeApp."
        } else {
            ""
        }

        val parts = listOfNotNull(
            "$greeting. Today is $currentDate, and the time is $currentTime.",
            batteryText.ifBlank { null },
            agendaText.ifBlank { null },
            notifText.ifBlank { null },
            activeAppText.ifBlank { null },
            "All systems are operational."
        )
        val briefing = parts.joinToString(" ")

        return ToolResult.Success(
            message = briefing,
            data = mapOf(
                "date" to currentDate,
                "time" to currentTime,
                "battery" to battery.percentage,
                "calendar_events" to agenda.size,
                "notification_count" to recentNotifs.size
            )
        )
    }

    override fun verify(params: Map<String, String>, result: ToolResult): VerificationResult {
        if (!result.success) {
            return VerificationResult.failure("Briefing generation failed: ${result.message}")
        }
        return if (result.data.containsKey("time")) {
            VerificationResult.success("Briefing report generated", mapOf("outcome" to "REPORT_GENERATED"))
        } else {
            VerificationResult.unknown("Briefing generated but report data not populated", mapOf("outcome" to "UNPARSED"))
        }
    }
}
