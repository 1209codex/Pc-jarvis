package com.jarvis.foundation

/**
 * Shared secret/credential term list used both by the model router (never
 * send private requests to cloud) and the memory store (never persist secrets).
 */
object SensitiveTerms {
    val TERMS = listOf(
        "password", "otp", "aadhaar", "pan card", "pan number", "cvv",
        "upi pin", "credit card", "debit card", "bank pin", "passport",
        "medical record", "private key", "credential", "secret code",
        "two-factor", "2fa", "sbi pin"
    )

    fun contains(text: String): Boolean {
        val low = text.lowercase()
        return TERMS.any { low.contains(it) }
    }
}