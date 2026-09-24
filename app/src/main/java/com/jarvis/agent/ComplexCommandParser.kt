package com.jarvis.agent

import java.util.Locale

/**
 * Condition operator for conditional command clauses.
 */
enum class ConditionOperator {
    EQUALS,
    LESS_THAN,
    GREATER_THAN,
    CONTAINS,
    IS_ON,
    IS_OFF,
    IS_CONNECTED,
    IS_LOW,
    IS_HIGH
}

/**
 * Parsed conditional predicate (e.g., "battery is low", "battery < 20%", "wifi is connected").
 */
data class CommandCondition(
    val rawCondition: String,
    val variable: String,
    val operator: ConditionOperator,
    val targetValue: String
)

/**
 * Represents a single parsed step or segment inside a complex/long command.
 */
data class CommandSegment(
    val index: Int,
    val text: String,
    val actionTypeGuess: String? = null,
    val condition: CommandCondition? = null,
    val entityRefs: Map<String, String> = emptyMap(),
    val isElseBranch: Boolean = false
)

/**
 * Full structural analysis of a long/complex multi-clause user command.
 */
data class ParsedComplexCommand(
    val originalUtterance: String,
    val wordCount: Int,
    val isLongCommand: Boolean,
    val segments: List<CommandSegment>,
    val hasConditionals: Boolean,
    val isSkillTeachingIntent: Boolean = false,
    val proposedSkillName: String? = null,
    val suggestedTriggers: List<String> = emptyList()
)

/**
 * Deep Natural Language Understanding & Complex Command Parser.
 *
 * Specializes in understanding >10 word multi-clause voice inputs in English, Hindi, and Hinglish.
 * Extracts conditional triggers (IF-THEN-ELSE), sequential stages, coreference entities, and
 * novel skill learning requests.
 */
object ComplexCommandParser {

    private val SKILL_CREATION_REGEX = Regex(
        "(?:(?:teach|learn|create|add|save|make|register)(?:\\s+(?:jarvis|me|assistant))?\\s+(?:a\\s+)?(?:new\\s+)?(?:brain\\s+)?(?:skill|routine|workflow|shortcut)\\s*(?:called|named|to|for)?\\s*[:\"']?\\s*([a-zA-Z0-9_ ]+?)(?:[\"']|\\s+that|\\s+which|\\s+to|\\s*:\\s*|\\s+jab|\\s+when|$))|" +
        "(?:(?:ek\\s+)?(?:naya|naye)\\s+(?:skill|routine|workflow)\\s+(?:sikho|seekho|banao|add\\s+karo)\\s*(?:jiska\\s+naam\\s+)?([a-zA-Z0-9_ ]+)?)|" +
        "(?:(?:whenever|jab\\s+bhi)\\s+i\\s+say\\s+[\"']?([^\"',]+)[\"']?\\s*(?:,|then|to|tab)\\s*(.+))",
        RegexOption.IGNORE_CASE
    )

    private val TRANSITION_SPLIT_REGEX = Regex(
        "(?:\\s+(?:and\\s+then|aur\\s+fir|aur\\s+phir|uske\\s+baad|uske\\s+bad|ke\\s+baad|ke\\s+bad|after\\s+that|tathapashchat|afterwards)\\s+)" +
        "|(?:\\s+(?:and|aur|fir|phir|also|plus|&)\\s+)" +
        "|(?:\\s*;\\s*)" +
        "|(?:,\\s*(?=(?:first|pehle|then|fir|phir|next|after|finally|open|launch|kholo|play|chalao|bajao|turn|torch|flashlight|stop|pause|band|search|google|call|sms|scroll|swipe|read|mode|driving|meeting|focus|night|workout|autopilot|wifi|bluetooth|volume|mute|unmute|check|dekho|batao|if|agar|jab|warna|otherwise|nahi\\s+to)))",
        RegexOption.IGNORE_CASE
    )

    private val CONDITIONAL_IF_REGEX = Regex(
        "^(?:if|agar|jab|when|in\\s+case)\\s+(.+?)\\s+(?:then|to|tab|so)\\s+(.+?)(?:\\s+(?:else|otherwise|warna|nahi\\s+to)\\s+(.+))?$",
        RegexOption.IGNORE_CASE
    )

    private val CONDITIONAL_ELSE_REGEX = Regex(
        "^(?:else|otherwise|warna|nahi\\s+to)\\s+(.+)$",
        RegexOption.IGNORE_CASE
    )

    /**
     * Parses any user command, with special high-fidelity multi-stage extraction for long (>10 words) commands.
     */
    fun parse(utterance: String): ParsedComplexCommand {
        val trimmed = utterance.trim()
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotBlank() }
        val wordCount = words.size
        val isLong = wordCount >= 10

        // Check for explicit skill creation / teaching intention
        val skillMatch = SKILL_CREATION_REGEX.find(trimmed)
        val isSkillTeaching = skillMatch != null
        val proposedSkillName = if (isSkillTeaching) {
            val nameCandidate = skillMatch?.groupValues?.drop(1)?.firstOrNull { it.isNotBlank() }?.trim()
            nameCandidate?.take(30)
        } else null

