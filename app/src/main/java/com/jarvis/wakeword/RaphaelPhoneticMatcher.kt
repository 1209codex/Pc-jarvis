package com.jarvis.wakeword

import java.util.Locale
import kotlin.math.min

object RaphaelPhoneticMatcher {

    fun resolveDetectedPhrase(detected: String, configuredWakeWord: String): String =
        detected.trim().ifBlank { configuredWakeWord.trim().ifBlank { "Jarvis" } }

    private val CANONICAL_VARIANTS = listOf(
        "jarvis",
        "jarviz",
        "jarves",
        "garvis",
        "jarviss",
        "javis",
        "travis",
        "jahvis",
        "charvis",
        "darvis",
        "sharvis",
        "sarvis",
        "jervis",
        "service",
        "charlies",
        "jarvi",
        "jarv"
    )

    private val PREFIX_HOTWORDS = listOf(
        "hey",
        "ok",
        "okay",
        "hi",
        "hello",
        "yo"
    )

    private val INTERRUPT_KEYWORDS = listOf(
        "stop",
        "cancel",
        "wait",
        "shut up",
        "quiet",
        "silence",
        "pause",
        "hold on",
        "enough"
    )

    fun isInterruptKeyword(phrase: String): Boolean {
        val clean = phrase.lowercase(Locale.ROOT).trim()
        if (clean.isBlank()) return false
        return INTERRUPT_KEYWORDS.any { clean == it || clean.startsWith("$it ") || clean.endsWith(" $it") }
    }

    fun phoneticKey(word: String): String {
        var clean = word.lowercase(Locale.ROOT).filter { it.isLetter() }
        if (clean.isEmpty()) return ""

        clean = clean.replace("z", "s")
        clean = clean.replace("es", "is")
        clean = clean.replace("g", "j")
        clean = clean.replace("ch", "j")
        clean = clean.replace("sh", "s")

        // Collapse repeated consecutive characters
        val sb = StringBuilder()
        var prev = ' '
        for (ch in clean) {
            if (ch != prev) {
                sb.append(ch)
                prev = ch
            }
        }
        return sb.toString()
    }

    /**
     * Checks if a single token matches the target wake word.
     * Supports both the canonical "Jarvis" variants and dynamic custom wake words (e.g. "Friday", "Computer").
     */
    fun isWakeWord(token: String, customWakeWord: String? = null): Boolean {
        val lower = token.lowercase(Locale.ROOT).trim().filter { it.isLetter() }
        if (lower.isEmpty()) return false

        val target = customWakeWord?.lowercase(Locale.ROOT)?.trim()?.filter { it.isLetter() }

        if (target.isNullOrBlank() || target == "jarvis") {
            if (CANONICAL_VARIANTS.contains(lower)) return true

            val key = phoneticKey(lower)
            val targetKey = phoneticKey("jarvis")
            if (key == targetKey || key == "jarvis" || key == "jarvs" || key == "jaris") return true

            for (v in CANONICAL_VARIANTS) {
                if (levenshteinDistance(lower, v) <= 1) return true
            }

            return false
        } else {
            // Dynamic custom wake word matching
            if (lower == target) return true
            if (levenshteinDistance(lower, target) <= 1) return true
            if (phoneticKey(lower) == phoneticKey(target)) return true
            return false
        }
    }

    fun findWakeWordIndex(phrase: String, customWakeWord: String? = null): Int {
        val words = phrase.lowercase(Locale.ROOT).split(Regex("\\s+"))
        for (i in words.indices) {
            val word = words[i].filter { it.isLetter() }
            if (isWakeWord(word, customWakeWord)) {
                return i
            }
            if (PREFIX_HOTWORDS.contains(word) && i + 1 < words.size) {
                val nextWord = words[i + 1].filter { it.isLetter() }
                if (isWakeWord(nextWord, customWakeWord)) {
                    return i + 1
                }
            }
        }
        return -1
    }

    fun containsWakeWord(phrase: String, customWakeWord: String? = null): Boolean {
        return findWakeWordIndex(phrase, customWakeWord) != -1
    }

    fun stripWakeWordPrefix(fullText: String, customWakeWord: String? = null): String {
        val words = fullText.trim().split(Regex("\\s+"))
        if (words.isEmpty()) return fullText

        val firstClean = words[0].lowercase(Locale.ROOT).filter { it.isLetter() }
        if (isWakeWord(firstClean, customWakeWord)) {
            val remaining = words.drop(1).joinToString(" ").trim()
            return if (remaining.isNotEmpty()) remaining else fullText
        }

        if (PREFIX_HOTWORDS.contains(firstClean) && words.size > 1) {
            val secondClean = words[1].lowercase(Locale.ROOT).filter { it.isLetter() }
            if (isWakeWord(secondClean, customWakeWord)) {
                val remaining = words.drop(2).joinToString(" ").trim()
                return if (remaining.isNotEmpty()) remaining else fullText
            }
        }

        return fullText
    }

    fun levenshteinDistance(s1: String, s2: String): Int {
        if (kotlin.math.abs(s1.length - s2.length) > 1) return 2
        return com.jarvis.foundation.TextDistance.levenshtein(s1, s2)
    }
}
