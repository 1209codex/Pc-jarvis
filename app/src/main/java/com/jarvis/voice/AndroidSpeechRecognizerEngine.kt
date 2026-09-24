package com.jarvis.voice

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

class AndroidSpeechRecognizerEngine(private val context: Context) : AsrEngine {
    private val TAG = "AndroidSpeechEngine"
    private val _state = MutableStateFlow(AsrState.STOPPED)
    override val state: StateFlow<AsrState> = _state.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionCounter = AtomicInteger(0)

    @Volatile private var speechStartedCallback: (() -> Unit)? = null
    @Volatile private var partialCallback: ((String) -> Unit)? = null
    @Volatile private var finalCallback: ((String) -> Unit)? = null
    @Volatile private var errorCallback: ((Int, String) -> Unit)? = null
    @Volatile private var emptyCallback: (() -> Unit)? = null
    @Volatile private var rmsCallback: ((Float) -> Unit)? = null
    // Debounce flag: speechStartedCallback should fire at most once per listening session
    @Volatile private var speechStartedFired = false

    override suspend fun start(asrDir: File) {
        mainHandler.post {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                _state.value = AsrState.READY
                ensureSpeechRecognizer()
                Log.i(TAG, "Android native SpeechRecognizer initialized, pre-warmed and READY")
            } else {
                Log.w(TAG, "SpeechRecognizer is not available on this device")
                _state.value = AsrState.ERROR
            }
        }
    }

    private fun ensureSpeechRecognizer(): SpeechRecognizer? {
        if (speechRecognizer == null) {
            try {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(createListener())
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to create SpeechRecognizer", e)
                speechRecognizer = null
            }
        }
        return speechRecognizer
    }

    private fun recreateRecognizer() {
        try {
            speechRecognizer?.destroy()
        } catch (_: Exception) {}
        speechRecognizer = null
        ensureSpeechRecognizer()
    }

    private fun buildRecognizerIntent(): Intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 150L)
        // Background wake handoff can add a few hundred milliseconds on Samsung devices;
        // do not finalize a valid command while the user is still speaking.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2200L)
    }

    override suspend fun startListening(
        onSpeechStarted: (() -> Unit)?,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: ((Int, String) -> Unit)?,
        onEmpty: (() -> Unit)?,
        onRmsChanged: ((Float) -> Unit)?
    ) {
        speechStartedCallback = onSpeechStarted
        partialCallback = onPartial
        finalCallback = onFinal
        errorCallback = onError
        emptyCallback = onEmpty
        rmsCallback = onRmsChanged
        speechStartedFired = false

        val session = sessionCounter.incrementAndGet()

        mainHandler.post {
            if (session != sessionCounter.get()) return@post
            try {
                val recognizer = ensureSpeechRecognizer()
                if (recognizer == null) {
                    _state.value = AsrState.ERROR
                    onError?.invoke(-1, "SpeechRecognizer unavailable")
                    return@post
                }

                recognizer.startListening(buildRecognizerIntent())
                _state.value = AsrState.LISTENING
                Log.i(TAG, "SpeechRecognizer (session $session) started listening for command (reused instance)")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start SpeechRecognizer (session $session), recreating instance", e)
                recreateRecognizer()
                try {
                    val recognizer = ensureSpeechRecognizer()
                    recognizer?.startListening(buildRecognizerIntent())
                    _state.value = AsrState.LISTENING
                } catch (retryEx: Exception) {
                    _state.value = AsrState.ERROR
                    onError?.invoke(-1, retryEx.message ?: "Failed to start speech recognizer")
                }
            }
        }
    }

    override suspend fun pushAudio(samples: ShortArray) {
        // System SpeechRecognizer captures microphone directly
    }

    override suspend fun stopListening() {
        if (_state.value != AsrState.LISTENING) return
        mainHandler.post {
            try {
                speechRecognizer?.stopListening()
                _state.value = AsrState.STOPPING
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping SpeechRecognizer", e)
            }
        }
    }

    override suspend fun stop() {
        sessionCounter.incrementAndGet()
        mainHandler.post {
            try {
                speechRecognizer?.destroy()
                speechRecognizer = null
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying SpeechRecognizer", e)
            } finally {
                _state.value = AsrState.STOPPED
            }
        }
    }

    private fun createListener() = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            val session = sessionCounter.get()
            Log.i(TAG, "Ready for speech (session $session)")
        }

        override fun onBeginningOfSpeech() {
            val session = sessionCounter.get()
            Log.i(TAG, "Beginning of speech detected (session $session)")
            speechStartedCallback?.invoke()
        }

        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB typically ranges between -2 dB (ambient silence) to +10 dB (loud speech)
            val normalized = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
            rmsCallback?.invoke(normalized)

            if (rmsdB > 3.0f && !speechStartedFired) {
                speechStartedFired = true
                speechStartedCallback?.invoke()
            }
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            val session = sessionCounter.get()
            Log.i(TAG, "End of speech (session $session)")
        }

        override fun onError(error: Int) {
            val session = sessionCounter.get()
            val msg = getErrorMessage(error)
            val isExpectedSilence = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            if (isExpectedSilence) {
                Log.d(TAG, "Speech recognition ended with silence (session $session): $msg ($error)")
            } else {
                Log.w(TAG, "Speech recognition error (session $session): $msg ($error)")
            }
            _state.value = AsrState.READY

            if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                recreateRecognizer()
            }

            if (isExpectedSilence) {
                emptyCallback?.invoke()
            } else {
                errorCallback?.invoke(error, msg)
            }
        }

        override fun onResults(results: Bundle?) {
            val session = sessionCounter.get()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            val confidence = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)?.firstOrNull() ?: -1f
            Log.i(TAG, "Final Speech Result (session $session): '$text'")
            _state.value = AsrState.READY
            if (text.isNotBlank() && (confidence < 0f || confidence >= 0.35f)) {
                finalCallback?.invoke(text)
            } else {
                if (text.isNotBlank()) Log.w(TAG, "Discarding low-confidence speech result ($confidence): '$text'")
                emptyCallback?.invoke()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull()?.trim() ?: ""
            if (text.isNotBlank()) {
                speechStartedCallback?.invoke()
                partialCallback?.invoke(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun getErrorMessage(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
        SpeechRecognizer.ERROR_CLIENT -> "Client-side error"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Insufficient permissions"
        SpeechRecognizer.ERROR_NETWORK -> "Network error"
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
        SpeechRecognizer.ERROR_NO_MATCH -> "No match found"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
        SpeechRecognizer.ERROR_SERVER -> "Server error"
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech input timeout"
        else -> "Unknown error ($error)"
    }
}
