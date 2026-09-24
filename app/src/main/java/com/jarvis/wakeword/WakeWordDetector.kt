package com.jarvis.wakeword

interface WakeWordDetector {
    fun isModelAvailable(): Boolean
    fun getModelLoadError(): String?
    fun pushAudio(samples: ShortArray): Float
    fun resetDetectorState()
    fun close()
}
