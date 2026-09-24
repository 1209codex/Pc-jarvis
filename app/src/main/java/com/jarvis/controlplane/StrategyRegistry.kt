package com.jarvis.controlplane

import java.util.concurrent.ConcurrentHashMap

data class ExecutionStrategy(
    val id: String,
    val domain: String,
    val strategyName: String,
    var weight: Float = 1.0f,
    var successCount: Int = 0,
    var failureCount: Int = 0
)

/**
 * Layer 9: Strategy Registry & Self-Learning Adaptation.
 *
 * Tracks alternative execution paths (e.g., notification remote-input vs accessibility vs in-app macro)
 * and promotes resilient strategies based on empirical success rates.
 */
class StrategyRegistry {
    private val strategies = ConcurrentHashMap<String, ExecutionStrategy>()

    init {
        // Seed default messaging strategies
        registerStrategy(ExecutionStrategy("strat_wa_remote_input", "WHATSAPP", "NotificationRemoteInput", weight = 2.0f))
        registerStrategy(ExecutionStrategy("strat_wa_accessibility_send", "WHATSAPP", "AccessibilitySendButton", weight = 1.5f))
        registerStrategy(ExecutionStrategy("strat_wa_search_macro", "WHATSAPP", "InAppSearchFallback", weight = 1.0f))
    }

    fun registerStrategy(strategy: ExecutionStrategy) {
        strategies[strategy.id] = strategy
    }

    fun getBestStrategy(domain: String): ExecutionStrategy? {
        return strategies.values
            .filter { it.domain.equals(domain, ignoreCase = true) }
            .maxByOrNull { it.weight }
    }

    fun recordOutcome(strategyId: String, success: Boolean) {
        val strat = strategies[strategyId] ?: return
        if (success) {
            strat.successCount++
            strat.weight = (strat.weight * 1.1f).coerceAtMost(10.0f)
        } else {
            strat.failureCount++
            strat.weight = (strat.weight * 0.8f).coerceAtLeast(0.1f)
        }
    }

    fun listStrategies(domain: String? = null): List<ExecutionStrategy> {
        val list = if (domain == null) strategies.values.toList() else strategies.values.filter { it.domain.equals(domain, ignoreCase = true) }
        return list.sortedByDescending { it.weight }
    }

    companion object {
        val shared: StrategyRegistry by lazy { StrategyRegistry() }
    }
}
