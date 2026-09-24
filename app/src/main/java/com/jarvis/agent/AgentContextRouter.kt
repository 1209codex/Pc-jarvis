package com.jarvis.agent

import com.jarvis.foundation.worldstate.StateProvider
import com.jarvis.memory.AugmentedMemoryPipeline
import com.jarvis.retrieval.engine.RaphaelRetrievalManager
import com.jarvis.ai.Message
import com.jarvis.runtime.MemoryNamespaces
import java.util.Locale

enum class ContextNeed {
    NONE,
    CONVERSATION,
    MEMORY,
    KNOWLEDGE_VAULT,
    DEVICE_STATE
}

class AgentContextRouter(
    private val memoryPipeline: AugmentedMemoryPipeline? = null,
    private val ragInjector: com.jarvis.rag.RagContextInjector? = null,
    private val stateProvider: StateProvider? = null,
    private val raphaelRetrievalManager: RaphaelRetrievalManager? = null
) {
    fun routeNeeds(goal: String): Set<ContextNeed> {
        val lower = goal.trim().lowercase(Locale.ROOT)
        val needs = mutableSetOf<ContextNeed>()

        // 1. Explicit memory references
        if (lower.contains("favorite") || lower.contains("favourite") || lower.contains("mera") ||
            lower.contains("yaad") || lower.contains("preference") || lower.contains("remember") ||
            lower.contains("badiya") || lower.contains("mujhe pasand")
        ) {
            needs.add(ContextNeed.MEMORY)
        }

        // 2. Previous context / references ("wahi", "again", "phir se", "usko", "chat", "history", "previous", "baat", "pehle")
        if (lower.contains("wahi") || lower.contains("again") || lower.contains("phir") ||
            lower.contains("it") || lower.contains("that song") || lower.contains("last") ||
            lower.contains("chat") || lower.contains("chats") || lower.contains("history") ||
            lower.contains("previous") || lower.contains("baat") || lower.contains("pehle") || lower.contains("purani")
        ) {
            needs.add(ContextNeed.CONVERSATION)
        }

        // 3. Document / Knowledge Vault references
        if (lower.contains("document") || lower.contains("doc") || lower.contains("notes") ||
            lower.contains("manual") || lower.contains("guide") || lower.contains("pdf") ||
            lower.contains("knowledge") || lower.contains("vault") || lower.contains("specs") ||
            lower.contains("report") || lower.contains("file") || lower.contains("kisme")
        ) {
            needs.add(ContextNeed.KNOWLEDGE_VAULT)
        }

        // 4. Current device/ambient state references
        if (lower.contains("battery") || lower.contains("status") || lower.contains("notification") ||
            lower.contains("media") || lower.contains("playing") || lower.contains("kya chal") ||
            lower.contains("device state")
        ) {
            needs.add(ContextNeed.DEVICE_STATE)
        }

        if (needs.isEmpty()) {
            needs.add(ContextNeed.NONE)
        }

        return needs
    }

    fun buildContext(
        goal: String,
        state: AgentState,
        workingMemory: AgentWorkingMemory,
        history: List<Message> = emptyList()
    ): String {
        // If RaphaelRetrievalManager is available, leverage intelligent multi-domain retrieval & re-ranking
        raphaelRetrievalManager?.let { manager ->
            val systemOverview = stateProvider?.snapshot()?.overview() ?: ""
            val intelligentContext = manager.retrieve(
                query = goal,
                stateContext = systemOverview,
                activeTask = state.goal
            )
            if (intelligentContext.isNotBlank()) {
                val formattedHistory = if (history.isNotEmpty()) {
                    "\nRECENT CONVERSATION HISTORY:\n" + history.takeLast(6).joinToString("\n") { "${it.role.uppercase()}: ${it.content}" }
                } else ""
                return intelligentContext + formattedHistory
            }
        }

        val needs = routeNeeds(goal)
        if (needs.contains(ContextNeed.NONE) && needs.size == 1 && history.isEmpty()) {
            return ""
        }

        val sb = StringBuilder()

        if (needs.contains(ContextNeed.MEMORY)) {
            val memories = memoryPipeline?.retrieveMemory(goal, limit = 4) ?: emptyList()
            if (memories.isNotEmpty()) {
                sb.append("USER MEMORY & PREFERENCES:\n")
                memories.forEach { item ->
                    sb.append("- ${item.key}: ${item.content}\n")
                }
            }
        }

        if (needs.contains(ContextNeed.CONVERSATION) || history.isNotEmpty()) {
            val formattedHistory = if (history.isNotEmpty()) {
                history.takeLast(6).joinToString("\n") { "${it.role.uppercase()}: ${it.content}" }
            } else ""
            val memoryHistoryContext = memoryPipeline?.build(
                currentRequest = goal,
                history = history,
                sessionId = MemoryNamespaces.CONVERSATION,
                ragLimit = 3,
                magLimit = 3
            )?.formatted ?: ""
            val fullHistory = listOf(formattedHistory, memoryHistoryContext).filter { it.isNotBlank() }.joinToString("\n")
            if (fullHistory.isNotBlank()) {
                sb.append("\nRECENT CONVERSATION HISTORY & CHAT CONTEXT:\n$fullHistory\n")
            }
        }

        if (needs.contains(ContextNeed.KNOWLEDGE_VAULT)) {
            val ragContext = ragInjector?.injectContext(goal, limit = 3) ?: ""
            if (ragContext.isNotBlank()) {
                sb.append("\n$ragContext\n")
            }
        }

        if (needs.contains(ContextNeed.DEVICE_STATE)) {
            stateProvider?.let { provider ->
                sb.append("\nDEVICE STATE (from World State): ${provider.snapshot().overview()}\n")
            }
        }

        if (workingMemory.facts.isNotEmpty()) {
            sb.append("\nWORKING FACTS:\n")
            workingMemory.facts.forEach { (k, v) -> sb.append("- $k: $v\n") }
        }

        if (workingMemory.candidates.isNotEmpty()) {
            sb.append("\nCANDIDATES IDENTIFIED:\n")
            workingMemory.candidates.take(5).forEachIndexed { i, c -> sb.append("[$i] $c\n") }
        }

        return sb.toString().trim()
    }
}
