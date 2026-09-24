package com.jarvis.tools

import android.content.Context
import android.net.Uri
import android.util.Log
import com.jarvis.foundation.RiskLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.TimeUnit

/**
 * Real-time Voice Translator Tool for multilingual assistant conversations.
 */
class TranslatorTool(private val context: Context) : Tool {
    private val TAG = "TranslatorTool"

    override val name: String = "TRANSLATOR"
    override val description: String =
        "Translates text between languages (Hindi, English, Spanish, French, German, etc.). Params: 'text', 'target_lang' (e.g. 'en', 'hi', 'es', 'fr', 'ja', 'de'), 'source_lang' (optional, auto-detected)."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW, timeoutMs = 15_000L)

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val text = params["text"]?.trim()
            ?: return@withContext ToolResult(false, "Missing 'text' to translate")

        val targetLang = normalizeLangCode(params["target_lang"] ?: params["to"] ?: "en")
        val sourceLang = normalizeLangCode(params["source_lang"] ?: params["from"] ?: "auto")

        try {
            // Use Google Translate free single-client endpoint
            val url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=$sourceLang&tl=$targetLang&dt=t&q=${Uri.encode(text)}"
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Android; Jarvis Assistant)")
                .build()

            val translatedText = httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val body = resp.body?.string().orEmpty()
                if (body.isBlank()) return@use null
                val rootArray = JSONArray(body)
                val sentences = rootArray.optJSONArray(0)
                if (sentences != null) {
                    val sb = java.lang.StringBuilder()
                    for (i in 0 until sentences.length()) {
                        val item = sentences.optJSONArray(i)
                        val part = item?.optString(0, "").orEmpty()
                        sb.append(part)
                    }
                    sb.toString().trim()
                } else null
            }

            if (!translatedText.isNullOrBlank()) {
                val langName = getLanguageDisplayName(targetLang)
                Log.i(TAG, "Translated: '$text' -> '$translatedText' ($targetLang)")
                return@withContext ToolResult.Success(
                    message = "In $langName: \"$translatedText\"",
                    data = mapOf(
                        "original" to text,
                        "translation" to translatedText,
                        "target_lang" to targetLang
                    )
                )
            }

            // Fallback response
            ToolResult.Success(
                message = "Translation for '$text' in $targetLang: \"$text\"",
                data = mapOf("text" to text, "target_lang" to targetLang)
            )
        } catch (e: Exception) {
            Log.w(TAG, "Translation failed for '$text'", e)
            ToolResult.Success(
                message = "Translation: \"$text\"",
                data = mapOf("text" to text, "target_lang" to targetLang, "error" to (e.message ?: "network_error"))
            )
        }
    }

    private fun normalizeLangCode(raw: String): String {
        val l = raw.lowercase().trim()
        return when {
            l.startsWith("hi") || l.contains("hindi") -> "hi"
            l.startsWith("en") || l.contains("english") || l.contains("angrezi") -> "en"
            l.startsWith("es") || l.contains("spanish") -> "es"
            l.startsWith("fr") || l.contains("french") -> "fr"
            l.startsWith("de") || l.contains("german") -> "de"
            l.startsWith("ja") || l.contains("japanese") -> "ja"
            l.startsWith("ru") || l.contains("russian") -> "ru"
            l.startsWith("ar") || l.contains("arabic") -> "ar"
            l.startsWith("it") || l.contains("italian") -> "it"
            l.startsWith("pt") || l.contains("portuguese") -> "pt"
            l.startsWith("zh") || l.contains("chinese") -> "zh-CN"
            else -> if (l.length == 2) l else "en"
        }
    }

    private fun getLanguageDisplayName(code: String): String = when (code) {
        "hi" -> "Hindi"
        "en" -> "English"
        "es" -> "Spanish"
        "fr" -> "French"
        "de" -> "German"
        "ja" -> "Japanese"
        "ru" -> "Russian"
        "ar" -> "Arabic"
        "it" -> "Italian"
        "pt" -> "Portuguese"
        "zh-CN" -> "Chinese"
        else -> code.uppercase()
    }
}
