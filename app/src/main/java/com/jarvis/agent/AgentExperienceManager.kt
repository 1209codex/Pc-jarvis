package com.jarvis.agent

import android.util.Log
import com.jarvis.foundation.AgentExperienceRecord
import com.jarvis.foundation.TaskStateManager
import com.jarvis.memory.AugmentedMemoryPipeline

data class Experience(
    val taskType: String,
    val goalPattern: String,
    val strategy: String,
    val action: String? = null,
    val result: String,
    val success: Boolean,
    val feedback: String? = null
)

class AgentExperienceManager(
    private val taskStateManager: TaskStateManager,
    private val memoryPipeline: AugmentedMemoryPipeline
) {
    private val TAG = "AgentExperienceManager"

    fun recordExperience(experience: Experience): Long {
        Log.i(TAG, "Recording experience for [${experience.taskType}] goal='${experience.goalPattern}' success=${experience.success}")
        val id = taskStateManager.saveExperience(
            taskType = experience.taskType,
            goalPattern = experience.goalPattern,
            strategy = experience.strategy,
            action = experience.action,
            result = experience.result,
            success = experience.success,
            feedback = experience.feedback
        )

        // Memory promotion rule: promote explicit preferences, corrections, or successful recurring strategies
        if (experience.success && (experience.feedback != null || experience.goalPattern.contains("favorite") || experience.goalPattern.contains("prefer"))) {
            promoteToDurableMemory(experience)
        }

        return id
    }

    /**
     * Explicit positive user feedback ("haan ye accha tha").
     * Only promoted to long-term memory when the utterance carries an identifiable preference.
     */
    fun recordPositiveFeedback(goalPattern: String, strategy: String, taskType: String, preference: String? = null) {
        recordExperience(
            Experience(
                taskType = taskType,
                goalPattern = goalPattern,
                strategy = strategy,
                result = "User indicated positive feedback",
                success = true,
                feedback = "positive"
            )
        )
        if (preference != null && preference.isNotBlank()) {
            memoryPipeline.rememberFact(
                key = "${taskType.lowercase()}_preference",
                value = preference,
                provenance = "user_feedback",
                importance = 0.8
            )
            Log.i(TAG, "Promoted explicit positive feedback to MAG: $preference")
        }
    }

    /**
     * Explicit negative user feedback ("ye video accha nahi hai").
     * Stored as a failed experience so the same selection is not repeated verbatim.
     */
    fun recordNegativeFeedback(goalPattern: String, strategy: String, taskType: String, correction: String) {
        recordExperience(
            Experience(
                taskType = taskType,
                goalPattern = goalPattern,
                strategy = strategy,
                result = "User rejected this selection",
                success = false,
                feedback = "negative: $correction"
            )
        )
        Log.i(TAG, "Recorded negative feedback for goal='$goalPattern': $correction")
    }

    /**
     * User correction ("change karo", "ye pasand nahi") — negative feedback with the correction noted.
     */
    fun recordCorrection(goalPattern: String, correction: String, taskType: String = "MEDIA") {
        recordNegativeFeedback(goalPattern, "User correction applied", taskType, correction)
    }

    /**
     * Retrieval: exact LIKE match first; if empty, fall back to normalized
     * token/entity overlap against recent experiences (not raw full-goal LIKE only).
     */
    fun findRelevantExperience(goal: String): List<Experience> {
        val records = taskStateManager.findExperiences(goal, limit = 3)
        if (records.isNotEmpty()) {
            return records.map { it.toExperience() }
        }

        val goalTokens = normalize(goal).toSet()
        if (goalTokens.isEmpty()) return emptyList()

        val scored = taskStateManager.findRecentExperiences(limit = 50).mapNotNull { rec ->
            val recTokens = normalize(rec.goalPattern).toSet()
            val overlap = (goalTokens intersect recTokens).size
            if (overlap > 0) overlap to rec else null
        }
        return scored.sortedByDescending { it.first }
            .take(3)
            .map { it.second.toExperience() }
    }

    private fun normalize(text: String): List<String> {
        val stopwords = setOf(
            "ko", "mera", "mere", "mujhe", "ek", "aur", "hai", "hain", "kar", "karo", "chalao",
            "bajao", "sunao", "please", "the", "a", "an", "for", "me", "my", "of", "to",
            "video", "song", "gana", "chahiye", "bhejo", "kholo", "open", "play"
        )
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() && it.length > 2 && it !in stopwords }
    }

    private fun AgentExperienceRecord.toExperience() = Experience(
        taskType = taskType,
        goalPattern = goalPattern,
        strategy = strategy,
        action = action,
        result = result,
        success = success,
        feedback = feedback
    )

    private fun promoteToDurableMemory(experience: Experience) {
        val key = "${experience.taskType.lowercase()}_preference"
        val content = "When '${experience.goalPattern}': ${experience.strategy} (result=${experience.result})"
        memoryPipeline.rememberFact(
            key = key,
            value = content,
            provenance = "agent_experience",
            importance = 0.8
        )
        Log.i(TAG, "Promoted experience to MAG durable memory: $key -> $content")
    }
}