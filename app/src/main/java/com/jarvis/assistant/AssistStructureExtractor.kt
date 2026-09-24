package com.jarvis.assistant

import android.app.assist.AssistStructure
import android.util.Log

data class ExtractedScreenContext(
    val title: String = "",
    val packageName: String = "",
    val visibleTexts: List<String> = emptyList(),
    val focusedText: String = "",
    val fullContextSummary: String = ""
)

/**
 * Parses [AssistStructure] captured during an Android Assist gesture
 * (long-press Home, swipe from corner, power button) to extract on-screen context.
 */
object AssistStructureExtractor {

    private const val TAG = "AssistStructureExtract"

    fun extract(structure: AssistStructure?): ExtractedScreenContext {
        if (structure == null) return ExtractedScreenContext()

        val textList = mutableListOf<String>()
        var focusedText = ""
        var windowTitle = ""
        var targetPackage = ""

        try {
            val windowCount = structure.windowNodeCount
            for (w in 0 until windowCount) {
                val windowNode = structure.getWindowNodeAt(w)
                if (windowTitle.isBlank() && windowNode.title != null) {
                    windowTitle = windowNode.title.toString()
                }

                val rootView = windowNode.rootViewNode
                if (rootView != null) {
                    traverseViewNode(rootView, textList) { focused ->
                        if (focusedText.isBlank()) {
                            focusedText = focused
                        }
                    }
                }
            }

            if (structure.activityComponent != null) {
                targetPackage = structure.activityComponent.packageName
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error extracting AssistStructure: ${e.message}")
        }

        val distinctTexts = textList.filter { it.isNotBlank() }.distinct()
        val summary = buildString {
            if (windowTitle.isNotBlank()) append("Screen: $windowTitle\n")
            if (targetPackage.isNotBlank()) append("App: $targetPackage\n")
            if (focusedText.isNotBlank()) append("Focused: $focusedText\n")
            if (distinctTexts.isNotEmpty()) {
                append("Visible Text: ")
                append(distinctTexts.take(30).joinToString(" | "))
            }
        }.trim()

        return ExtractedScreenContext(
            title = windowTitle,
            packageName = targetPackage,
            visibleTexts = distinctTexts,
            focusedText = focusedText,
            fullContextSummary = summary
        )
    }

    private fun traverseViewNode(
        node: AssistStructure.ViewNode,
        textList: MutableList<String>,
        onFocused: (String) -> Unit
    ) {
        val text = node.text?.toString()?.trim()
        val contentDesc = node.contentDescription?.toString()?.trim()
        val hint = node.hint?.trim()

        if (!text.isNullOrBlank()) {
            textList.add(text)
            if (node.isFocused) {
                onFocused(text)
            }
        }
        if (!contentDesc.isNullOrBlank() && contentDesc != text) {
            textList.add(contentDesc)
        }
        if (!hint.isNullOrBlank() && hint != text) {
            textList.add(hint)
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChildAt(i) ?: continue
            traverseViewNode(child, textList, onFocused)
        }
    }
}
