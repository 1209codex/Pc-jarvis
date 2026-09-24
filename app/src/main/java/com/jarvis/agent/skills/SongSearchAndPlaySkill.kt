package com.jarvis.agent.skills

import com.jarvis.agent.AgentAction

/**
 * Skill that handles compound "search/research and play song" directives.
 * First conducts query research/planning, resolves candidate tracks and user preferences,
 * and then executes media playback with the best target track.
 */
class SongSearchAndPlaySkill : Skill {
    override val id: String = "song_search_and_play"
    override val name: String = "Song Search, Research & Play Skill"
    override val description: String = "Researches, plans, and discovers the best matching tracks based on search queries and preferences before triggering playback."

    override val triggers: List<String> = listOf(
        "search and play song", "search and play", "search and plan song", "search and plan",
        "research and play song", "reseach and play song", "research and play", "reseach and play",
        "find and play song", "find and play", "search song and play", "find song and play",
        "search karke gaana bajao", "search karke gana chalao", "gaana search karke chalao", "gana search karke bajao",
        "song research karke play karo", "pehle search karo fir gana chalao", "pehle research karo fir gana chalao",
        "search for a song and play", "search for song and play", "search for a song", "dhundo aur chalao", "dhoondo aur chalao"
    )

    override suspend fun canHandle(goal: String, context: SkillContext): Boolean {
        val lower = goal.lowercase().trim()

        // Reject website/code creation tasks even if they mention music/youtube
        val isCreation = lower.contains("website") || lower.contains("webapp") || lower.contains("web app") ||
                lower.contains("banao") || lower.contains("bana do") || lower.contains("create") ||
                lower.contains("build") || lower.contains("make") || lower.contains("code") || lower.contains("develop")
        if (isCreation) return false

        if (triggers.any { lower.contains(it) }) {
            return true
        }

        // Pattern matching for "search/research/find ... [song/gana/music] ... [play/chalao/bajao]"
        val hasSearch = lower.contains("search") || lower.contains("reseach") || lower.contains("research") ||
                lower.contains("find") || lower.contains("dhundo") || lower.contains("dhoondo") || lower.contains("khojo")
        val hasSong = lower.contains("song") || lower.contains("gaana") || lower.contains("gana") ||
                lower.contains("music") || lower.contains("track")
        val hasPlay = lower.contains("play") || lower.contains("chalao") || lower.contains("bajao") ||
                lower.contains("sunao") || lower.contains("plan")

        return hasSearch && hasSong && hasPlay
    }

    override suspend fun execute(goal: String, context: SkillContext): SkillResult {
        val lower = goal.lowercase().trim()
        val isSpotify = lower.contains("spotify")

        val rawTopic = extractSongQuery(lower)
        val prefs = context.userPreferences
        val favoriteArtist = prefs["favorite_artist"] ?: prefs["artist"]
        val favoriteGenre = prefs["favorite_genre"] ?: prefs["genre"]

        val resolvedQuery: String
        val candidateQueries: MutableList<String> = mutableListOf()

        if (rawTopic.isBlank() || rawTopic in listOf("song", "a song", "music", "gaana", "gana", "track")) {
            if (favoriteArtist != null) {
                resolvedQuery = "$favoriteArtist top hits"
                candidateQueries.add("$favoriteArtist popular tracks")
                candidateQueries.add("$favoriteArtist latest song 2026")
                candidateQueries.add("$favoriteArtist best playlist")
            } else if (favoriteGenre != null) {
                resolvedQuery = "$favoriteGenre top songs"
                candidateQueries.add("$favoriteGenre popular tracks")
                candidateQueries.add("trending $favoriteGenre music")
            } else {
                resolvedQuery = "latest trending songs 2026"
                candidateQueries.add("latest hindi trending songs 2026")
                candidateQueries.add("top global viral hits")
                candidateQueries.add("top billboard hot tracks")
            }
        } else {
            resolvedQuery = cleanTrackTitle(rawTopic)
            candidateQueries.add("$resolvedQuery official video")
            candidateQueries.add("$resolvedQuery official audio")
            candidateQueries.add("$resolvedQuery lyrics")
            if (favoriteArtist != null && !resolvedQuery.contains(favoriteArtist, ignoreCase = true)) {
                candidateQueries.add("$resolvedQuery $favoriteArtist version")
            }
        }

        val toolType = if (isSpotify) "SPOTIFY_PLAY" else "YOUTUBE_PLAY"
        val explanation = "Researched and planned playback for song: $resolvedQuery"

        return SkillResult(
            handled = true,
            proposedAction = AgentAction(
                type = toolType,
                params = mapOf("query" to resolvedQuery),
                expectedOutcome = "Playing researched song: $resolvedQuery"
            ),
            explanation = explanation,
            candidateQueries = candidateQueries.distinct()
        )
    }

    private fun extractSongQuery(text: String): String {
        var clean = text
            // Remove conversational prefixes/suffixes
            .replace(Regex("(?i)^(please\\s+)?(first\\s+)?(pehle\\s+)?(reseach|research|search|find|dhundo|dhoondo|khojo)\\s+(and|aur|fir|phir|then)?\\s*(plan|play|chalao|bajao|sunao)\\s*(a\\s+)?(song|gaana|gana|music|track)?\\s*(called|named|of|for|about|from)?"), "")
            .replace(Regex("(?i)^(please\\s+)?(reseach|research|search|find|dhundo|dhoondo|khojo)\\s+(a\\s+)?(song|gaana|gana|music|track)?\\s*(and|aur|fir|phir|then)?\\s*(plan|play|chalao|bajao|sunao)?\\s*(it|isko|use)?"), "")
            .replace(Regex("(?i)\\s+(and|aur|fir|phir|then)\\s+(plan|play|chalao|bajao|sunao)\\s*(it|isko|use)?$"), "")
            .replace(Regex("(?i)\\s+(dhundo|dhoondo|search\\s+karke|research\\s+karke)\\s+(chalao|bajao|play\\s+karo|play)$"), "")
            .replace(Regex("(?i)\\b(on\\s+youtube|on\\s+spotify|pe\\s+chalao|par\\s+chalao|pe\\s+bajao|par\\s+bajao)\\b"), "")
            .replace(Regex("\\s+"), " ")
            .trim()

        // Strip leading/trailing "song", "gaana", "gana" if present at edges
        clean = clean.replace(Regex("(?i)^(song|gaana|gana|music|track)\\s+"), "")
            .replace(Regex("(?i)\\s+(song|gaana|gana|music|track)$"), "")
            .trim()

        return clean
    }

    private fun cleanTrackTitle(topic: String): String {
        return topic
            .replace(Regex("(?i)^(play|search|find|chalao|bajao)\\s+"), "")
            .trim()
    }
}
