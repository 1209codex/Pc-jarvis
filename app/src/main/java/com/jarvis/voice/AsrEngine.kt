package com.jarvis.voice

import kotlinx.coroutines.flow.StateFlow
import java.io.File

enum class AsrState {
    STOPPED,
    STARTING,
    READY,
    LISTENING,
    STOPPING,
    ERROR
}

interface AsrEngine {
    val state: StateFlow<AsrState>
    /** True if the engine needs raw PCM pushed via pushAudio(); false if it manages its own mic (e.g. Android SpeechRecognizer). */
    val usesRawAudio: Boolean get() = false
    suspend fun start(asrDir: File)
    suspend fun startListening(
        onSpeechStarted: (() -> Unit)? = null,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: ((Int, String) -> Unit)? = null,
        onEmpty: (() -> Unit)? = null,
        onRmsChanged: ((Float) -> Unit)? = null
    )
    suspend fun pushAudio(samples: ShortArray)
    suspend fun stopListening()
    suspend fun stop()
}
