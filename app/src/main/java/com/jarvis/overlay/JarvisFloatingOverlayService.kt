package com.jarvis.overlay

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.animation.DecelerateInterpolator
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.*
import androidx.core.app.NotificationCompat
import com.jarvis.R
import com.jarvis.service.JarvisForegroundService
import com.jarvis.ui.JarvisOrbView
import com.jarvis.ui.MainActivity
import com.jarvis.ui.components.Ui
import com.jarvis.voice.VoiceState
import kotlinx.coroutines.*
import kotlin.math.hypot

/**
 * Android Replicant of the JARVIS-PC Floating Orb ("Background Watching").
 *
 * Replicates the always-on-top draggable ambient presence from jarvis-pc,
 * enhanced specifically for Android:
 * - Always-on-top Arc Reactor core with rotating rings and state-driven pulsing glow.
 * - Magnetic edge-snapping and auto-tuck dimming (alpha 0.65) when idle against screen edge.
 * - Single-Tap: Toggles a holographic cybernetic Mini HUD card right over the active app.
 * - Long-Press: Instant Push-to-Talk voice listening with haptic pulse.
 * - Double-Tap: Quick-command inline text box with soft-keyboard integration.
 * - Real-time state synchronization with VoiceEngine and AssistantRuntime.
 */
class JarvisFloatingOverlayService : Service() {

