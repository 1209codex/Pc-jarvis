package com.jarvis

import com.jarvis.skills.db.PersistedSkill
import com.jarvis.skills.db.PersistedSkillStep
import org.junit.Assert.*
import org.junit.Test

class BluetoothWebAppStitchSkillsTest {

    @Test
    fun testPersistedSkillDataModels() {
        val skill = PersistedSkill(
            id = "test_custom_skill",
            name = "Custom Test Skill",
            description = "Executes custom multi-step task",
            triggers = listOf("run custom task", "test custom"),
            steps = listOf(
                PersistedSkillStep(0, "DEVICE_SETTINGS", mapOf("action" to "volume", "volume" to "80")),
                PersistedSkillStep(1, "SPEAK", mapOf("text" to "Volume configured"))
            )
        )

        assertEquals("test_custom_skill", skill.id)
        assertEquals(2, skill.steps.size)
        assertEquals("DEVICE_SETTINGS", skill.steps[0].actionType)
        assertEquals("SPEAK", skill.steps[1].actionType)
    }
}
