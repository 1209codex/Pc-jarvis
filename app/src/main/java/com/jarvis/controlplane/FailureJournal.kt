package com.jarvis.controlplane

import java.util.ArrayDeque

data class FailureRecord(
    val id: String = java.util.UUID.randomUUID().toString().take(8),
    val actionType: String,
    val errorMessage: String,
    val paramsSnapshot: Map<String, String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Layer 8: Failure Journal.
 *
 * Keeps an append-only journal of failed actions and signatures.
 * Enables the agent to detect chronic action failures and trigger strategy adaptation.
 */
class FailureJournal(private val maxEntries: Int = 100) {
    private val journal = ArrayDeque<FailureRecord>(maxEntries)
    private val lock = Any()

    fun recordFailure(actionType: String, errorMessage: String, params: Map<String, String>): FailureRecord {
        val record = FailureRecord(
            actionType = actionType,
            errorMessage = errorMessage,
            paramsSnapshot = params
        )
        synchronized(lock) {
            journal.addLast(record)
            while (journal.size > maxEntries) {
                journal.removeFirst()
            }
        }
        return record
    }

    fun getRecentFailures(limit: Int = 20): List<FailureRecord> = synchronized(lock) {
        journal.toList().takeLast(limit)
    }

    /**
     * Checks if a specific action has failed repeatedly within a given time window.
     */
    fun hasRepeatedFailure(actionType: String, windowMs: Long = 60_000L, threshold: Int = 3): Boolean {
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val count = journal.count {
                it.actionType.equals(actionType, ignoreCase = true) && (now - it.timestamp) <= windowMs
            }
            return count >= threshold
        }
    }

    fun clear() = synchronized(lock) {
        journal.clear()
    }

    companion object {
        val shared: FailureJournal by lazy { FailureJournal() }
    }
}
