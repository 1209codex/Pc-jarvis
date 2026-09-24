package com.jarvis.accessibility

import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Advanced Autonomous UI Navigator for cross-app automation.
 * Features:
 * 1. Fuzzy Accessibility node resolution (Levenshtein distance + token overlap).
 * 2. Self-healing popup / intrusive dialog dismissal.
 * 3. Autonomous scroll recovery for off-screen targets.
 * 4. Contextual form analysis & semantic input field filling.
 */
object AutonomousUiNavigator {

    private const val TAG = "AutonomousUiNavigator"

    // Common intrusive popups / system prompts to dismiss
    val POPUP_DISMISS_TARGETS = listOf(
        "dismiss", "close", "not now", "later", "cancel",
        "skip", "got it", "allow", "ok", "maybe later", "no thanks"
    )

    /**
     * Calculates string similarity between 0.0 (completely different) and 1.0 (identical).
     * Combines Levenshtein distance with token subset matching.
     */
    fun calculateSimilarity(s1: String, s2: String): Float {
        val clean1 = s1.trim().lowercase()
        val clean2 = s2.trim().lowercase()

        if (clean1 == clean2) return 1.0f
        if (clean1.isEmpty() || clean2.isEmpty()) return 0.0f

        // Token containment bonus (Dice coefficient + Overlap)
        val tokens1 = clean1.split(Regex("\\s+")).filter { it.isNotBlank() }
        val tokens2 = clean2.split(Regex("\\s+")).filter { it.isNotBlank() }
        val commonTokens = tokens1.intersect(tokens2.toSet()).size
        val diceSimilarity = if (tokens1.isNotEmpty() && tokens2.isNotEmpty()) {
            (2.0f * commonTokens) / (tokens1.size + tokens2.size)
        } else 0.0f
        val overlapSimilarity = if (tokens1.isNotEmpty() && tokens2.isNotEmpty()) {
            (commonTokens.toFloat() / minOf(tokens1.size, tokens2.size)) * 0.75f
        } else 0.0f
        val tokenSimilarity = max(diceSimilarity, overlapSimilarity)

        // Levenshtein similarity
        val levDist = com.jarvis.foundation.TextDistance.levenshtein(clean1, clean2)
        val maxLen = max(clean1.length, clean2.length)
        val levSimilarity = 1.0f - (levDist.toFloat() / maxLen)

        return max(tokenSimilarity, levSimilarity)
    }

