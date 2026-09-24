package com.jarvis.clipboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import com.jarvis.bluetooth.BluetoothHeadsetManager
import com.jarvis.controlplane.JarvisEvent
import com.jarvis.controlplane.JarvisEventBus
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.voice.TtsEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

data class ClipboardAnalysis(
    val type: String,
    val extractedValue: String,
    val suggestedAction: String,
    val promptText: String
)

/**
 * Autonomous Clipboard Intelligence Engine:
 * Monitors the Android system clipboard in the background, extracts actionable data
 * (OTPs, YouTube/Spotify links, URLs, phone numbers, postal addresses, tracking numbers),
 * and provides proactive suggestions via earbuds whisper or overlay.
 */
class ClipboardIntelligenceEngine(
    private val context: Context,
    private val bluetoothHeadsetManager: BluetoothHeadsetManager? = null,
    private val ttsEngine: TtsEngine? = null
) : ClipboardManager.OnPrimaryClipChangedListener {

    private val TAG = "ClipboardIntelligence"
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var clipboardManager: ClipboardManager? = null
    private val isRunning = AtomicBoolean(false)

    @Volatile
    private var lastProcessedText: String? = null

    @Volatile
    private var lastProcessedTime: Long = 0L

    var isEnabled: Boolean = true

    fun start() {
        if (!isRunning.compareAndSet(false, true)) return
        Log.i(TAG, "Starting ClipboardIntelligenceEngine...")

        try {
            clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboardManager?.addPrimaryClipChangedListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register Clipboard listener: ${e.message}")
        }
    }

    fun stop() {
        if (!isRunning.compareAndSet(true, false)) return
        try {
            clipboardManager?.removePrimaryClipChangedListener(this)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to unregister Clipboard listener: ${e.message}")
        }
        clipboardManager = null
        scope.cancel()
        Log.i(TAG, "ClipboardIntelligenceEngine stopped")
    }

    override fun onPrimaryClipChanged() {
        if (!isEnabled) return

        val settings = runCatching { UiPreferencesStore(context).loadSettings() }.getOrNull()
        if (settings?.clipboardIntelligenceEnabled == false) return

        scope.launch {
            handleClipChanged()
        }
    }

    private suspend fun handleClipChanged() {
        val clip: ClipData = clipboardManager?.primaryClip ?: return
        if (clip.itemCount == 0) return

        val text = clip.getItemAt(0)?.coerceToText(context)?.toString()?.trim() ?: return
        if (text.isBlank() || text.length > 2000) return

        val now = System.currentTimeMillis()
        if (text == lastProcessedText && (now - lastProcessedTime) < 30_000L) {
            return
        }

        lastProcessedText = text
        lastProcessedTime = now

        // Check if content is sensitive (passwords, private keys, credit cards)
        if (isSensitiveContent(text)) {
            Log.d(TAG, "Ignoring clipboard content identified as sensitive/credential.")
            return
        }

        val analysis = analyzeText(text) ?: return

        Log.i(TAG, "Clipboard detected: type=${analysis.type}, value='${analysis.extractedValue}'")

        JarvisEventBus.shared.post(
            JarvisEvent.ClipboardSuggestion(
                type = analysis.type,
                content = analysis.extractedValue,
                suggestedAction = analysis.suggestedAction,
                promptText = analysis.promptText
            )
        )

        // If Bluetooth headset is connected, whisper subtle proactive cue
        val isHeadset = bluetoothHeadsetManager?.isHeadsetConnected == true
        if (isHeadset && ttsEngine != null) {
            ttsEngine.speak(analysis.promptText)
        }
    }

    fun analyzeText(text: String): ClipboardAnalysis? {
        val clean = text.trim()

        // 1. YouTube Link
        if (clean.contains("youtube.com/watch", ignoreCase = true) || clean.contains("youtu.be/", ignoreCase = true)) {
            return ClipboardAnalysis(
                type = "YOUTUBE_URL",
                extractedValue = clean,
                suggestedAction = "play_youtube",
                promptText = "Copied YouTube video. Would you like me to play it?"
            )
        }

        // 2. Spotify Link
        if (clean.contains("open.spotify.com/track", ignoreCase = true) || clean.contains("spotify.link/", ignoreCase = true)) {
            return ClipboardAnalysis(
                type = "SPOTIFY_URL",
                extractedValue = clean,
                suggestedAction = "play_spotify",
                promptText = "Copied Spotify track. Would you like me to play it?"
            )
        }

        // 3. Web URL
        if (clean.startsWith("http://", ignoreCase = true) || clean.startsWith("https://", ignoreCase = true)) {
            val domain = runCatching {
                android.net.Uri.parse(clean).host?.removePrefix("www.")
            }.getOrNull() ?: "link"
            return ClipboardAnalysis(
                type = "WEB_URL",
                extractedValue = clean,
                suggestedAction = "open_browser",
                promptText = "Copied $domain link. Would you like me to open it?"
            )
        }

        // 4. Package Tracking Numbers (UPS, FedEx, India Post/EMS, BlueDart)
        val upsMatch = Regex("\\b(1Z[0-9A-Z]{16})\\b", RegexOption.IGNORE_CASE).find(clean)
        if (upsMatch != null) {
            val code = upsMatch.groupValues[1]
            return ClipboardAnalysis(
                type = "TRACKING_NUMBER",
                extractedValue = code,
                suggestedAction = "track_package",
                promptText = "Copied package tracking number $code. Would you like to track it?"
            )
        }

        val emsMatch = Regex("\\b([A-Z]{2}[0-9]{9}[A-Z]{2})\\b").find(clean)
        if (emsMatch != null) {
            val code = emsMatch.groupValues[1]
            return ClipboardAnalysis(
                type = "TRACKING_NUMBER",
                extractedValue = code,
                suggestedAction = "track_package",
                promptText = "Copied consignment number $code. Would you like to track it?"
            )
        }

        // 5. Verification OTP (4 to 8 digits)
        val otpCode = extractOtpFromText(clean)
        if (otpCode != null) {
            return ClipboardAnalysis(
                type = "OTP",
                extractedValue = otpCode,
                suggestedAction = "copy_otp",
                promptText = "Copied verification code $otpCode. Ready to paste."
            )
        }

        // 6. Phone Number (+91..., 10 digits)
        val phoneMatch = Regex("(?:\\+?[0-9]{1,3}[-. ]?)?([6-9][0-9]{9})\\b").find(clean)
        if (phoneMatch != null && clean.length <= 25) {
            val number = phoneMatch.value.replace(Regex("[^0-9+]"), "")
            return ClipboardAnalysis(
                type = "PHONE_NUMBER",
                extractedValue = number,
                suggestedAction = "dial_or_message",
                promptText = "Copied phone number $number. Would you like to call or send a WhatsApp message?"
            )
        }

        // 7. Physical Address / Map Location
        if (isAddressText(clean)) {
            return ClipboardAnalysis(
                type = "ADDRESS",
                extractedValue = clean,
                suggestedAction = "navigate_maps",
                promptText = "Copied location address. Would you like to open it in Google Maps?"
            )
        }

        return null
    }

    private fun extractOtpFromText(text: String): String? {
        val lower = text.lowercase(Locale.ROOT)
        if (lower.contains("pincode") || lower.contains("pin code") || lower.contains("postal")) {
            return null
        }
        val otpKeywords = listOf("\\botp\\b", "\\bcode\\b", "\\bpin\\b", "\\bpasscode\\b", "\\bsecret\\b", "\\bverification\\b", "\\bauth\\b")
        val hasOtpKeyword = otpKeywords.any { Regex(it).containsMatchIn(lower) }

        if (hasOtpKeyword) {
            val regex = Regex("\\b([0-9]{4,8})\\b")
            val match = regex.find(text)
            if (match != null) return match.groupValues[1]
        } else if (text.matches(Regex("^[0-9]{4,6}$"))) {
            // Standalone 4 to 6 digit code copied
            return text
        }
        return null
    }

    private fun isAddressText(text: String): Boolean {
        if (text.length < 15 || text.length > 300) return false
        val lower = text.lowercase(Locale.ROOT)
        val addressKeywords = listOf(
            "street", "road", "rd.", "ave", "avenue", "blvd", "lane", "nagar", "marg",
            "sector", "block", "floor", "apartment", "colony", "chowk", "cross", "pincode", "pin code"
        )
        val count = addressKeywords.count { lower.contains(it) }
        val hasPin = Regex("\\b[1-9][0-9]{5}\\b").containsMatchIn(text) // 6-digit postal code
        return count >= 2 || (count >= 1 && hasPin)
    }

    private fun isSensitiveContent(text: String): Boolean {
        val lower = text.lowercase(Locale.ROOT)
        val sensitiveKeywords = listOf(
            "password=", "passwd", "bearer ", "authorization:", "private_key", "secret_key",
            "BEGIN PRIVATE KEY", "api_key", "client_secret"
        )
        if (sensitiveKeywords.any { lower.contains(it.lowercase()) }) return true

        // Credit Card Luhn-candidate: 13-19 digits with spaces/hyphens
        val cardCandidate = text.filter { it.isDigit() }
        if (cardCandidate.length in 13..19 && (text.contains("-") || text.contains(" "))) {
            return true
        }
        return false
    }
}
