package com.jarvis.runtime

import android.content.ComponentCallbacks2
import android.content.Context
import android.util.Log
import com.jarvis.camera.CameraPerceptionEngine
import com.jarvis.camera.TfLiteVisionDetector
import com.jarvis.controlplane.DeviceGuardian
import com.jarvis.controlplane.ExecutionPipeline
import com.jarvis.controlplane.FailureJournal
import com.jarvis.controlplane.GoalManager
import com.jarvis.controlplane.JarvisEventBus
import com.jarvis.controlplane.StrategyRegistry
import com.jarvis.controlplane.WorldStateStore
import com.jarvis.execution.VerificationEngine
import com.jarvis.files.FileManager
import com.jarvis.foundation.MetricsCollector
import com.jarvis.foundation.PolicyEngine
import com.jarvis.macro.MacroWorkflowEngine
import com.jarvis.media.MediaSessionManager
import com.jarvis.memory.AugmentedMemoryPipeline
import com.jarvis.memory.MemoryStore
import com.jarvis.conversation.ConversationManager
import com.jarvis.foundation.TaskStateManager
import com.jarvis.messaging.WhatsAppMessagingEngine
import com.jarvis.rag.DocumentChunker
import com.jarvis.rag.RagContextInjector
import com.jarvis.rag.RagIndexStore
import com.jarvis.rag.RagRetriever
import com.jarvis.reliability.CheckpointManager
import com.jarvis.retrieval.engine.RaphaelRetrievalManager
import com.jarvis.telecom.CallAndSmsAgent
import com.jarvis.telecom.ContactResolver
import com.jarvis.voice.AndroidTtsEngine
import com.jarvis.voice.AsrEngine
import com.jarvis.voice.GroqTtsEngine
import com.jarvis.voice.AndroidSpeechRecognizerEngine
import com.jarvis.voice.TtsEngine
import com.jarvis.voice.VadEngine
import com.jarvis.voice.WakeWordEngine
import java.util.concurrent.atomic.AtomicInteger

/**
 * Diagnostics report detailing the allocation and memory footprint of managed subsystems.
 */
data class SubsystemMemoryReport(
    val totalSubsystems: Int,
    val initializedSubsystems: Int,
    val activeSubsystemNames: List<String>,
    val heapAllocatedMb: Double,
    val totalEvictions: Int
)

/**
 * Lazy Subsystem Container and Memory Lifecycle Coordinator.
 *
 * Segregates core boot-critical subsystems (WakeWordEngine, EventBus, WorldStore, DeviceGuardian)
 * from heavy, on-demand modules (Camera/Vision, RAG Vectors, WhatsApp, Telephony, TTS).
 *
 * Handles Android [ComponentCallbacks2.onTrimMemory] callbacks by proactively evicting
 * idle vision detectors, flushing RAG dense vector caches, and releasing temporary native memory.
 */
class SubsystemManager(private val context: Context? = null) {

    private val TAG = "SubsystemManager"
    private val evictionCounter = AtomicInteger(0)

    init {
        context?.let { com.jarvis.storage.JarvisStorageHub.initStorage(it) }
    }

    // =========================================================================
    // 1. CORE SUBSYSTEMS (Boot-critical, lightweight, always available)
    // =========================================================================
    val worldStore: WorldStateStore = WorldStateStore.shared
    val eventBus: JarvisEventBus = JarvisEventBus.shared
    val deviceGuardian: DeviceGuardian = DeviceGuardian(worldStore)
    val goalManager: GoalManager = GoalManager.shared
    val failureJournal: FailureJournal = FailureJournal.shared
    val strategyRegistry: StrategyRegistry = StrategyRegistry.shared
    val metrics: MetricsCollector = MetricsCollector.shared

    val taskStateManager by lazy { TaskStateManager(requireNotNull(context) { "Context required for TaskStateManager" }) }
    val policyEngine by lazy {
        PolicyEngine(
            autonomousFullAuto = false,
            isDeviceLockedProvider = {
                val ctx = context
                if (ctx != null) {
                    val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager
                    km?.isDeviceLocked == true || km?.isKeyguardLocked == true
                } else false
            }
        )
    }
    val verificationEngine by lazy { VerificationEngine(requireNotNull(context) { "Context required for VerificationEngine" }) }
    val checkpointManager by lazy { CheckpointManager(requireNotNull(context) { "Context required for CheckpointManager" }) }

