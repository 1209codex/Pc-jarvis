package com.jarvis

import com.jarvis.memory.IMemoryStore
import com.jarvis.memory.MemoryConflictResolver
import com.jarvis.memory.MemoryGarbageCollector
import com.jarvis.memory.MemoryItem
import com.jarvis.memory.MemoryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class MemoryConflictAndGcTest {

    private class TestMemoryStore : IMemoryStore {
        private val items = mutableListOf<MemoryItem>()
        private var nextId = 1L

        override fun queryByKey(key: String, namespace: String): List<MemoryItem> {
            return items.filter { it.key == key && it.namespace == namespace && !it.archived }
                .sortedByDescending { it.version }
        }

        override fun getAllActiveMemories(namespace: String): List<MemoryItem> {
            return items.filter { it.namespace == namespace && !it.archived }
                .sortedByDescending { it.createdAt }
        }

        override fun archiveMemory(id: Long) {
            val idx = items.indexOfFirst { it.id == id }
            if (idx != -1) {
                items[idx] = items[idx].copy(archived = true)
            }
        }

        override fun saveMemoryItem(item: MemoryItem): Long {
            val id = if (item.id <= 0) nextId++ else item.id
            val saved = item.copy(id = id)
            items.add(saved)
            return id
        }

        override fun rebuildFtsIndex() {}
    }

    @Test
    fun testHalfLifeDecayForUserPreferenceVsShortTerm() {
        val store = TestMemoryStore()
        val gc = MemoryGarbageCollector(store)
        val now = System.currentTimeMillis()
        val ninetyDaysAgo = now - TimeUnit.DAYS.toMillis(90)
        val twoHoursAgo = now - TimeUnit.HOURS.toMillis(2)

        val prefItem = MemoryItem(
            type = MemoryType.USER_PREFERENCE,
            key = "fav_mode",
            content = "Dark",
            provenance = "user",
            importance = 1.0,
            createdAt = ninetyDaysAgo
        )

        val shortTermItem = MemoryItem(
            type = MemoryType.SHORT_TERM,
            key = "temp_query",
            content = "search",
            provenance = "user",
            importance = 1.0,
            createdAt = twoHoursAgo
        )

        // After 90 days (1 half life), preference importance should be ~0.5
        val prefDecayed = gc.computeDecayedImportance(prefItem, now)
        assertEquals(0.5, prefDecayed, 0.05)

        // After 2 hours (1 half life for short term), short term importance should be ~0.5
        val shortTermDecayed = gc.computeDecayedImportance(shortTermItem, now)
        assertEquals(0.5, shortTermDecayed, 0.05)
    }

    @Test
    fun testConflictResolutionLogic() {
        val store = TestMemoryStore()
        val resolver = MemoryConflictResolver(store)

        val oldMem = MemoryItem(
            type = MemoryType.USER_PREFERENCE,
            key = "model_pref",
            content = "User prefers local Ollama",
            provenance = "user",
            importance = 0.8,
            createdAt = System.currentTimeMillis() - 10000
        )
        val oldId = resolver.resolveAndStore(oldMem)
        assertTrue(oldId > 0)

        val newMem = MemoryItem(
            type = MemoryType.USER_PREFERENCE,
            key = "model_pref",
            content = "User moved to cloud inference",
            provenance = "user",
            importance = 0.9,
            createdAt = System.currentTimeMillis(),
            isConfirmed = true
        )
        val newId = resolver.resolveAndStore(newMem)
        assertTrue(newId > oldId)

        val active = store.queryByKey("model_pref")
        assertEquals(1, active.size)
        assertEquals("User moved to cloud inference", active.first().content)
        assertEquals(2, active.first().version)
        assertEquals(oldId.toString(), active.first().supersedesId)
    }
}
