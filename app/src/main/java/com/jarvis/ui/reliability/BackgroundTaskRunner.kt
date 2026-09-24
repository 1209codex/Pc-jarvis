package com.jarvis.ui.reliability

import kotlinx.coroutines.*

class BackgroundTaskRunner {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun <T> ioTask(block: suspend () -> T) = scope.async(Dispatchers.IO) { block() }
    fun <T> cpuTask(block: suspend () -> T) = scope.async(Dispatchers.Default) { block() }
    fun cancel(job: Job) = job.cancel()
    fun dispose() = scope.cancel()
}
