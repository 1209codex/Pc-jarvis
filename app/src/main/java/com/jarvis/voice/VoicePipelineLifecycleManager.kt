package com.jarvis.voice

import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicReference

enum class PipelineState {
    STANDBY,
    WAKE_LISTENING,
    ACTIVE_ASR,
    SPEAKING,
    UNLOADING
}

class VoicePipelineLifecycleManager(
    private var asrEngine: AsrEngine? = null,
    private val idleTimeoutMs: Long = 45_000L,
    private val onUnload: (suspend () -> Unit)? = null
) {
    private val currentState = AtomicReference(PipelineState.STANDBY)
    private var idleJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    fun getCurrentState(): PipelineState = currentState.get()

    fun setAsrEngine(engine: AsrEngine) {
        this.asrEngine = engine
    }

    @Synchronized
    fun transitionTo(newState: PipelineState) {
        currentState.set(newState)
        when (newState) {
            PipelineState.WAKE_LISTENING -> resetIdleTimer()
            PipelineState.ACTIVE_ASR, PipelineState.SPEAKING -> {
                idleJob?.cancel()
            }
            PipelineState.STANDBY, PipelineState.UNLOADING -> {
                idleJob?.cancel()
            }
        }
    }

    fun onUserActivity() {
        transitionTo(PipelineState.ACTIVE_ASR)
    }

    @Synchronized
    fun resetIdleTimer() {
        idleJob?.cancel()
        idleJob = scope.launch {
            delay(idleTimeoutMs)
            unloadPipeline()
        }
    }

    suspend fun unloadPipeline() {
        currentState.set(PipelineState.UNLOADING)
        try {
            onUnload?.invoke()
            asrEngine?.stop()
        } catch (_: Exception) {}
        currentState.set(PipelineState.STANDBY)
    }

    fun close() {
        idleJob?.cancel()
        scope.cancel()
        currentState.set(PipelineState.STANDBY)
    }
}
