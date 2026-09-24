package com.jarvis.macro

import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.accessibility.JarvisAccessibilityService
import com.jarvis.accessibility.UiAutomationManager
import kotlinx.coroutines.delay
import java.util.concurrent.ConcurrentHashMap

class MacroWorkflowEngine(
    private val context: Context? = null,
    val customMacroRepo: com.jarvis.routine.CustomMacroRepository? = context?.let { com.jarvis.routine.CustomMacroRepository(it) },
    var simulateForTesting: Boolean = false
) {
    private val TAG = "MacroWorkflowEngine"
    private val macros = ConcurrentHashMap<String, UiMacro>()

    init {
        registerDefaultMacros()
        loadCustomMacros()
    }

    private fun loadCustomMacros() {
        val custom = customMacroRepo?.getAll() ?: emptyList()
        for (m in custom) {
            macros[m.id] = m
        }
    }

    private fun registerDefaultMacros() {
        val clearApps = UiMacro(
            id = "macro_clear_apps",
            name = "Clear All Background Apps",
            description = "Navigates to recent tasks screen and dismisses all background apps.",
            targetPackage = null,
            steps = listOf(
                MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "recents", description = "Open Recents screen"),
                MacroStep(2, MacroActionType.DELAY, payload = "450", description = "Wait for recents animation"),
                MacroStep(3, MacroActionType.CLICK_TEXT, target = "Close all", isOptional = true, description = "Tap Close all"),
                MacroStep(4, MacroActionType.CLICK_TEXT, target = "Clear all", isOptional = true, description = "Tap Clear all fallback")
            ),
            triggers = listOf("clear apps", "close all apps", "clear background apps", "saari apps clear")
        )

        val ytSearch = UiMacro(
            id = "macro_youtube_search",
            name = "YouTube Quick Search",
            description = "Opens YouTube and initiates search query input.",
            targetPackage = "com.google.android.youtube",
            steps = listOf(
                MacroStep(1, MacroActionType.LAUNCH_APP, payload = "com.google.android.youtube", description = "Launch YouTube"),
                MacroStep(2, MacroActionType.WAIT_FOR_PACKAGE, payload = "com.google.android.youtube", timeoutMs = 4000L, description = "Wait for YouTube"),
                MacroStep(3, MacroActionType.CLICK_TEXT, target = "Search", isOptional = true, description = "Click Search icon"),
                MacroStep(4, MacroActionType.TYPE_TEXT, target = "", payload = "trending music", description = "Type query")
            ),
            triggers = listOf("youtube search", "youtube video search", "youtube pe dhoondo")
        )

        val softwareUpdate = UiMacro(
            id = "macro_software_update",
            name = "Check Software Update",
            description = "Navigates through Android settings to the Software Update section.",
            targetPackage = "com.android.settings",
            steps = listOf(
                MacroStep(1, MacroActionType.LAUNCH_APP, payload = "com.android.settings", description = "Open Settings"),
                MacroStep(2, MacroActionType.WAIT_FOR_PACKAGE, payload = "com.android.settings", timeoutMs = 3000L, description = "Wait for Settings"),
                MacroStep(3, MacroActionType.SCROLL, target = "down", description = "Scroll down to find update option"),
                MacroStep(4, MacroActionType.CLICK_TEXT, target = "Software update", isOptional = true, description = "Click Software update"),
                MacroStep(5, MacroActionType.CLICK_TEXT, target = "System update", isOptional = true, description = "Click System update fallback")
            ),
            triggers = listOf("software update", "system update", "check update")
        )

        val quickHome = UiMacro(
            id = "macro_quick_home",
            name = "Return Home & Notification Shade",
            description = "Navigates to home launcher and opens notification shade.",
            targetPackage = null,
            steps = listOf(
                MacroStep(1, MacroActionType.GLOBAL_ACTION, target = "home", description = "Go to Home Screen"),
                MacroStep(2, MacroActionType.DELAY, payload = "250", description = "Wait for transition"),
                MacroStep(3, MacroActionType.GLOBAL_ACTION, target = "notifications", description = "Open Notification Shade")
            ),
            triggers = listOf("quick home", "home shade", "ghar jao")
        )

        val storageManager = UiMacro(
            id = "macro_device_storage",
            name = "Open Storage & Device Care",
            description = "Navigates to Device Care and Storage Settings to inspect and clean space.",
            targetPackage = "com.android.settings",
            steps = listOf(
                MacroStep(1, MacroActionType.LAUNCH_APP, payload = "com.android.settings", description = "Open Settings"),
                MacroStep(2, MacroActionType.WAIT_FOR_PACKAGE, payload = "com.android.settings", timeoutMs = 3000L, description = "Wait for Settings"),
                MacroStep(3, MacroActionType.SCROLL, target = "down", description = "Scroll down"),
                MacroStep(4, MacroActionType.CLICK_TEXT, target = "Storage", isOptional = true, description = "Click Storage"),
                MacroStep(5, MacroActionType.CLICK_TEXT, target = "Device care", isOptional = true, description = "Click Device care fallback")
            ),
            triggers = listOf("storage manager", "device storage", "free up space", "storage saaf karo")
        )

        val whatsappUpdates = UiMacro(
            id = "macro_whatsapp_status",
            name = "Open WhatsApp Status / Updates",
            description = "Opens WhatsApp and navigates directly to the Updates / Status tab.",
            targetPackage = "com.whatsapp",
            steps = listOf(
                MacroStep(1, MacroActionType.LAUNCH_APP, payload = "com.whatsapp", description = "Open WhatsApp"),
                MacroStep(2, MacroActionType.WAIT_FOR_PACKAGE, payload = "com.whatsapp", timeoutMs = 3000L, description = "Wait for WhatsApp"),
                MacroStep(3, MacroActionType.CLICK_TEXT, target = "Updates", isOptional = true, description = "Click Updates"),
                MacroStep(4, MacroActionType.CLICK_TEXT, target = "Status", isOptional = true, description = "Click Status fallback")
            ),
            triggers = listOf("whatsapp status", "whatsapp updates", "status dikhao")
        )

        macros[clearApps.id] = clearApps
        macros[ytSearch.id] = ytSearch
        macros[softwareUpdate.id] = softwareUpdate
        macros[quickHome.id] = quickHome
        macros[storageManager.id] = storageManager
        macros[whatsappUpdates.id] = whatsappUpdates
    }

    fun getAllMacros(): List<UiMacro> {
        val custom = customMacroRepo?.getAll() ?: emptyList()
        for (m in custom) {
            macros[m.id] = m
        }
        return macros.values.toList()
    }

    fun getMacro(idOrName: String): UiMacro? {
        val clean = idOrName.trim().lowercase()
        getAllMacros()
        return macros[clean]
            ?: macros.values.firstOrNull { m ->
                m.id.equals(clean, true) ||
                m.name.lowercase().contains(clean) ||
                m.triggers.any { t -> t.equals(clean, true) || clean.contains(t.lowercase()) || t.lowercase().contains(clean) }
            }
    }

    fun registerMacro(macro: UiMacro) {
        macros[macro.id] = macro
        customMacroRepo?.save(macro)
    }

    fun deleteMacro(idOrName: String): Boolean {
        val macro = getMacro(idOrName) ?: return false
        macros.remove(macro.id)
        customMacroRepo?.delete(macro.id)
        return true
    }

    suspend fun executeMacro(
        macro: UiMacro,
        dynamicParams: Map<String, String> = emptyMap()
    ): MacroExecutionResult {
        val stepLogs = mutableListOf<String>()
        var completedCount = 0
        val isServiceActive = JarvisAccessibilityService.isEnabled || simulateForTesting

        macro.lastRunAt = System.currentTimeMillis()
        macro.runCount++

        for (step in macro.steps) {
            val resolvedPayload = when {
                dynamicParams.containsKey("query") && step.actionType == MacroActionType.TYPE_TEXT -> dynamicParams["query"]!!
                dynamicParams.containsKey("payload") -> dynamicParams["payload"]!!
                else -> step.payload
            }

            val stepLog = "Step ${step.stepIndex}: [${step.actionType.name}] ${step.description} target='${step.target}' payload='$resolvedPayload'"

            if (!isServiceActive) {
                // Accessibility Service is required for UI automation. Do NOT simulate success —
                // returning a false-success would corrupt agent reasoning, task history, and metrics.
                val blockedMsg = "BLOCKED at Step ${step.stepIndex} (${step.actionType.name}): " +
                    "Accessibility Service is not enabled. Enable it in Settings → Accessibility → Jarvis."
                stepLogs.add(blockedMsg)
                return MacroExecutionResult(
                    macroId = macro.id,
                    macroName = macro.name,
                    success = false,
                    completedSteps = completedCount,
                    totalSteps = macro.steps.size,
                    stepLogs = stepLogs,
                    failureReason = "REQUIRES_ACCESSIBILITY: Accessibility Service is disabled"
                )
            }

            try {
                val stepSuccess = executeStep(step, resolvedPayload)
                if (stepSuccess) {
                    stepLogs.add("$stepLog -> OK")
                    completedCount++
                } else {
                    if (step.isOptional) {
                        stepLogs.add("$stepLog -> Skipped optional step")
                        completedCount++
                    } else {
                        val failMsg = "Failed at Step ${step.stepIndex} (${step.actionType.name}): target='${step.target}'"
                        stepLogs.add(failMsg)
                        return MacroExecutionResult(
                            macroId = macro.id,
                            macroName = macro.name,
                            success = false,
                            completedSteps = completedCount,
                            totalSteps = macro.steps.size,
                            stepLogs = stepLogs,
                            failureReason = failMsg
                        )
                    }
                }
            } catch (e: Exception) {
                if (step.isOptional) {
                    stepLogs.add("$stepLog -> Skipped optional step (${e.message})")
                    completedCount++
                } else {
                    val errorMsg = "Exception at Step ${step.stepIndex}: ${e.message}"
                    stepLogs.add(errorMsg)
                    return MacroExecutionResult(
                        macroId = macro.id,
                        macroName = macro.name,
                        success = false,
                        completedSteps = completedCount,
                        totalSteps = macro.steps.size,
                        stepLogs = stepLogs,
                        failureReason = errorMsg
                    )
                }
            }
        }

        runCatching {
            Log.i(TAG, "Macro '${macro.name}' finished ($completedCount/${macro.steps.size} steps)")
        }

        return MacroExecutionResult(
            macroId = macro.id,
            macroName = macro.name,
            success = true,
            completedSteps = completedCount,
            totalSteps = macro.steps.size,
            stepLogs = stepLogs
        )
    }

    private suspend fun executeStep(step: MacroStep, resolvedPayload: String): Boolean {
        if (simulateForTesting) return true
        return when (step.actionType) {
            MacroActionType.LAUNCH_APP -> {
                val pkg = resolvedPayload.ifBlank { step.target }
                if (pkg.isBlank() || context == null) return false
                val intent = context.packageManager.getLaunchIntentForPackage(pkg) ?: return false
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                true
            }

            MacroActionType.WAIT_FOR_PACKAGE -> {
                val pkg = resolvedPayload.ifBlank { step.target }
                UiAutomationManager.waitForPackage(pkg, step.timeoutMs)
            }

            MacroActionType.CLICK_TEXT -> {
                val res = UiAutomationManager.click(step.target)
                res.success
            }

            MacroActionType.CLICK_VIEW_ID -> {
                val res = UiAutomationManager.click(step.target)
                res.success
            }

            MacroActionType.CLICK_COORDINATES -> {
                val parts = step.target.split(",")
                if (parts.size == 2) {
                    val x = parts[0].trim().toFloatOrNull() ?: return false
                    val y = parts[1].trim().toFloatOrNull() ?: return false
                    val res = UiAutomationManager.clickCoordinates(x, y)
                    res.success
                } else false
            }

            MacroActionType.TYPE_TEXT -> {
                val res = UiAutomationManager.type(resolvedPayload, step.target.ifBlank { null })
                res.success
            }

            MacroActionType.SCROLL -> {
                if (context != null) {
                    val res = UiAutomationManager.scroll(context, step.target.ifBlank { "down" })
                    res.success
                } else true
            }

            MacroActionType.GLOBAL_ACTION -> {
                val res = UiAutomationManager.performGlobalAction(step.target)
                res.success
            }

            MacroActionType.DELAY -> {
                val delayMs = resolvedPayload.toLongOrNull() ?: step.target.toLongOrNull() ?: 300L
                delay(delayMs.coerceIn(50L, 5000L))
                true
            }

            MacroActionType.VERIFY_TEXT -> {
                val service = JarvisAccessibilityService.instance ?: return false
                val node = service.findNodeByText(step.target)
                node != null
            }
        }
    }

    fun clear() {
        macros.clear()
    }

    fun resetToDefaults() {
        macros.clear()
        registerDefaultMacros()
    }
}
