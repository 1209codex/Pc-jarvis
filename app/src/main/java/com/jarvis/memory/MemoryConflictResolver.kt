package com.jarvis.memory

/**
 * Resolves memory conflicts by versioning and superseding previous entries with the same key.
 */
class MemoryConflictResolver(private val store: IMemoryStore) {

    fun resolveAndStore(item: MemoryItem): Long {
        val existing = store.queryByKey(item.key, item.namespace)
        if (existing.isNotEmpty()) {
            val latest = existing.maxByOrNull { it.version } ?: existing.first()
            existing.forEach { store.archiveMemory(it.id) }
            val newItem = item.copy(
                version = latest.version + 1,
                supersedesId = latest.id.toString()
            )
            return store.saveMemoryItem(newItem)
        }
        return store.saveMemoryItem(item)
    }
}
