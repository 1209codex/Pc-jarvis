package com.jarvis.macro

enum class MacroActionType {
    LAUNCH_APP,
    CLICK_TEXT,
    CLICK_VIEW_ID,
    CLICK_COORDINATES,
    TYPE_TEXT,
    SCROLL,
    GLOBAL_ACTION,
    DELAY,
    VERIFY_TEXT,
    WAIT_FOR_PACKAGE
}

data class MacroStep(
    val stepIndex: Int,
    val actionType: MacroActionType,
    val target: String = "",       // text, viewId, coordinates ("x,y"), or direction ("down"/"up")
    val payload: String = "",      // text to type, package to launch, or delay ms
    val timeoutMs: Long = 3000L,
    val isOptional: Boolean = false,
    val description: String = ""
)

data class UiMacro(
    val id: String,
    val name: String,
    val description: String,
    val targetPackage: String? = null,
    val steps: List<MacroStep>,
    val createdAt: Long = System.currentTimeMillis(),
    var lastRunAt: Long = 0L,
    var runCount: Int = 0,
    val triggers: List<String> = emptyList()
)

data class MacroExecutionResult(
    val macroId: String,
    val macroName: String,
    val success: Boolean,
    val completedSteps: Int,
    val totalSteps: Int,
    val stepLogs: List<String>,
    val failureReason: String? = null
)
