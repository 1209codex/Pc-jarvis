package com.jarvis.voice

import java.io.File

interface TtsEngine {
    fun start(ttsDir: File = File(""))
    fun synthesize(text: String, onAudio: (ShortArray) -> Unit = {})
    suspend fun speak(text: String): Boolean
    fun stop()
    fun cancel()
    fun isSpeaking(): Boolean = false
    fun updateBluetoothScoState(scoActive: Boolean) {}
}
