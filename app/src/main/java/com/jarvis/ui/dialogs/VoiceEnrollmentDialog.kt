package com.jarvis.ui.dialogs

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.jarvis.ui.components.Ui
import com.jarvis.voice.audio.AcousticFeedbackEngine
import com.jarvis.wakeword.UserVoiceProfile
import com.jarvis.wakeword.VoiceEnrollmentCalibrator
import com.jarvis.wakeword.VoiceSampleRecorder
import com.jarvis.wakeword.WakeWordDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Google Stitch Material 3 Cyber-Slate Dialog for guided voice wake-word enrollment
 * and personal acoustic calibration.
 *
 * Guides the user through 3 clean audio recordings of "Jarvis", validates acoustic parameters,
 * visualizes real-time speech energy levels, and calibrates an individualized detection threshold.
 */
class VoiceEnrollmentDialog(
    private val context: Context,
    private val detector: WakeWordDetector? = null,
    private val calibrator: VoiceEnrollmentCalibrator = VoiceEnrollmentCalibrator(),
    private val onProfileEnrolled: (UserVoiceProfile) -> Unit
) {
    private val recorder = VoiceSampleRecorder()
    private val feedbackEngine = runCatching { AcousticFeedbackEngine(context) }.getOrNull()
    private val dialogScope = CoroutineScope(Dispatchers.Main + Job())
    private var recordJob: Job? = null

    private val recordedSamples = mutableListOf<VoiceEnrollmentCalibrator.ValidatedSample>()
    private var calibratedProfile: UserVoiceProfile? = null

    private var alertDialog: AlertDialog? = null

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE_CONTAINER)
            setPadding(Ui.dp(context, 20), Ui.dp(context, 20), Ui.dp(context, 20), Ui.dp(context, 20))
        }

        // Header: Icon + Title
        val headerRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(context, 8))
        }
        val iconView = TextView(context).apply {
            text = "🎙"
            textSize = 20f
            setPadding(0, 0, Ui.dp(context, 8), 0)
        }
        val titleView = TextView(context).apply {
            text = "VOICE CALIBRATION WIZARD"
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.06f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        headerRow.addView(iconView)
        headerRow.addView(titleView)
        root.addView(headerRow)

        // Subtitle
        val subtitleView = TextView(context).apply {
            text = "Record 3 clean samples saying 'Jarvis' to calibrate detection sensitivity to your voice and room acoustics."
            textSize = 12f
            setTextColor(Ui.MUTED)
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, Ui.dp(context, 16))
        }
        root.addView(subtitleView)

        // Step Indicator Chips Row
        val stepChipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(context, 14))
        }
        val chip1 = createStepChip(context, "1. SAMPLE 1")
        val chip2 = createStepChip(context, "2. SAMPLE 2")
        val chip3 = createStepChip(context, "3. SAMPLE 3")
        stepChipsRow.addView(chip1)
        stepChipsRow.addView(chip2)
        stepChipsRow.addView(chip3)
        root.addView(stepChipsRow)

        // Real-time Audio Level Bar Container
        val levelLabel = TextView(context).apply {
            text = "SPEECH ENERGY LEVEL"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, Ui.dp(context, 4))
        }
        root.addView(levelLabel)

        val levelBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(Ui.CYAN)
            progressBackgroundTintList = ColorStateList.valueOf(Ui.SURFACE_2)
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 8)).apply {
                bottomMargin = Ui.dp(context, 16)
            }
        }
        root.addView(levelBar)

        // Status Card / Instruction Text
        val statusCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER_GLOW, 10)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = Ui.dp(context, 18)
            }
        }
        val statusTitle = TextView(context).apply {
            text = "READY TO ENROLL"
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, Ui.dp(context, 4))
        }
        val statusMessage = TextView(context).apply {
            text = "Tap 'RECORD SAMPLE 1' below and say 'Jarvis' clearly at your normal speaking distance."
            textSize = 12f
            setTextColor(Ui.TEXT)
            setLineSpacing(0f, 1.15f)
        }
        statusCard.addView(statusTitle)
        statusCard.addView(statusMessage)
        root.addView(statusCard)

        // Summary Details Card (Hidden until all 3 samples are completed)
        val summaryCard = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN_DIM, 10)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
                bottomMargin = Ui.dp(context, 18)
            }
        }
        val summaryTitle = TextView(context).apply {
            text = "CALIBRATION ANALYSIS COMPLETE"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.SUCCESS)
            letterSpacing = 0.08f
            setPadding(0, 0, 0, Ui.dp(context, 4))
        }
        val summaryDetails = TextView(context).apply {
            text = ""
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            setLineSpacing(0f, 1.2f)
        }
        summaryCard.addView(summaryTitle)
        summaryCard.addView(summaryDetails)
        root.addView(summaryCard)

        // Action Buttons Row
        val buttonRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
        }
        val cancelButton = MaterialButton(context).apply {
            text = "CANCEL"
            textSize = 11f
            setTextColor(Ui.MUTED)
            strokeColor = ColorStateList.valueOf(Ui.BORDER)
            strokeWidth = Ui.dp(context, 1)
            cornerRadius = Ui.dp(context, 8)
            backgroundTintList = ColorStateList.valueOf(Ui.SURFACE)
            setPadding(Ui.dp(context, 14), Ui.dp(context, 8), Ui.dp(context, 14), Ui.dp(context, 8))
            setOnClickListener {
                recorder.stop()
                recordJob?.cancel()
                dialogScope.cancel()
                alertDialog?.dismiss()
            }
        }
        val actionButton = MaterialButton(context).apply {
            text = "RECORD SAMPLE 1"
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.BG)
            cornerRadius = Ui.dp(context, 8)
            backgroundTintList = ColorStateList.valueOf(Ui.CYAN)
            setPadding(Ui.dp(context, 16), Ui.dp(context, 8), Ui.dp(context, 16), Ui.dp(context, 8))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = Ui.dp(context, 8)
            }
        }
        buttonRow.addView(cancelButton)
        buttonRow.addView(actionButton)
        root.addView(buttonRow)

        fun updateChips() {
            val chips = listOf(chip1, chip2, chip3)
            val currentIdx = recordedSamples.size
            for (i in 0 until 3) {
                val chip = chips[i]
                when {
                    i < currentIdx -> {
                        chip.setTextColor(Ui.SUCCESS)
                        chip.background = Ui.rounded(context, Ui.SURFACE_2, Ui.SUCCESS, 6)
                    }
                    i == currentIdx && currentIdx < 3 -> {
                        chip.setTextColor(Ui.CYAN)
                        chip.background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN, 6)
                    }
                    else -> {
                        chip.setTextColor(Ui.MUTED)
                        chip.background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 6)
                    }
                }
            }
        }
        updateChips()

        actionButton.setOnClickListener {
            val currentSampleIdx = recordedSamples.size
            if (currentSampleIdx >= 3) {
                // Save and activate profile
                calibratedProfile?.let { prof ->
                    onProfileEnrolled(prof)
                    feedbackEngine?.playSuccessTone()
                }
                dialogScope.cancel()
                alertDialog?.dismiss()
                return@setOnClickListener
            }

            // Start recording sample
            actionButton.isEnabled = false
            actionButton.text = "RECORDING (2s)..."
            statusTitle.text = "RECORDING SAMPLE ${currentSampleIdx + 1}/3"
            statusTitle.setTextColor(Ui.WARNING)
            statusMessage.text = "🎙 Listening... Please say 'Jarvis' now."
            feedbackEngine?.playListeningTone()

            recordJob?.cancel()
            recordJob = dialogScope.launch {
                val pcm = recorder.recordSample(durationMs = 2000L) { normalizedRms ->
                    levelBar.progress = (normalizedRms * 100).toInt()
                }
                levelBar.progress = 0

                if (pcm == null || pcm.isEmpty()) {
                    statusTitle.text = "RECORDING FAILED"
                    statusTitle.setTextColor(Ui.ERROR)
                    statusMessage.text = "Microphone error or permission denied. Tap to retry."
                    actionButton.isEnabled = true
                    actionButton.text = "RE-TRY SAMPLE ${currentSampleIdx + 1}"
                    return@launch
                }

                statusTitle.text = "ANALYZING ACOUSTICS"
                statusTitle.setTextColor(Ui.CYAN)
                statusMessage.text = "Validating vocal envelope, speech energy, and confidence..."

                val validation = calibrator.validateSample(pcm, detector)
                when (validation) {
                    is VoiceEnrollmentCalibrator.ValidationResult.Valid -> {
                        feedbackEngine?.playSuccessTone()
                        val validSample = VoiceEnrollmentCalibrator.ValidatedSample(
                            pcm = pcm,
                            rmsEnergy = validation.rmsEnergy,
                            score = validation.score,
                            speechDurationMs = validation.speechDurationMs
                        )
                        recordedSamples.add(validSample)
                        updateChips()

                        if (recordedSamples.size < 3) {
                            val nextIdx = recordedSamples.size + 1
                            val matchPct = (validation.score * 100).toInt()
                            statusTitle.text = "SAMPLE ${recordedSamples.size} ACCEPTED ($matchPct% MATCH)"
                            statusTitle.setTextColor(Ui.SUCCESS)
                            statusMessage.text = "Great! Clean sample captured. Ready for Sample $nextIdx of 3."
                            actionButton.text = "RECORD SAMPLE $nextIdx"
                            actionButton.isEnabled = true
                        } else {
                            // All 3 samples gathered, perform final calibration!
                            val profile = calibrator.calibrate(recordedSamples)
                            calibratedProfile = profile

                            statusCard.visibility = View.GONE
                            summaryCard.visibility = View.VISIBLE
                            summaryDetails.text = buildString {
                                append("• Average Wake Confidence: ${(profile.averageScore * 100).toInt()}%\n")
                                append("• Calibrated Threshold: ${String.format(Locale.US, "%.2f", profile.calibratedThreshold)}\n")
                                append("• Calibrated Energy Floor: ${profile.calibratedMinEnergyRms.toInt()} RMS\n")
                                append("• Estimated Room Noise: ${profile.ambientNoiseRms.toInt()} RMS\n")
                                append("• Status: Ready to activate")
                            }

                            actionButton.text = "SAVE & ACTIVATE"
                            actionButton.backgroundTintList = ColorStateList.valueOf(Ui.SUCCESS)
                            actionButton.isEnabled = true
                        }
                    }
                    is VoiceEnrollmentCalibrator.ValidationResult.Rejected -> {
                        statusTitle.text = "SAMPLE REJECTED"
                        statusTitle.setTextColor(Ui.ERROR)
                        statusMessage.text = validation.message
                        actionButton.text = "RE-TRY SAMPLE ${currentSampleIdx + 1}"
                        actionButton.isEnabled = true
                    }
                }
            }
        }

        alertDialog = AlertDialog.Builder(context)
            .setView(root)
            .setCancelable(false)
            .create().apply {
                window?.setBackgroundDrawable(
                    GradientDrawable().apply {
                        setColor(Ui.SURFACE_CONTAINER)
                        cornerRadius = Ui.dp(context, 16).toFloat()
                        setStroke(Ui.dp(context, 1), Ui.BORDER_GLOW)
                    }
                )
            }

        alertDialog?.show()
    }

    private fun createStepChip(context: Context, label: String): TextView = TextView(context).apply {
        text = label
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.04f
        gravity = Gravity.CENTER
        setTextColor(Ui.MUTED)
        background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 6)
        setPadding(Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4))
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = Ui.dp(context, 6)
        }
    }
}
