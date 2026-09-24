package com.jarvis.runtime

import android.content.Context
import android.util.Log
import com.jarvis.agent.*
import com.jarvis.ai.*
import com.jarvis.conversation.ConversationManager
import com.jarvis.execution.VerificationEngine
import com.jarvis.foundation.*
import com.jarvis.interaction.BrowserDomTool
import com.jarvis.interaction.OsSystemInspectionTool
import com.jarvis.memory.AugmentedMemoryPipeline
import com.jarvis.memory.MemoryStore
import com.jarvis.tools.*
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.voice.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class RuntimeExecutionResult(
    val success: Boolean,
    val spokenResponse: String,
    val stepResults: List<ToolResult>,
    val verified: Boolean = true
)

class AssistantRuntime(
    val context: Context,
    private var groqApiKey: String = "",
    private var groqModel: String = LlmConfig.DEFAULT_MODEL
) {
    private val TAG = "AssistantRuntime"
    private var scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private fun ensureActiveScope(): CoroutineScope {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        }
        return scope
    }

    // Subsystem Container
    val subsystems = SubsystemManager(context)

    // Infrastructure & Services
    val metrics get() = subsystems.metrics
    val memoryStore get() = subsystems.memoryStore
    val conversationManager get() = subsystems.conversationManager
    val augmentedMemoryPipeline get() = subsystems.augmentedMemoryPipeline
    val taskStateManager get() = subsystems.taskStateManager

    // Voice subsystem
    val vadEngine get() = subsystems.vadEngine
    val wakeEngine: WakeWordEngine get() = subsystems.wakeEngine
    var asrEngine: AsrEngine
        get() = subsystems.asrEngine
        set(value) { subsystems.asrEngine = value }
    val ttsEngine get() = subsystems.ttsEngine

    // Execution & Verification
    val policyEngine: PolicyEngine get() = subsystems.policyEngine
    val verificationEngine get() = subsystems.verificationEngine
    val toolRegistry = ToolRegistry()
    val toolExecutor = ToolExecutor(
        toolRegistry,
        policyEngine,
        verificationEngine,
        auditSink = { actionType, risk, reason, status, details ->
            taskStateManager.logAudit(activeTaskId, actionType, risk, reason, status, details)
        }
    )

    // Autonomous Control Plane Architecture (Layers 0 - 10)
    val eventBus: com.jarvis.controlplane.JarvisEventBus get() = subsystems.eventBus
    val worldStore: com.jarvis.controlplane.WorldStateStore get() = subsystems.worldStore
    val deviceGuardian: com.jarvis.controlplane.DeviceGuardian get() = subsystems.deviceGuardian
    val goalManager: com.jarvis.controlplane.GoalManager get() = subsystems.goalManager
    val executionPipeline: com.jarvis.controlplane.ExecutionPipeline get() = subsystems.executionPipeline
    val failureJournal: com.jarvis.controlplane.FailureJournal get() = subsystems.failureJournal
    val strategyRegistry: com.jarvis.controlplane.StrategyRegistry get() = subsystems.strategyRegistry

    // Reliability & Persistence
    val checkpointManager get() = subsystems.checkpointManager

    // RAG & Knowledge Vault Subsystem
    val ragIndexStore get() = subsystems.ragIndexStore
    val ragChunker get() = subsystems.ragChunker
    val ragRetriever get() = subsystems.ragRetriever
    val ragContextInjector get() = subsystems.ragContextInjector

    // Camera Optical Perception Subsystem
    val cameraPerceptionEngine get() = subsystems.cameraPerceptionEngine
    val cameraDetector get() = subsystems.cameraDetector

    // Autonomous WhatsApp & Messaging Subsystem
    val contactResolver get() = subsystems.contactResolver
    val callAndSmsAgent get() = subsystems.callAndSmsAgent
    val whatsappMessagingEngine get() = subsystems.whatsappMessagingEngine

    // System Telemetry & Log Reader Subsystem
    val logReaderEngine by lazy { com.jarvis.logs.LogReaderEngine() }

    // Agent Intelligence Architecture
    val mediaSessionManager get() = subsystems.mediaSessionManager
    val calendarManager get() = subsystems.calendarManager
    val ambientContextEngine get() = subsystems.ambientContextEngine
    val worldStateProvider by lazy { com.jarvis.autonomous.AndroidStateProvider(context, ambientContextEngine, mediaSessionManager, taskStateManager) }
    val worldStateService by lazy { com.jarvis.foundation.worldstate.WorldStateService(worldStateProvider) }
    val raphaelRetrievalManager get() = subsystems.raphaelRetrievalManager
    val contextRouter by lazy {
        AgentContextRouter(
            memoryPipeline = augmentedMemoryPipeline,
            ragInjector = ragContextInjector,
            stateProvider = worldStateProvider,
            raphaelRetrievalManager = raphaelRetrievalManager
        )
    }
    val recoveryManager = AgentRecoveryManager()
    val goalEvaluator: GoalEvaluator = GoalEvaluator()
    val experienceManager by lazy { AgentExperienceManager(taskStateManager, augmentedMemoryPipeline) }
    val habitManager by lazy { com.jarvis.habit.HabitManager(memoryStore) }
    val smartRoutineEngine by lazy {
        com.jarvis.routine.SmartRoutineEngine(toolExecutor).also {
            com.jarvis.routine.SmartRoutineEngine.instance = it
        }
    }
    val macroWorkflowEngine get() = subsystems.macroWorkflowEngine
    val fileManager get() = subsystems.fileManager
    val autonomousDaemon by lazy {
        com.jarvis.autonomous.AutonomousDaemon(
            ambientEngine = ambientContextEngine,
            calendarManager = calendarManager,
            fileManager = fileManager,
            toolExecutor = toolExecutor,
            verificationEngine = verificationEngine,
            // A proactive 60s heartbeat must never race an in-flight user command.
            busyProvider = { activeTaskJob?.isActive == true || pendingClarificationTaskId != null }
        )
    }
    val selfHealingSupervisor: com.jarvis.autonomous.SelfHealingSupervisor by lazy {
        com.jarvis.autonomous.SelfHealingSupervisor(
            toolExecutor = toolExecutor,
            onDeferredTaskDue = ::replayDeferredTask
        )
    }

    /**
     * Self-healing: network is back — re-run the deferred goal through the full
     * agent loop. If it fails again, re-queue it for the next network restore.
     */
    private fun replayDeferredTask(task: com.jarvis.autonomous.DeferredTask) {
        scope.launch {
            val result = executeCommand(task.goal)
            if (!result.success) {
                selfHealingSupervisor.queueDeferredTask(
                    task.goal,
                    "Replay failed: ${result.spokenResponse.take(120)}"
                )
                Log.w(TAG, "Deferred task replay failed; re-queued '${task.goal}'")
            }
        }
    }
    val networkStateMonitor = com.jarvis.routine.NetworkStateMonitor(
        context = context,
        onWifiConnected = { ssid ->
            smartRoutineEngine.onWifiConnected(ssid)
            selfHealingSupervisor.setNetworkStatus(true)
        },
        onWifiDisconnected = {
            smartRoutineEngine.onWifiDisconnected()
            selfHealingSupervisor.setNetworkStatus(false)
        }
    )
    val learnedSkillStore = com.jarvis.skilllearning.LearnedSkillStore()
    val persistentSkillStore = com.jarvis.skills.db.PersistentSkillStore(context)
    val skillRegistry = com.jarvis.agent.skills.SkillRegistry().apply {
        register(com.jarvis.agent.skills.SongSearchAndPlaySkill())
        register(com.jarvis.agent.skills.MediaSkill())
        register(com.jarvis.agent.skills.AppControlSkill())
        register(com.jarvis.agent.skills.CommunicationSkill())
        register(com.jarvis.agent.skills.ResearchSkill())
        register(com.jarvis.agent.skills.BrowserSkill())
        register(com.jarvis.agent.skills.VisionSkill())
        register(com.jarvis.agent.skills.ProactiveSkill())
        register(com.jarvis.agent.skills.RoutineSkill())
        register(com.jarvis.agent.skills.MacroSkill(macroWorkflowEngine))
        register(com.jarvis.agent.skills.AppAutopilotSkill())
        register(com.jarvis.agent.skills.TelecomSkill())
        register(com.jarvis.agent.skills.CalendarSkill())
        register(com.jarvis.agent.skills.SecuritySkill())
        register(com.jarvis.agent.skills.FileSkill())
        register(com.jarvis.agent.skills.AutonomousSkill())
        register(com.jarvis.agent.skills.RagSkill())
        register(com.jarvis.agent.skills.LogSkill())
        register(com.jarvis.agent.skills.TimeSkill())
        register(com.jarvis.agent.skills.LocationSkill())
    }
    val adaptiveSkillEvolution = com.jarvis.skilllearning.AdaptiveSkillEvolution(
        context = context,
        skillStore = persistentSkillStore,
        skillRegistry = skillRegistry
    ).also {
        it.loadPersistedSkillsIntoRegistry()
    }
    val skillDiscoveryEngine by lazy { com.jarvis.agent.SkillDiscoveryEngine(context, persistentSkillStore, skillRegistry) }

    private var activeTaskJob: Job? = null
    var activeTaskId: Long? = null
        private set
    var pendingClarificationTaskId: Long? = null
        private set
    private var pendingResumeStrikes = 0
    private companion object {
        const val MAX_CLARIFICATION_RESUMES = 2
    }

    private var llmPlanner: LlmPlanner? = null
    var agentKernel: AgentKernel? = null
        private set

    private val recentlySpokenUtterances = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    fun recordSpokenUtterance(text: String) {
        if (text.isNotBlank()) {
            recentlySpokenUtterances.add(text.trim().lowercase())
        }
    }

    fun wasSpokenRecently(text: String): Boolean {
        val target = text.trim().lowercase()
        if (target.isBlank()) return false
        return recentlySpokenUtterances.any { spoken ->
            spoken == target || spoken.contains(target) || target.contains(spoken)
        }
    }

    fun clearRecentlySpoken() {
        recentlySpokenUtterances.clear()
    }

    private suspend fun announceIncomingWhatsAppMessage(sender: String, message: String, platform: String) {
        val msgType = whatsappMessagingEngine.classifyMessageType(message)
        val displayMsg = if (msgType != "text") {
            "a $msgType"
        } else if (message.isNotBlank()) {
            ": ${message.take(100)}"
        } else {
            ""
        }
        val announcement = "New message on $platform from $sender$displayMsg"
        recordSpokenUtterance(announcement)
        ttsEngine.speak(announcement)
    }

    init {
        registerDefaultTools()
        reapInterruptedTasks()
        initPlannerAndKernel()
        networkStateMonitor.start()
        startEventBusObserver()
        loadVoiceProfile()
        scope.launch(Dispatchers.IO) {
            com.jarvis.tools.AppScanner.scanInstalledApps(context)
        }
    }

    private fun loadVoiceProfile() {
        runCatching {
            val store = UiPreferencesStore(context)
            val profile = store.loadVoiceProfile()
            if (profile.isEnrolled) {
                wakeEngine.applyVoiceProfile(profile)
            }
        }
    }

    private fun startEventBusObserver() {
        scope.launch {
            eventBus.events.collect { event ->
                when (event) {
                    is com.jarvis.controlplane.JarvisEvent.BatteryStateChanged -> {
                        worldStore.update { it.copy(batteryPercent = event.level, isCharging = event.isCharging) }
                    }
                    is com.jarvis.controlplane.JarvisEvent.IncomingCall -> {
                        worldStore.update { it.copy(activeCaller = event.callerName ?: event.phoneNumber) }
                    }
                    is com.jarvis.controlplane.JarvisEvent.CallStateChanged -> {
                        if (event.state.equals("IDLE", ignoreCase = true)) {
                            worldStore.update { it.copy(activeCaller = null) }
                        }
                    }
                    is com.jarvis.controlplane.JarvisEvent.MediaStateChanged -> {
                        worldStore.update { it.copy(isMediaPlaying = event.isPlaying, currentMediaTitle = event.title) }
                    }
                    is com.jarvis.controlplane.JarvisEvent.BluetoothStateChanged -> {
                        worldStore.update { it.copy(isBluetoothScoConnected = event.isScoReady) }
                    }
                    is com.jarvis.controlplane.JarvisEvent.NetworkStateChanged -> {
                        worldStore.update { it.copy(isNetworkOnline = event.isOnline, isMeteredNetwork = event.isMetered) }
                    }
                    is com.jarvis.controlplane.JarvisEvent.SmsReceived -> {
                        worldStore.update { it.copy(unreadSmsCount = it.unreadSmsCount + 1, latestOtp = event.detectedOtp ?: it.latestOtp) }
                    }
                    is com.jarvis.controlplane.JarvisEvent.WhatsAppNotification -> {
                        worldStore.update { it.copy(unreadWhatsAppCount = it.unreadWhatsAppCount + 1) }
                        if (event.sender.isNotBlank()) {
                            announceIncomingWhatsAppMessage(event.sender, event.message, event.platform)
                        }
                    }
                    else -> Unit
                }
            }
        }
    }

    // A fresh process means no closed loop is still alive. Tasks the previous
    // process left mid-flight or waiting on a clarification are orphaned:
    // mark them CANCELLED so the ledger never shows a live task nobody will finish.
    private fun reapInterruptedTasks() {
        val interrupted = listOf("RUNNING", "THINKING", "EXECUTING", "VERIFYING", "WAITING_FOR_USER")
        for (task in taskStateManager.getRecentTasks(limit = 20)) {
            if (task.status in interrupted) {
                taskStateManager.updateTaskStatus(task.id, "CANCELLED", "Session restarted; task interrupted")
                Log.i(TAG, "Reaped orphaned task ${task.id} (${task.status}) left by previous process")
            }
        }
    }

    private var currentLlmClient: LlmClient? = null
    private var currentStrongClient: LlmClient? = null

    private fun initPlannerAndKernel() {
        (currentLlmClient as? java.io.Closeable)?.close()
        (currentStrongClient as? java.io.Closeable)?.close()

        val secureStore = com.jarvis.ui.security.SecureCredentialStore(context)
        val uiStore = com.jarvis.ui.data.UiPreferencesStore(context)

        val effectiveGroqKey = groqApiKey.ifBlank {
            secureStore.get("groq_api_key") ?: secureStore.get("groq") ?: com.jarvis.BuildConfig.GROQ_API_KEY
        }
        val effectiveGeminiKey = (secureStore.get("gemini_api_key") ?: secureStore.get("gemini") ?: com.jarvis.BuildConfig.GEMINI_API_KEY).orEmpty()
        val effectiveOpenRouterKey = (secureStore.get("openrouter_api_key") ?: secureStore.get("openrouter") ?: com.jarvis.BuildConfig.OPENROUTER_API_KEY).orEmpty()
        val effectiveOllamaUrl = (secureStore.get("ollama_base_url") ?: secureStore.get("ollama") ?: com.jarvis.BuildConfig.OLLAMA_BASE_URL).orEmpty()

        val providers = mutableListOf<ProviderEntry>()
        val strongProviders = mutableListOf<ProviderEntry>()

        if (effectiveGroqKey.isNotBlank()) {
            providers.add(ProviderEntry("Groq", GroqLlm(effectiveGroqKey, model = groqModel), priority = 0))
            strongProviders.add(ProviderEntry("Groq-Strong", GroqLlm(effectiveGroqKey, model = LlmConfig.STRONG_MODEL), priority = 0))
        }

        if (effectiveGeminiKey.isNotBlank()) {
            providers.add(ProviderEntry("Gemini", GeminiLlm(effectiveGeminiKey, model = LlmConfig.GEMINI_DEFAULT_MODEL), priority = 1))
            strongProviders.add(ProviderEntry("Gemini-Strong", GeminiLlm(effectiveGeminiKey, model = LlmConfig.GEMINI_STRONG_MODEL), priority = 1))
        }

        if (effectiveOpenRouterKey.isNotBlank()) {
            providers.add(ProviderEntry("OpenRouter", OpenRouterLlm(effectiveOpenRouterKey, model = LlmConfig.OPENROUTER_DEFAULT_MODEL), priority = 2))
            strongProviders.add(ProviderEntry("OpenRouter-Strong", OpenRouterLlm(effectiveOpenRouterKey, model = LlmConfig.OPENROUTER_STRONG_MODEL), priority = 2))
        }

        if (effectiveOllamaUrl.isNotBlank()) {
            providers.add(ProviderEntry("Ollama", OllamaLlm(baseUrl = effectiveOllamaUrl, model = LlmConfig.OLLAMA_DEFAULT_MODEL), priority = 3))
            strongProviders.add(ProviderEntry("Ollama", OllamaLlm(baseUrl = effectiveOllamaUrl, model = LlmConfig.OLLAMA_DEFAULT_MODEL), priority = 3))
        }

        // Also check custom APIs configured via ApiManager
        for (api in uiStore.loadApis().filter { it.active }) {
            val key = api.credentialId?.let { secureStore.get(it) }.orEmpty()
            val name = api.name.lowercase()
            if (key.isNotBlank()) {
                if (name.contains("gemini") && providers.none { it.name == "Gemini" }) {
                    providers.add(ProviderEntry("Gemini", GeminiLlm(key, model = LlmConfig.GEMINI_DEFAULT_MODEL), priority = 1))
                    strongProviders.add(ProviderEntry("Gemini-Strong", GeminiLlm(key, model = LlmConfig.GEMINI_STRONG_MODEL), priority = 1))
                } else if ((name.contains("openrouter") || name.contains("claude") || name.contains("deepseek")) && providers.none { it.name == "OpenRouter" }) {
                    providers.add(ProviderEntry("OpenRouter", OpenRouterLlm(key, model = LlmConfig.OPENROUTER_DEFAULT_MODEL), priority = 2))
                    strongProviders.add(ProviderEntry("OpenRouter-Strong", OpenRouterLlm(key, model = LlmConfig.OPENROUTER_STRONG_MODEL), priority = 2))
                }
            }
        }

        val client: LlmClient? = if (providers.isNotEmpty()) MultiProviderLlmClient(providers) else null
        val strongClient: LlmClient? = if (strongProviders.isNotEmpty()) MultiProviderLlmClient(strongProviders) else null

        currentLlmClient = client
        currentStrongClient = strongClient

        if (client != null) {
            llmPlanner = LlmPlanner(
                client,
                router = com.jarvis.ai.ModelRouter(defaultModel = groqModel),
                strongLlm = strongClient
            )
        }
        toolRegistry.registerLazy(
            name = "SCREEN_VISION",
            description = "Analyzes on-screen content and active application context"
        ) {
            com.jarvis.tools.ScreenVisionTool(context, currentLlmClient)
        }
        toolRegistry.registerLazy(
            name = "CAMERA_VISION",
            description = "Captures and analyzes real-world camera images via on-device TFLite MobileNet detection"
        ) {
            com.jarvis.tools.CameraVisionTool(context, cameraPerceptionEngine, cameraDetector, currentLlmClient)
        }

        agentKernel = AgentKernel(
            planner = llmPlanner,
            contextRouter = contextRouter,
            executor = toolExecutor,
            verifier = verificationEngine,
            taskState = taskStateManager,
            memory = augmentedMemoryPipeline,
            recovery = recoveryManager,
            goalEvaluator = goalEvaluator,
            experienceManager = experienceManager,
            skillRegistry = skillRegistry,
            learnedSkillStore = learnedSkillStore,
            checkpointManager = checkpointManager,
            skillDiscoveryEngine = skillDiscoveryEngine
        )
    }

    fun cancelCurrentTask() {
        val toCancel = activeTaskId ?: pendingClarificationTaskId
        Log.i(TAG, "Cancelling task: $toCancel")
        activeTaskJob?.cancel()
        toCancel?.let { id ->
            taskStateManager.updateTaskStatus(id, "CANCELLED", "User cancelled task")
        }
        activeTaskId = null
        pendingClarificationTaskId = null
        pendingResumeStrikes = 0
    }

    suspend fun executeAutonomousGoal(
        objective: String,
        actionType: String,
        params: Map<String, String> = emptyMap(),
        priority: Int = 0,
        rollbackAction: (suspend () -> Unit)? = null
    ): com.jarvis.controlplane.GoalExecutionResult {
        val goal = com.jarvis.controlplane.Goal(
            objective = objective,
            actionType = actionType,
            params = params,
            priority = priority,
            rollbackAction = rollbackAction
        )
        goalManager.submitGoal(goal)
        return executionPipeline.executeGoal(goal, toolExecutor)
    }

    private fun registerDefaultTools() {
        // App controls & Discovery
        val appListTool = ListInstalledAppsTool(context)
        toolRegistry.register(appListTool, appListTool.metadata)
        toolRegistry.register(OpenAppTool(context))
        toolRegistry.register(CloseAppTool(context))
        toolRegistry.register(AppsCloseAllTool(context))

        // Media & YouTube
        toolRegistry.register(MusicPlayTool(context))
        toolRegistry.register(YouTubePlayTool(context))
        toolRegistry.register(MediaPlaybackControlTool(context))
        toolRegistry.registerLazy("SPOTIFY_PLAY", "Controls Spotify playback and playlists") {
            SpotifyControlTool(context, mediaSessionManager)
        }

        // Communication (Lazy)
        toolRegistry.registerLazy(
            name = "WHATSAPP",
            description = "Sends messages, reads unread messages, interactively replies, or configures auto-reply for WhatsApp and WhatsApp Business",
            metadata = com.jarvis.foundation.ToolMetadata(
                name = "WHATSAPP",
                description = "Sends messages, reads unread messages, interactively replies, or configures auto-reply for WhatsApp and WhatsApp Business",
                parameters = listOf(
                    com.jarvis.foundation.ParameterSchema("recipient", "string", "Recipient name or phone number", required = false),
                    com.jarvis.foundation.ParameterSchema("message", "string", "Message to send", required = false),
                    com.jarvis.foundation.ParameterSchema("action", "string", "Action: send_message, read_messages, reply, auto_reply, open", required = false),
                    com.jarvis.foundation.ParameterSchema("mode", "string", "Auto-reply mode: driving, meeting, busy, off", required = false),
                    com.jarvis.foundation.ParameterSchema("platform", "string", "Target platform: standard (default) or business", required = false),
                    com.jarvis.foundation.ParameterSchema("sender", "string", "Filter messages by sender name", required = false),
                    com.jarvis.foundation.ParameterSchema("limit", "integer", "Maximum messages to read (1-10)", required = false)
                ),
                riskLevel = com.jarvis.foundation.RiskLevel.HIGH
            )
        ) {
            WhatsAppTool(context, whatsappMessagingEngine)
        }
        // System & Knowledge
        val flashlight = FlashlightTool(context)
        toolRegistry.register(flashlight, flashlight.metadata)
        val deepResearch = DeepResearchTool(context, fileManager) { currentLlmClient }
        toolRegistry.register(deepResearch, deepResearch.metadata)
        toolRegistry.register(WebSearchTool(context))
        toolRegistry.register(CalculatorTool(context))
        toolRegistry.register(WeatherTool(context))
        toolRegistry.register(TranslatorTool(context))
        toolRegistry.register(ClockTool(context))
        toolRegistry.register(QuickNotesTool(context))
        val protocolTool = JarvisProtocolTool(context)
        toolRegistry.register(protocolTool, protocolTool.metadata)
        val navTool = NavigationTool(context)
        toolRegistry.register(navTool, navTool.metadata)
        val unitConverterTool = UnitCurrencyConverterTool(context)
        toolRegistry.register(unitConverterTool, unitConverterTool.metadata)
        toolRegistry.registerLazy("SPEAK", "Speaks text aloud using on-device TTS") {
            SpeakTool(ttsEngine) { text -> recordSpokenUtterance(text) }
        }
        toolRegistry.registerLazy("NOTE", "Saves notes and long-term memory") {
            NoteTool(memoryStore)
        }
        toolRegistry.registerLazy("SEARCH_MEMORY", "Searches encrypted on-device memory store") {
            SearchMemoryTool(memoryStore)
        }

        // Foundation File & System tools
        val jarvisDbTool = JarvisDbTool(context)
        toolRegistry.register(jarvisDbTool, jarvisDbTool.metadata)
        val locate = FileLocateTool(context)
        val read = FileReadTool(context)
        val write = FileWriteTool(context)
        toolRegistry.register(locate, locate.metadata)
        toolRegistry.register(read, read.metadata)
        toolRegistry.register(write, write.metadata)

        val osInspect = OsSystemInspectionTool(context)
        toolRegistry.register(osInspect, osInspect.metadata)
        val browserDom = BrowserDomTool(context)
        toolRegistry.register(browserDom, browserDom.metadata)

        // UI & Screen Automation tools
        val uiClick = UiClickTool(context)
        val uiScroll = UiScrollTool(context)
        val uiType = UiTypeTool(context)
        val uiInspect = UiInspectTool(context)
        val uiGlobal = UiGlobalActionTool(context)
        val notifs = com.jarvis.tools.NotificationsTool(context)
        val battery = com.jarvis.tools.BatteryStatusTool(context)
        val briefing = com.jarvis.tools.DailyBriefingTool(context)
        val screenLock = com.jarvis.tools.ScreenLockTool(context)
        val screenUnlock = com.jarvis.tools.ScreenUnlockTool(context)
        toolRegistry.register(uiClick, uiClick.metadata)
        toolRegistry.register(uiScroll, uiScroll.metadata)
        toolRegistry.register(uiType, uiType.metadata)
        toolRegistry.register(uiInspect, uiInspect.metadata)
        toolRegistry.register(uiGlobal, uiGlobal.metadata)
        toolRegistry.register(screenLock, screenLock.metadata)
        toolRegistry.register(screenUnlock, screenUnlock.metadata)
        toolRegistry.register(notifs, notifs.metadata)
        toolRegistry.register(battery, battery.metadata)
        toolRegistry.register(briefing, briefing.metadata)

        // Lazy automation and knowledge tools
        val deviceSettings = com.jarvis.tools.DeviceSettingsTool(context)
        toolRegistry.register(deviceSettings, deviceSettings.metadata)
        toolRegistry.registerLazy("ROUTINE_MANAGE", "Creates, executes, and manages smart automation routines") {
            com.jarvis.tools.RoutineManageTool(smartRoutineEngine)
        }
        toolRegistry.registerLazy("MACRO_WORKFLOW", "Runs recorded autonomous UI macro workflows") {
            com.jarvis.tools.MacroWorkflowTool(macroWorkflowEngine)
        }
        toolRegistry.registerLazy("APP_AUTOPILOT", "Autonomously interacts with on-screen UI across third-party apps") {
            com.jarvis.tools.AppAutopilotTool(context)
        }
        toolRegistry.registerLazy("TELEPHONY_CONTROL", "Places calls, reads SMS, answers/declines calls") {
            com.jarvis.tools.TelephonyTool(context, contactResolver, callAndSmsAgent)
        }
        val calendarTool = com.jarvis.tools.CalendarTool(context)
        toolRegistry.register(calendarTool, calendarTool.metadata)
        val securityTool = com.jarvis.tools.SecurityAuditorTool(context)
        toolRegistry.register(securityTool, securityTool.metadata)
        toolRegistry.registerLazy("FILE_MANAGER", "Searches storage, inspects disk space, and suggests cleanup") {
            com.jarvis.tools.FileManagerTool(context)
        }
        toolRegistry.registerLazy("AUTONOMOUS_CONTROL", "Configures ambient autopilot and situational modes") {
            com.jarvis.tools.AutonomousModeTool(ambientContextEngine, autonomousDaemon)
        }
        toolRegistry.registerLazy("RAG_RETRIEVE", "Retrieves relevant passages and facts from local knowledge vault") {
            com.jarvis.tools.RagQueryTool(ragRetriever)
        }
        toolRegistry.registerLazy("RAG_INDEX", "Indexes local documents and notes into vector knowledge vault") {
            com.jarvis.tools.RagIndexTool(ragIndexStore, ragChunker)
        }
        val logsTool = com.jarvis.tools.LogsTool(logReaderEngine)
        toolRegistry.register(logsTool)
        val reminderTool = com.jarvis.tools.ReminderSchedulerTool(context)
        toolRegistry.register(reminderTool, reminderTool.metadata)
        val locationTool = com.jarvis.tools.LocationTool(context)
        toolRegistry.register(locationTool, locationTool.metadata)
        val switchboardTool = com.jarvis.tools.SystemSwitchboardTool(context)
        toolRegistry.register(switchboardTool, switchboardTool.metadata)
        val messageReaderTool = com.jarvis.tools.MessageReaderTool()
        toolRegistry.register(messageReaderTool, messageReaderTool.metadata)

    }

    /**
     * Responds to OS memory pressure signals from ComponentCallbacks2.
     * Evicts idle vision detectors, flushes RAG vector caches, and frees idle lazy tools.
     */
    fun onTrimMemory(level: Int) {
        subsystems.onTrimMemory(level)
        toolRegistry.evictIdleLazyTools()
    }

    fun updateConfig(apiKey: String, model: String) {
        groqApiKey = apiKey
        groqModel = model.ifBlank { LlmConfig.DEFAULT_MODEL }
        initPlannerAndKernel()
        Log.i(TAG, "AssistantRuntime updated with LLM config (model: $groqModel)")
    }

    fun reloadLlmProviders() {
        initPlannerAndKernel()
        Log.i(TAG, "AssistantRuntime LLM providers reloaded")
    }

    private val commandMutex = Mutex()

    suspend fun executeCommand(
        utterance: String,
        userApprovalGranted: Boolean = false,   // SECURITY: fail-closed — callers must explicitly grant approval
        onEvent: (AgentEvent) -> Unit = {}
    ): RuntimeExecutionResult = commandMutex.withLock {
        val start = System.currentTimeMillis()
        val result = executeCommandInternal(utterance, userApprovalGranted, onEvent)
        metrics.record("command", "execute", ok = result.success, wallMs = System.currentTimeMillis() - start)
        result
    }

    private suspend fun executeCommandInternal(
        utterance: String,
        userApprovalGranted: Boolean = false,   // SECURITY: fail-closed
        onEvent: (AgentEvent) -> Unit = {}
    ): RuntimeExecutionResult {
        Log.i(TAG, "Runtime executing command: '$utterance'")
        clearRecentlySpoken()
        worldStateService.fresh()
        conversationManager.addMessage("user", utterance)

        // 1. Check local fast-path intent resolver first for sub-millisecond offline execution.
        //    Actions with real-world side effects (or whose outcome needs verification) are
        //    NEVER fast-pathed — they must go through the closed loop so they can be verified.
        val resolvedIntent = IntentResolver.resolve(utterance)

        // Cancel is a runtime control, not a tool: handle it before the busy gate and the
        // clarification routing, so "cancel" always works while a task is in flight or
        // awaiting clarification (unlike a normal command, which is rejected while busy).
        if (resolvedIntent?.intent == AssistantIntent.CANCEL_TASK) {
            Log.i(TAG, "Cancel intent detected; cancelling active task")
            cancelCurrentTask()
            val spoken = "Task cancelled."
            conversationManager.addMessage("assistant", spoken)
            onEvent(AgentEvent.Cancelled(spoken))
            return RuntimeExecutionResult(
                success = true,
                spokenResponse = spoken,
                stepResults = emptyList(),
                verified = false
            )
        }

        // Busy gate: a task is already running — don't stack a second closed loop.
        if (activeTaskJob?.isActive == true) {
            Log.w(TAG, "A task is already active (taskId=$activeTaskId); rejecting concurrent command.")
            return RuntimeExecutionResult(
                success = false,
                spokenResponse = "Still working on the previous task. Please wait or cancel it first.",
                stepResults = emptyList(),
                verified = false
            )
        }

        // If there is a pending task waiting for user clarification, route to task resumption.
        // Only for a bounded number of unanswered re-asks; after that the clarification is
        // considered abandoned and new utterances run as fresh tasks instead of being eaten.
        if (pendingClarificationTaskId != null) {
            if (pendingResumeStrikes < MAX_CLARIFICATION_RESUMES) {
                Log.i(TAG, "Routing utterance '$utterance' to resume pending task $pendingClarificationTaskId")
                return resumePendingTask(utterance, userApprovalGranted, onEvent)
            }
            val stale = pendingClarificationTaskId
            pendingClarificationTaskId = null
            pendingResumeStrikes = 0
            if (stale != null) {
                taskStateManager.updateTaskStatus(stale, "CANCELLED", "Not clarified after repeated asks; superseded by new command")
                Log.i(TAG, "Pending task $stale abandoned (clarification unanswered twice); running new command fresh")
            }
        }

        val fastPathSafe = resolvedIntent?.directPlan?.actions?.none { action ->
            toolRegistry.requiresOutcomeVerification(action.type)
        } == true

        if (fastPathSafe) {
            val directPlan = resolvedIntent!!.directPlan!!
            Log.i(TAG, "Executing fast-path plan for intent: ${resolvedIntent.intent}")
            val stepResults = mutableListOf<ToolResult>()
            var overallSuccess = true

            for (action in directPlan.actions) {
                val res = toolExecutor.execute(action.type, action.params, userApprovalGranted)
                stepResults.add(res)
                if (!res.success) {
                    overallSuccess = false
                    break
                }
            }

            augmentedMemoryPipeline.rememberUserTurn(MemoryNamespaces.CONVERSATION, utterance)
            augmentedMemoryPipeline.rememberAssistantTurn(MemoryNamespaces.CONVERSATION, directPlan.response)
            conversationManager.addMessage("assistant", directPlan.response)
            onEvent(AgentEvent.Completed(directPlan.response))

            if (overallSuccess) {
                when (resolvedIntent.intent) {
                    AssistantIntent.APP_OPEN -> habitManager.recordAppUsage(resolvedIntent.params["app"].orEmpty())
                    AssistantIntent.PLAY_MEDIA -> habitManager.recordMediaPlayback(resolvedIntent.params["query"].orEmpty())
                    else -> Unit
                }
            }

            return RuntimeExecutionResult(
                success = overallSuccess,
                spokenResponse = directPlan.response,
                stepResults = stepResults,
                // 'verified' means a real-world outcome was confirmed, NOT just that the tool call returned success.
                // Dispatching is not verification. The VerificationEngine is the only authoritative source of verified=true.
                verified = false
            )
        }

        // 2. Delegate to closed-loop AgentKernel on a tracked job so it is cancellable.
        val kernel = agentKernel ?: run {
            initPlannerAndKernel()
            agentKernel ?: error("AgentKernel is not initialized")
        }
        val historySnapshot = conversationManager.getHistory()
        val job = ensureActiveScope().async {
            kernel.run(
                goal = utterance,
                userApprovalGranted = userApprovalGranted,
                history = historySnapshot,
                onEvent = { event ->
                    when (event) {
                        is AgentEvent.TaskStarted -> {
                            activeTaskId = event.taskId
                            pendingClarificationTaskId = null
                            pendingResumeStrikes = 0
                        }
                        else -> onEvent(event)
                    }
                }
            )
        }
        activeTaskJob = job

        val agentResult = try {
            job.await()
        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Task cancelled: ${e.message}")
            taskStateManager.updateTaskStatus(activeTaskId ?: -1L, "CANCELLED", "Task cancelled by user")
            onEvent(AgentEvent.Cancelled("Task cancelled by user"))
            return RuntimeExecutionResult(
                success = false,
                spokenResponse = "Task cancelled.",
                stepResults = emptyList(),
                verified = false
            )
        } finally {
            activeTaskJob = null
            activeTaskId = null
        }

        if (agentResult.response.isNotBlank()) {
            augmentedMemoryPipeline.rememberUserTurn(MemoryNamespaces.CONVERSATION, utterance)
            augmentedMemoryPipeline.rememberAssistantTurn(MemoryNamespaces.CONVERSATION, agentResult.response)
            conversationManager.addMessage("assistant", agentResult.response)
        }

        val taskRec = taskStateManager.getTask(agentResult.taskId)
        if (taskRec?.status == "WAITING_FOR_USER") {
            pendingClarificationTaskId = agentResult.taskId
            Log.i(TAG, "Task ${agentResult.taskId} set as pending clarification")
        } else {
            pendingClarificationTaskId = null
        }

        val toolResults = agentResult.steps.map {
            ToolResult(it.success, it.message)
        }

        if (agentResult.success && agentResult.verifiedActions.isNotEmpty()) {
            adaptiveSkillEvolution.onTaskCompleted(utterance, agentResult.verifiedActions)
        }

        return RuntimeExecutionResult(
            success = agentResult.success,
            spokenResponse = agentResult.response,
            stepResults = toolResults,
            verified = agentResult.verified
        )
    }

    suspend fun resumePendingTask(
        clarification: String,
        userApprovalGranted: Boolean = true,
        onEvent: (AgentEvent) -> Unit = {}
    ): RuntimeExecutionResult {
        val taskId = pendingClarificationTaskId
        val kernel = agentKernel ?: error("AgentKernel is not initialized")
        if (taskId != null) {
            pendingClarificationTaskId = null
            conversationManager.addMessage("user", clarification)
            val historySnapshot = conversationManager.getHistory()
            val job = scope.async {
                kernel.resumeTask(
                    taskId = taskId,
                    clarification = clarification,
                    userApprovalGranted = userApprovalGranted,
                    history = historySnapshot,
                    onEvent = { event ->
                        when (event) {
                            is AgentEvent.TaskStarted -> {
                                activeTaskId = event.taskId
                                pendingClarificationTaskId = null
                            }
                            else -> onEvent(event)
                        }
                    }
                )
            }
            activeTaskJob = job

            val agentResult = try {
                job.await()
            } catch (e: kotlinx.coroutines.CancellationException) {
                Log.i(TAG, "Task cancelled during resume: ${e.message}")
                taskStateManager.updateTaskStatus(activeTaskId ?: taskId, "CANCELLED", "Task cancelled by user")
                onEvent(AgentEvent.Cancelled("Task cancelled by user"))
                return RuntimeExecutionResult(
                    success = false,
                    spokenResponse = "Task cancelled.",
                    stepResults = emptyList(),
                    verified = false
                )
            } finally {
                activeTaskJob = null
                activeTaskId = null
            }

            val taskRec = taskStateManager.getTask(agentResult.taskId)
            if (taskRec?.status == "WAITING_FOR_USER") {
                pendingClarificationTaskId = agentResult.taskId
                // A resume that leaves the task still asking was fruitless — count it so an
                // unfixable task is abandoned after MAX_CLARIFICATION_RESUMES instead of
                // swallowing every later command as a clarification of a stuck thread.
                pendingResumeStrikes++
            } else {
                pendingClarificationTaskId = null
                pendingResumeStrikes = 0
            }

            augmentedMemoryPipeline.rememberUserTurn(MemoryNamespaces.CONVERSATION, clarification)
            augmentedMemoryPipeline.rememberAssistantTurn(MemoryNamespaces.CONVERSATION, agentResult.response)

            val toolResults = agentResult.steps.map {
                ToolResult(it.success, it.message)
            }

            return RuntimeExecutionResult(
                success = agentResult.success,
                spokenResponse = agentResult.response,
                stepResults = toolResults,
                verified = agentResult.verified
            )
        }
        return executeCommand(clarification, userApprovalGranted, onEvent)
    }

    fun release() {
        cancelCurrentTask()
        networkStateMonitor.stop()
        currentLlmClient?.close()
        currentStrongClient?.close()
        subsystems.releaseAll()
        scope.cancel()
    }
}

object MemoryNamespaces {
    const val DOCUMENTS = "documents"
    const val CONVERSATION = "conversation"
    const val LONG_TERM = "long_term"
    const val PREFERENCES = "preferences"
}

