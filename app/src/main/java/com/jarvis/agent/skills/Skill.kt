package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction
import com.jarvis.agent.AgentWorkingMemory

data class SkillContext(
    val goal: String,
    val workingMemory: AgentWorkingMemory,
    val userPreferences: Map<String, String> = emptyMap()
)

data class SkillResult(
    val handled: Boolean,
    val proposedAction: AgentAction? = null,
    val explanation: String = "",
    val candidateQueries: List<String> = emptyList()
)

interface Skill {
    val id: String
    val name: String
    val description: String
    val triggers: List<String>

    suspend fun canHandle(goal: String, context: SkillContext): Boolean
    suspend fun execute(goal: String, context: SkillContext): SkillResult
}
