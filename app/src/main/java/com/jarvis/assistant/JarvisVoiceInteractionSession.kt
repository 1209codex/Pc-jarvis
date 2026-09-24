package com.jarvis.assistant

import android.annotation.SuppressLint
import android.app.assist.AssistContent
import android.app.assist.AssistStructure
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.service.voice.VoiceInteractionSession
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import com.jarvis.runtime.MemoryNamespaces
import com.jarvis.service.JarvisForegroundService
import com.jarvis.ui.JarvisOrbView
import com.jarvis.voice.VoiceState
import kotlinx.coroutines.*

/**
 * Android System VoiceInteractionSession.
 * Renders the Cybernetic Assistant HUD Bottom Sheet overlay when the system assist gesture
 * (long-press Home, swipe from corner, power button) is triggered.
 */
class JarvisVoiceInteractionSession(context: Context) : VoiceInteractionSession(context) {

    companion object {
        private const val TAG = "JarvisVoiceSession"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // UI elements
    private var rootContainer: FrameLayout? = null
    private var bottomSheet: LinearLayout? = null
    private var orbView: JarvisOrbView? = null
    private var statusBadge: TextView? = null
    private var transcriptText: TextView? = null
    private var responseText: TextView? = null
    private var inputEditText: EditText? = null
    private var chipScroll: HorizontalScrollView? = null
    private var screenContextCache: ExtractedScreenContext? = null

    private var stateObserverJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        setContentView(createSessionView())
        try {
            @Suppress("DEPRECATION")
            window?.window?.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        } catch (e: Exception) {
            Log.d(TAG, "Failed applying lockscreen flags: ${e.message}")
        }
    }

    @SuppressLint("SetTextI18n")
    private fun createSessionView(): View {
        val ctx = context
        val density = ctx.resources.displayMetrics.density

        fun dp(value: Int): Int = (value * density).toInt()

        val root = FrameLayout(ctx).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#44000000")) // semi-transparent backdrop
            setOnClickListener { hide() }
        }
        rootContainer = root

