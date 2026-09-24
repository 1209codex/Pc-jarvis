package com.jarvis.retrieval.engine

import com.jarvis.retrieval.model.CandidateMetadata
import com.jarvis.retrieval.model.CandidateSource

class CandidateFusionEngine {

    fun fuse(
        ragCandidates: List<CandidateMetadata> = emptyList(),
        magCandidates: List<CandidateMetadata> = emptyList(),
        cagCandidates: List<CandidateMetadata> = emptyList(),
        lexicalCandidates: List<CandidateMetadata> = emptyList()
    ): List<CandidateMetadata> {
        val merged = mutableListOf<CandidateMetadata>()
        merged.addAll(ragCandidates)
        merged.addAll(magCandidates)
        merged.addAll(cagCandidates)
        merged.addAll(lexicalCandidates)

        if (merged.isEmpty()) return emptyList()

        // Deduplicate candidates by unique ID or matching content
        val uniqueMap = mutableMapOf<String, CandidateMetadata>()
        for (c in merged) {
            val key = if (c.id.isNotBlank()) c.id else c.text.trim().lowercase()
            val existing = uniqueMap[key]
            if (existing == null) {
                uniqueMap[key] = c
            } else {
                // Fuse metadata scores from multiple sources
                val fused = existing.copy(
                    denseScore = kotlin.math.max(existing.denseScore, c.denseScore),
                    lexicalScore = kotlin.math.max(existing.lexicalScore, c.lexicalScore),
                    importanceScore = kotlin.math.max(existing.importanceScore, c.importanceScore),
                    sourcePriority = kotlin.math.max(existing.sourcePriority, c.sourcePriority)
                )
                uniqueMap[key] = fused
            }
        }

        return uniqueMap.values.toList()
    }
}