    val wakeEngine: WakeWordEngine by lazy { WakeWordEngine(requireNotNull(context) { "Context required for WakeWordEngine" }) }
    val vadEngine: VadEngine by lazy { VadEngine() }
    val conversationManager: ConversationManager = ConversationManager()

    val memoryStore: MemoryStore by lazy { MemoryStore(requireNotNull(context) { "Context required for MemoryStore" }) }
    val augmentedMemoryPipeline: AugmentedMemoryPipeline by lazy { AugmentedMemoryPipeline(memoryStore) }
    val executionPipeline: ExecutionPipeline by lazy {
        ExecutionPipeline(
            worldStore = worldStore,
            guardian = deviceGuardian,
            eventBus = eventBus,
            goalManager = goalManager
        )
    }

    // =========================================================================
    // 2. VISION SUBSYSTEM (Lazy, evictable)
    // =========================================================================
    @Volatile
    private var _cameraPerceptionEngine: CameraPerceptionEngine? = null
    val cameraPerceptionEngine: CameraPerceptionEngine
        get() = _cameraPerceptionEngine ?: synchronized(this) {
            _cameraPerceptionEngine ?: CameraPerceptionEngine(requireNotNull(context) { "Context required for CameraPerceptionEngine" }).also {
                _cameraPerceptionEngine = it
                Log.d(TAG, "Initialized CameraPerceptionEngine on-demand")
            }
        }

    @Volatile
    private var _cameraDetector: TfLiteVisionDetector? = null
    val cameraDetector: TfLiteVisionDetector
        get() = _cameraDetector ?: synchronized(this) {
            _cameraDetector ?: TfLiteVisionDetector().also {
                _cameraDetector = it
                Log.d(TAG, "Initialized TfLiteVisionDetector on-demand")
            }
        }

    fun isVisionInitialized(): Boolean = _cameraPerceptionEngine != null || _cameraDetector != null

    // =========================================================================
    // 3. RAG SUBSYSTEM (Lazy, cache-flushable)
    // =========================================================================
    @Volatile
    private var _ragIndexStore: RagIndexStore? = null
    val ragIndexStore: RagIndexStore
        get() = _ragIndexStore ?: synchronized(this) {
            _ragIndexStore ?: RagIndexStore(requireNotNull(context) { "Context required for RagIndexStore" }).also {
                _ragIndexStore = it
                Log.d(TAG, "Initialized RagIndexStore on-demand")
            }
        }

    val ragChunker by lazy { DocumentChunker() }

    @Volatile
    private var _ragRetriever: RagRetriever? = null
    val ragRetriever: RagRetriever
        get() = _ragRetriever ?: synchronized(this) {
            _ragRetriever ?: RagRetriever(
                indexStore = ragIndexStore,
                vectorizer = com.jarvis.ai.EmbeddingVectorizer()
            ).also {
                _ragRetriever = it
                Log.d(TAG, "Initialized RagRetriever on-demand")
            }
        }

    @Volatile
    private var _ragContextInjector: RagContextInjector? = null
    val ragContextInjector: RagContextInjector
        get() = _ragContextInjector ?: synchronized(this) {
            _ragContextInjector ?: RagContextInjector(ragRetriever).also {
                _ragContextInjector = it
            }
        }

    @Volatile
    private var _raphaelRetrievalManager: RaphaelRetrievalManager? = null
    val raphaelRetrievalManager: RaphaelRetrievalManager
        get() = _raphaelRetrievalManager ?: synchronized(this) {
            _raphaelRetrievalManager ?: RaphaelRetrievalManager(
                ragRetriever = ragRetriever,
                memoryStore = memoryStore,
                ambientEngine = ambientContextEngine
            ).also {
                _raphaelRetrievalManager = it
            }
        }

    fun isRagInitialized(): Boolean = _ragIndexStore != null || _ragRetriever != null

    // =========================================================================
    // 4. TELEPHONY & MESSAGING SUBSYSTEM (Lazy)
    // =========================================================================
    @Volatile
    private var _contactResolver: ContactResolver? = null
    val contactResolver: ContactResolver
        get() = _contactResolver ?: synchronized(this) {
            _contactResolver ?: ContactResolver(requireNotNull(context) { "Context required for ContactResolver" }).also {
                _contactResolver = it
                Log.d(TAG, "Initialized ContactResolver on-demand")
            }
        }

