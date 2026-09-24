package com.jarvis.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.jarvis.service.JarvisForegroundService
import com.jarvis.ui.JarvisOrbView
import com.jarvis.ui.JarvisWaveformView
import com.jarvis.ui.components.Ui
import com.jarvis.ui.model.AppSettings
import com.jarvis.ui.model.safeDisplay
import com.jarvis.ui.reliability.BackgroundTaskRunner
import com.jarvis.ui.reliability.CorrelationLogger
import com.jarvis.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Rebuilt J.A.R.V.I.S. Dashboard Screen conforming to Google Stitch & Material 3.
 * Features:
 * - Clean Top HUD with single dynamic status pill & settings shortcut
 * - Interactive Arc Reactor hero with tap-to-talk & live waveform
 * - 5 high-impact quick action chips
 * - Context-aware active task card (hidden when idle)
 * - Live conversational exchange
 * - Unified Smart Command Bar (input + dynamic 🎙 / ➤ / ⏹ button)
 */
class DashboardScreen(
    context: Context,
    private val owner: LifecycleOwner,
    private val runner: BackgroundTaskRunner,
    private val settings: () -> AppSettings,
    private val onOpenSettings: () -> Unit,
    private val logger: CorrelationLogger,
    private val onOpenSkills: (() -> Unit)? = null,
    private val onOpenMemory: (() -> Unit)? = null
) : ScrollView(context) {

    private val status = TextView(context)
    private val transcript = TextView(context)
    private val response = TextView(context)
    private val activityDetail = TextView(context)
    private val protocolCard: View
    private val protocolStep1 = TextView(context)
    private val protocolStep2 = TextView(context)
    private val protocolStep3 = TextView(context)
    private val protocolBadge = Ui.hudBadge(context, "STANDBY", Ui.CYAN)
    private val input = EditText(context)
    private val smartActionBtn = TextView(context)
    private val orb = JarvisOrbView(context)
    private val waveform = JarvisWaveformView(context)
    private val serviceBadge = Ui.hudBadge(context, "● READY", Ui.CYAN)

    private var currentVoiceState: VoiceState = VoiceState.WAITING_FOR_WAKE
    private var commandJob: Job? = null
    private var statusJob: Job? = null
    private var boundService: JarvisForegroundService? = null

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 12), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        addView(root)

        // 1. Top HUD Bar (Title + Dynamic Status Pill + Settings)
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(context, 8)
            }
        }

        val brand = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(TextView(context).apply {
                text = "J.A.R.V.I.S."
                textSize = 21f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.CYAN)
                letterSpacing = 0.16f
            })
            addView(TextView(context).apply {
                text = "INTELLIGENT VOICE SYSTEM"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.MUTED)
                letterSpacing = 0.12f
            })
        }
        header.addView(brand)

        serviceBadge.apply {
            setOnClickListener {
                val state = boundService?.voiceEngine?.status?.value?.voiceState
                if (state == VoiceState.IDLE || boundService == null) {
                    startEngine()
                    Toast.makeText(context, "Starting Jarvis service…", Toast.LENGTH_SHORT).show()
                } else {
                    stopEngine()
                    Toast.makeText(context, "Jarvis service halted", Toast.LENGTH_SHORT).show()
                }
            }
        }
        header.addView(serviceBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val settingsButton = TextView(context).apply {
            text = "⚙"
            textSize = 18f
            gravity = Gravity.CENTER
            setTextColor(Ui.CYAN)
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER_GLOW, 8)
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 34), Ui.dp(context, 34)).apply {
                marginStart = Ui.dp(context, 8)
            }
            setOnClickListener { onOpenSettings() }
            contentDescription = "Open settings"
        }
        header.addView(settingsButton)
        root.addView(header)
        root.addView(Ui.divider(context))

        // 2. Holographic Hero Section (Arc Reactor Orb + Waveform + Tap-to-Talk)
        val hero = Ui.glassCard(context, Ui.CYAN_DIM)
        val heroInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14))
        }

        val orbSize = Ui.dp(context, 190)
        orb.layoutParams = FrameLayout.LayoutParams(orbSize, orbSize).apply { gravity = Gravity.CENTER }
        val orbFrame = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 195))
            background = Ui.rounded(context, 0x0D63EFFF.toInt(), Ui.BORDER, 999)
            isClickable = true
            isFocusable = true
            setOnClickListener { triggerListen() }
        }
        orbFrame.addView(orb)
        heroInner.addView(orbFrame)

        waveform.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 44)).apply {
            topMargin = Ui.dp(context, 6)
            bottomMargin = Ui.dp(context, 4)
        }
        heroInner.addView(waveform)

        status.apply {
            text = "LISTENING FOR ${settings().wakeWord.uppercase()}…"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setTextColor(Ui.CYAN)
        }
        heroInner.addView(status)

        activityDetail.apply {
            text = "Tap Arc Reactor or say \"${settings().wakeWord}\" to speak"
            textSize = 10.5f
            gravity = Gravity.CENTER
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, 0)
        }
        heroInner.addView(activityDetail)

        hero.addView(heroInner)
        root.addView(hero)

        // 3. Quick Action Tech Chips (Curated 5 High-Impact Prompts)
        val chipsScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = Ui.dp(context, 10)
                bottomMargin = Ui.dp(context, 12)
            }
        }
        val chipsRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

        chipsRow.addView(Ui.techChip(context, "Floating Orb", "🔮") {
            toggleFloatingOverlay(context)
        })
        chipsRow.addView(Ui.techChip(context, "Play Music", "▶") {
            input.setText("play music")
            sendCommand()
        })
        chipsRow.addView(Ui.techChip(context, "WhatsApp", "💬") {
            input.setText("send whatsapp message to ")
            input.setSelection(input.text.length)
            input.requestFocus()
        })
        chipsRow.addView(Ui.techChip(context, "Search", "🔍") {
            input.setText("search ")
            input.setSelection(input.text.length)
            input.requestFocus()
        })
        chipsRow.addView(Ui.techChip(context, "Driving", "🚗") {
            input.setText("driving mode on")
            sendCommand()
        })
        chipsRow.addView(Ui.techChip(context, "Meeting", "📅") {
            input.setText("meeting mode on")
            sendCommand()
        })
        chipsRow.addView(Ui.techChip(context, "Focus", "🧠") {
            input.setText("focus mode on")
            sendCommand()
        })
        chipsRow.addView(Ui.techChip(context, "Night", "🌙") {
            input.setText("night mode on")
            sendCommand()
        })
        chipsRow.addView(Ui.techChip(context, "Workout", "🏋️") {
            input.setText("workout mode on")
            sendCommand()
        })
        chipsRow.addView(Ui.techChip(context, "Skills Hub", "⚡") {
            onOpenSkills?.invoke()
        })

        chipsScroll.addView(chipsRow)
        root.addView(chipsScroll)

        // 4. Context-Aware Active Protocol / Task Card (Hidden by default when idle)
        protocolCard = Ui.glassCard(context, Ui.BORDER_GLOW).apply {
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(context, 12)
            }
        }
        val protocolInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        val protocolHeader = Ui.row(context)
        val protocolTitle = TextView(context).apply {
            text = "⚡ ACTIVE PROTOCOL"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.10f
            setTextColor(Ui.CYAN)
        }
        protocolHeader.addView(protocolTitle, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        protocolHeader.addView(protocolBadge, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        val cancelTaskBtn = TextView(context).apply {
            text = "CANCEL"
            textSize = 10f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.ERROR)
            background = Ui.rounded(context, Ui.SURFACE_2, 0xFF6E3038.toInt(), 6)
            setPadding(Ui.dp(context, 10), Ui.dp(context, 4), Ui.dp(context, 10), Ui.dp(context, 4))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = Ui.dp(context, 8)
            }
            setOnClickListener { cancelActiveTask() }
            contentDescription = "Cancel active task"
        }
        protocolHeader.addView(cancelTaskBtn)
        protocolInner.addView(protocolHeader)

        protocolStep1.apply {
            text = "• Analyzing voice context & intent"
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 2))
        }
        protocolStep2.apply {
            text = "• Executing autonomous skills…"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 2))
        }
        protocolStep3.apply {
            text = "• Verifying outcome & assertions"
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 4))
        }
        protocolInner.addView(protocolStep1)
        protocolInner.addView(protocolStep2)
        protocolInner.addView(protocolStep3)
        protocolCard.addView(protocolInner)
        root.addView(protocolCard)

        // 5. Live Glassmorphic Exchange (Clean Chat Bubbles)
        val exchange = Ui.glassCard(context)
        val ex = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        ex.addView(Ui.sectionLabel(context, "Live Exchange"))

        // User Chat Bubble
        val userBubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, 0x1A63EFFF.toInt(), Ui.CYAN_DIM, 10)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = Ui.dp(context, 8)
            }
        }
        transcript.apply {
            text = "You  •  —"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        }
        userBubble.addView(transcript)
        ex.addView(userBubble)

        // Jarvis Chat Bubble
        val jarvisBubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 10)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 8), Ui.dp(context, 12), Ui.dp(context, 8))
        }
        response.apply {
            text = "JARVIS  •  How can I assist you today, sir?"
            textSize = 12.5f
            setTextColor(Ui.TEXT)
            setLineSpacing(0f, 1.18f)
        }
        jarvisBubble.addView(response)
        ex.addView(jarvisBubble)

        exchange.addView(ex)
        root.addView(exchange)

        // 6. Unified Smart Command Bar (Pill Container with Dynamic Button)
        root.addView(Ui.sectionLabel(context, "Command Input").apply {
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 4))
        })

        val composer = Ui.glassCard(context, Ui.BORDER_GLOW)
        val composerInner = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(context, Ui.BG, Ui.CYAN_DIM, 24)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 4), Ui.dp(context, 6), Ui.dp(context, 4))
        }

        input.apply {
            hint = "Ask Jarvis anything…"
            textSize = 13.5f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_SEND
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            layoutParams = LinearLayout.LayoutParams(0, Ui.dp(context, 46), 1f)
        }
        composerInner.addView(input)

        smartActionBtn.apply {
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(Ui.dp(context, 42), Ui.dp(context, 42))
        }
        updateSmartActionButton()

        smartActionBtn.setOnClickListener {
            val isTaskActive = currentVoiceState in setOf(
                VoiceState.THINKING, VoiceState.EXECUTING, VoiceState.VERIFYING, VoiceState.RESEARCHING
            )
            val hasText = !input.text.isNullOrBlank()

            if (isTaskActive) {
                cancelActiveTask()
            } else if (hasText) {
                sendCommand()
            } else {
                triggerListen()
            }
        }

        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updateSmartActionButton()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                sendCommand()
                true
            } else {
                false
            }
        }

        composerInner.addView(smartActionBtn)
        composer.addView(composerInner)
        root.addView(composer)

        orb.setState(VoiceState.WAITING_FOR_WAKE)
        waveform.setState(VoiceState.WAITING_FOR_WAKE)
    }

    private fun updateSmartActionButton() {
        val isTaskActive = currentVoiceState in setOf(
            VoiceState.THINKING, VoiceState.EXECUTING, VoiceState.VERIFYING, VoiceState.RESEARCHING
        )
        val hasText = !input.text.isNullOrBlank()

        if (isTaskActive) {
            smartActionBtn.text = "⏹"
            smartActionBtn.setTextColor(Ui.ERROR)
            smartActionBtn.background = Ui.rounded(context, 0x33FF5555.toInt(), Ui.ERROR, 21)
            smartActionBtn.contentDescription = "Stop active task"
        } else if (hasText) {
            smartActionBtn.text = "➤"
            smartActionBtn.setTextColor(Color.BLACK)
            smartActionBtn.background = Ui.rounded(context, Ui.CYAN, Ui.CYAN, 21)
            smartActionBtn.contentDescription = "Send command"
        } else {
            smartActionBtn.text = "🎙"
            smartActionBtn.setTextColor(Ui.CYAN)
            smartActionBtn.background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN, 21)
            smartActionBtn.contentDescription = "Voice input"
        }
    }

    private fun triggerListen() {
        val engine = boundService?.voiceEngine
        val state = engine?.status?.value?.voiceState
        if (state == VoiceState.SPEAKING || state == VoiceState.THINKING || state == VoiceState.RESEARCHING || state == VoiceState.EXECUTING) {
            if (!engine.bargeIn()) {
                engine.triggerManualListening()
            }
        } else {
            if (boundService == null || state == VoiceState.IDLE || state == null) {
                startEngine()
            }
            engine?.triggerManualListening()
        }
    }

    private fun sendCommand() {
        val command = input.text?.toString()?.trim().orEmpty()
        if (command.isBlank()) return
        input.text?.clear()
        updateSmartActionButton()

        transcript.text = "You  •  ${command.safeDisplay()}"
        response.text = "JARVIS  •  Processing command…"
        status.text = "THINKING…"
        protocolStep1.text = "✓ Analyzed request: \"${command.take(24)}\""
        protocolStep1.setTextColor(Ui.SUCCESS)
        protocolStep2.text = "• Executing autonomous skills…"
        protocolStep2.setTextColor(Ui.CYAN)
        protocolBadge.apply {
            text = "● PLANNING"
            setTextColor(Ui.CYAN)
        }
        protocolCard.visibility = View.VISIBLE

        orb.setState(VoiceState.THINKING)
        waveform.setState(VoiceState.THINKING)

        commandJob?.cancel()
        commandJob = owner.lifecycleScope.launch {
            try {
                val engine = boundService?.voiceEngine
                if (engine == null) {
                    response.text = "JARVIS  •  Voice service is unavailable."
                    status.text = "SERVICE OFFLINE"
                    serviceBadge.apply {
                        text = "● OFFLINE"
                        setTextColor(Ui.ERROR)
                    }
                    orb.setState(VoiceState.ERROR)
                    waveform.setState(VoiceState.ERROR)
                    protocolCard.visibility = View.GONE
                    return@launch
                }
                engine.processTextCommand(command)
            } catch (t: Throwable) {
                val id = logger.error("Dashboard", "Command failed", t)
                response.text = "JARVIS  •  Failed. Reference ${id.take(8)}."
                status.text = "ERROR"
                orb.setState(VoiceState.ERROR)
                waveform.setState(VoiceState.ERROR)
            }
        }
    }

    private fun cancelActiveTask() {
        boundService?.voiceEngine?.assistantRuntime?.cancelCurrentTask()
        status.text = "TASK CANCELLED"
        activityDetail.text = "Current execution cancelled by user"
        protocolStep2.text = "• Execution cancelled by user"
        protocolStep2.setTextColor(Ui.ERROR)
        protocolBadge.apply {
            text = "● CANCELLED"
            setTextColor(Ui.ERROR)
        }
        orb.setState(VoiceState.CANCELLED)
        waveform.setState(VoiceState.CANCELLED)
        currentVoiceState = VoiceState.CANCELLED
        updateSmartActionButton()
    }

    private fun startEngine() {
        JarvisForegroundService.startService(context)
        boundService?.startEngine()
        status.text = "STARTING ENGINE…"
        serviceBadge.apply {
            text = "● READY"
            setTextColor(Ui.SUCCESS)
        }
        activityDetail.text = "Service starting • Listening for wake word"
        orb.setState(VoiceState.WAITING_FOR_WAKE)
        waveform.setState(VoiceState.WAITING_FOR_WAKE)
        currentVoiceState = VoiceState.WAITING_FOR_WAKE
        updateSmartActionButton()
    }

    private fun stopEngine() {
        runner.ioTask { JarvisForegroundService.stopService(context) }
        status.text = "ENGINE HALTED"
        serviceBadge.apply {
            text = "● HALTED"
            setTextColor(Ui.ERROR)
        }
        activityDetail.text = "Service halted • Tap status badge or Arc Reactor to resume"
        orb.setState(VoiceState.IDLE)
        waveform.setState(VoiceState.IDLE)
        currentVoiceState = VoiceState.IDLE
        protocolCard.visibility = View.GONE
        updateSmartActionButton()
    }

    fun bindService(service: JarvisForegroundService) {
        boundService = service
        serviceBadge.apply {
            text = "● READY"
            setTextColor(Ui.SUCCESS)
        }
        service.voiceEngine?.let { engine ->
            statusJob?.cancel()
            statusJob = owner.lifecycleScope.launch {
                engine.status.collect { s ->
                    currentVoiceState = s.voiceState
                    updateSmartActionButton()

                    if (s.voiceState == VoiceState.IDLE) {
                        serviceBadge.apply {
                            text = "● HALTED"
                            setTextColor(Ui.ERROR)
                        }
                    } else {
                        serviceBadge.apply {
                            text = "● READY"
                            setTextColor(Ui.SUCCESS)
                        }
                    }

                    status.text = when (s.voiceState) {
                        VoiceState.WAITING_FOR_WAKE -> if (s.wakeModelAvailable) "LISTENING FOR ${settings().wakeWord.uppercase()}…" else "WAKE MODEL UNAVAILABLE • TAP TO TALK"
                        VoiceState.WAKE_DETECTED -> "WAKE WORD DETECTED"
                        VoiceState.LISTENING -> "LISTENING…"
                        VoiceState.THINKING -> "THINKING…"
                        VoiceState.EXECUTING -> "EXECUTING…"
                        VoiceState.VERIFYING -> "VERIFYING…"
                        VoiceState.RESEARCHING -> "RESEARCHING…"
                        VoiceState.SPEAKING -> "SPEAKING…"
                        VoiceState.ERROR -> "ERROR"
                        else -> s.statusMessage.ifBlank { "READY" }.uppercase()
                    }

                    activityDetail.text = when (s.voiceState) {
                        VoiceState.WAITING_FOR_WAKE -> "Tap Arc Reactor or say \"${settings().wakeWord}\" to speak"
                        VoiceState.LISTENING -> "Listening… Speak now"
                        VoiceState.THINKING -> "Analyzing your request…"
                        VoiceState.EXECUTING -> "Executing task: ${s.lastTaskGoal?.take(28) ?: "Device automation"}"
                        VoiceState.VERIFYING -> "Verifying outcome…"
                        VoiceState.SPEAKING -> "Speaking response • Tap Arc Reactor to interrupt"
                        VoiceState.IDLE -> "Service halted • Tap status badge to resume"
                        VoiceState.ERROR -> "An error occurred. Check logs."
                        else -> s.statusMessage.ifBlank { "Ready for commands" }
                    }

                    val isTaskActive = s.voiceState in setOf(
                        VoiceState.THINKING, VoiceState.EXECUTING, VoiceState.VERIFYING, VoiceState.RESEARCHING, VoiceState.WAITING_FOR_USER
                    ) || (!s.lastTaskGoal.isNullOrBlank() && s.voiceState != VoiceState.WAITING_FOR_WAKE && s.voiceState != VoiceState.IDLE)

                    protocolCard.visibility = if (isTaskActive) View.VISIBLE else View.GONE

                    if (isTaskActive) {
                        val badgeLabel = when (s.voiceState) {
                            VoiceState.THINKING -> "PLANNING"
                            VoiceState.EXECUTING -> "EXECUTING"
                            VoiceState.VERIFYING -> "VERIFYING"
                            VoiceState.RESEARCHING -> "RESEARCHING"
                            VoiceState.WAITING_FOR_USER -> "AWAITING INPUT"
                            else -> "ACTIVE"
                        }
                        protocolBadge.apply {
                            text = "● $badgeLabel"
                            setTextColor(if (s.voiceState == VoiceState.ERROR) Ui.ERROR else Ui.CYAN)
                        }

                        if (!s.lastTaskGoal.isNullOrBlank()) {
                            protocolStep1.text = "✓ Analyzed context: \"${s.lastTaskGoal.take(24)}\""
                            protocolStep1.setTextColor(Ui.SUCCESS)
                        }
                        if (s.voiceState == VoiceState.EXECUTING) {
                            protocolStep2.text = "✓ Executing: ${s.lastTaskGoal?.take(30) ?: "Device automation"}"
                            protocolStep2.setTextColor(Ui.CYAN)
                        } else if (s.voiceState == VoiceState.VERIFYING) {
                            protocolStep3.text = "✓ Verifying outcome honesty & assertions"
                            protocolStep3.setTextColor(Ui.SUCCESS)
                        }
                    }

                    if (s.partialTranscript.isNotBlank()) {
                        transcript.text = "You  •  ${s.partialTranscript.safeDisplay()}"
                    }
                    if (s.lastResponse.isNotBlank()) {
                        response.text = "JARVIS  •  ${s.lastResponse.safeDisplay()}"
                    }

                    orb.setState(s.voiceState)
                    waveform.setState(s.voiceState)

                    if (s.voiceState == VoiceState.LISTENING) {
                        val level = if (s.audioLevelRms > 0.05f) s.audioLevelRms else 0.25f
                        orb.setAudioLevel(level)
                        waveform.setAudioLevel(level)
                    } else {
                        orb.setAudioLevel(0.5f)
                        waveform.setAudioLevel(0.5f)
                    }
                }
            }
        }
    }

    private fun toggleFloatingOverlay(context: Context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
            !android.provider.Settings.canDrawOverlays(context)
        ) {
            Toast.makeText(context, "Please grant Overlay Permission for J.A.R.V.I.S.", Toast.LENGTH_LONG).show()
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:${context.packageName}")
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            context.startActivity(intent)
            return
        }

        if (com.jarvis.overlay.JarvisFloatingOverlayService.isRunning) {
            val intent = android.content.Intent(context, com.jarvis.overlay.JarvisFloatingOverlayService::class.java).apply {
                action = com.jarvis.overlay.JarvisFloatingOverlayService.ACTION_STOP
            }
            context.stopService(intent)
            Toast.makeText(context, "Floating Orb stopped", Toast.LENGTH_SHORT).show()
        } else {
            val intent = android.content.Intent(context, com.jarvis.overlay.JarvisFloatingOverlayService::class.java)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Toast.makeText(context, "Floating Orb activated", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onDetachedFromWindow() {
        commandJob?.cancel()
        statusJob?.cancel()
        boundService = null
        super.onDetachedFromWindow()
    }
}
