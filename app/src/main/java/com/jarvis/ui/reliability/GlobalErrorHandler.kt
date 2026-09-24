package com.jarvis.ui.reliability

import android.content.Context
import android.os.Process
import android.util.Log

object GlobalErrorHandler {
    private var installed = false

    fun install(context: Context) {
        if (installed) return
        installed = true
        val logger = CorrelationLogger(context.applicationContext)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            val id = logger.error("GlobalError", "Unhandled exception on ${thread.name}", error)
            Log.e("GlobalError", "correlationId=$id", error)
            // Android still receives the crash after durable logging. This avoids silently swallowing corrupted process state.
            previous?.uncaughtException(thread, error) ?: Process.killProcess(Process.myPid())
        }
    }
}