        // Check for top-level conditional construct: "If X then Y else Z"
        val topConditionalMatch = CONDITIONAL_IF_REGEX.find(trimmed)
        if (topConditionalMatch != null) {
            val rawCond = topConditionalMatch.groupValues[1].trim()
            val thenBranch = topConditionalMatch.groupValues[2].trim()
            val elseBranch = topConditionalMatch.groupValues.getOrNull(3)?.trim().orEmpty()

            val condition = parseCondition(rawCond)
            val segments = mutableListOf<CommandSegment>()

            // Then-branch segments
            val thenSubParts = splitClauses(thenBranch)
            thenSubParts.forEachIndexed { idx, part ->
                segments.add(
                    CommandSegment(
                        index = idx,
                        text = part,
                        actionTypeGuess = guessActionType(part),
                        condition = if (idx == 0) condition else null,
                        entityRefs = extractEntityRefs(part),
                        isElseBranch = false
                    )
                )
            }

            // Else-branch segments
            if (elseBranch.isNotBlank()) {
                val elseSubParts = splitClauses(elseBranch)
                val baseIdx = segments.size
                elseSubParts.forEachIndexed { idx, part ->
                    segments.add(
                        CommandSegment(
                            index = baseIdx + idx,
                            text = part,
                            actionTypeGuess = guessActionType(part),
                            condition = null,
                            entityRefs = extractEntityRefs(part),
                            isElseBranch = true
                        )
                    )
                }
            }

            return ParsedComplexCommand(
                originalUtterance = trimmed,
                wordCount = wordCount,
                isLongCommand = isLong,
                segments = segments,
                hasConditionals = true,
                isSkillTeachingIntent = isSkillTeaching,
                proposedSkillName = proposedSkillName,
                suggestedTriggers = listOf(trimmed.lowercase(Locale.ROOT))
            )
        }

        // Clause decomposition for sequential multi-task long commands
        val rawClauses = splitClauses(trimmed)
        val segments = mutableListOf<CommandSegment>()
        var lastEntity: String? = null
        var hasAnyCondition = false

        rawClauses.forEachIndexed { idx, clause ->
            var textToUse = clause
            var segmentCond: CommandCondition? = null
            var isElse = false

            // Check if clause starts with conditional
            val clauseCondMatch = CONDITIONAL_IF_REGEX.find(clause)
            if (clauseCondMatch != null) {
                hasAnyCondition = true
                segmentCond = parseCondition(clauseCondMatch.groupValues[1].trim())
                textToUse = clauseCondMatch.groupValues[2].trim()
            } else {
                val elseMatch = CONDITIONAL_ELSE_REGEX.find(clause)
                if (elseMatch != null) {
                    hasAnyCondition = true
                    isElse = true
                    textToUse = elseMatch.groupValues[1].trim()
                }
            }

            // Coreference & pronoun resolution
            val entityMap = extractEntityRefs(textToUse).toMutableMap()
            val mentionsPronoun = textToUse.contains(Regex("\\b(it|that|this|isko|use|usse)\\b", RegexOption.IGNORE_CASE))
            if (mentionsPronoun && lastEntity != null) {
                entityMap["target_coreference"] = lastEntity!!
            }

            // Update last seen salient entity for future steps
            val salient = extractSalientEntity(textToUse)
            if (salient != null) {
                lastEntity = salient
                entityMap["extracted_entity"] = salient
            }

            segments.add(
                CommandSegment(
                    index = idx,
                    text = textToUse,
                    actionTypeGuess = guessActionType(textToUse),
                    condition = segmentCond,
                    entityRefs = entityMap,
                    isElseBranch = isElse
                )
            )
        }

