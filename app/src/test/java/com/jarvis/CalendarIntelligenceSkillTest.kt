package com.jarvis

import com.jarvis.agent.AgentWorkingMemory
import com.jarvis.agent.skills.CalendarEvent
import com.jarvis.agent.skills.CalendarSkill
import com.jarvis.agent.skills.SkillContext
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.TimeUnit

class CalendarIntelligenceSkillTest {

    private val skill = CalendarSkill()
    private fun createContext(goal: String) = SkillContext(goal, AgentWorkingMemory(goal))

    @Test
    fun testDurationParsing() {
        assertEquals(TimeUnit.HOURS.toMillis(2), CalendarSkill.parseDurationMs("Schedule meeting for 2 hours"))
        assertEquals(TimeUnit.MINUTES.toMillis(45), CalendarSkill.parseDurationMs("Call team for 45 mins"))
        assertEquals(TimeUnit.MINUTES.toMillis(60), CalendarSkill.parseDurationMs("Sync meeting"))
    }

    @Test
    fun testTitleExtraction() {
        val title1 = CalendarSkill.extractTitle("Schedule sync meeting with Rahul tomorrow at 3 PM")
        assertTrue(title1.contains("Sync"))

        val title2 = CalendarSkill.extractTitle("kal 4 baje project discussion meeting rakho")
        assertTrue(title2.contains("Project Discussion"))
    }

    @Test
    fun testConflictDetection() {
        val now = System.currentTimeMillis()
        val oneHour = TimeUnit.HOURS.toMillis(1)

        val existing = listOf(
            CalendarEvent("1", "Team Sync", now, now + oneHour)
        )

        // Overlapping event: proposed starts 30 mins after now
        val overlapping = CalendarEvent("2", "Client Call", now + TimeUnit.MINUTES.toMillis(30), now + oneHour + TimeUnit.MINUTES.toMillis(30))
        val conflictResult = CalendarSkill.detectConflict(existing, overlapping)

        assertTrue(conflictResult.hasConflict)
        assertNotNull(conflictResult.conflictingEvent)
        assertEquals("Team Sync", conflictResult.conflictingEvent?.title)
        assertNotNull(conflictResult.suggestedFreeSlotEpoch)
        assertEquals(existing[0].endTimeEpoch, conflictResult.suggestedFreeSlotEpoch)

        // Non-overlapping event: proposed starts after existing ends
        val nonOverlapping = CalendarEvent("3", "1-on-1", now + oneHour + TimeUnit.MINUTES.toMillis(15), now + oneHour * 2)
        val cleanResult = CalendarSkill.detectConflict(existing, nonOverlapping)
        assertFalse(cleanResult.hasConflict)
    }

    @Test
    fun testCalendarSkillExecution() = runBlocking {
        val goal = "Schedule team sync meeting tomorrow at 3 PM for 2 hours"
        val context = createContext(goal)

        assertTrue(skill.canHandle(goal, context))
        val result = skill.execute(goal, context)
        assertTrue(result.handled)
        assertNotNull(result.proposedAction)

        val action = result.proposedAction!!
        assertEquals("CALENDAR_MANAGE", action.type)
        assertEquals("add_event", action.params["action"])
        assertEquals("120", action.params["durationMinutes"])
    }
}
