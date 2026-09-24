package com.jarvis.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import com.jarvis.voice.VoiceState
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random

enum class WaveformLayout { LINEAR, CIRCULAR }
enum class WaveformVisualization { AMPLITUDE, SPECTRUM }

class JarvisWaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val wavePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xFF00E5FF.toInt()
    }

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = 0x8000E5FF.toInt()
    }

    private val particlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = 0xAA00E5FF.toInt()
    }

    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var currentState = VoiceState.IDLE
    private var audioLevel = 0.5f
    private var smoothedLevel = 0.0f
    private var layout = WaveformLayout.LINEAR
    private var visualization = WaveformVisualization.AMPLITUDE
    private var bandCount = 32

    private val barCount = 48
    private val particles = List(20) {
        floatArrayOf(Random.nextFloat(), Random.nextFloat(), Random.nextFloat() * 3f + 1f)
    }

    init {
        startAnimation()
    }

    fun setState(state: VoiceState) {
        currentState = state
        invalidate()
    }

    fun setAudioLevel(level: Float) {
        audioLevel = level.coerceIn(0f, 1.0f)
        invalidate()
    }

    fun setLayout(value: WaveformLayout) {
        layout = value
        invalidate()
    }

    fun setVisualization(value: WaveformVisualization, bands: Int = 32) {
        visualization = value
        bandCount = bands.coerceIn(8, 64)
        invalidate()
    }

    private fun startAnimation() {
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 2f * Math.PI.toFloat()).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phase = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val centerY = h / 2f
        smoothedLevel += (audioLevel - smoothedLevel) * 0.18f
        if (layout == WaveformLayout.CIRCULAR) {
            drawCircular(canvas, w, h)
            return
        }
        val barWidth = (w / barCount) * 0.55f
        val step = w / barCount

        val isSpeakingOrListening = currentState == VoiceState.LISTENING || currentState == VoiceState.SPEAKING
        val isThinking = currentState == VoiceState.THINKING || currentState == VoiceState.EXECUTING

        val color = when (currentState) {
            VoiceState.SPEAKING -> 0xFF00E676.toInt()
            VoiceState.THINKING, VoiceState.EXECUTING -> 0xFF2196F3.toInt()
            VoiceState.ERROR -> 0xFFFF5252.toInt()
            else -> 0xFF00E5FF.toInt()
        }

        wavePaint.color = color
        glowPaint.color = (color and 0x00FFFFFF) or 0x60000000.toInt()
        particlePaint.color = (color and 0x00FFFFFF) or 0x90000000.toInt()

        // Draw horizontal baseline glow
        canvas.drawLine(0f, centerY, w, centerY, glowPaint)

        // Draw waveform bars
        for (i in 0 until barCount) {
            val normalizedX = (i.toFloat() / barCount) * 2f - 1f // -1 to 1
            val bell = (1f - normalizedX * normalizedX).coerceAtLeast(0.05f)

            val wave1 = sin(normalizedX * 6f + phase) * 0.5f + 0.5f
            val wave2 = sin(normalizedX * 12f - phase * 1.5f) * 0.3f + 0.3f

            val multiplier = when {
                isSpeakingOrListening -> 0.85f * smoothedLevel
                isThinking -> 0.5f
                else -> 0.25f
            }

            val barHeight = ((wave1 + wave2) * bell * (h * 0.7f) * multiplier).coerceAtLeast(3f)
            val x = i * step + step / 2f

            val rect = RectF(
                x - barWidth / 2f,
                centerY - barHeight / 2f,
                x + barWidth / 2f,
                centerY + barHeight / 2f
            )
            canvas.drawRoundRect(rect, barWidth / 2f, barWidth / 2f, wavePaint)
        }

        // Draw floating particles around the wave
        for (p in particles) {
            val px = ((p[0] + phase * 0.05f) % 1f) * w
            val py = centerY + (sin(px * 0.05f + phase) * h * 0.35f * (p[1] - 0.5f))
            canvas.drawCircle(px, py, p[2], particlePaint)
        }
    }

    private fun drawCircular(canvas: Canvas, w: Float, h: Float) {
        val cx = w / 2f
        val cy = h / 2f
        val radius = minOf(w, h) * 0.28f
        val count = if (visualization == WaveformVisualization.SPECTRUM) bandCount else barCount
        val paint = wavePaint
        for (i in 0 until count) {
            val a = (i.toFloat() / count) * (Math.PI * 2.0)
            val wave = if (visualization == WaveformVisualization.SPECTRUM) {
                (sin(i * 0.73 + phase * 1.2) * 0.5 + 0.5).toFloat()
            } else {
                (sin(i * 0.42 + phase) * 0.5 + 0.5).toFloat()
            }
            val amp = radius * (0.08f + 0.42f * smoothedLevel * (0.35f + wave * 0.65f))
            val r1 = radius
            val r2 = radius + amp
            canvas.drawLine(
                cx + cos(a).toFloat() * r1,
                cy + sin(a).toFloat() * r1,
                cx + cos(a).toFloat() * r2,
                cy + sin(a).toFloat() * r2,
                paint
            )
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }
}
