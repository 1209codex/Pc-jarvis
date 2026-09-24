package com.jarvis.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.voice.VoiceState
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

class JarvisOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var currentScale = 1.0f
    private var targetScale = 1.0f
    private var rotationAngle = 0f
    private var counterRotationAngle = 0f
    private var pulsePhase = 0f

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val tickPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private var pulseAnimator: ValueAnimator? = null
    private var rotationAnimator: ValueAnimator? = null
    private var currentState = VoiceState.IDLE
    private var audioLevel = 0.5f
    private var animationIntensity = 1.0f

    // 24 ambient orbital particles
    private val particles = List(24) { i ->
        val angle = (i * 15) * Math.PI.toFloat() / 180f
        floatArrayOf(angle, 1.1f + (i % 5) * 0.08f, 2f + (i % 3) * 1.5f)
    }

    init {
        startContinuousAnimations()
    }

    fun setState(state: VoiceState) {
        if (currentState != state) {
            currentState = state
            updateAnimationForState()
            invalidate()
        }
    }

    fun setState(stateName: String) {
        val voiceState = when (stateName.lowercase().trim()) {
            "idle", "ok", "good" -> VoiceState.IDLE
            "listening" -> VoiceState.LISTENING
            "thinking", "working" -> VoiceState.THINKING
            "speaking" -> VoiceState.SPEAKING
            "error", "bad" -> VoiceState.ERROR
            "waiting_for_wake" -> VoiceState.WAITING_FOR_WAKE
            "wake_detected" -> VoiceState.WAKE_DETECTED
            else -> VoiceState.IDLE
        }
        setState(voiceState)
    }

    fun getCurrentState(): VoiceState = currentState
    fun getStateName(): String = currentState.name.lowercase()

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1f)
        invalidate()
    }

    fun setAnimationIntensity(value: Float) {
        animationIntensity = value.coerceIn(0f, 2f)
        invalidate()
    }

    private fun startContinuousAnimations() {
        pulseAnimator?.cancel()
        pulseAnimator = ValueAnimator.ofFloat(0f, 2f * Math.PI.toFloat())?.apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                pulsePhase = it.animatedValue as Float
                rotationAngle = (rotationAngle + 0.8f) % 360f
                counterRotationAngle = (counterRotationAngle - 1.2f) % 360f
                invalidate()
            }
            start()
        }
    }

    private fun updateAnimationForState() {
        rotationAnimator?.cancel()
        rotationAnimator = null

        when (currentState) {
            VoiceState.THINKING, VoiceState.EXECUTING -> {
                rotationAnimator = ValueAnimator.ofFloat(0f, 360f)?.apply {
                    duration = 1200
                    repeatCount = ValueAnimator.INFINITE
                    interpolator = LinearInterpolator()
                    addUpdateListener {
                        rotationAngle = (rotationAngle + 3.5f) % 360f
                        counterRotationAngle = (counterRotationAngle - 4.5f) % 360f
                        invalidate()
                    }
                    start()
                }
                targetScale = 0.95f
            }
            VoiceState.LISTENING -> {
                targetScale = 1.05f + audioLevel * 0.25f * animationIntensity
            }
            VoiceState.SPEAKING -> {
                targetScale = 1.0f + audioLevel * 0.2f * animationIntensity
            }
            VoiceState.WAITING_FOR_WAKE -> {
                targetScale = 1.0f
            }
            VoiceState.WAKE_DETECTED -> {
                targetScale = 1.25f
                postDelayed({ targetScale = 1.0f; invalidate() }, 350)
            }
            else -> {
                targetScale = 1.0f
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val centerX = width / 2f
        val centerY = height / 2f
        val baseRadius = min(width, height) / 2f * 0.58f
        if (baseRadius <= 0) return

        val pulse = sin(pulsePhase.toDouble()).toFloat() * 0.04f * animationIntensity + 1.0f
        val scale = currentScale + (targetScale - currentScale) * 0.15f
        currentScale = scale
        val radius = baseRadius * scale * pulse

        val primaryColor = getPrimaryColor()
        val secondaryColor = getSecondaryColor()
        val darkColor = getDarkColor()

        // 1. Radiant Ambient Outer Glow
        glowPaint.shader = RadialGradient(
            centerX, centerY, radius * 1.6f,
            intArrayOf(primaryColor and 0x55FFFFFF.toInt(), primaryColor and 0x18FFFFFF.toInt(), Color.TRANSPARENT),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, radius * 1.6f, glowPaint)

        // 2. Orbital Glowing Particles
        particlePaint.color = (primaryColor and 0x00FFFFFF) or 0x90000000.toInt()
        for (p in particles) {
            val pAngle = p[0] + rotationAngle * 0.03f
            val pDist = radius * p[1]
            val px = centerX + cos(pAngle) * pDist
            val py = centerY + sin(pAngle) * pDist
            canvas.drawCircle(px, py, p[2], particlePaint)
        }

        // 3. Segmented Outer Tech Ring (Rotating Clockwise)
        canvas.save()
        canvas.rotate(rotationAngle, centerX, centerY)
        ringPaint.strokeWidth = 3.5f
        ringPaint.color = primaryColor
        val outerRect = RectF(centerX - radius, centerY - radius, centerX + radius, centerY + radius)
        val segmentCount = 6
        val sweepAngle = 40f
        val gapAngle = (360f - segmentCount * sweepAngle) / segmentCount

        for (i in 0 until segmentCount) {
            val startAngle = i * (sweepAngle + gapAngle)
            canvas.drawArc(outerRect, startAngle, sweepAngle, false, ringPaint)
        }

        // Outer tech tick marks
        tickPaint.strokeWidth = 2f
        tickPaint.color = (primaryColor and 0x00FFFFFF) or 0xAA000000.toInt()
        for (i in 0 until 36) {
            val a = (i * 10f) * Math.PI.toFloat() / 180f
            val r1 = radius * 0.94f
            val r2 = radius * (if (i % 3 == 0) 0.88f else 0.91f)
            canvas.drawLine(
                centerX + cos(a) * r1, centerY + sin(a) * r1,
                centerX + cos(a) * r2, centerY + sin(a) * r2,
                tickPaint
            )
        }
        canvas.restore()

        // 4. Middle Arc Reactor Ring (Counter-Rotating)
        canvas.save()
        canvas.rotate(counterRotationAngle, centerX, centerY)
        val midRadius = radius * 0.76f
        val midRect = RectF(centerX - midRadius, centerY - midRadius, centerX + midRadius, centerY + midRadius)
        ringPaint.strokeWidth = 4f
        ringPaint.color = (secondaryColor and 0x00FFFFFF) or 0xDD000000.toInt()

        for (i in 0 until 4) {
            val startAngle = i * 90f + 10f
            canvas.drawArc(midRect, startAngle, 70f, false, ringPaint)
        }

        // Inner ring notches
        for (i in 0 until 12) {
            val a = (i * 30f) * Math.PI.toFloat() / 180f
            val r1 = midRadius * 0.95f
            val r2 = midRadius * 0.88f
            canvas.drawLine(
                centerX + cos(a) * r1, centerY + sin(a) * r1,
                centerX + cos(a) * r2, centerY + sin(a) * r2,
                ringPaint
            )
        }
        canvas.restore()

        // 5. Central Arc Reactor Core
        val coreRadius = radius * 0.52f
        corePaint.shader = RadialGradient(
            centerX - coreRadius * 0.25f, centerY - coreRadius * 0.25f, coreRadius,
            intArrayOf(Color.WHITE, primaryColor, secondaryColor, darkColor),
            floatArrayOf(0f, 0.25f, 0.65f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(centerX, centerY, coreRadius, corePaint)

        // 6. Inner Glowing Node Core & Concentric Rings
        val centerNodeRadius = coreRadius * 0.42f
        val centerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            shader = RadialGradient(
                centerX, centerY, centerNodeRadius,
                intArrayOf(Color.WHITE, primaryColor, darkColor),
                floatArrayOf(0f, 0.4f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(centerX, centerY, centerNodeRadius, centerPaint)

        // Inner Core Ring
        ringPaint.strokeWidth = 2f
        ringPaint.color = Color.WHITE and 0xCCFFFFFF.toInt()
        canvas.drawCircle(centerX, centerY, centerNodeRadius * 0.65f, ringPaint)
    }

    private fun getPrimaryColor(): Int = when (currentState) {
        VoiceState.IDLE -> 0xFF39E0A0.toInt() // jarvis-pc good / arc green
        VoiceState.WAITING_FOR_WAKE -> 0xFF39E0A0.toInt()
        VoiceState.WAKE_DETECTED -> 0xFF3FD0FF.toInt()
        VoiceState.LISTENING -> 0xFF3FD0FF.toInt() // jarvis-pc accent cyan
        VoiceState.UNDERSTANDING, VoiceState.CHECKING_MEMORY, VoiceState.RESEARCHING -> 0xFFFFCF5C.toInt()
        VoiceState.THINKING -> 0xFFFFCF5C.toInt() // jarvis-pc warn amber
        VoiceState.EXECUTING, VoiceState.VERIFYING, VoiceState.RECOVERING -> 0xFF3FD0FF.toInt()
        VoiceState.WAITING_FOR_USER -> 0xFFFFCF5C.toInt()
        VoiceState.SPEAKING -> 0xFF7C9BFF.toInt() // jarvis-pc speaking periwinkle
        VoiceState.COMPLETED -> 0xFF39E0A0.toInt()
        VoiceState.CANCELLED, VoiceState.INTERRUPTED -> 0xFFFFAB00.toInt()
        VoiceState.OFFLINE -> 0xFF8FA3C4.toInt() // jarvis-pc text_dim
        VoiceState.ERROR -> 0xFFFF5C7A.toInt() // jarvis-pc bad red
    }

    private fun getSecondaryColor(): Int = when (currentState) {
        VoiceState.IDLE -> 0xFF1FA670.toInt()
        VoiceState.WAITING_FOR_WAKE -> 0xFF1FA670.toInt()
        VoiceState.WAKE_DETECTED -> 0xFF0091EA.toInt()
        VoiceState.LISTENING -> 0xFF0091EA.toInt()
        VoiceState.UNDERSTANDING, VoiceState.CHECKING_MEMORY, VoiceState.RESEARCHING -> 0xFFF57F17.toInt()
        VoiceState.THINKING -> 0xFFF57F17.toInt()
        VoiceState.EXECUTING, VoiceState.VERIFYING, VoiceState.RECOVERING -> 0xFF0091EA.toInt()
        VoiceState.WAITING_FOR_USER -> 0xFFFFA000.toInt()
        VoiceState.SPEAKING -> 0xFF3D5AFE.toInt()
        VoiceState.COMPLETED -> 0xFF1FA670.toInt()
        VoiceState.CANCELLED, VoiceState.INTERRUPTED -> 0xFFFF8F00.toInt()
        VoiceState.OFFLINE -> 0xFF546E7A.toInt()
        VoiceState.ERROR -> 0xFFD32F2F.toInt()
    }

    private fun getDarkColor(): Int = when (currentState) {
        VoiceState.SPEAKING -> 0xFF1A237E.toInt()
        VoiceState.COMPLETED, VoiceState.IDLE -> 0xFF0A3D28.toInt()
        VoiceState.THINKING, VoiceState.UNDERSTANDING -> 0xFF3E2723.toInt()
        VoiceState.ERROR -> 0xFFB71C1C.toInt()
        else -> 0xFF00363A.toInt()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        pulseAnimator?.cancel()
        rotationAnimator?.cancel()
    }
}
