package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.AlarmClock
import android.util.Log
import com.jarvis.foundation.RiskLevel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Clock, Timer & World Clock Tool with Android OS alarm clock integration.
 */
class ClockTool(private val context: Context) : Tool {
    private val TAG = "ClockTool"

    override val name: String = "CLOCK"
    override val description: String =
        "Sets countdown timers and checks world clock time in major cities. Actions: 'set_timer' (minutes/seconds, label), 'world_clock' (city or timezone), 'show_timers'."
    override val policy: ToolPolicy = ToolPolicy(idempotent = false, retryable = true, riskLevel = RiskLevel.LOW)

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "set_timer"

        return when (action) {
            "set_timer", "timer" -> {
                val mins = params["minutes"]?.toIntOrNull() ?: params["min"]?.toIntOrNull() ?: 0
                val secs = params["seconds"]?.toIntOrNull() ?: params["sec"]?.toIntOrNull() ?: 0
                val totalSeconds = (mins * 60) + secs
                val finalSeconds = if (totalSeconds > 0) totalSeconds else 300 // default 5 mins

                val label = params["label"]?.trim()?.ifBlank { "Jarvis Timer" } ?: "Jarvis Timer"

                try {
                    val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(AlarmClock.EXTRA_LENGTH, finalSeconds)
                        putExtra(AlarmClock.EXTRA_MESSAGE, label)
                        putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(intent)

                    val displayTime = if (finalSeconds >= 60) "${finalSeconds / 60} minute(s)" else "$finalSeconds seconds"
                    Log.i(TAG, "Started timer for $displayTime: '$label'")
                    ToolResult.Success(
                        message = "Timer set for $displayTime ($label).",
                        data = mapOf("seconds" to finalSeconds, "label" to label)
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch native timer intent: ${e.message}")
                    ToolResult.Failed("Could not set system timer: ${e.message}")
                }
            }

            "show_timers", "list_timers" -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
                        val intent = Intent(AlarmClock.ACTION_SHOW_TIMERS).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(intent)
                        ToolResult.Success("Opening system timers.")
                    } else {
                        ToolResult.Success("System timers active.")
                    }
                } catch (e: Exception) {
                    ToolResult.Failed("Could not show timers: ${e.message}")
                }
            }

            "world_clock", "time", "time_in" -> {
                val query = params["city"] ?: params["timezone"] ?: params["location"] ?: "London"
                val tz = resolveTimeZone(query)
                val sdf = SimpleDateFormat("h:mm a, EEEE, MMM d", Locale.getDefault()).apply {
                    timeZone = tz
                }
                val formattedTime = sdf.format(Date())
                val summary = "Current time in $query is $formattedTime."
                Log.i(TAG, "World clock for $query (${tz.id}): $summary")
                ToolResult.Success(
                    message = summary,
                    data = mapOf("city" to query, "time" to formattedTime, "timezone" to tz.id)
                )
            }

            else -> ToolResult.Failed("Unknown clock action '$action'")
        }
    }

    private fun resolveTimeZone(city: String): TimeZone {
        val lower = city.lowercase().trim()
        val tzId = when {
            lower.contains("new york") || lower.contains("nyc") || lower.contains("est") -> "America/New_York"
            lower.contains("california") || lower.contains("los angeles") || lower.contains("san francisco") || lower.contains("pst") -> "America/Los_Angeles"
            lower.contains("chicago") || lower.contains("cst") -> "America/Chicago"
            lower.contains("london") || lower.contains("uk") || lower.contains("gmt") || lower.contains("utc") -> "Europe/London"
            lower.contains("paris") || lower.contains("berlin") || lower.contains("rome") || lower.contains("cet") -> "Europe/Paris"
            lower.contains("dubai") || lower.contains("uae") -> "Asia/Dubai"
            lower.contains("tokyo") || lower.contains("japan") || lower.contains("jst") -> "Asia/Tokyo"
            lower.contains("sydney") || lower.contains("australia") -> "Australia/Sydney"
            lower.contains("singapore") -> "Asia/Singapore"
            lower.contains("mumbai") || lower.contains("delhi") || lower.contains("india") || lower.contains("ist") -> "Asia/Kolkata"
            else -> TimeZone.getDefault().id
        }
        return TimeZone.getTimeZone(tzId)
    }
}
