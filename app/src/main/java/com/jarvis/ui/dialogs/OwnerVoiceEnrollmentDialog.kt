package com.jarvis.ui.dialogs

import android.content.Context
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.jarvis.ui.components.Ui
import com.jarvis.wakeword.OwnerVoiceProfile
import com.jarvis.wakeword.TfliteOwnerVoiceVerifier
import com.jarvis.wakeword.VoiceSampleRecorder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers.IO

class OwnerVoiceEnrollmentDialog(
    private val context: Context,
    private val onEnrolled: (OwnerVoiceProfile) -> Unit
) {
    private val recorder = VoiceSampleRecorder()
    private val scope = CoroutineScope(Dispatchers.Main + Job())
    private val embeddings = mutableListOf<FloatArray>()
    private var verifier: TfliteOwnerVoiceVerifier? = null
    private var dialog: AlertDialog? = null

    fun show() {
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 20), Ui.dp(context, 18), Ui.dp(context, 20), Ui.dp(context, 12))
            setBackgroundColor(Ui.SURFACE_CONTAINER)
        }
        root.addView(TextView(context).apply {
            text = "TRAIN OWNER VOICE"
            textSize = 17f
            setTextColor(Ui.CYAN)
            setPadding(0, 0, 0, Ui.dp(context, 8))
        })
        val status = TextView(context).apply {
            text = "Say ‘Jarvis, please listen’ naturally for each of 3 samples. Audio is processed on this phone, discarded after embedding extraction, and never uploaded. This convenience filter is not secure against recordings or imitation."
            textSize = 13f
            setTextColor(Ui.TEXT)
            setPadding(0, 0, 0, Ui.dp(context, 16))
        }
        root.addView(status)
        val record = MaterialButton(context).apply { text = "RECORD SAMPLE 1 / 3" }
        root.addView(record)
        dialog = AlertDialog.Builder(context).setView(root).setNegativeButton("CANCEL", null).create()
        record.setOnClickListener {
            val n = embeddings.size + 1
            record.isEnabled = false
            record.text = "LOADING LOCAL SPEAKER MODEL..."
            scope.launch {
                if (verifier == null) verifier = withContext(IO) { runCatching { TfliteOwnerVoiceVerifier(context) }.getOrNull() }
                val activeVerifier = verifier
                if (activeVerifier?.isAvailable() != true) {
                    status.text = "Speaker model could not be loaded. Owner-only wake remains unavailable; tap-to-talk still works. ${activeVerifier?.error().orEmpty()}"
                    record.text = "RETRY SAMPLE $n / 3"
                    record.isEnabled = true
                    return@launch
                }
                record.text = "LISTENING..."
                val pcm = recorder.recordSample(2600L)
                record.text = "ANALYZING LOCALLY..."
                val vector = pcm?.let { withContext(IO) { activeVerifier.embed(it) } }
                pcm?.fill(0)
                if (vector == null) {
                    status.text = "Could not get a clear speaker embedding. Speak clearly and retry in a quieter place."
                    record.text = "RETRY SAMPLE $n / 3"
                    record.isEnabled = true
                } else {
                    embeddings += vector
                    if (embeddings.size < 3) {
                        status.text = "Sample ${embeddings.size} analyzed. Pause, then record the same phrase again. Only speaker vectors are retained during this dialog."
                        record.text = "RECORD SAMPLE ${embeddings.size + 1} / 3"
                        record.isEnabled = true
                    } else {
                        val profile = runCatching { OwnerVoiceProfile.fromEmbeddings(embeddings) }.getOrNull()
                        embeddings.forEach { it.fill(0f) }
                        embeddings.clear()
                        if (profile == null) {
                            status.text = "Could not build a valid template. Please retrain all 3 samples."
                            record.text = "RETRY SAMPLE 1 / 3"
                            record.isEnabled = true
                        } else {
                            status.text = "Speaker template created. Saving it encrypted on this device."
                            onEnrolled(profile)
                            dialog?.dismiss()
                        }
                    }
                }
            }
        }
        dialog?.setOnDismissListener {
            recorder.stop()
            embeddings.forEach { it.fill(0f) }
            embeddings.clear()
            verifier?.close()
            verifier = null
            scope.cancel()
        }
        dialog?.show()
    }
}