        return ParsedComplexCommand(
            originalUtterance = trimmed,
            wordCount = wordCount,
            isLongCommand = isLong,
            segments = segments,
            hasConditionals = hasAnyCondition,
            isSkillTeachingIntent = isSkillTeaching,
            proposedSkillName = proposedSkillName,
            suggestedTriggers = listOf(trimmed.lowercase(Locale.ROOT))
        )
    }

    /**
     * Splits a compound utterance into distinct sequential action clauses.
     */
    private fun splitClauses(text: String): List<String> {
        val lower = text.lowercase(Locale.ROOT)
        // Keep unified music searches intact
        if (lower.contains("search and play") || lower.contains("research and play") ||
            lower.contains("find and play") || lower.contains("search song and play") ||
            lower.contains("dhundo aur chalao")
        ) {
            return listOf(text)
        }

        val parts = text.split(TRANSITION_SPLIT_REGEX)
            .map { it.trim().removePrefix("first ").removePrefix("pehle ").removePrefix("then ").removePrefix("fir ") }
            .filter { it.isNotBlank() }

        if (parts.size <= 1) return listOf(text)

        // Only split if all parts contain actual command action verbs (not mere descriptive noun phrases or prepositional objects)
        val isMeaningfullyActionableSplit = parts.size > 1 &&
            MultiTaskDecomposer.hasActionVerb(parts.first()) &&
            parts.drop(1).all { MultiTaskDecomposer.hasActionVerb(it) }
        if (!isMeaningfullyActionableSplit) {
            return listOf(text)
        }

        return parts
    }

    /**
     * Parses a condition string (e.g. "battery is low", "battery is less than 20", "wifi is off").
     */
    fun parseCondition(condText: String): CommandCondition {
        val lower = condText.lowercase(Locale.ROOT).trim()

        return when {
            lower.contains("battery") && (lower.contains("low") || lower.contains("kam") || lower.contains("<") || lower.contains("less")) -> {
                val num = Regex("\\d+").find(lower)?.value ?: "20"
                CommandCondition(condText, "battery", ConditionOperator.IS_LOW, num)
            }
            lower.contains("battery") && (lower.contains("high") || lower.contains("full") || lower.contains(">") || lower.contains("greater")) -> {
                val num = Regex("\\d+").find(lower)?.value ?: "80"
                CommandCondition(condText, "battery", ConditionOperator.IS_HIGH, num)
            }
            lower.contains("wifi") && (lower.contains("off") || lower.contains("band") || lower.contains("disconnected")) -> {
                CommandCondition(condText, "wifi", ConditionOperator.IS_OFF, "false")
            }
            lower.contains("wifi") && (lower.contains("on") || lower.contains("connected") || lower.contains("chalu")) -> {
                CommandCondition(condText, "wifi", ConditionOperator.IS_ON, "true")
            }
            lower.contains("bluetooth") && (lower.contains("connected") || lower.contains("on")) -> {
                CommandCondition(condText, "bluetooth", ConditionOperator.IS_CONNECTED, "true")
            }
            else -> {
                CommandCondition(condText, "general", ConditionOperator.EQUALS, "true")
            }
        }
    }

    /**
     * Extracts salient entity names from a command clause to enable pronoun resolution across steps.
     */
    private fun extractSalientEntity(clause: String): String? {
        val lower = clause.lowercase(Locale.ROOT)
        return when {
            lower.contains("photo") || lower.contains("picture") || lower.contains("image") || lower.contains("selfie") -> "photo"
            lower.contains("video") -> "video"
            lower.contains("song") || lower.contains("music") || lower.contains("gana") || lower.contains("gaana") -> "song"
            lower.contains("flashlight") || lower.contains("torch") -> "flashlight"
            lower.contains("battery") -> "battery"
            lower.contains("wifi") -> "wifi"
            lower.contains("bluetooth") -> "bluetooth"
            lower.contains("sms") || lower.contains("message") || lower.contains("whatsapp") -> "message"
            lower.contains("camera") -> "camera"
            lower.contains("spotify") -> "spotify"
            lower.contains("youtube") -> "youtube"
            else -> null
        }
    }

    /**
     * Extracts explicit entity references from text (e.g. app name, contact, query).
     */
    private fun extractEntityRefs(text: String): Map<String, String> {
        val refs = mutableMapOf<String, String>()

        // Extract contact name if WhatsApp or SMS
        val contactMatch = Regex("(?:to|ko)\\s+([a-zA-Z0-9]+)(?:\\s+(?:on|par|pe)\\s+(?:whatsapp|sms))?", RegexOption.IGNORE_CASE).find(text)
        if (contactMatch != null) {
            refs["contact"] = contactMatch.groupValues[1].trim()
        }

        // Extract app name if opening/launching
        val appMatch = Regex("(?:open|launch|kholo)\\s+([a-zA-Z0-9_ ]+)", RegexOption.IGNORE_CASE).find(text)
        if (appMatch != null) {
            refs["app"] = appMatch.groupValues[1].trim()
        }

        // Extract search query
        val searchMatch = Regex("(?:search|google|find|dhundo)\\s+(?:for\\s+)?(.+)", RegexOption.IGNORE_CASE).find(text)
        if (searchMatch != null) {
            refs["query"] = searchMatch.groupValues[1].trim()
        }

        return refs
    }

    /**
     * Guesses the action type based on keywords.
     */
    private fun guessActionType(clause: String): String {
        val lower = clause.lowercase(Locale.ROOT)
        return when {
            lower.contains("song") || lower.contains("music") || lower.contains("play") || lower.contains("bajao") || lower.contains("chalao") -> "PLAY_MEDIA"
            lower.contains("torch") || lower.contains("flashlight") -> "FLASHLIGHT"
            lower.contains("camera") || lower.contains("photo") || lower.contains("picture") -> "CAMERA"
            lower.contains("whatsapp") || lower.contains("message") || lower.contains("bhejo") -> "WHATSAPP"
            lower.contains("call") || lower.contains("dial") || lower.contains("phone") -> "PHONE_CALL"
            lower.contains("search") || lower.contains("google") || lower.contains("find") -> "SEARCH"
            lower.contains("battery") || lower.contains("battery saver") -> "BATTERY_SAVER"
            lower.contains("wifi") -> "WIFI"
            lower.contains("bluetooth") -> "BLUETOOTH"
            lower.contains("open") || lower.contains("launch") || lower.contains("kholo") -> "OPEN_APP"
            lower.contains("close") || lower.contains("band") -> "CLOSE_APP"
            else -> "EXECUTE_INTENT"
        }
    }
}