    @Volatile
    private var _callAndSmsAgent: CallAndSmsAgent? = null
    val callAndSmsAgent: CallAndSmsAgent
        get() = _callAndSmsAgent ?: synchronized(this) {
            _callAndSmsAgent ?: (CallAndSmsAgent.instance ?: CallAndSmsAgent(requireNotNull(context) { "Context required for CallAndSmsAgent" }, contactResolver)).also {
                _callAndSmsAgent = it
            }
        }

    @Volatile
    private var _whatsappMessagingEngine: WhatsAppMessagingEngine? = null
    val whatsappMessagingEngine: WhatsAppMessagingEngine
        get() = _whatsappMessagingEngine ?: synchronized(this) {
            _whatsappMessagingEngine ?: WhatsAppMessagingEngine(requireNotNull(context) { "Context required for WhatsAppMessagingEngine" }, contactResolver).also {
                _whatsappMessagingEngine = it
                Log.d(TAG, "Initialized WhatsAppMessagingEngine on-demand")
            }
        }

    fun isTelephonyInitialized(): Boolean = _contactResolver != null || _whatsappMessagingEngine != null

    // =========================================================================
    // 5. AUTOMATIONS & MEDIA SUBSYSTEM (Lazy)
    // =========================================================================
    val mediaSessionManager by lazy { MediaSessionManager() }
    val calendarManager by lazy { com.jarvis.calendar.CalendarManager(requireNotNull(context) { "Context required for CalendarManager" }) }
    val ambientContextEngine by lazy { com.jarvis.autonomous.AmbientContextEngine(requireNotNull(context) { "Context required for AmbientContextEngine" }, calendarManager) }
    val macroWorkflowEngine by lazy { MacroWorkflowEngine(requireNotNull(context) { "Context required for MacroWorkflowEngine" }) }
    val fileManager by lazy { FileManager(requireNotNull(context) { "Context required for FileManager" }) }

    // =========================================================================
    // 6. SPEECH SUBSYSTEM (Lazy TTS & ASR)
    // =========================================================================
    var apiKeyProvider: () -> String = { "" }

    @Volatile
    private var _ttsEngine: TtsEngine? = null
    val ttsEngine: TtsEngine
        get() = _ttsEngine ?: synchronized(this) {
            _ttsEngine ?: run {
                val ctx = requireNotNull(context) { "Context required for TtsEngine" }
                val fallback = AndroidTtsEngine(ctx)
                val settings = runCatching { com.jarvis.ui.data.UiPreferencesStore(ctx).loadSettings() }.getOrDefault(com.jarvis.ui.model.AppSettings())
                val engine: TtsEngine = if (settings.ttsProvider.equals("android", ignoreCase = true)) {
                    fallback
                } else {
                    GroqTtsEngine(
                        context = ctx,
                        apiKeyProvider = apiKeyProvider,
                        voiceProvider = {
                            runCatching { com.jarvis.ui.data.UiPreferencesStore(ctx).loadSettings().ttsVoice }.getOrDefault("Fritz-PlayAI")
                        },
                        fallbackEngine = fallback
                    )
                }
                engine.also {
                    _ttsEngine = it
                    Log.d(TAG, "Initialized TtsEngine on-demand (provider=${settings.ttsProvider})")
                }
            }
        }

    @Volatile
    private var _asrEngine: AsrEngine? = null
    var asrEngine: AsrEngine
        get() = _asrEngine ?: synchronized(this) {
            _asrEngine ?: AndroidSpeechRecognizerEngine(requireNotNull(context) { "Context required for AsrEngine" }).also {
                _asrEngine = it
                Log.d(TAG, "Initialized AndroidSpeechRecognizerEngine on-demand")
            }
        }
        set(value) {
            synchronized(this) {
                _asrEngine = value
            }
        }

    fun isTtsInitialized(): Boolean = _ttsEngine != null

    // =========================================================================
    // 7. MEMORY PRESSURE MANAGEMENT & EVICTION
    // =========================================================================

