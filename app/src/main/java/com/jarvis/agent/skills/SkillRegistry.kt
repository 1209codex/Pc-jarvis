package com.jarvis.agent.skills

class SkillRegistry {
    private val skills = mutableListOf<Skill>()

    fun register(skill: Skill) {
        skills.removeAll { it.id == skill.id }
        skills.add(skill)
    }

    fun all(): List<Skill> = skills.toList()

    suspend fun findSkill(goal: String, context: SkillContext): Skill? {
        return skills.firstOrNull { it.canHandle(goal, context) }
    }
}
