package com.jarvis.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import java.util.Locale

data class AppPermissionAudit(
    val packageName: String,
    val appName: String,
    val isSystemApp: Boolean,
    val hasCamera: Boolean,
    val hasMic: Boolean,
    val hasLocation: Boolean,
    val hasSmsContacts: Boolean
)

data class PhishingAnalysisResult(
    val isSuspicious: Boolean,
    val threatLevel: String, // "SAFE", "SUSPICIOUS", "HIGH_RISK"
    val riskFactors: List<String>
)

/**
 * Production SecurityAuditor inspecting device permissions and analyzing phishing risks.
 * Does NOT embed synthetic mock packages in production when Context is null.
 */
open class SecurityAuditor(
    private val context: Context? = null,
    private val testAudits: List<AppPermissionAudit>? = null
) {

    open fun auditInstalledApps(): List<AppPermissionAudit> {
        if (testAudits != null) return testAudits
        val ctx = context ?: return emptyList()
        val pm = ctx.packageManager
        val packages = pm.getInstalledPackages(PackageManager.GET_PERMISSIONS)
        val audits = mutableListOf<AppPermissionAudit>()

        for (pkg in packages) {
            val appInfo = pkg.applicationInfo ?: continue
            val isSystem = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            val appName = pm.getApplicationLabel(appInfo).toString()

            val perms = pkg.requestedPermissions ?: emptyArray()
            val hasCamera = perms.contains(android.Manifest.permission.CAMERA)
            val hasMic = perms.contains(android.Manifest.permission.RECORD_AUDIO)
            val hasLocation = perms.contains(android.Manifest.permission.ACCESS_FINE_LOCATION) || perms.contains(android.Manifest.permission.ACCESS_COARSE_LOCATION)
            val hasSmsContacts = perms.contains(android.Manifest.permission.READ_SMS) ||
                    perms.contains(android.Manifest.permission.SEND_SMS) ||
                    perms.contains(android.Manifest.permission.READ_CONTACTS)

            if (hasCamera || hasMic || hasLocation || hasSmsContacts) {
                audits.add(
                    AppPermissionAudit(
                        packageName = pkg.packageName,
                        appName = appName,
                        isSystemApp = isSystem,
                        hasCamera = hasCamera,
                        hasMic = hasMic,
                        hasLocation = hasLocation,
                        hasSmsContacts = hasSmsContacts
                    )
                )
            }
        }
        return audits
    }

    open fun getCameraAndMicApps(): List<AppPermissionAudit> {
        return auditInstalledApps().filter { (it.hasCamera || it.hasMic) && !it.isSystemApp }
    }

    open fun analyzeMessageSecurity(content: String): PhishingAnalysisResult {
        val lower = content.lowercase(Locale.ROOT)
        val factors = mutableListOf<String>()

        // 1. Phishing / Scam Keywords
        val urgentKeywords = listOf("urgent", "immediately", "blocked", "suspended", "kyc", "lottery", "won", "expire", "verification")
        val matchedUrgent = urgentKeywords.filter { lower.contains(it) }
        if (matchedUrgent.isNotEmpty()) {
            factors.add("High-pressure urgency terms: ${matchedUrgent.joinToString(", ")}")
        }

        // 2. Sensitive Credential / OTP Traps
        if (lower.contains("otp") || lower.contains("pin") || lower.contains("cvv") || lower.contains("password")) {
            factors.add("Requests sensitive auth tokens (OTP/PIN/Password)")
        }

        // 3. Suspicious Links / IP Addresses
        val hasIpUrl = Regex("http[s]?://\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}").containsMatchIn(lower)
        val hasSuspiciousTld = Regex("http[s]?://[a-zA-Z0-9.-]+\\.(xyz|top|work|click|tk|gq|cf)/").containsMatchIn(lower)
        val hasShortener = Regex("http[s]?://(bit\\.ly|tinyurl\\.com|is\\.gd|t\\.co)/").containsMatchIn(lower)

        if (hasIpUrl) factors.add("Direct IP address URL detected")
        if (hasSuspiciousTld) factors.add("Suspicious top-level domain detected")
        if (hasShortener) factors.add("Shortened URL hiding real destination")

        val threatLevel = when {
            factors.size >= 2 -> "HIGH_RISK"
            factors.isNotEmpty() -> "SUSPICIOUS"
            else -> "SAFE"
        }

        return PhishingAnalysisResult(
            isSuspicious = threatLevel != "SAFE",
            threatLevel = threatLevel,
            riskFactors = factors
        )
    }

    open fun calculatePrivacyScore(): Int {
        val audits = auditInstalledApps()
        val nonSystemHighRisk = audits.count { !it.isSystemApp && (it.hasCamera && it.hasMic && it.hasLocation) }
        var score = 100 - (nonSystemHighRisk * 5)
        if (score < 40) score = 40
        return score
    }

    companion object {
        fun createFake(audits: List<AppPermissionAudit> = defaultFakeAudits()): FakeSecurityAuditor =
            FakeSecurityAuditor(audits)

        fun defaultFakeAudits(): List<AppPermissionAudit> = listOf(
            AppPermissionAudit("com.whatsapp", "WhatsApp", false, hasCamera = true, hasMic = true, hasLocation = true, hasSmsContacts = true),
            AppPermissionAudit("com.instagram.android", "Instagram", false, hasCamera = true, hasMic = true, hasLocation = true, hasSmsContacts = true),
            AppPermissionAudit("com.google.android.youtube", "YouTube", false, hasCamera = true, hasMic = true, hasLocation = false, hasSmsContacts = false)
        )
    }
}

/**
 * Dedicated test implementation of SecurityAuditor for deterministic JVM testing.
 */
class FakeSecurityAuditor(
    customAudits: List<AppPermissionAudit> = SecurityAuditor.defaultFakeAudits()
) : SecurityAuditor(context = null, testAudits = customAudits)
