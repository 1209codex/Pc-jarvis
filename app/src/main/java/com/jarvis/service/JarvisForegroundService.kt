package com.jarvis.service

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import com.jarvis.BuildConfig
import com.jarvis.voice.VoiceEngine
import com.jarvis.voice.VoiceEngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ServiceStartRequest(val action: String, val command: String? = null)

class JarvisForegroundService : Service() {
    private val TAG = "JarvisService"
    private val binder = LocalBinder()
    private var scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private fun ensureActiveScope(): CoroutineScope {
        if (!scope.isActive) {
            scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
        }
        return scope
    }

    var voiceEngine: VoiceEngine? = null
        private set
    var proactiveContextEngine: com.jarvis.context.ProactiveContextEngine? = null
        private set
    var bluetoothHeadsetManager: com.jarvis.bluetooth.BluetoothHeadsetManager? = null
        private set
    var headsetMediaButtonManager: com.jarvis.bluetooth.HeadsetMediaButtonManager? = null
        private set
    var lanServer: com.jarvis.network.JarvisLanServer? = null
        private set
    var pocketAndMotionManager: com.jarvis.sensors.PocketAndMotionManager? = null
        private set
    var earbudsAssistantEngine: com.jarvis.earbuds.EarbudsAssistantEngine? = null
        private set
    var clipboardIntelligenceEngine: com.jarvis.clipboard.ClipboardIntelligenceEngine? = null
        private set
    var batteryAutopilotEngine: com.jarvis.battery.BatteryThermalAutopilotEngine? = null
        private set
    var ambientContextEngine: com.jarvis.context.AmbientContextEngine? = null
        private set
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var guardianJob: kotlinx.coroutines.Job? = null
    private var isListeningSuspended = false

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
            wakeLock = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::VoiceWakeLock")
            wakeLock?.setReferenceCounted(false)
        }
        if (wakeLock?.isHeld == false) {
            wakeLock?.acquire(24 * 60 * 60 * 1000L) // 24 hour timeout safeguard
            Log.d(TAG, "Acquired partial WakeLock for background assistant")
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "Released partial WakeLock")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error releasing WakeLock", e)
        }
    }

    private fun startGuardianObserver() {
        guardianJob?.cancel()
        val engine = voiceEngine ?: return
        val guardian = engine.assistantRuntime.deviceGuardian

        guardianJob = ensureActiveScope().launch {
            engine.assistantRuntime.worldStore.state.collect { _ ->
                val verdict = guardian.canExecuteAutonomousTask(requiresAudio = true)
                if (!verdict.allowed) {
                    if (!isListeningSuspended) {
                        isListeningSuspended = true
                        Log.i(TAG, "Suspending continuous listening & releasing WakeLock: ${verdict.reason}")
                        engine.continuousAudioStream.stop()
                        releaseWakeLock()
                    }
                } else {
                    if (isListeningSuspended) {
                        isListeningSuspended = false
                        Log.i(TAG, "Resuming continuous listening & acquiring WakeLock: ${verdict.reason}")
                        acquireWakeLock()
                        engine.continuousAudioStream.start()
                    }
                }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): JarvisForegroundService = this@JarvisForegroundService
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.i(TAG, "JarvisForegroundService onCreate")
        createNotificationChannel()

        val (apiKey, model) = loadActiveConfig()
        voiceEngine = VoiceEngine(applicationContext, groqApiKey = apiKey, groqModel = model)
        lanServer = com.jarvis.network.JarvisLanServer(applicationContext)
    }

    fun reloadConfig() {
        val (apiKey, model) = loadActiveConfig()
        voiceEngine?.updateConfig(apiKey, model)
        voiceEngine?.assistantRuntime?.reloadLlmProviders()
    }

    private fun loadActiveConfig(): Pair<String, String> {
        val secureStore = com.jarvis.ui.security.SecureCredentialStore(applicationContext)
        val uiStore = com.jarvis.ui.data.UiPreferencesStore(applicationContext)
        val apis = uiStore.loadApis()
        val activeApi = apis.firstOrNull { it.active && it.name.contains("groq", ignoreCase = true) }
            ?: apis.firstOrNull { it.active }
        val storedKey = activeApi?.credentialId?.let { secureStore.get(it) }
            ?: secureStore.get("groq_api_key")
            ?: secureStore.get("groq")
        val apiKey = (storedKey ?: "").takeIf { it.isNotBlank() } ?: BuildConfig.GROQ_API_KEY.takeIf { it.isNotBlank() } ?: ""
        val settings = uiStore.loadSettings()
        val model = settings.preferredLlmModel.ifBlank { BuildConfig.GROQ_MODEL }
        return Pair(apiKey, model)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "JarvisForegroundService onStartCommand: ${intent?.action}")

        val notification = createNotification("J.A.R.V.I.S. is online — wake word detection active")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val action = intent?.action
        when {
            action == ACTION_START -> startEngine()
            action == ACTION_MANUAL_LISTEN -> startEngine(manualListen = true)
            action == ACTION_STOP -> stopEngine()
            action == "com.jarvis.action.EXECUTE" || action == ACTION_EXECUTE_COMMAND -> {
                val command = intent.getStringExtra("command")
                    ?: intent.getStringExtra("query")
                    ?: intent.getStringExtra("command_text")
                if (!command.isNullOrBlank()) {
                    Log.i(TAG, "JarvisForegroundService received direct command: '$command'")
                    executeTextCommand(command)
                }
            }
            else -> startEngine()
        }

        return START_STICKY
    }

    fun startEngine(manualListen: Boolean = false) {
        acquireWakeLock()
        ensureActiveScope().launch {
            if (voiceEngine == null) {
                val (apiKey, model) = loadActiveConfig()
                voiceEngine = VoiceEngine(applicationContext, groqApiKey = apiKey, groqModel = model)
            }
            if (proactiveContextEngine == null) {
                proactiveContextEngine = com.jarvis.context.ProactiveContextEngine(applicationContext, voiceEngine)
            } else {
                proactiveContextEngine?.voiceEngine = voiceEngine
            }
            proactiveContextEngine?.start()

            val appSettings = com.jarvis.ui.data.UiPreferencesStore(applicationContext).loadSettings()

            if (bluetoothHeadsetManager == null) {
                bluetoothHeadsetManager = com.jarvis.bluetooth.BluetoothHeadsetManager(applicationContext)
                bluetoothHeadsetManager?.onHeadsetStateChanged = { connected, deviceName ->
                    Log.i(TAG, "Bluetooth headset connected=$connected ($deviceName)")
                    val settings = com.jarvis.ui.data.UiPreferencesStore(applicationContext).loadSettings()
                    if (connected && settings.bluetoothScoRoutingEnabled) {
                        val ok = bluetoothHeadsetManager?.startScoRouting() == true
                        Log.i(TAG, "Bluetooth headset connected: startScoRouting result=$ok")
                        voiceEngine?.updateBluetoothScoState(true)
                        voiceEngine?.continuousAudioStream?.updatePreferredDevice()
                    } else if (!connected) {
                        bluetoothHeadsetManager?.stopScoRouting()
                        voiceEngine?.updateBluetoothScoState(false)
                        voiceEngine?.continuousAudioStream?.updatePreferredDevice()
                    }
                }
            }
            bluetoothHeadsetManager?.start()

            if (headsetMediaButtonManager == null) {
                headsetMediaButtonManager = com.jarvis.bluetooth.HeadsetMediaButtonManager(applicationContext) {
                    Log.i(TAG, "Headset hook button triggered: waking Jarvis or physical barge-in")
                    val engine = voiceEngine ?: return@HeadsetMediaButtonManager
                    val state = engine.conversationManager.currentStateValue
                    if (state == com.jarvis.voice.VoiceState.SPEAKING || state == com.jarvis.voice.VoiceState.THINKING) {
                        engine.bargeIn()
                    } else {
                        engine.startManualListening()
                    }
                }
            }
            headsetMediaButtonManager?.isHookActivationEnabled = appSettings.headsetHookActivationEnabled
            headsetMediaButtonManager?.start()

            if (appSettings.wearableSyncEnabled) {
                com.jarvis.wear.WearableNotificationDispatcher.dispatchWearableCard(applicationContext)
            }

            if (appSettings.floatingOverlayEnabled &&
                (Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(applicationContext))
            ) {
                try {
                    val overlayIntent = Intent(applicationContext, com.jarvis.overlay.JarvisFloatingOverlayService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(overlayIntent)
                    } else {
                        applicationContext.startService(overlayIntent)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not auto-start floating overlay service", e)
                }
            }

            voiceEngine?.start()
            if (manualListen) voiceEngine?.startManualListening()

            if (pocketAndMotionManager == null) {
                pocketAndMotionManager = com.jarvis.sensors.PocketAndMotionManager(applicationContext).apply {
                    onFlipMuteTriggered = {
                        voiceEngine?.handleFlipMute()
                    }
                }
            }
            voiceEngine?.pocketAndMotionManager = pocketAndMotionManager
            pocketAndMotionManager?.start()

            val btMgr = bluetoothHeadsetManager
            if (btMgr != null && earbudsAssistantEngine == null) {
                earbudsAssistantEngine = com.jarvis.earbuds.EarbudsAssistantEngine(
                    context = applicationContext,
                    bluetoothHeadsetManager = btMgr,
                    voiceEngine = voiceEngine
                )
            }
            earbudsAssistantEngine?.start()

            if (clipboardIntelligenceEngine == null) {
                clipboardIntelligenceEngine = com.jarvis.clipboard.ClipboardIntelligenceEngine(
                    context = applicationContext,
                    bluetoothHeadsetManager = bluetoothHeadsetManager,
                    ttsEngine = voiceEngine?.ttsEngine
                )
            }
            clipboardIntelligenceEngine?.start()

            if (batteryAutopilotEngine == null) {
                batteryAutopilotEngine = com.jarvis.battery.BatteryThermalAutopilotEngine(
                    context = applicationContext,
                    ttsEngine = voiceEngine?.ttsEngine
                )
            }
            batteryAutopilotEngine?.start()

            if (ambientContextEngine == null) {
                ambientContextEngine = com.jarvis.context.AmbientContextEngine(
                    context = applicationContext,
                    bluetoothHeadsetManager = bluetoothHeadsetManager,
                    ttsEngine = voiceEngine?.ttsEngine
                )
            }
            ambientContextEngine?.start()

            startBluetoothScoObserver()
            startGuardianObserver()
            startNotificationObserver()
            scheduleHeartbeatWatchdog()
        }
    }

    private var scoObserverJob: kotlinx.coroutines.Job? = null

    private fun startBluetoothScoObserver() {
        scoObserverJob?.cancel()
        val engine = voiceEngine ?: return

        scoObserverJob = ensureActiveScope().launch {
            engine.status.collect { status ->
                val headsetMgr = bluetoothHeadsetManager ?: return@collect
                val settings = com.jarvis.ui.data.UiPreferencesStore(applicationContext).loadSettings()
                val isConnected = headsetMgr.isHeadsetConnected
                val scoEnabled = settings.bluetoothScoRoutingEnabled
                val isInteractionActive = status.voiceState == com.jarvis.voice.VoiceState.LISTENING ||
                        status.voiceState == com.jarvis.voice.VoiceState.SPEAKING

                if (isConnected && scoEnabled && isInteractionActive) {
                    if (!headsetMgr.isScoActive) {
                        val ok = headsetMgr.startScoRouting()
                        if (ok) {
                            engine.updateBluetoothScoState(true)
                            engine.continuousAudioStream.updatePreferredDevice()
                        }
                    }
                } else {
                    if (headsetMgr.isScoActive) {
                        headsetMgr.stopScoRouting()
                        engine.updateBluetoothScoState(false)
                        engine.continuousAudioStream.updatePreferredDevice()
                    }
                }
            }
        }
    }

    private var notificationJob: kotlinx.coroutines.Job? = null

    private fun startNotificationObserver() {
        notificationJob?.cancel()
        val engine = voiceEngine ?: return

        notificationJob = ensureActiveScope().launch {
            engine.status.collect { status ->
                val headsetName = bluetoothHeadsetManager?.connectedDeviceName
                val headsetText = if (!headsetName.isNullOrBlank()) " ($headsetName)" else ""
                val stateText = when (status.voiceState) {
                    com.jarvis.voice.VoiceState.WAITING_FOR_WAKE -> "Wake word active$headsetText"
                    com.jarvis.voice.VoiceState.WAKE_DETECTED -> "Wake word detected!"
                    com.jarvis.voice.VoiceState.LISTENING -> "Listening..."
                    com.jarvis.voice.VoiceState.THINKING -> "Thinking..."
                    com.jarvis.voice.VoiceState.EXECUTING -> "Executing..."
                    com.jarvis.voice.VoiceState.SPEAKING -> "Speaking..."
                    com.jarvis.voice.VoiceState.COMPLETED -> "Ready$headsetText"
                    com.jarvis.voice.VoiceState.ERROR -> "Ready"
                    else -> "Online$headsetText"
                }
                updateNotification(stateText)
            }
        }
    }

    private fun scheduleHeartbeatWatchdog() {
        try {
            val intent = Intent(applicationContext, JarvisBootReceiver::class.java).apply {
                action = "com.jarvis.action.HEARTBEAT"
            }
            val pendingIntent = PendingIntent.getBroadcast(
                applicationContext,
                1003,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + (15 * 60 * 1000L),
                15 * 60 * 1000L,
                pendingIntent
            )
        } catch (e: Exception) {
            Log.d(TAG, "Watchdog schedule notice: ${e.message}")
        }
    }

    fun updateNotification(content: String) {
        try {
            val manager = getSystemService(NotificationManager::class.java)
            val notification = createNotification(content)
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.d(TAG, "Notification update notice: ${e.message}")
        }
    }

    fun executeTextCommand(command: String) {
        ensureActiveScope().launch {
            voiceEngine?.executeDirectCommand(command)
        }
    }

    fun stopEngine() {
        scoObserverJob?.cancel()
        scoObserverJob = null
        guardianJob?.cancel()
        guardianJob = null
        releaseWakeLock()
        wakeLock = null
        try {
            headsetMediaButtonManager?.stop()
            headsetMediaButtonManager = null
            bluetoothHeadsetManager?.stop()
            bluetoothHeadsetManager = null
            pocketAndMotionManager?.stop()
            pocketAndMotionManager = null
            earbudsAssistantEngine?.stop()
            earbudsAssistantEngine = null
            clipboardIntelligenceEngine?.stop()
            clipboardIntelligenceEngine = null
            batteryAutopilotEngine?.stop()
            batteryAutopilotEngine = null
            ambientContextEngine?.stop()
            ambientContextEngine = null
            com.jarvis.wear.WearableNotificationDispatcher.dismiss(applicationContext)
        } catch (_: Exception) {}
        ensureActiveScope().launch {
            proactiveContextEngine?.stop()
            proactiveContextEngine = null
            voiceEngine?.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.i(TAG, "Jarvis task removed from recents; scheduling auto-revival watchdog")
        try {
            val restartServiceIntent = Intent(applicationContext, JarvisForegroundService::class.java).apply {
                setPackage(packageName)
                action = ACTION_START
            }
            val restartPendingIntent = PendingIntent.getService(
                applicationContext,
                1002,
                restartServiceIntent,
                PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
            alarmManager?.set(
                AlarmManager.ELAPSED_REALTIME,
                android.os.SystemClock.elapsedRealtime() + 1000L,
                restartPendingIntent
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to schedule revival watchdog on task removed", e)
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.i(TAG, "JarvisForegroundService onTrimMemory: level=$level")
        voiceEngine?.assistantRuntime?.onTrimMemory(level)
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "JarvisForegroundService onLowMemory received from OS")
        voiceEngine?.assistantRuntime?.onTrimMemory(android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
    }

    override fun onDestroy() {
        Log.i(TAG, "JarvisForegroundService onDestroy")
        try {
            headsetMediaButtonManager?.stop()
            headsetMediaButtonManager = null
            bluetoothHeadsetManager?.stop()
            bluetoothHeadsetManager = null
            com.jarvis.wear.WearableNotificationDispatcher.dismiss(applicationContext)
        } catch (_: Exception) {}
        try {
            proactiveContextEngine?.stop()
            proactiveContextEngine = null
        } catch (_: Exception) {}
        try {
            lanServer?.stop()
            lanServer = null
        } catch (_: Exception) {}
        guardianJob?.cancel()
        guardianJob = null
        releaseWakeLock()
        wakeLock = null
        try {
            kotlinx.coroutines.runBlocking(Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(1500) {
                    voiceEngine?.stop()
                }
            }
            voiceEngine?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error during synchronous service stop in onDestroy", e)
        }
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Jarvis Voice Assistant",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps Jarvis wake word detection active in background"
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(content: String): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Jarvis Assistant")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        @Volatile
        var instance: JarvisForegroundService? = null
            internal set

        const val CHANNEL_ID = "jarvis_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.jarvis.action.START"
        const val ACTION_STOP = "com.jarvis.action.STOP"
        const val ACTION_EXECUTE_COMMAND = "com.jarvis.action.EXECUTE_COMMAND"
        const val ACTION_MANUAL_LISTEN = "com.jarvis.action.MANUAL_LISTEN"
        fun commandStartRequest(command: String?): ServiceStartRequest? =
            command?.trim()?.takeIf { it.isNotEmpty() }?.let { ServiceStartRequest(ACTION_EXECUTE_COMMAND, it) }

        fun startService(context: Context) {
            val intent = Intent(context, JarvisForegroundService::class.java).apply {
                action = ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            // Use stopService() directly — do NOT call startForegroundService() just to send
            // a stop action. That triggers the restricted microphone FGS startup mechanism
            // unnecessarily and can crash on Android 15+ when called from the background.
            val intent = Intent(context, JarvisForegroundService::class.java)
            context.stopService(intent)
        }
    }
}
