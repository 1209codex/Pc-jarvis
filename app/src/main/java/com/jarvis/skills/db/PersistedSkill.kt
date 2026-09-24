package com.jarvis.skills.db

import com.jarvis.foundation.RiskLevel

enum class PersistedSkillType {
    BUILT_IN,
    DYNAMIC,
    LEARNED,
    WEBAPP
}

data class PersistedSkill(
    val id: String,
    val name: String,
    val description: String,
    val triggers: List<String>,
    val type: PersistedSkillType = PersistedSkillType.DYNAMIC,
    val version: Int = 1,
    val confidence: Double = 1.0,
    val status: String = "ACTIVE",
    val riskLevel: RiskLevel = RiskLevel.LOW,
    val configJson: String = "{}",
    val steps: List<PersistedSkillStep> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

data class PersistedSkillStep(
    val stepIndex: Int,
    val actionType: String,
    val params: Map<String, String> = emptyMap()
)

data class PersistedWebApp(
    val id: String,
    val name: String,
    val description: String,
    val path: String,
    val port: Int = 8888,
    val entryPoint: String = "index.html",
    val url: String,
    val status: String = "READY",
    val theme: String = "JARVIS_HUD",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)
