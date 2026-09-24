package com.jarvis.voice

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.util.Log
import com.jarvis.ai.GroqLlm
import com.jarvis.ai.LlmConfig
import com.jarvis.ai.LlmPlanner
import com.jarvis.conversation.ConversationManager
import com.jarvis.memory.MemoryStore
import com.jarvis.tools.*
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.wakeword.RaphaelPhoneticMatcher
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

sealed class ModelState {
    object NotInstalled : ModelState()
    object Ready : ModelState()
}

data class VoiceEngineStatus(
    val voiceState: VoiceState = VoiceState.IDLE,
    val asrState: AsrState = AsrState.STOPPED,
    val modelState: ModelState = ModelState.NotInstalled,
    val partialTranscript: String = "",
    val lastResponse: String = "",
    val statusMessage: String = "",
    val wakeModelAvailable: Boolean = true,
    val lastTaskGoal: String? = null,
    val audioLevelRms: Float = 0.0f,
    val isLiveConversationActive: Boolean = false
)

class VoiceEngine(
    private val context: Context,
    private var groqApiKey: String = "",
    private var groqModel: String = LlmConfig.DEFAULT_MODEL
) {
    private val TAG = "VoiceEngine"
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var stateCollectionJob: Job? = null
    @Volatile private var preferencesLoadJob: Job? = null

    private fun ensureActiveScope(): CoroutineScope {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        return scope
    }

    val assistantRuntime = com.jarvis.runtime.AssistantRuntime(context, groqApiKey, groqModel)
    val conversationManager = assistantRuntime.conversationManager
    val continuousAudioStream = com.jarvis.voice.audio.ContinuousAudioStream(context)
    val vadEngine = assistantRuntime.vadEngine
    val wakeEngine = assistantRuntime.wakeEngine
    @Volatile var asrEngine: AsrEngine = assistantRuntime.asrEngine
    val ttsEngine = assistantRuntime.ttsEngine
    val voiceRouter = com.jarvis.voice.router.VoiceRouter(
        rollingBuffer = continuousAudioStream.rollingBuffer,
        wakeEngine = wakeEngine,
        vadEngine = vadEngine,
        asrEngine = asrEngine,
        continuousAudioStream = continuousAudioStream
    )
    val lifecycleManager = VoicePipelineLifecycleManager(
        asrEngine = asrEngine,
        idleTimeoutMs = 45_000L,
        onUnload = {
            Log.i("VoiceEngine", "VoicePipelineLifecycleManager idle timeout — unloaded active ASR session")
        }
    )
    val memoryStore = assistantRuntime.memoryStore
    val augmentedMemoryPipeline = assistantRuntime.augmentedMemoryPipeline
    val toolRegistry = assistantRuntime.toolRegistry
    val toolExecutor = assistantRuntime.toolExecutor
    private val memorySessionId = "voice-session"

    var pocketAndMotionManager: com.jarvis.sensors.PocketAndMotionManager? = null

    private val _status = MutableStateFlow(VoiceEngineStatus())
    val status: StateFlow<VoiceEngineStatus> = _status.asStateFlow()

    val audioFocusManager = com.jarvis.media.VoiceAudioFocusManager(context)
    val mediaDuckingManager = com.jarvis.media.MediaDuckingManager(context, audioFocusManager)
    val acousticFeedbackEngine = com.jarvis.voice.audio.AcousticFeedbackEngine(context)

    // Session tracking and race protection gates
    private val sessionSequence = AtomicInteger(0)
    private val wakeTransitionGate = AtomicBoolean(false)
    private val commandFinalizationGate = AtomicBoolean(false)
    private val isStarting = AtomicBoolean(false)

    // Tracked coroutine jobs
    private var listeningTimeoutJob: Job? = null
    private var activeExecutionJob: Job? = null
    private var returnToWakeJob: Job? = null
    private var toneGenerator: ToneGenerator? = null

    var isContinuousConversationEnabled: Boolean = true
    var isBargeInEnabled: Boolean = true
    var followUpTimeoutMs: Long = 6000L
    @Volatile var configuredWakeWord: String = "Jarvis"
    @Volatile private var ownerVoiceWakeEnabled: Boolean = false

    val liveConversationController = LiveConversationController(
        voiceRouter = voiceRouter,
        ttsEngine = ttsEngine,
        rollingBuffer = continuousAudioStream.rollingBuffer,
        onStateChanged = { active ->
            _status.value = _status.value.copy(isLiveConversationActive = active)
        }
    )

    init {
        preferencesLoadJob = ensureActiveScope().launch(Dispatchers.IO) {
            loadPreferences()
        }
        startStateCollection()

        // Wire full-duplex speech interruption into VoiceRouter
        voiceRouter.onBargeInDetected = { reason ->
            if (liveConversationController.isSessionActive()) {
                liveConversationController.handleBargeInInterruption(reason)
            } else if (isBargeInEnabled) {
                val state = conversationManager.currentStateValue
                val isRawEnergy = (reason == "acoustic_energy")
                val isLoudspeakerBleed = isRawEnergy &&
                        (ttsEngine.isSpeaking() || state == VoiceState.SPEAKING) &&
                        ((ttsEngine as? AndroidTtsEngine)?.isHeadsetConnected() != true)
                val isThinkingState = (state == VoiceState.THINKING || state == VoiceState.RESEARCHING || state == VoiceState.CHECKING_MEMORY || state == VoiceState.EXECUTING || state == VoiceState.VERIFYING)

                if (isLoudspeakerBleed || (isRawEnergy && isThinkingState)) {
                    Log.d(TAG, "Dropping acoustic_energy barge-in in state $state to prevent false interruption")
                } else if (state == VoiceState.SPEAKING || state == VoiceState.THINKING || state == VoiceState.RESEARCHING || state == VoiceState.WAITING_FOR_USER) {
                    Log.i(TAG, "Barge-in triggered via $reason in state: $state")
                    bargeIn()
                }
            }
        }
    }

    fun startLiveConversation() {
        liveConversationController.startSession()
    }

    fun stopLiveConversation() {
        liveConversationController.stopSession()
    }

    fun updateBluetoothScoState(scoActive: Boolean) {
        ttsEngine.updateBluetoothScoState(scoActive)
        acousticFeedbackEngine.isBluetoothScoActive = scoActive
        continuousAudioStream.updatePreferredDevice()
    }

    private fun loadPreferences() {
        val store = UiPreferencesStore(context)
        val appSettings = runCatching { store.loadSettings() }.getOrDefault(com.jarvis.ui.model.AppSettings())
        isContinuousConversationEnabled = appSettings.continuousConversation
        isBargeInEnabled = appSettings.bargeInEnabled
        configuredWakeWord = appSettings.wakeWord.ifBlank { "Jarvis" }
        followUpTimeoutMs = (appSettings.followUpTimeoutSeconds * 1000L).coerceIn(3000L, 15000L)
        voiceRouter.bargeInDetector.setEnabled(appSettings.bargeInEnabled)
        voiceRouter.bargeInDetector.setSensitivity(appSettings.wakeSensitivity)
        acousticFeedbackEngine.isAudioEnabled = appSettings.acousticFeedbackEnabled
        acousticFeedbackEngine.isHapticEnabled = appSettings.hapticFeedbackEnabled
        val voiceProfile = runCatching { store.loadVoiceProfile() }.getOrDefault(com.jarvis.wakeword.UserVoiceProfile.DEFAULT)
        wakeEngine.applyVoiceProfile(voiceProfile)
        val ownerVoiceProfile = runCatching { store.loadOwnerVoiceProfile() }.getOrNull()
        ownerVoiceWakeEnabled = appSettings.ownerVoiceWakeEnabled
        wakeEngine.configureOwnerVoiceWake(appSettings.ownerVoiceWakeEnabled, ownerVoiceProfile)
        if (voiceProfile.wakeWordPhrase.isNotBlank()) {
            configuredWakeWord = voiceProfile.wakeWordPhrase
        }
    }

    fun applyVoiceProfile(profile: com.jarvis.wakeword.UserVoiceProfile) {
        wakeEngine.applyVoiceProfile(profile)
        if (profile.wakeWordPhrase.isNotBlank()) {
            configuredWakeWord = profile.wakeWordPhrase
        }
    }

    private fun startStateCollection() {
        stateCollectionJob?.cancel()
        stateCollectionJob = ensureActiveScope().launch {
            conversationManager.stateFlow.collect { state ->
                val currentSession = sessionSequence.get()
                Log.i(TAG, "VOICE session=$currentSession state=$state")
                _status.value = _status.value.copy(
                    voiceState = state,
                    statusMessage = getStatusLabel(state)
                )
            }
        }
    }

    fun updateConfig(apiKey: String, model: String) {
        groqApiKey = apiKey
        groqModel = model.ifBlank { LlmConfig.DEFAULT_MODEL }
        assistantRuntime.updateConfig(apiKey, model)
        val resumeWake = conversationManager.currentStateValue == VoiceState.WAITING_FOR_WAKE
        if (resumeWake) wakeEngine.stop()
        preferencesLoadJob = ensureActiveScope().launch(Dispatchers.IO) {
            loadPreferences()
            if (resumeWake && conversationManager.currentStateValue == VoiceState.WAITING_FOR_WAKE) {
                startWakeSession(preferencesAlreadyLoaded = true)
            }
        }
        Log.i(TAG, "VoiceEngine updated config (model: $groqModel, continuous=$isContinuousConversationEnabled, bargeIn=$isBargeInEnabled)")
    }

    fun bargeIn(): Boolean {
        if (!isBargeInEnabled) return false
        val state = conversationManager.currentStateValue
        if (state == VoiceState.SPEAKING || state == VoiceState.THINKING || state == VoiceState.RESEARCHING) {
            Log.i(TAG, "Barge-in triggered from state: $state")
            activeExecutionJob?.cancel()
            activeExecutionJob = null
            ttsEngine.cancel()
            acousticFeedbackEngine.playBargeInEarcon()
            ensureActiveScope().launch {
                conversationManager.transitionTo(VoiceState.INTERRUPTED)
                val preRoll = voiceRouter.routeToAsr(preRollSamples = 16000 * 1)
                delay(40)
                startListeningForCommand(isFollowUp = true, initialAudio = preRoll)
            }
            return true
        }
        return false
    }

    fun handleFlipMute() {
        val state = conversationManager.currentStateValue
        if (state == VoiceState.SPEAKING) {
            Log.i(TAG, "Flip-to-mute gesture: immediately silencing TTS")
            ttsEngine.stop()
            ttsEngine.cancel()
            ensureActiveScope().launch {
                returnToWakeListening()
            }
        }
    }

    suspend fun start() {
        startStateCollection()
        if (conversationManager.currentStateValue != VoiceState.IDLE) {
            Log.d(TAG, "VoiceEngine is already running in state: ${conversationManager.currentStateValue}.")
            return
        }
        if (!isStarting.compareAndSet(false, true)) {
            Log.d(TAG, "VoiceEngine.start() already in progress — skipping duplicate call.")
            return
        }
        try {
            val sessionId = sessionSequence.incrementAndGet()
            Log.i(TAG, "Starting VoiceEngine (session=$sessionId)...")
            _status.value = _status.value.copy(statusMessage = "Starting Voice Engine...")

            acousticFeedbackEngine.start()

            // 1. Initialize continuous audio capture (never stops across transitions)
            continuousAudioStream.clearConsumers()
            continuousAudioStream.addConsumer(voiceRouter)
            val streamStarted = continuousAudioStream.start()
            if (!streamStarted) {
                Log.e(TAG, "ContinuousAudioStream failed to start (AudioRecord unavailable or permission missing)")
                _status.value = _status.value.copy(
                    statusMessage = "Microphone unavailable / permission missing",
                    voiceState = VoiceState.ERROR
                )
                isStarting.set(false)
                return
            }

            // 2. Setup ASR / TTS engines
            withContext(Dispatchers.IO) {
                asrEngine.start(context.filesDir)
                ttsEngine.start(context.filesDir)
            }

            startWakeSession()
        } finally {
            isStarting.set(false)
        }
    }

    private fun onWakeDetectedDirect(phrase: String = "") {
        val currentState = conversationManager.currentStateValue
        if (currentState == VoiceState.SPEAKING || currentState == VoiceState.THINKING || currentState == VoiceState.RESEARCHING) {
            Log.i(TAG, "Wake word '$phrase' detected during $currentState — triggering barge-in!")
            bargeIn()
            return
        }

        if (currentState != VoiceState.WAITING_FOR_WAKE) {
            return
        }

        if (!wakeTransitionGate.compareAndSet(false, true)) {
            Log.d(TAG, "Wake transition dropped: gate already locked")
            return
        }

        ensureActiveScope().launch {
            try {
                onWakeWordDetected(phrase)
            } finally {
                wakeTransitionGate.set(false)
            }
        }
    }

    private suspend fun onWakeWordDetected(detectedPhrase: String = "") {
        if (pocketAndMotionManager?.isPocketed == true) {
            Log.i(TAG, "Wake word detected but suppressed: device is in pocket or proximity covered")
            wakeTransitionGate.set(false)
            return
        }
        val current = conversationManager.currentStateValue
        if (current == VoiceState.SPEAKING || current == VoiceState.THINKING || current == VoiceState.RESEARCHING) {
            bargeIn()
            return
        }
        if (current != VoiceState.WAITING_FOR_WAKE && current != VoiceState.IDLE) return
        val currentSession = sessionSequence.get()
        Log.i(TAG, "VOICE session=$currentSession event=WAKE_DETECTED phrase=\"$detectedPhrase\"")

        // Wake screen if display is currently inactive and lock-screen voice is enabled
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
            val appSettings = runCatching { UiPreferencesStore(context).loadSettings() }.getOrDefault(com.jarvis.ui.model.AppSettings())
            val isLocked = km?.isKeyguardLocked == true || pm?.isInteractive == false
            if (appSettings.lockScreenVoiceEnabled && isLocked) {
                if (appSettings.deviceUnlockPin.isNotBlank()) {
                    com.jarvis.accessibility.UiAutomationManager.unlockScreen(context, appSettings.deviceUnlockPin)
                } else {
                    @Suppress("DEPRECATION")
                    val screenLock = pm?.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                        "Jarvis::ScreenWakeOnVoice"
                    )
                    screenLock?.acquire(4000L)
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Screen wake/unlock attempt: ${e.message}")
        }

        // 1. Duck background media and route to ASR
        val appSettings = runCatching { UiPreferencesStore(context).loadSettings() }.getOrDefault(com.jarvis.ui.model.AppSettings())
        if (appSettings.mediaDuckingEnabled) {
            mediaDuckingManager.startDucking(duckPercent = appSettings.mediaDuckingPercent, isMusicAllowed = appSettings.mediaDuckingMusic)
        } else {
            audioFocusManager.requestDuckFocus()
        }
        val preRoll = voiceRouter.routeToAsr(preRollSamples = 16000 * 2)

        vadEngine.reset()
        conversationManager.transitionTo(VoiceState.WAKE_DETECTED)
        _status.value = _status.value.copy(partialTranscript = "", statusMessage = "Wake word detected!")

        listeningTimeoutJob?.cancel()
        listeningTimeoutJob = null

        // 2. Check for same-breath command (e.g. "Jarvis play music" or "Friday play music")
        val stripped = RaphaelPhoneticMatcher.stripWakeWordPrefix(detectedPhrase, configuredWakeWord).trim()
        if (stripped.isNotBlank() && !RaphaelPhoneticMatcher.isWakeWord(stripped, configuredWakeWord)) {
            Log.i(TAG, "Same-breath command detected: \"$stripped\". Executing immediately without acknowledgment.")
            executeCommandFlow(stripped)
            return
        }

        // 3. Start standard command listening with pre-roll audio handoff
        startListeningForCommand(isFollowUp = false, initialAudio = preRoll)
    }

    private suspend fun startListeningForCommand(isFollowUp: Boolean = false, initialAudio: ShortArray? = null) {
        stopCommandSession()
        lifecycleManager.transitionTo(PipelineState.ACTIVE_ASR)

        val routeToAppropriateAsr: (Int) -> Unit = { preRoll ->
            if (asrEngine.usesRawAudio) {
                voiceRouter.routeToAsr(preRoll)
            } else {
                voiceRouter.routeToAndroidAsr()
            }
        }

        // 1. Prompt user if initial wake turn based on wakeAcknowledgment setting
        if (!isFollowUp) {
            val appSettings = runCatching { UiPreferencesStore(context).loadSettings() }.getOrDefault(com.jarvis.ui.model.AppSettings())
            val ackMode = appSettings.wakeAcknowledgment.uppercase()
            when (ackMode) {
                "VOICE" -> {
                    _status.value = _status.value.copy(statusMessage = "Yes boss")
                    voiceRouter.mute()
                    ttsEngine.speak("Yes boss")
                    delay(120)
                    routeToAppropriateAsr(16000 * 1)
                }
                "BOTH" -> {
                    acousticFeedbackEngine.playWakeTone()
                    _status.value = _status.value.copy(statusMessage = "Yes boss")
                    voiceRouter.mute()
                    ttsEngine.speak("Yes boss")
                    delay(120)
                    routeToAppropriateAsr(16000 * 1)
                }
                "SILENT" -> {
                    routeToAppropriateAsr(16000 * 1)
                }
                else -> {
                    // Default: "CHIME" — ultra-low latency earcon (<120ms total)
                    acousticFeedbackEngine.playWakeTone()
                    routeToAppropriateAsr(16000 * 1)
                }
            }
        } else {
            delay(60)
            routeToAppropriateAsr(16000 * 1)
        }

        // Samsung audio policy needs a short settling window after the passive
        // AudioRecord is released; starting SpeechRecognizer immediately loses
        // the first command or returns NO_MATCH in background mode.
        delay(250)

        acousticFeedbackEngine.playListeningTone()

        // 2. Initialize command finalization gate for this listening session
        commandFinalizationGate.set(false)

        var lastCapturedTranscript = ""
        var retryAttempts = 0
        val maxEmptyRetries = if (isFollowUp) 0 else 1

        fun scheduleListeningTimeout(delayMs: Long, reason: String) {
            listeningTimeoutJob?.cancel()
            listeningTimeoutJob = ensureActiveScope().launch {
                delay(delayMs)
                if (commandFinalizationGate.compareAndSet(false, true)) {
                    val candidate = lastCapturedTranscript.trim()
                    if (candidate.isNotBlank()) {
                        Log.i(TAG, "Listening timeout ($delayMs ms - $reason) with captured partial: '$candidate'")
                        handleRecognizedTranscript(candidate)
                    } else {
                        Log.i(TAG, "Listening timeout ($delayMs ms - $reason): returning to wake listening.")
                        returnToWakeListening()
                    }
                }
            }
        }

        // Wire VAD events into VoiceRouter callbacks
        voiceRouter.onSpeechActivity = { scheduleListeningTimeout(8000L, "silence post-speech") }
        voiceRouter.onSpeechEnd = {
            if (asrEngine.usesRawAudio) {
                ensureActiveScope().launch { onSpeechEnded() }
            }
        }

        val onSpeechActivity: () -> Unit = { scheduleListeningTimeout(8000L, "silence post-speech") }

        fun handleAsrEmptyOrError(code: Int, message: String) {
            if (commandFinalizationGate.get()) return

            val candidate = lastCapturedTranscript.trim()
            if (candidate.isNotBlank()) {
                if (commandFinalizationGate.compareAndSet(false, true)) {
                    listeningTimeoutJob?.cancel()
                    Log.i(TAG, "ASR empty/error ($code: $message), using captured partial: '$candidate'.")
                    ensureActiveScope().launch { handleRecognizedTranscript(candidate) }
                    return
                }
            }

            if (retryAttempts < maxEmptyRetries && !commandFinalizationGate.get() &&
                conversationManager.currentStateValue == VoiceState.LISTENING) {
                retryAttempts++
                Log.i(TAG, "ASR pause/no-match (attempt $retryAttempts of $maxEmptyRetries). Retrying...")
                ensureActiveScope().launch {
                    delay(200)
                    if (!commandFinalizationGate.get() && conversationManager.currentStateValue == VoiceState.LISTENING) {
                        launchAsrListen(onSpeechActivity, ::handleAsrEmptyOrError,
                            { p -> if (p.isNotBlank()) lastCapturedTranscript = p }, { lastCapturedTranscript }) { t ->
                            if (commandFinalizationGate.compareAndSet(false, true)) {
                                listeningTimeoutJob?.cancel()
                                ensureActiveScope().launch { handleRecognizedTranscript(t) }
                            }
                        }
                    }
                }
            } else {
                if (commandFinalizationGate.compareAndSet(false, true)) {
                    listeningTimeoutJob?.cancel()
                    Log.d(TAG, "ASR empty/error ($code: $message) retry limit reached. Returning to wake.")
                    ensureActiveScope().launch { returnToWakeListening() }
                }
            }
        }

        val initialTimeout = if (isFollowUp) followUpTimeoutMs.coerceIn(4000L, 8000L) else 8000L
        scheduleListeningTimeout(initialTimeout, if (isFollowUp) "follow-up silence" else "initial silence")

        conversationManager.transitionTo(VoiceState.LISTENING)
        _status.value = _status.value.copy(
            partialTranscript = "",
            statusMessage = if (isFollowUp) "Listening... (say 'bye' to finish)" else "Listening to command..."
        )

        launchAsrListen(onSpeechActivity, ::handleAsrEmptyOrError,
            { p -> if (p.isNotBlank()) lastCapturedTranscript = p }, { lastCapturedTranscript }) { t ->
            if (commandFinalizationGate.compareAndSet(false, true)) {
                listeningTimeoutJob?.cancel()
                ensureActiveScope().launch { handleRecognizedTranscript(t) }
            }
        }

        // Feed pre-roll audio to raw-audio ASR only after stream is active and in LISTENING state
        if (asrEngine.usesRawAudio && initialAudio != null && initialAudio.isNotEmpty()) {
            asrEngine.pushAudio(initialAudio)
        }
    }

    /** Deduplication helper — single place that calls asrEngine.startListening. */
    private suspend fun launchAsrListen(
        onSpeechActivity: () -> Unit,
        onEmptyOrError: (Int, String) -> Unit,
        capturePartial: (String) -> Unit,
        getLastPartial: () -> String,
        onFinalHandled: (String) -> Unit
    ) {
        asrEngine.startListening(
            onSpeechStarted = onSpeechActivity,
            onPartial = { partial ->
                capturePartial(partial)
                onSpeechActivity()
                _status.value = _status.value.copy(partialTranscript = partial)
            },
            onFinal = { finalTranscript ->
                val transcript = finalTranscript.trim().ifBlank { getLastPartial().trim() }
                if (transcript.isNotBlank()) onFinalHandled(transcript)
                else onEmptyOrError(-1, "empty final")
            },
            onError = onEmptyOrError,
            onEmpty = { onEmptyOrError(0, "empty") },
            onRmsChanged = { rms ->
                if (conversationManager.currentStateValue == VoiceState.LISTENING) {
                    _status.value = _status.value.copy(audioLevelRms = rms)
                }
            }
        )
    }

    private suspend fun handleRecognizedTranscript(transcript: String) {
        stopCommandSession()
        if (conversationManager.isExitUtterance(transcript, configuredWakeWord)) {
            val reply = conversationManager.getExitResponse(transcript)
            Log.i(TAG, "Exit utterance detected ('$transcript'). Replying: '$reply'")
            _status.value = _status.value.copy(lastResponse = reply, statusMessage = "Speaking")
            conversationManager.transitionTo(VoiceState.SPEAKING)
            val isHeadset = (ttsEngine as? AndroidTtsEngine)?.isHeadsetConnected() == true
            voiceRouter.bargeInDetector.isSpeakerBleedGated = !isHeadset
            try {
                ttsEngine.speak(reply)
            } finally {
                voiceRouter.bargeInDetector.isSpeakerBleedGated = false
            }
            returnToWakeListening()
            return
        }
        executeCommandFlow(transcript)
    }

    private suspend fun onSpeechEnded() {
        if (conversationManager.currentStateValue == VoiceState.LISTENING) {
            Log.i(TAG, "VAD SpeechEnded triggered — requesting ASR stop/finalization")
            asrEngine.stopListening()
        }
    }

    private suspend fun stopCommandSession() {
        val currentJob = coroutineContext[Job]
        if (listeningTimeoutJob != currentJob) {
            listeningTimeoutJob?.cancel()
        }
        listeningTimeoutJob = null
        asrEngine.stopListening()
    }

    private suspend fun startWakeSession(preferencesAlreadyLoaded: Boolean = false) {
        if (!preferencesAlreadyLoaded) preferencesLoadJob?.join()
        stopCommandSession()
        lifecycleManager.transitionTo(PipelineState.WAKE_LISTENING)

        val isWakeAvailable = wakeEngine.isAvailable()
        wakeTransitionGate.set(false)
        commandFinalizationGate.set(false)

        conversationManager.transitionTo(VoiceState.WAITING_FOR_WAKE)

        if (isWakeAvailable) {
            wakeEngine.reset()
            wakeEngine.start(null) { phrase ->
                onWakeDetectedDirect(RaphaelPhoneticMatcher.resolveDetectedPhrase(phrase, configuredWakeWord))
            }
            voiceRouter.routeToWakeWord()
            _status.value = _status.value.copy(
                partialTranscript = "",
                statusMessage = "Listening for 'Jarvis'...",
                wakeModelAvailable = true
            )
            Log.i(TAG, "Passive wake session active (model loaded)")
        } else {
            val err = wakeEngine.getModelError() ?: "Model asset missing"
            _status.value = _status.value.copy(
                partialTranscript = "",
                statusMessage = if (ownerVoiceWakeEnabled) "Owner-only wake unavailable ($err). Tap to talk." else "Wake model unavailable. Tap to talk.",
                wakeModelAvailable = false
            )
            Log.w(TAG, "Passive wake disabled ($err). Tap-to-talk manual mode ready.")
        }
    }

    private suspend fun returnToWakeListening() {
        returnToWakeJob?.cancel()
        returnToWakeJob = ensureActiveScope().launch {
            Log.i(TAG, "Returning cleanly to wake word listening mode")
            mediaDuckingManager.restoreVolume()
            _status.value = _status.value.copy(audioLevelRms = 0.0f)
            stopCommandSession()
            delay(150)
            val state = conversationManager.currentStateValue
            if (state == VoiceState.LISTENING || state == VoiceState.COMPLETED || state == VoiceState.IDLE || state == VoiceState.INTERRUPTED || state == VoiceState.ERROR || state == VoiceState.WAITING_FOR_WAKE) {
                startWakeSession()
            } else {
                Log.d(TAG, "Aborting returnToWakeListening — active state is $state")
            }
        }
    }

    private suspend fun executeCommandFlow(rawTranscript: String) {
        activeExecutionJob?.cancel()
        returnToWakeJob?.cancel()
        returnToWakeJob = null
        val currentJob = kotlin.coroutines.coroutineContext[Job]
        activeExecutionJob = currentJob
        try {
            stopCommandSession()
            val currentSession = sessionSequence.get()
            Log.i(TAG, "VOICE session=$currentSession event=EXECUTE_COMMAND command='$rawTranscript'")

            val stripped = RaphaelPhoneticMatcher.stripWakeWordPrefix(rawTranscript, configuredWakeWord)
            val cleanCommand = (conversationManager.processUtterance(stripped, configuredWakeWord) ?: stripped).trim()
            if (cleanCommand.isBlank() || RaphaelPhoneticMatcher.isWakeWord(cleanCommand, configuredWakeWord)) {
                Log.i(TAG, "Utterance was wake-word-only or blank ('$rawTranscript'). Returning to WAITING_FOR_WAKE")
                returnToWakeListening()
                return
            }

            conversationManager.transitionTo(VoiceState.THINKING)
            _status.value = _status.value.copy(
                partialTranscript = cleanCommand,
                statusMessage = "Thinking..."
            )
            if (isBargeInEnabled) {
                voiceRouter.routeToBargeIn()
            } else {
                voiceRouter.mute()
            }

            val result = try {
                assistantRuntime.executeCommand(cleanCommand) { event ->
                    when (event) {
                        is com.jarvis.agent.AgentEvent.BuildingContext -> {
                            conversationManager.transitionTo(VoiceState.CHECKING_MEMORY)
                            _status.value = _status.value.copy(statusMessage = "Checking context & memory...")
                        }
                        is com.jarvis.agent.AgentEvent.Thinking -> {
                            conversationManager.transitionTo(VoiceState.THINKING)
                            _status.value = _status.value.copy(statusMessage = "Thinking...")
                        }
                        is com.jarvis.agent.AgentEvent.Researching -> {
                            conversationManager.transitionTo(VoiceState.RESEARCHING)
                            _status.value = _status.value.copy(statusMessage = "Researching: ${event.query}")
                        }
                        is com.jarvis.agent.AgentEvent.UsingMemory -> {
                            conversationManager.transitionTo(VoiceState.CHECKING_MEMORY)
                            _status.value = _status.value.copy(statusMessage = event.message)
                        }
                        is com.jarvis.agent.AgentEvent.Executing -> {
                            conversationManager.transitionTo(VoiceState.EXECUTING)
                            _status.value = _status.value.copy(statusMessage = "Executing ${event.action}...")
                        }
                        is com.jarvis.agent.AgentEvent.Verifying -> {
                            conversationManager.transitionTo(VoiceState.VERIFYING)
                            _status.value = _status.value.copy(statusMessage = "Verifying ${event.action}...")
                        }
                        is com.jarvis.agent.AgentEvent.Recovering -> {
                            conversationManager.transitionTo(VoiceState.RECOVERING)
                            _status.value = _status.value.copy(statusMessage = "Recovering: ${event.reason}")
                        }
                        is com.jarvis.agent.AgentEvent.WaitingForUser -> {
                            conversationManager.transitionTo(VoiceState.WAITING_FOR_USER)
                            _status.value = _status.value.copy(statusMessage = event.question, lastResponse = event.question)
                        }
                        is com.jarvis.agent.AgentEvent.Completed -> {
                            conversationManager.transitionTo(VoiceState.COMPLETED)
                            _status.value = _status.value.copy(statusMessage = "Task completed", lastResponse = event.message)
                            acousticFeedbackEngine.playSuccessTone()
                        }
                        is com.jarvis.agent.AgentEvent.Failed -> {
                            conversationManager.transitionTo(VoiceState.ERROR)
                            _status.value = _status.value.copy(statusMessage = "Failed: ${event.message}")
                        }
                        is com.jarvis.agent.AgentEvent.TaskStarted -> {
                            _status.value = _status.value.copy(lastTaskGoal = cleanCommand)
                        }
                        is com.jarvis.agent.AgentEvent.Cancelled -> {
                            conversationManager.transitionTo(VoiceState.IDLE)
                            _status.value = _status.value.copy(statusMessage = "Task cancelled", lastResponse = event.message)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Command flow cancelled; returning to wake listening")
                returnToWakeListening()
                return
            }

            val finalResponse = result.spokenResponse
            val wasAlreadySpoken = assistantRuntime.wasSpokenRecently(finalResponse) ||
                    result.stepResults.any { it.message.isNotBlank() && finalResponse.contains(it.message.take(20)) }

            if (finalResponse.isNotBlank() && !wasAlreadySpoken) {
                _status.value = _status.value.copy(lastResponse = finalResponse, statusMessage = "Speaking")
                conversationManager.transitionTo(VoiceState.SPEAKING)
                lifecycleManager.transitionTo(PipelineState.SPEAKING)
                val isHeadset = (ttsEngine as? AndroidTtsEngine)?.isHeadsetConnected() == true
                voiceRouter.bargeInDetector.isSpeakerBleedGated = !isHeadset
                if (isBargeInEnabled) {
                    voiceRouter.routeToBargeIn()
                } else {
                    voiceRouter.mute()
                }
                try {
                    ttsEngine.speak(finalResponse)
                } finally {
                    voiceRouter.bargeInDetector.isSpeakerBleedGated = false
                }
                delay(120)
            } else if (finalResponse.isNotBlank() && wasAlreadySpoken) {
                Log.i(TAG, "Skipping duplicate finalResponse speech — already spoken during task execution: '$finalResponse'")
                _status.value = _status.value.copy(lastResponse = finalResponse)
            }

            // Continuous conversation mode: enter follow-up window unless an exit phrase was received
            if (isContinuousConversationEnabled && !conversationManager.isExitUtterance(cleanCommand, configuredWakeWord)) {
                Log.i(TAG, "Continuous conversation active — entering follow-up window")
                // Restore media volume during the follow-up silence window so background
                // music is audible between turns. Ducking re-applies on next command.
                mediaDuckingManager.restoreVolume()
                startListeningForCommand(isFollowUp = true)
            } else {
                returnToWakeListening()
            }
        } finally {
            if (activeExecutionJob == currentJob) {
                activeExecutionJob = null
            }
        }
    }

    @Synchronized
    private fun playWakeTone() {
        acousticFeedbackEngine.playWakeTone()
    }

    suspend fun stop() {
        Log.i(TAG, "Stopping VoiceEngine...")
        mediaDuckingManager.restoreVolume()
        acousticFeedbackEngine.release()
        activeExecutionJob?.cancel()
        activeExecutionJob = null
        lifecycleManager.transitionTo(PipelineState.STANDBY)
        stopCommandSession()
        continuousAudioStream.stop()
        wakeEngine.stop()
        asrEngine.stop()
        ttsEngine.stop()
        conversationManager.transitionTo(VoiceState.IDLE)
        wakeTransitionGate.set(false)
        commandFinalizationGate.set(false)
        _status.value = VoiceEngineStatus()
        Log.i(TAG, "VoiceEngine stopped")
    }

    fun close() {
        Log.i(TAG, "Closing VoiceEngine...")
        mediaDuckingManager.restoreVolume()
        audioFocusManager.abandon()
        try {
            toneGenerator?.release()
            toneGenerator = null
        } catch (_: Exception) {}
        stateCollectionJob?.cancel()
        lifecycleManager.close()
        scope.cancel()
        continuousAudioStream.stop()
        wakeEngine.close()
        voiceRouter.close()
        assistantRuntime.release()
        Log.i(TAG, "VoiceEngine closed")
    }

    suspend fun processTextCommand(text: String): Boolean {
        listeningTimeoutJob?.cancel()
        return if (commandFinalizationGate.compareAndSet(false, true)) {
            try {
                executeCommandFlow(text)
            } finally {
                // Always reset the gate so future commands are not silently dropped,
                // even if executeCommandFlow() exits early (blank command, cancellation, error).
                commandFinalizationGate.set(false)
            }
            true
        } else {
            Log.w(TAG, "processTextCommand dropped for '$text': command finalization gate is locked (active command in progress)")
            _status.value = _status.value.copy(
                statusMessage = "Still processing previous command..."
            )
            false
        }
    }

    fun triggerManualListening() {
        val current = conversationManager.currentStateValue
        if (current == VoiceState.IDLE) {
            ensureActiveScope().launch {
                start()
                onWakeWordDetected("")
            }
        } else if (current == VoiceState.WAITING_FOR_WAKE) {
            ensureActiveScope().launch {
                onWakeWordDetected("")
            }
        }
    }

    fun startManualListening() = triggerManualListening()

    fun executeDirectCommand(command: String) {
        ensureActiveScope().launch {
            executeCommandFlow(command)
        }
    }

    fun cancelCurrentTask() {
        activeExecutionJob?.cancel()
        assistantRuntime.cancelCurrentTask()
        ttsEngine.cancel()
        ensureActiveScope().launch {
            returnToWakeListening()
        }
    }

    fun speakProactiveAnnouncement(message: String) {
        val current = conversationManager.currentStateValue
        if (current != VoiceState.IDLE && current != VoiceState.WAITING_FOR_WAKE) {
            Log.i(TAG, "Skipping proactive announcement: conversation is busy in state $current")
            return
        }

        ensureActiveScope().launch {
            try {
                // Wake screen if display is currently inactive and lock-screen voice is enabled
                val pm = context.getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
                val appSettings = runCatching { UiPreferencesStore(context).loadSettings() }.getOrDefault(com.jarvis.ui.model.AppSettings())
                if (appSettings.lockScreenVoiceEnabled && pm?.isInteractive == false) {
                    @Suppress("DEPRECATION")
                    val screenLock = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        android.os.PowerManager.ON_AFTER_RELEASE,
                        "Jarvis::ProactiveWake"
                    )
                    screenLock.acquire(4000L)
                }

                // Duck background media
                if (appSettings.mediaDuckingEnabled) {
                    mediaDuckingManager.startDucking(duckPercent = appSettings.mediaDuckingPercent, isMusicAllowed = appSettings.mediaDuckingMusic)
                } else {
                    audioFocusManager.requestDuckFocus()
                }

                conversationManager.transitionTo(VoiceState.SPEAKING)
                _status.value = _status.value.copy(
                    lastResponse = message,
                    statusMessage = "Proactive alert"
                )

                acousticFeedbackEngine.playWakeTone()
                delay(120)

                val isHeadset = (ttsEngine as? AndroidTtsEngine)?.isHeadsetConnected() == true
                voiceRouter.bargeInDetector.isSpeakerBleedGated = !isHeadset
                if (isBargeInEnabled) {
                    voiceRouter.routeToBargeIn()
                } else {
                    voiceRouter.mute()
                }

                try {
                    ttsEngine.speak(message)
                } finally {
                    voiceRouter.bargeInDetector.isSpeakerBleedGated = false
                }
                delay(120)
            } finally {
                returnToWakeListening()
            }
        }
    }

    private fun getStatusLabel(state: VoiceState): String = when (state) {
        VoiceState.IDLE -> "Ready"
        VoiceState.WAITING_FOR_WAKE -> if (wakeEngine.isAvailable()) "Listening for 'Jarvis'..." else "Wake model unavailable. Tap to talk."
        VoiceState.WAKE_DETECTED -> "Wake word detected!"
        VoiceState.LISTENING -> "Listening to command..."
        VoiceState.UNDERSTANDING -> "Understanding goal..."
        VoiceState.CHECKING_MEMORY -> "Checking context & memory..."
        VoiceState.RESEARCHING -> "Gathering intelligence..."
        VoiceState.THINKING -> "Reasoning & planning..."
        VoiceState.EXECUTING -> "Executing tasks..."
        VoiceState.VERIFYING -> "Verifying outcome..."
        VoiceState.RECOVERING -> "Applying recovery..."
        VoiceState.WAITING_FOR_USER -> "Waiting for input..."
        VoiceState.SPEAKING -> "Speaking..."
        VoiceState.COMPLETED -> "Task completed"
        VoiceState.CANCELLED -> "Task cancelled"
        VoiceState.INTERRUPTED -> "Interrupted"
        VoiceState.OFFLINE -> "Offline mode"
        VoiceState.ERROR -> "Error occurred"
    }
}