    /**
     * Responds to OS memory pressure signals from [ComponentCallbacks2.onTrimMemory].
     */
    fun onTrimMemory(level: Int) {
        Log.i(TAG, "onTrimMemory called with level=$level")
        when {
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
            level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                evictHeavySubsystems(aggressive = true)
            }
            level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level >= ComponentCallbacks2.TRIM_MEMORY_MODERATE ||
            level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                evictHeavySubsystems(aggressive = false)
            }
        }
    }

    /**
     * Evicts idle or cache-heavy subsystems to reduce memory usage.
     */
    @Synchronized
    fun evictHeavySubsystems(aggressive: Boolean = false) {
        var evicted = 0

        // 1. Evict vision detector & close camera resources
        if (_cameraPerceptionEngine != null) {
            _cameraPerceptionEngine = null
            evicted++
        }
        if (_cameraDetector != null) {
            _cameraDetector = null
            evicted++
        }

        // 2. Clear RAG dense vectors & query caches
        _ragRetriever?.clearCache()

        // 3. Aggressive: Evict TTS engine if currently idle
        if (aggressive && _ttsEngine != null) {
            runCatching { _ttsEngine?.stop() }
            _ttsEngine = null
            evicted++
        }

        evictionCounter.addAndGet(evicted)
        Log.i(TAG, "Evicted $evicted heavy subsystem references (aggressive=$aggressive)")

        // Request garbage collection hint to reclaim heap
        System.gc()
    }

    /**
     * Generates a memory diagnostics report of active vs lazy subsystems.
     */
    fun getMemoryReport(): SubsystemMemoryReport {
        val names = mutableListOf("Core", "WorldStore", "EventBus", "DeviceGuardian")
        var initializedCount = 4

        if (_cameraPerceptionEngine != null || _cameraDetector != null) {
            names.add("Vision")
            initializedCount++
        }
        if (_ragIndexStore != null || _ragRetriever != null) {
            names.add("RAG")
            initializedCount++
        }
        if (_contactResolver != null || _whatsappMessagingEngine != null) {
            names.add("Telephony/Messaging")
            initializedCount++
        }
        if (_ttsEngine != null) {
            names.add("TTS")
            initializedCount++
        }
        if (_asrEngine != null) {
            names.add("ASR")
            initializedCount++
        }

        val runtime = Runtime.getRuntime()
        val heapAllocatedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024.0 * 1024.0)

        return SubsystemMemoryReport(
            totalSubsystems = ALL_MANAGED_SUBSYSTEMS.size,
            initializedSubsystems = initializedCount,
            activeSubsystemNames = names,
            heapAllocatedMb = heapAllocatedMb,
            totalEvictions = evictionCounter.get()
        )
    }

    companion object {
        val ALL_MANAGED_SUBSYSTEMS = listOf(
            "Core", "WorldStore", "EventBus", "DeviceGuardian",
            "Vision", "RAG", "Telephony/Messaging", "TTS", "ASR"
        )
    }

    /**
     * Complete lifecycle shutdown and cleanup of all allocated subsystems.
     */
    @Synchronized
    fun releaseAll() {
        // Fix: Do NOT access `wakeEngine` via the lazy delegate during shutdown — that would
        // initialize the subsystem if it was never created. Use the backing field instead.
        // WakeWordEngine is a Kotlin `by lazy` — check if it was initialized before closing.
        if (isWakeEngineInitialized()) {
            runCatching { wakeEngine.close() }
        }

        runCatching { _ttsEngine?.stop() }
        runCatching { _asrEngine?.let { kotlinx.coroutines.runBlocking { it.stop() } } }

        // Fix: Camera resources should be cleaned up before nulling to prevent leaks.
        // CameraPerceptionEngine opens/closes the camera per capture (no persistent session),
        // but TfLiteVisionDetector may hold a TFLite Interpreter. Null them after any cleanup.
        // If these classes grow persistent native resources in future, add a close() method there.
        _cameraPerceptionEngine = null
        _cameraDetector = null

        _ragRetriever?.clearCache()
        _ttsEngine = null
        _asrEngine = null
        Log.i(TAG, "All subsystems released successfully")
    }

    /** Returns true only if the wakeEngine lazy delegate has already been initialized. */
    private fun isWakeEngineInitialized(): Boolean {
        // The Kotlin compiler names the backing field for `val x by lazy {}` as `x$delegate`.
        // Accessing isInitialized() avoids forcing initialization during shutdown.
        return try {
            val lazyField = this.javaClass.getDeclaredField("wakeEngine\$delegate")
            lazyField.isAccessible = true
            val lazyValue = lazyField.get(this) as? Lazy<*>
            lazyValue?.isInitialized() ?: false
        } catch (_: Exception) {
            // Field name unavailable (proguard/different Kotlin version) — skip close safely.
            false
        }
    }
}