    companion object {
        private const val TAG = "FloatingOverlayService"
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "jarvis_overlay_channel"
        private const val PREFS_NAME = "jarvis_floating_overlay_prefs"
        private const val KEY_POS_X = "overlay_pos_x"
        private const val KEY_POS_Y = "overlay_pos_y"

        const val ACTION_START = "com.jarvis.action.START_OVERLAY"
        const val ACTION_STOP = "com.jarvis.action.STOP_OVERLAY"
        const val ACTION_TALK = "com.jarvis.action.OVERLAY_TALK"

        var isRunning: Boolean = false
            private set

        /**
         * Calculates target X coordinate to magnetically snap the floating orb
         * to the nearest screen edge.
         */
        fun calculateSnapTargetX(currentX: Int, orbWidthPx: Int, screenWidthPx: Int, marginPx: Int = 24): Int {
            val centerX = currentX + orbWidthPx / 2
            return if (centerX < screenWidthPx / 2) {
                marginPx
            } else {
                screenWidthPx - orbWidthPx - marginPx
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var rootContainer: FrameLayout? = null
    private var orbView: JarvisOrbView? = null
    private var expandedPanel: LinearLayout? = null
    private var contextMenu: LinearLayout? = null
    private var statusBadge: TextView? = null
    private var responsePreviewText: TextView? = null
    private var inputContainer: LinearLayout? = null
    private var commandInput: EditText? = null

    private lateinit var windowParams: WindowManager.LayoutParams
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObservationJob: Job? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private var isExpanded = false
    private var isContextMenuOpen = false
    private var isInputOpen = false
    private var isDragging = false

    private val autoTuckRunnable = Runnable {
        if (!isExpanded && !isDragging) {
            rootContainer?.animate()?.alpha(0.65f)?.setDuration(400)?.start()
        }
    }

    private val autoCollapseRunnable = Runnable {
        if (isExpanded && !isInputOpen) {
            collapseToOrb()
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "JarvisFloatingOverlayService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW permission not granted; stopping overlay service")
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        isRunning = true

        if (rootContainer == null) {
            setupFloatingWindow()
        }

        if (intent?.action == ACTION_TALK) {
            handleQuickVoiceTrigger()
        }

        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupFloatingWindow() {
        windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return

        val layoutFlag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }

        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        val orbSizePx = (68 * density).toInt()

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val defaultX = displayMetrics.widthPixels - orbSizePx - (16 * density).toInt()
        val defaultY = (displayMetrics.heightPixels * 0.35f).toInt()
        val initialX = prefs.getInt(KEY_POS_X, defaultX)
        val initialY = prefs.getInt(KEY_POS_Y, defaultY)

        @Suppress("DEPRECATION")
        val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

        windowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            layoutFlag,
            baseFlags,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = initialX
            y = initialY
        }

        val root = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // 1. Arc Reactor Floating Orb View
        val orb = JarvisOrbView(this).apply {
            layoutParams = FrameLayout.LayoutParams(orbSizePx, orbSizePx)
            setState(VoiceState.IDLE)
        }
        root.addView(orb)

        // 2. Holographic Expanded Mini HUD Panel
        val panel = createExpandedPanel(density, orbSizePx)
        panel.visibility = View.GONE
        root.addView(panel)

        // 3. Quick Context Action Menu (PC right-click equivalent)
        val menu = createContextMenu(density, orbSizePx)
        menu.visibility = View.GONE
        root.addView(menu)

        // Touch & Drag Handling on the Orb
        var downRawX = 0f
        var downRawY = 0f
        var downWindowX = 0
        var downWindowY = 0
        var lastTapTime = 0L
        val dragThresholdPx = 10 * density

        val longPressRunnable = Runnable {
            if (!isDragging) {
                triggerHapticFeedback()
                handleLongPress()
            }
        }

        orb.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    resetTuckTimer(wakeNow = true)
                    downRawX = event.rawX
                    downRawY = event.rawY
                    downWindowX = windowParams.x
                    downWindowY = windowParams.y
                    isDragging = false
                    mainHandler.postDelayed(longPressRunnable, 480)
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!isDragging && hypot(dx.toDouble(), dy.toDouble()) > dragThresholdPx) {
                        isDragging = true
                        mainHandler.removeCallbacks(longPressRunnable)
                    }

                    if (isDragging) {
                        windowParams.x = (downWindowX + dx).toInt()
                        windowParams.y = (downWindowY + dy).toInt()
                        windowManager?.updateViewLayout(root, windowParams)
                    }
                    true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    mainHandler.removeCallbacks(longPressRunnable)
                    if (!isDragging) {
                        val now = System.currentTimeMillis()
                        if (now - lastTapTime < 320) {
                            // Double tap -> open quick command input directly
                            handleDoubleTap()
                        } else {
                            // Single tap -> toggle expanded mini HUD
                            handleSingleTap()
                        }
                        lastTapTime = now
                    } else {
                        // Smoothly snap to nearest screen margin
                        snapToNearestEdge(orbSizePx, displayMetrics.widthPixels)
                    }
                    scheduleAutoTuck()
                    true
                }

                else -> false
            }
        }

        try {
            windowManager?.addView(root, windowParams)
            rootContainer = root
            orbView = orb
            expandedPanel = panel
            contextMenu = menu

            observeAssistantState()
            scheduleAutoTuck()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add floating overlay window", e)
        }
    }

    private fun createExpandedPanel(density: Float, orbSizePx: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val bg = GradientDrawable().apply {
                setColor(0xEE0B0F1A.toInt()) // Deep space dark background (#0B0F1A)
                setStroke((1.5f * density).toInt(), 0xFF26344F.toInt()) // Hairline cyan/slate border
                cornerRadius = 18f * density
            }
            background = bg
            setPadding((14 * density).toInt(), (12 * density).toInt(), (14 * density).toInt(), (12 * density).toInt())
            elevation = 16f * density

            val maxWidth = (300 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(maxWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = orbSizePx + (8 * density).toInt()
            }

            // Top Header: Title + Status + Close Button
            val headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)

                val title = TextView(context).apply {
                    text = "⚡ J.A.R.V.I.S."
                    textSize = 12.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF3FD0FF.toInt()) // JARVIS cyan
                    letterSpacing = 0.08f
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                }
                addView(title)

                val badge = TextView(context).apply {
                    text = "ONLINE"
                    textSize = 9.5f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF39E0A0.toInt()) // Arc green
                    background = GradientDrawable().apply {
                        setColor(0x2239E0A0.toInt())
                        cornerRadius = 8f * density
                    }
                    setPadding((8 * density).toInt(), (2 * density).toInt(), (8 * density).toInt(), (2 * density).toInt())
                }
                statusBadge = badge
                addView(badge)

                val closeBtn = TextView(context).apply {
                    text = "✕"
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF8FA3C4.toInt())
                    setPadding((10 * density).toInt(), 0, (4 * density).toInt(), 0)
                    setOnClickListener { collapseToOrb() }
                }
                addView(closeBtn)
            }
            addView(headerRow)

            // Spoken Response / Message Preview
            val preview = TextView(context).apply {
                text = "Ambient presence active. Tap Mic to talk or Type to send commands."
                textSize = 11.5f
                setTextColor(0xFFE8F0FF.toInt())
                setPadding(0, (8 * density).toInt(), 0, (10 * density).toInt())
                maxLines = 4
            }
            responsePreviewText = preview
            addView(preview)

            // Quick Command Text Box (collapsible)
            val inputRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(-1, -2)
                setPadding(0, 0, 0, (8 * density).toInt())

                val edit = EditText(context).apply {
                    hint = "Type command to Jarvis..."
                    setHintTextColor(0xFF8FA3C4.toInt())
                    setTextColor(0xFFFFFFFF.toInt())
                    textSize = 12f
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    background = GradientDrawable().apply {
                        setColor(0xFF172033.toInt())
                        setStroke((1f * density).toInt(), 0xFF3FD0FF.toInt())
                        cornerRadius = 8f * density
                    }
                    setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f)

                    setOnEditorActionListener { _, actionId, _ ->
                        if (actionId == EditorInfo.IME_ACTION_SEND) {
                            sendTypedCommand(text.toString().trim())
                            true
                        } else false
                    }
                }
                commandInput = edit
                addView(edit)

                val sendBtn = TextView(context).apply {
                    text = "➔"
                    textSize = 15f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(0xFF000000.toInt())
                    background = GradientDrawable().apply {
                        setColor(0xFF3FD0FF.toInt())
                        cornerRadius = 8f * density
                    }
                    setPadding((12 * density).toInt(), (6 * density).toInt(), (12 * density).toInt(), (6 * density).toInt())
                    val margin = (6 * density).toInt()
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = margin }
                    setOnClickListener {
                        sendTypedCommand(commandInput?.text?.toString()?.trim().orEmpty())
                    }
                }
                addView(sendBtn)
            }
            inputContainer = inputRow
            addView(inputRow)

            // Action Buttons Row (Mic, Type, Open App)
            val actionRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(-1, -2)

                val micBtn = createActionButton(context, "🎙 TALK", 0xFF3FD0FF.toInt(), density) {
                    handleQuickVoiceTrigger()
                }
                addView(micBtn)

                val typeBtn = createActionButton(context, "⌨ TYPE", 0xFFE8F0FF.toInt(), density) {
                    toggleTextInput()
                }
                addView(typeBtn)

                val openBtn = createActionButton(context, "📱 OPEN", 0xFFE8F0FF.toInt(), density) {
                    openFullApp()
                }
                addView(openBtn)
            }
            addView(actionRow)
        }
    }

    private fun createActionButton(context: Context, text: String, textColor: Int, density: Float, onClick: () -> Unit): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            letterSpacing = 0.04f
            gravity = Gravity.CENTER
            setTextColor(textColor)
            background = GradientDrawable().apply {
                setColor(0xFF172033.toInt())
                setStroke((1f * density).toInt(), 0xFF26344F.toInt())
                cornerRadius = 8f * density
            }
            setPadding((10 * density).toInt(), (6 * density).toInt(), (10 * density).toInt(), (6 * density).toInt())
            val margin = (4 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
                leftMargin = margin
                rightMargin = margin
            }
            setOnClickListener { onClick() }
        }
    }

    private fun handleSingleTap() {
        if (isContextMenuOpen) {
            hideContextMenu()
            return
        }
        if (isExpanded) {
            collapseToOrb()
        } else {
            expandToMiniHud()
        }
    }

    private fun handleDoubleTap() {
        if (isContextMenuOpen) {
            hideContextMenu()
        }
        if (!isExpanded) {
            expandToMiniHud()
        }
        toggleTextInput(forceOpen = true)
    }

    private fun handleLongPress() {
        Log.i(TAG, "Floating Orb Long-Press: Displaying Holographic Context Menu")
        showContextMenu()
    }

    private fun createContextMenu(density: Float, orbSizePx: Int): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(0xFA0B0F1A.toInt()) // High-opacity deep space #0B0F1A
                setStroke((1.5f * density).toInt(), 0xFF26344F.toInt())
                cornerRadius = 14f * density
            }
            setPadding((12 * density).toInt(), (10 * density).toInt(), (12 * density).toInt(), (10 * density).toInt())
            elevation = 20f * density

            val minWidth = (165 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(minWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = orbSizePx + (6 * density).toInt()
            }

            // Menu Header
            addView(TextView(context).apply {
                text = "⚡ JARVIS ACTIONS"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(0xFF3FD0FF.toInt())
                letterSpacing = 0.08f
                setPadding(0, 0, 0, (6 * density).toInt())
            })

            // 1. Open Full App
            addView(createContextMenuItem("📱 Open Full App", density) {
                hideContextMenu()
                openFullApp()
            })

            // 2. Voice Command
            addView(createContextMenuItem("🎙️ Talk / Voice", density) {
                hideContextMenu()
                handleQuickVoiceTrigger()
            })

            // 3. Type Command
            addView(createContextMenuItem("⌨️ Type Command", density) {
                hideContextMenu()
                if (!isExpanded) expandToMiniHud()
                toggleTextInput(forceOpen = true)
            })

            // Separator
            addView(View(context).apply {
                layoutParams = LinearLayout.LayoutParams(-1, (1f * density).toInt()).apply {
                    topMargin = (4 * density).toInt()
                    bottomMargin = (4 * density).toInt()
                }
                setBackgroundColor(0xFF26344F.toInt())
            })

            // 4. Close Floating Orb
            addView(createContextMenuItem("✕ Close Floating Orb", density, textColor = 0xFFFF5C7A.toInt()) {
                hideContextMenu()
                stopSelf()
            })
        }
    }

    private fun createContextMenuItem(title: String, density: Float, textColor: Int = 0xFFE8F0FF.toInt(), onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = title
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(textColor)
            setPadding((6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt())
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
        }
    }

    private fun showContextMenu() {
        if (isExpanded) collapseToOrb()
        isContextMenuOpen = true
        resetTuckTimer(wakeNow = true)
        contextMenu?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.88f
            scaleY = 0.88f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(180)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        mainHandler.postDelayed({ hideContextMenu() }, 6000)
    }

    private fun hideContextMenu() {
        if (!isContextMenuOpen) return
        isContextMenuOpen = false
        contextMenu?.animate()
            ?.alpha(0f)
            ?.scaleX(0.88f)
            ?.scaleY(0.88f)
            ?.setDuration(150)
            ?.withEndAction {
                contextMenu?.visibility = View.GONE
            }
            ?.start()
        scheduleAutoTuck()
    }

    private fun expandToMiniHud() {
        if (isContextMenuOpen) hideContextMenu()
        isExpanded = true
        resetTuckTimer(wakeNow = true)
        expandedPanel?.apply {
            visibility = View.VISIBLE
            alpha = 0f
            scaleX = 0.92f
            scaleY = 0.92f
            animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(220)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
        scheduleAutoCollapse()
    }

    private fun collapseToOrb() {
        isExpanded = false
        if (isInputOpen) {
            toggleTextInput(forceOpen = false)
        }
        mainHandler.removeCallbacks(autoCollapseRunnable)

        expandedPanel?.animate()
            ?.alpha(0f)
            ?.scaleX(0.92f)
            ?.scaleY(0.92f)
            ?.setDuration(180)
            ?.withEndAction {
                expandedPanel?.visibility = View.GONE
            }
            ?.start()

        scheduleAutoTuck()
    }

    private fun toggleTextInput(forceOpen: Boolean? = null) {
        val nextState = forceOpen ?: !isInputOpen
        isInputOpen = nextState
        inputContainer?.visibility = if (nextState) View.VISIBLE else View.GONE

        // Toggle window focusability so Android displays soft keyboard
        if (nextState) {
            windowParams.flags = windowParams.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            windowManager?.updateViewLayout(rootContainer, windowParams)
            commandInput?.requestFocus()
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.showSoftInput(commandInput, InputMethodManager.SHOW_IMPLICIT)
            mainHandler.removeCallbacks(autoCollapseRunnable)
        } else {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.hideSoftInputFromWindow(commandInput?.windowToken, 0)
            windowParams.flags = windowParams.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            windowManager?.updateViewLayout(rootContainer, windowParams)
            commandInput?.clearFocus()
            scheduleAutoCollapse()
        }
    }

    private fun sendTypedCommand(command: String) {
        if (command.isBlank()) return
        commandInput?.setText("")
        toggleTextInput(forceOpen = false)

        responsePreviewText?.text = "Thinking: \"$command\"..."
        statusBadge?.text = "THINKING"
        statusBadge?.setTextColor(0xFFFFCF5C.toInt())
        orbView?.setState(VoiceState.THINKING)

        serviceScope.launch(Dispatchers.IO) {
            val service = JarvisForegroundService.instance
            val runtime = service?.voiceEngine?.assistantRuntime
            if (runtime != null) {
                val result = runtime.executeCommand(command)
                withContext(Dispatchers.Main) {
                    val resp = if (result.spokenResponse.isNotBlank()) result.spokenResponse else "Command completed."
                    responsePreviewText?.text = resp
                    statusBadge?.text = if (result.success) "SUCCESS" else "FAILED"
                    statusBadge?.setTextColor(if (result.success) 0xFF39E0A0.toInt() else 0xFFFF5C7A.toInt())
                    orbView?.setState(if (result.success) VoiceState.COMPLETED else VoiceState.ERROR)
                    service.voiceEngine?.speakProactiveAnnouncement(resp)
                    scheduleAutoCollapse()
                }
            } else {
                withContext(Dispatchers.Main) {
                    responsePreviewText?.text = "Assistant runtime offline. Please start Jarvis service."
                    statusBadge?.text = "OFFLINE"
                    statusBadge?.setTextColor(0xFFFF5C7A.toInt())
                    orbView?.setState(VoiceState.ERROR)
                }
            }
        }
    }

    private fun handleQuickVoiceTrigger() {
        val service = JarvisForegroundService.instance
        val engine = service?.voiceEngine
        if (engine != null) {
            engine.triggerManualListening()
            statusBadge?.text = "LISTENING"
            statusBadge?.setTextColor(0xFF3FD0FF.toInt())
            orbView?.setState(VoiceState.LISTENING)
            if (!isExpanded) expandToMiniHud()
            scheduleAutoCollapse()
        } else {
            val serviceIntent = Intent(this, JarvisForegroundService::class.java).apply {
                action = JarvisForegroundService.ACTION_START
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            serviceScope.launch {
                repeat(10) {
                    delay(150)
                    val retryEngine = JarvisForegroundService.instance?.voiceEngine
                    if (retryEngine != null) {
                        retryEngine.triggerManualListening()
                        statusBadge?.text = "LISTENING"
                        statusBadge?.setTextColor(0xFF3FD0FF.toInt())
                        orbView?.setState(VoiceState.LISTENING)
                        if (!isExpanded) expandToMiniHud()
                        return@launch
                    }
                }
                openFullApp()
            }
        }
    }

    private fun openFullApp() {
        collapseToOrb()
        val mainIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        startActivity(mainIntent)
    }

    private fun snapToNearestEdge(orbSizePx: Int, screenWidthPx: Int) {
        val targetX = calculateSnapTargetX(windowParams.x, orbSizePx, screenWidthPx)
        val startX = windowParams.x

        ValueAnimator.ofInt(startX, targetX)?.apply {
            duration = 240
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                windowParams.x = it.animatedValue as Int
                windowManager?.updateViewLayout(rootContainer, windowParams)
            }
            start()
        }

        // Persist coordinates
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_POS_X, targetX).putInt(KEY_POS_Y, windowParams.y).apply()
    }

    private fun resetTuckTimer(wakeNow: Boolean) {
        mainHandler.removeCallbacks(autoTuckRunnable)
        if (wakeNow) {
            rootContainer?.alpha = 1.0f
        }
    }

    private fun scheduleAutoTuck() {
        mainHandler.removeCallbacks(autoTuckRunnable)
        mainHandler.postDelayed(autoTuckRunnable, 3500)
    }

    private fun scheduleAutoCollapse() {
        mainHandler.removeCallbacks(autoCollapseRunnable)
        mainHandler.postDelayed(autoCollapseRunnable, 6000)
    }

    private fun triggerHapticFeedback() {
        triggerHapticLongPress()
    }

    private fun triggerHapticClick() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    private fun triggerHapticLongPress() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(45, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(45)
            }
        } catch (_: Exception) {}
    }

    private fun observeAssistantState() {
        stateObservationJob?.cancel()
        stateObservationJob = serviceScope.launch {
            while (isActive) {
                val service = JarvisForegroundService.instance
                val engine = service?.voiceEngine
                if (engine == null) {
                    delay(250)
                    continue
                }

                try {
                    engine.status.collect { engineStatus ->
                        val state = engineStatus.voiceState
                        val level = engineStatus.audioLevelRms

                        orbView?.setState(state)
                        orbView?.setAudioLevel(level)

                        // Auto-expand mini HUD if assistant starts listening or wake is detected
                        if (state == VoiceState.LISTENING || state == VoiceState.WAKE_DETECTED) {
                            if (!isExpanded && !isDragging) {
                                expandToMiniHud()
                            }
                        }

                        if (isExpanded) {
                            val label = when (state) {
                                VoiceState.IDLE -> "ONLINE • IDLE"
                                VoiceState.LISTENING -> "LISTENING..."
                                VoiceState.WAKE_DETECTED -> "WAKE DETECTED"
                                VoiceState.THINKING -> "THINKING..."
                                VoiceState.SPEAKING -> "SPEAKING..."
                                VoiceState.CHECKING_MEMORY -> "CHECKING MEMORY..."
                                VoiceState.EXECUTING -> "EXECUTING..."
                                VoiceState.VERIFYING -> "VERIFYING..."
                                VoiceState.RECOVERING -> "RECOVERING..."
                                VoiceState.WAITING_FOR_USER -> "WAITING FOR INPUT"
                                VoiceState.COMPLETED -> "TASK COMPLETED"
                                VoiceState.ERROR -> "ERROR"
                                else -> state.name
                            }
                            statusBadge?.text = label

                            // Live streaming partial transcript or status update
                            if (state == VoiceState.LISTENING && engineStatus.partialTranscript.isNotBlank()) {
                                responsePreviewText?.text = engineStatus.partialTranscript
                            } else {
                                val msg = engineStatus.statusMessage
                                if (msg.isNotBlank() && msg != "Ready") {
                                    responsePreviewText?.text = msg
                                }
                            }

                            if (state == VoiceState.SPEAKING && engineStatus.lastResponse.isNotBlank()) {
                                responsePreviewText?.text = engineStatus.lastResponse
                            }
                        }

                        if (state == VoiceState.IDLE || state == VoiceState.WAITING_FOR_WAKE) {
                            scheduleAutoCollapse()
                        }
                    }
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.d(TAG, "StateFlow collection exception: ${e.message}")
                    delay(300)
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "J.A.R.V.I.S. Floating Overlay",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the J.A.R.V.I.S. Multitasking Hologram active."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val talkIntent = Intent(this, JarvisFloatingOverlayService::class.java).apply { action = ACTION_TALK }
        val talkPending = PendingIntent.getService(
            this,
            1,
            talkIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val closeIntent = Intent(this, JarvisFloatingOverlayService::class.java).apply { action = ACTION_STOP }
        val closePending = PendingIntent.getService(
            this,
            2,
            closeIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("J.A.R.V.I.S. Ambient Floating Presence")
            .setContentText("Tap overlay bubble anytime to speak or control")
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(pendingIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Talk", talkPending)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Close", closePending)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val metrics = resources.displayMetrics
        val density = metrics.density
        val orbSizePx = (68 * density).toInt()
        snapToNearestEdge(orbSizePx, metrics.widthPixels)
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(autoTuckRunnable)
        mainHandler.removeCallbacks(autoCollapseRunnable)
        stateObservationJob?.cancel()
        serviceScope.cancel()

        if (rootContainer != null) {
            try {
                windowManager?.removeView(rootContainer)
            } catch (e: Exception) {
                Log.w(TAG, "Error removing overlay view", e)
            }
            rootContainer = null
            orbView = null
            expandedPanel = null
            contextMenu = null
        }
        super.onDestroy()
        Log.i(TAG, "JarvisFloatingOverlayService destroyed")
    }
}