        val sheet = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(16), dp(20), dp(24))

            val bg = GradientDrawable().apply {
                setColor(Color.parseColor("#E6090D16"))
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), Color.parseColor("#3300E5FF"))
            }
            background = bg

            // Consume clicks inside sheet
            setOnClickListener { /* no-op */ }
        }
        bottomSheet = sheet

        // Drag pill header
        val pill = View(ctx).apply {
            val pillBg = GradientDrawable().apply {
                setColor(Color.parseColor("#4400E5FF"))
                cornerRadius = dp(3).toFloat()
            }
            background = pillBg
        }
        sheet.addView(pill, LinearLayout.LayoutParams(dp(36), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(14)
        })

        // Header Row: Arc Reactor Orb + Title + Status Badge
        val headerRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val orb = JarvisOrbView(ctx)
        orbView = orb
        headerRow.addView(orb, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            rightMargin = dp(12)
        })

        val titleContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
        }

        val titleText = TextView(ctx).apply {
            text = "JARVIS DIGITAL ASSISTANT"
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.08f
        }
        titleContainer.addView(titleText)

        val status = TextView(ctx).apply {
            text = "READY FOR DIRECTIVES"
            setTextColor(Color.parseColor("#80FFFFFF"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
        }
        statusBadge = status
        titleContainer.addView(status)

        headerRow.addView(titleContainer, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val closeBtn = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_close_clear_cancel)
            setColorFilter(Color.parseColor("#80FFFFFF"))
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { hide() }
        }
        headerRow.addView(closeBtn, LinearLayout.LayoutParams(dp(32), dp(32)))

        sheet.addView(headerRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Transcription & Output Display
        val transcript = TextView(ctx).apply {
            text = "Listening..."
            setTextColor(Color.parseColor("#E0FFFFFF"))
            textSize = 16f
            typeface = Typeface.DEFAULT
            setPadding(0, dp(12), 0, dp(6))
        }
        transcriptText = transcript
        sheet.addView(transcript, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val response = TextView(ctx).apply {
            text = ""
            setTextColor(Color.parseColor("#00E5FF"))
            textSize = 14f
            typeface = Typeface.DEFAULT
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(8))
        }
        responseText = response
        sheet.addView(response, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Quick Suggestion Chips Scroll View
        val chipsScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        chipScroll = chipsScroll

        val chipsLayout = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, dp(8))
        }

        val chips = listOf(
            "🔍 Search & Play Song" to "search and play song",
            "👁️ What's on screen?" to "what is on my screen",
            "⏱️ Set 5m Timer" to "set timer for 5 minutes",
            "💡 Turn on Torch" to "turn on flashlight",
            "⚡ Daily Briefing" to "daily briefing",
            "📝 Take a Note" to "remember this note"
        )

        for ((label, command) in chips) {
            val chip = createActionChip(ctx, label, ::dp) {
                executeDirectCommand(command)
            }
            chipsLayout.addView(chip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(8)
            })
        }
        chipsScroll.addView(chipsLayout)
        sheet.addView(chipsScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // Silent Text Input Bar
        val inputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val inputBg = GradientDrawable().apply {
                setColor(Color.parseColor("#2200E5FF"))
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), Color.parseColor("#3300E5FF"))
            }
            background = inputBg
            setPadding(dp(12), dp(2), dp(6), dp(2))
        }

        val editText = EditText(ctx).apply {
            hint = "Ask Jarvis anything..."
            setHintTextColor(Color.parseColor("#60FFFFFF"))
            setTextColor(Color.WHITE)
            textSize = 14f
            background = null
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                    val query = text.toString().trim()
                    if (query.isNotBlank()) {
                        executeDirectCommand(query)
                        text.clear()
                    }
                    true
                } else false
            }
        }
        inputEditText = editText
        inputRow.addView(editText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val sendBtn = ImageView(ctx).apply {
            setImageResource(android.R.drawable.ic_menu_send)
            setColorFilter(Color.parseColor("#00E5FF"))
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener {
                val query = editText.text.toString().trim()
                if (query.isNotBlank()) {
                    executeDirectCommand(query)
                    editText.text.clear()
                }
            }
        }
        inputRow.addView(sendBtn, LinearLayout.LayoutParams(dp(36), dp(36)))

        sheet.addView(inputRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6)
        })

        val sheetParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.BOTTOM
            leftMargin = dp(12)
            rightMargin = dp(12)
            bottomMargin = dp(16)
        }

        root.addView(sheet, sheetParams)
        return root
    }

    private fun createActionChip(ctx: Context, label: String, dp: (Int) -> Int, onClick: () -> Unit): TextView {
        return TextView(ctx).apply {
            text = label
            setTextColor(Color.parseColor("#D0E5FF"))
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(12), dp(8), dp(12), dp(8))

            val chipBg = GradientDrawable().apply {
                setColor(Color.parseColor("#1A00E5FF"))
                cornerRadius = dp(14).toFloat()
                setStroke(dp(1), Color.parseColor("#4400E5FF"))
            }
            background = chipBg

            setOnClickListener { onClick() }
        }
    }

    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        Log.i(TAG, "JarvisVoiceInteractionSession onShow with flags: $showFlags")

        // 1. If SYSTEM_ALERT_WINDOW permission is granted, immediately launch the interactive Floating Orb overlay!
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)) {
            try {
                val overlayIntent = Intent(context, com.jarvis.overlay.JarvisFloatingOverlayService::class.java).apply {
                    action = com.jarvis.overlay.JarvisFloatingOverlayService.ACTION_TALK
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(overlayIntent)
                } else {
                    context.startService(overlayIntent)
                }
                // Seamlessly hide the system session bottom sheet window so the floating orb directly appears
                hide()
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed launching floating overlay on assist show: ${e.message}")
            }
        }

        transcriptText?.text = "Overlay permission required to enable Floating Orb. Tap below to grant."
        responseText?.visibility = View.GONE
        statusBadge?.text = "GRANT OVERLAY"
        orbView?.setState(VoiceState.LISTENING)

        // Ensure Jarvis foreground service is active
        try {
            val serviceIntent = Intent(context, JarvisForegroundService::class.java).apply {
                action = JarvisForegroundService.ACTION_START
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed ensuring foreground service: ${e.message}")
        }

        // Trigger manual listening
        JarvisForegroundService.instance?.voiceEngine?.startManualListening()

        startStateObservation()
    }

    private fun startStateObservation() {
        stateObserverJob?.cancel()
        stateObserverJob = sessionScope.launch {
            val engine = JarvisForegroundService.instance?.voiceEngine ?: return@launch
            engine.status.collect { status ->
                mainHandler.post {
                    orbView?.setState(status.voiceState)
                    orbView?.setAudioLevel(status.audioLevelRms)

                    when (status.voiceState) {
                        VoiceState.LISTENING -> {
                            statusBadge?.text = "LISTENING"
                            if (status.partialTranscript.isNotBlank()) {
                                transcriptText?.text = status.partialTranscript
                            }
                        }
                        VoiceState.THINKING, VoiceState.RESEARCHING -> {
                            statusBadge?.text = "THINKING & PLANNING"
                            if (status.partialTranscript.isNotBlank()) {
                                transcriptText?.text = status.partialTranscript
                            }
                        }
                        VoiceState.EXECUTING -> {
                            statusBadge?.text = "EXECUTING DIRECTIVE"
                        }
                        VoiceState.SPEAKING -> {
                            statusBadge?.text = "SPEAKING"
                            if (status.lastResponse.isNotBlank()) {
                                responseText?.visibility = View.VISIBLE
                                responseText?.text = status.lastResponse
                            }
                        }
                        VoiceState.IDLE -> {
                            statusBadge?.text = "READY"
                            if (status.lastResponse.isNotBlank()) {
                                responseText?.visibility = View.VISIBLE
                                responseText?.text = status.lastResponse
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
    }

    override fun onHandleAssist(data: Bundle?, structure: AssistStructure?, content: AssistContent?) {
        super.onHandleAssist(data, structure, content)
        Log.i(TAG, "onHandleAssist captured structure and content")

        val extracted = AssistStructureExtractor.extract(structure)
        screenContextCache = extracted

        if (extracted.fullContextSummary.isNotBlank()) {
            Log.d(TAG, "Extracted Screen Context: ${extracted.title} (${extracted.packageName})")
            // Provide context to AgentContextRouter / AssistantRuntime
            JarvisForegroundService.instance?.voiceEngine?.assistantRuntime?.augmentedMemoryPipeline?.rememberUserTurn(
                MemoryNamespaces.CONVERSATION,
                "Screen Context: ${extracted.fullContextSummary}"
            )
        }
    }

    override fun onHandleScreenshot(screenshot: Bitmap?) {
        super.onHandleScreenshot(screenshot)
        if (screenshot != null) {
            Log.i(TAG, "onHandleScreenshot received ${screenshot.width}x${screenshot.height} bitmap")
        }
    }

    private fun executeDirectCommand(command: String) {
        transcriptText?.text = command
        statusBadge?.text = "EXECUTING"
        orbView?.setState(VoiceState.THINKING)

        // Dismiss keyboard if open
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        inputEditText?.let { imm?.hideSoftInputFromWindow(it.windowToken, 0) }

        sessionScope.launch(Dispatchers.IO) {
            val runtime = JarvisForegroundService.instance?.voiceEngine?.assistantRuntime
            if (runtime != null) {
                val result = runtime.executeCommand(command, userApprovalGranted = true)
                mainHandler.post {
                    responseText?.visibility = View.VISIBLE
                    responseText?.text = result.spokenResponse
                    statusBadge?.text = if (result.success) "COMPLETED" else "FAILED"
                    orbView?.setState(VoiceState.IDLE)
                }
            } else {
                // Fallback to CommandReceiver broadcast
                val intent = Intent(com.jarvis.service.CommandReceiver.ACTION_EXECUTE_COMMAND).apply {
                    putExtra(com.jarvis.service.CommandReceiver.EXTRA_COMMAND, command)
                }
                context.sendBroadcast(intent)
            }
        }
    }

    override fun onHide() {
        super.onHide()
        stateObserverJob?.cancel()
    }

    override fun onDestroy() {
        super.onDestroy()
        stateObserverJob?.cancel()
        sessionScope.cancel()
    }
}