    /**
     * Recursively traverses AccessibilityNodeInfo tree and finds the best matching node
     * by text, viewId, contentDescription, or fuzzy similarity.
     */
    fun findNodeFuzzy(
        target: String,
        root: AccessibilityNodeInfo?,
        threshold: Float = 0.65f
    ): AccessibilityNodeInfo? {
        if (root == null) return null

        val lowerTarget = target.lowercase().trim()
        var bestNode: AccessibilityNodeInfo? = null
        var bestScore = threshold

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()

            val text = node.text?.toString() ?: ""
            val desc = node.contentDescription?.toString() ?: ""
            val viewId = node.viewIdResourceName ?: ""

            // Exact check
            if (text.equals(lowerTarget, ignoreCase = true) ||
                desc.equals(lowerTarget, ignoreCase = true) ||
                viewId.endsWith("/$lowerTarget", ignoreCase = true) ||
                viewId.equals(lowerTarget, ignoreCase = true)
            ) {
                return node
            }

            // Substring check
            if (text.contains(lowerTarget, ignoreCase = true) || desc.contains(lowerTarget, ignoreCase = true)) {
                return node
            }

            // Fuzzy check
            val scoreText = if (text.isNotBlank()) calculateSimilarity(text, lowerTarget) else 0f
            val scoreDesc = if (desc.isNotBlank()) calculateSimilarity(desc, lowerTarget) else 0f
            val score = max(scoreText, scoreDesc)

            if (score > bestScore) {
                bestScore = score
                bestNode = node
            }

            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    queue.add(child)
                }
            }
        }

        return bestNode
    }

    /**
     * Automatically dismisses blocking dialogs or modal prompts.
     */
    fun dismissIntrusiveDialogs(service: JarvisAccessibilityService): Boolean {
        val root = service.rootInActiveWindow ?: return false
        for (target in POPUP_DISMISS_TARGETS) {
            val node = findNodeFuzzy(target, root, threshold = 0.85f)
            if (node != null && (node.isClickable || node.parent?.isClickable == true)) {
                Log.i(TAG, "Dismissing intrusive dialog via button: '$target'")
                val clicked = service.clickNode(node)
                if (clicked) return true
            }
        }
        return false
    }

    /**
     * Scrolls downward or upward if target is off-screen, re-evaluating the tree up to maxAttempts.
     */
    suspend fun scrollAndFind(
        target: String,
        direction: String = "down",
        service: JarvisAccessibilityService,
        maxAttempts: Int = 3
    ): AccessibilityNodeInfo? {
        val root = service.rootInActiveWindow
        val initial = findNodeFuzzy(target, root)
        if (initial != null) return initial

        for (attempt in 1..maxAttempts) {
            Log.i(TAG, "Target '$target' not visible. Auto-scrolling $direction (attempt $attempt/$maxAttempts)...")
            val scrolled = if (direction.equals("up", ignoreCase = true)) {
                service.scrollUp()
            } else {
                service.scrollDown()
            }
            if (!scrolled) break
            delay(500)

            val currentRoot = service.rootInActiveWindow
            val found = findNodeFuzzy(target, currentRoot)
            if (found != null) return found
        }
        return null
    }

    /**
     * Smart form filler: Scans editable fields and infers semantic targets from hints or adjacent labels.
     */
    fun smartFillForm(
        formData: Map<String, String>,
        service: JarvisAccessibilityService
    ): Map<String, Boolean> {
        val root = service.rootInActiveWindow ?: return emptyMap()
        val results = mutableMapOf<String, Boolean>()

        val editableNodes = mutableListOf<Pair<String, AccessibilityNodeInfo>>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.isEditable) {
                val label = (node.hintText?.toString()
                    ?: node.text?.toString()
                    ?: node.contentDescription?.toString()
                    ?: node.viewIdResourceName?.substringAfterLast("/")
                    ?: "").lowercase()
                editableNodes.add(label to node)
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }

        for ((fieldKey, valueToType) in formData) {
            val cleanKey = fieldKey.lowercase().trim()
            val matched = editableNodes.firstOrNull { (label, _) ->
                label.contains(cleanKey) || calculateSimilarity(label, cleanKey) > 0.6f
            }?.second

            if (matched != null) {
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, valueToType)
                }
                val success = matched.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                results[fieldKey] = success
                Log.i(TAG, "Smart filled form field '$fieldKey' with '$valueToType': success=$success")
            } else {
                results[fieldKey] = false
                Log.w(TAG, "Could not locate editable field matching '$fieldKey'")
            }
        }

        return results
    }

    /**
     * Finds a clickable or interactive node adjacent (right/left/below) to a label node
     * (e.g. finding the switch toggle next to 'Dark mode' or 'Bluetooth').
     */
    fun findNeighborNode(
        labelTarget: String,
        direction: String = "right",
        root: AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        if (root == null) return null
        val labelNode = findNodeFuzzy(labelTarget, root) ?: return null
        val labelRect = Rect()
        labelNode.getBoundsInScreen(labelRect)

        var bestNeighbor: AccessibilityNodeInfo? = null
        var minDistance = Int.MAX_VALUE

        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)

        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node != labelNode && (node.isClickable || node.isCheckable || node.isEditable)) {
                val rect = Rect()
                node.getBoundsInScreen(rect)
                if (!rect.isEmpty) {
                    val isCandidate = when (direction.lowercase()) {
                        "right" -> rect.left >= labelRect.left && Math.abs(rect.centerY() - labelRect.centerY()) <= (labelRect.height() * 1.5)
                        "left" -> rect.right <= labelRect.right && Math.abs(rect.centerY() - labelRect.centerY()) <= (labelRect.height() * 1.5)
                        "below", "down" -> rect.top >= labelRect.top && Math.abs(rect.centerX() - labelRect.centerX()) <= (labelRect.width() * 1.5)
                        "above", "up" -> rect.bottom <= labelRect.bottom && Math.abs(rect.centerX() - labelRect.centerX()) <= (labelRect.width() * 1.5)
                        else -> true
                    }

                    if (isCandidate) {
                        val dist = when (direction.lowercase()) {
                            "right" -> Math.abs(rect.left - labelRect.right) + Math.abs(rect.centerY() - labelRect.centerY())
                            "left" -> Math.abs(labelRect.left - rect.right) + Math.abs(rect.centerY() - labelRect.centerY())
                            "below", "down" -> Math.abs(rect.top - labelRect.bottom) + Math.abs(rect.centerX() - labelRect.centerX())
                            "above", "up" -> Math.abs(labelRect.top - rect.bottom) + Math.abs(rect.centerX() - labelRect.centerX())
                            else -> Math.abs(rect.centerX() - labelRect.centerX()) + Math.abs(rect.centerY() - labelRect.centerY())
                        }
                        if (dist < minDistance) {
                            minDistance = dist
                            bestNeighbor = node
                        }
                    }
                }
            }
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) queue.add(child)
            }
        }

        return bestNeighbor ?: labelNode
    }
}

