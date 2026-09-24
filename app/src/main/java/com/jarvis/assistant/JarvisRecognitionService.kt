package com.jarvis.assistant

import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import android.util.Log
import com.jarvis.service.JarvisForegroundService
import kotlinx.coroutines.*

/**
 * Android system RecognitionService implementation that enables keyboard dictation
 * and system voice typing via Jarvis speech engine.
 */
class JarvisRecognitionService : RecognitionService() {

    companion object {
        private const val TAG = "JarvisRecognitionSvc"
    }

    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var listeningJob: Job? = null

    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {
        Log.i(TAG, "onStartListening requested by client")
        listener?.readyForSpeech(Bundle())

        val voiceEngine = JarvisForegroundService.instance?.voiceEngine
        if (voiceEngine == null) {
            listener?.error(SpeechRecognizer.ERROR_SERVER)
            return
        }

        listeningJob?.cancel()
        listeningJob = serviceScope.launch {
            try {
                voiceEngine.asrEngine.startListening(
                    onSpeechStarted = { listener?.beginningOfSpeech() },
                    onPartial = { partial ->
                        val b = Bundle().apply {
                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(partial))
                        }
                        listener?.partialResults(b)
                    },
                    onFinal = { text ->
                        listener?.endOfSpeech()
                        val b = Bundle().apply {
                            putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
                        }
                        listener?.results(b)
                    },
                    onError = { code, _ ->
                        listener?.error(code)
                    },
                    onEmpty = {
                        listener?.error(SpeechRecognizer.ERROR_NO_MATCH)
                    },
                    onRmsChanged = { rms ->
                        listener?.rmsChanged(rms)
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error in RecognitionService onStartListening: ${e.message}")
                listener?.error(SpeechRecognizer.ERROR_CLIENT)
            }
        }
    }

    override fun onStopListening(listener: Callback?) {
        Log.i(TAG, "onStopListening")
        serviceScope.launch {
            JarvisForegroundService.instance?.voiceEngine?.asrEngine?.stopListening()
        }
    }

    override fun onCancel(listener: Callback?) {
        Log.i(TAG, "onCancel")
        listeningJob?.cancel()
        serviceScope.launch {
            JarvisForegroundService.instance?.voiceEngine?.asrEngine?.stop()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        listeningJob?.cancel()
        serviceScope.cancel()
    }
}
