package com.jarvis.ui.reliability

import android.content.Context
import android.util.Log
import com.jarvis.ui.data.UiPreferencesStore
import java.util.UUID

class CorrelationLogger(context: Context) {
    private val store = UiPreferencesStore(context)

    fun info(tag: String, message: String): String = write("INFO", tag, message)
    fun warn(tag: String, message: String): String = write("WARN", tag, message)
    fun error(tag: String, message: String, throwable: Throwable? = null): String =
        write("ERROR", tag, message + (throwable?.message?.let { ": $it" } ?: ""))

    fun correlationId(): String = UUID.randomUUID().toString()

    private fun write(level: String, tag: String, message: String): String {
        val id = UUID.randomUUID().toString().take(12)
        val line = "${System.currentTimeMillis()} [$level] [$id] $tag: ${message.replace(Regex("[\\r\\n]"), " ")}"
        when (level) {
            "ERROR" -> Log.e(tag, line)
            "WARN" -> Log.w(tag, line)
            else -> Log.i(tag, line)
        }
        store.appendLog(line)
        return id
    }
}
