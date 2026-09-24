package com.jarvis.logs

import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedDeque

data class TelemetryLogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val tag: String,
    val level: String,
    val message: String
)

class LogReaderEngine {

    companion object {
        private const val TAG = "LogReaderEngine"
        private const val MAX_IN_MEMORY_LOGS = 100

        @Volatile
        var instance: LogReaderEngine? = null
            internal set
    }

    init {
        instance = this
    }

    private val inMemoryLogs = ConcurrentLinkedDeque<TelemetryLogEntry>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)

    fun recordLog(tag: String, level: String = "INFO", message: String) {
        val entry = TelemetryLogEntry(
            timestamp = System.currentTimeMillis(),
            tag = tag,
            level = level,
            message = message
        )
        inMemoryLogs.addLast(entry)
        while (inMemoryLogs.size > MAX_IN_MEMORY_LOGS) {
            inMemoryLogs.pollFirst()
        }
    }

    /**
     * Reads recent logcat output from Android or fallback in-memory ring buffer.
     */
    fun readLogcat(maxLines: Int = 30, filter: String? = null): List<String> {
        val results = mutableListOf<String>()

        // 1. Try reading live Android logcat
        runCatching {
            val count = maxLines.coerceIn(5, 100)
            val cmd = arrayOf("logcat", "-d", "-v", "time", "-t", count.toString())
            val process = Runtime.getRuntime().exec(cmd)

            // Drain stderr on a background thread to prevent the subprocess blocking on a full pipe
            val stderrThread = Thread { runCatching { process.errorStream.use { it.readBytes() } } }
            stderrThread.isDaemon = true
            stderrThread.start()

            val reader = BufferedReader(InputStreamReader(process.inputStream))
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val current = line ?: continue
                    if (filter.isNullOrBlank()) {
                        // Filter for Jarvis-relevant components
                        val isJarvisRelevant = current.contains("Jarvis", ignoreCase = true) ||
                                current.contains("AgentKernel", ignoreCase = true) ||
                                current.contains("VoiceEngine", ignoreCase = true) ||
                                current.contains("AndroidSpeech", ignoreCase = true) ||
                                current.contains("ToolExecutor", ignoreCase = true) ||
                                current.contains("GroqLlm", ignoreCase = true) ||
                                current.contains("AndroidTts", ignoreCase = true)
                        if (isJarvisRelevant) {
                            results.add(current)
                        }
                    } else {
                        if (current.contains(filter, ignoreCase = true)) {
                            results.add(current)
                        }
                    }
                }
            } finally {
                reader.close()
                process.destroy()
            }
        }.onFailure { e ->
            Log.w(TAG, "Logcat execution unavailable, falling back to in-memory logs: ${e.message}")
        }

        // 2. Fallback to in-memory logs if logcat yielded fewer lines
        if (results.isEmpty()) {
            val memEntries = inMemoryLogs.toList()
            val filteredMem = if (!filter.isNullOrBlank()) {
                memEntries.filter {
                    it.tag.contains(filter, ignoreCase = true) ||
                            it.message.contains(filter, ignoreCase = true) ||
                            it.level.contains(filter, ignoreCase = true)
                }
            } else {
                memEntries
            }

            for (entry in filteredMem.takeLast(maxLines)) {
                val timeStr = timeFormat.format(Date(entry.timestamp))
                results.add("$timeStr [${entry.level}] ${entry.tag}: ${entry.message}")
            }
        }

        return results.takeLast(maxLines)
    }

    /**
     * Formats an audio-friendly and readable summary of recent device activity.
     */
    fun getFormattedSummary(maxLines: Int = 20, filter: String? = null): String {
        val lines = readLogcat(maxLines, filter)
        if (lines.isEmpty()) {
            return "No recent log entries found. All systems are operating normally in 100% Autonomous mode."
        }

        // Extract key events: commands, tools executed, errors
        val recentCommands = lines.filter { it.contains("Final Speech Result", ignoreCase = true) || it.contains("Runtime executing command", ignoreCase = true) }
        val toolsExecuted = lines.filter { it.contains("Executing tool", ignoreCase = true) }
        val errors = lines.filter { it.contains(" W ", ignoreCase = true) || it.contains(" E ", ignoreCase = true) || it.contains("error", ignoreCase = true) || it.contains("HTTP 429", ignoreCase = true) }

        val summary = buildString {
            append("Recent System Telemetry: ")
            if (recentCommands.isNotEmpty()) {
                val lastCmd = recentCommands.last().substringAfterLast(":")
                append("Last command recognized: ${lastCmd.trim()}. ")
            }
            if (toolsExecuted.isNotEmpty()) {
                val lastTool = toolsExecuted.last().substringAfterLast("Executing tool")
                append("Last tool run: ${lastTool.trim()}. ")
            }
            if (errors.isNotEmpty()) {
                append("Noticed ${errors.size} warning or notice events in recent logs. ")
            } else {
                append("No critical system errors. ")
            }
            append("Showing ${lines.size} log records.")
        }

        return summary
    }

    fun getRecentErrors(maxLines: Int = 15): List<String> {
        val all = readLogcat(50, null)
        return all.filter {
            it.contains(" E ", ignoreCase = true) ||
                    it.contains(" W ", ignoreCase = true) ||
                    it.contains("error", ignoreCase = true) ||
                    it.contains("warn", ignoreCase = true) ||
                    it.contains("exception", ignoreCase = true) ||
                    it.contains("fail", ignoreCase = true) ||
                    it.contains("429", ignoreCase = true)
        }.takeLast(maxLines)
    }
}
