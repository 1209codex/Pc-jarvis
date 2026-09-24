package com.jarvis.ui.model

import java.util.UUID

enum class AuthMethod { API_KEY, OAUTH2, BEARER, BASIC }

data class ApiConfig(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New API",
    val baseUrl: String = "",
    val authMethod: AuthMethod = AuthMethod.API_KEY,
    val credentialId: String? = null,
    val apiKeyHeader: String = "Authorization",
    val active: Boolean = true,
    val timeoutMs: Long = 30_000L,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class AppSettings(
    val theme: String = "system",
    val wakeWord: String = "Jarvis",
    val preferredLlmModel: String = "openai/gpt-oss-20b",
    val continuousConversation: Boolean = true,
    val followUpTimeoutSeconds: Int = 6,
    val bargeInEnabled: Boolean = true,
    val offlineAsrPreferred: Boolean = true,
    val batteryAnnouncementsEnabled: Boolean = true,
    val proactiveCalendarAlertsEnabled: Boolean = true,
    val proactivePreBriefingMinutes: Int = 15,
    val proactiveBatteryAlertsEnabled: Boolean = true,
    val wakeSensitivity: String = "BALANCED",
    val mediaDuckingEnabled: Boolean = true,
    val mediaDuckingPercent: Int = 45,
    val mediaDuckingMusic: Boolean = true,
    val wakeAcknowledgment: String = "CHIME",
    val dailyBriefingEnabled: Boolean = true,
    val dailyBriefingTime: String = "08:00",
    val lockScreenVoiceEnabled: Boolean = true,
    val whatsAppAutoReturnEnabled: Boolean = true,
    val acousticFeedbackEnabled: Boolean = true,
    val hapticFeedbackEnabled: Boolean = true,
    val bluetoothScoRoutingEnabled: Boolean = true,
    val headsetHookActivationEnabled: Boolean = true,
    val wearableSyncEnabled: Boolean = true,
    val floatingOverlayEnabled: Boolean = false,
    val ttsProvider: String = "groq",
    val ttsVoice: String = "Fritz-PlayAI",
    val callScreeningEnabled: Boolean = true,
    val clipboardIntelligenceEnabled: Boolean = true,
    val deviceUnlockPin: String = "",
    val autoLearnEnabled: Boolean = true,
    val ownerVoiceWakeEnabled: Boolean = false
)

fun String.safeDisplay(): String = replace(Regex("[\\r\\n\\t]"), " ").trim()
