package com.jarvis.memory

import java.util.concurrent.TimeUnit

class MemoryGarbageCollector(private val memoryStore: IMemoryStore) {

    fun computeDecayedImportance(item: MemoryItem, now: Long = System.currentTimeMillis()): Double {
        val ageMs = (now - item.createdAt).coerceAtLeast(0)
        val halfLifeMs: Long = when (item.type) {
            MemoryType.USER_PREFERENCE, MemoryType.LEARNED_PATTERN -> TimeUnit.DAYS.toMillis(90)
            MemoryType.STRUCTURED_FACT -> TimeUnit.DAYS.toMillis(180)
            MemoryType.TASK_ARTIFACT -> TimeUnit.DAYS.toMillis(14)
            MemoryType.WORKING_MEMORY -> TimeUnit.HOURS.toMillis(24)
            MemoryType.SHORT_TERM, MemoryType.DOCUMENT_SNIPPET -> TimeUnit.HOURS.toMillis(2)
        }

        val numHalfLives = ageMs.toDouble() / halfLifeMs.toDouble()
        return item.importance * Math.pow(0.5, numHalfLives)
    }

    fun runGarbageCollection(now: Long = System.currentTimeMillis()): Int {
        val activeMemories = memoryStore.getAllActiveMemories()
        var archivedCount = 0

        for (mem in activeMemories) {
            val decayed = computeDecayedImportance(mem, now)
            val isExpired = mem.expiresAt != null && now > mem.expiresAt

            if (isExpired || (decayed < 0.05 && !mem.isConfirmed)) {
                memoryStore.archiveMemory(mem.id)
                archivedCount++
            }
        }

        if (archivedCount > 0) {
            memoryStore.rebuildFtsIndex()
        }

        return archivedCount
    }
}
