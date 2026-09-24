package com.jarvis.tools

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.accessibility.AutonomousUiNavigator
import com.jarvis.accessibility.JarvisAccessibilityService
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import org.json.JSONObject

/**
 * Autonomous Cross-App UI Navigation & Automation Tool.
 * Provides self-healing UI interaction, fuzzy node matching, form filling, and popup dismissal.
 */
class AppAutopilotTool(private val context: Context? = null) : Tool {

    private val TAG = "AppAutopilotTool"

    override val name: String = "APP_AUTOPILOT"
    override val description: String =
        "Autonomously interacts with on-screen UI across any app. Actions: 'click', 'type', 'fill_form', 'scroll', 'dismiss_popup'. Params: action, target, text, direction."
    override val policy: ToolPolicy = ToolPolicy(idempotent = false, retryable = true, riskLevel = RiskLevel.MEDIUM)

    val metadata = ToolMetadata(
        name = "APP_AUTOPILOT",
        description = "Navigates and drives third-party application user interfaces via accessibility tree.",
        parameters = listOf(
            com.jarvis.foundation.ParameterSchema("action", "string", "Action to perform: click, type, fill_form, scroll, dismiss_popup", required = true),
            com.jarvis.foundation.ParameterSchema("target", "string", "Button, field label, hint, or view ID to interact with", required = false),
            com.jarvis.foundation.ParameterSchema("text", "string", "Text to type or form data key-value JSON", required = false),
            com.jarvis.foundation.ParameterSchema("direction", "string", "Scroll direction: 'down' or 'up'", required = false)
        ),
        riskLevel = RiskLevel.MEDIUM
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(false, "Jarvis Accessibility Service is not running. Please enable it in Android Settings -> Accessibility.")

        val action = params["action"]?.lowercase()?.trim()
            ?: if (params.containsKey("direction")) "scroll" else "click"
        val target = params["target"]?.trim().orEmpty()
        val text = params["text"] ?: params["payload"] ?: ""
        val direction = params["direction"]?.lowercase()?.trim() ?: "down"

        Log.i(TAG, "Executing UI Autopilot: action='$action', target='$target', text='$text'")

        return when (action) {
            "dismiss_popup", "dismiss", "close_dialog" -> {
                val dismissed = AutonomousUiNavigator.dismissIntrusiveDialogs(service)
                if (dismissed) {
                    ToolResult.Success("Successfully detected and dismissed intrusive dialog/popup.")
                } else {
                    ToolResult.Success("No intrusive dialog found to dismiss.")
                }
            }

            "scroll" -> {
                val scrolled = if (direction == "up") service.scrollUp() else service.scrollDown()
                if (scrolled) {
                    ToolResult.Success("Scrolled $direction successfully.")
                } else {
                    ToolResult.Failed("Could not scroll $direction (end of container or no scrollable view).")
                }
            }

            "type", "input" -> {
                if (target.isBlank() && text.isBlank()) {
                    return ToolResult.Failed("Target field or text to type must be specified.")
                }
                var node = AutonomousUiNavigator.scrollAndFind(target, direction = "down", service = service)
                if (node == null) {
                    AutonomousUiNavigator.dismissIntrusiveDialogs(service)
                    node = AutonomousUiNavigator.scrollAndFind(target, direction = "down", service = service)
                }

                if (node == null) {
                    return ToolResult.Failed("Could not locate editable field matching '$target'.")
                }

                val targetNode = if (node.isEditable) node else node.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: node
                val bundle = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                val typed = targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, bundle)
                if (typed) {
                    ToolResult.Success("Typed '$text' into field '$target'.")
                } else {
                    ToolResult.Failed("Failed to set text on field '$target'.")
                }
            }

            "fill_form" -> {
                val formMap = parseFormData(text)
                if (formMap.isEmpty()) {
                    return ToolResult.Failed("No form fields provided. Pass JSON or 'key: value, key2: value2'.")
                }
                val results = AutonomousUiNavigator.smartFillForm(formMap, service)
                val successCount = results.count { it.value }
                val summary = results.entries.joinToString(", ") { "${it.key}: ${if (it.value) "OK" else "MISSING"}" }
                if (successCount > 0) {
                    ToolResult.Success("Form filled ($successCount/${results.size} fields): $summary")
                } else {
                    ToolResult.Failed("Failed to fill any form fields: $summary")
                }
            }

            "click_coords", "tap_coords", "click_point" -> {
                val normX = params["norm_x"]?.toFloatOrNull() ?: params["normX"]?.toFloatOrNull()
                val normY = params["norm_y"]?.toFloatOrNull() ?: params["normY"]?.toFloatOrNull()
                val x = params["x"]?.toFloatOrNull()
                val y = params["y"]?.toFloatOrNull()

                val clicked = if (normX != null && normY != null) {
                    service.clickNormalizedCoordinatesAsync(normX, normY)
                } else if (x != null && y != null) {
                    service.clickCoordinatesAsync(x, y)
                } else {
                    return ToolResult.Failed("Missing coordinates. Provide 'norm_x' & 'norm_y' (0..1) or 'x' & 'y'.")
                }

                if (clicked) {
                    ToolResult.Success("Clicked screen at coordinates (${normX ?: x}, ${normY ?: y}).")
                } else {
                    ToolResult.Failed("Failed to dispatch click gesture at coordinates.")
                }
            }

            "long_press", "long_click" -> {
                val normX = params["norm_x"]?.toFloatOrNull()
                val normY = params["norm_y"]?.toFloatOrNull()
                val x = params["x"]?.toFloatOrNull()
                val y = params["y"]?.toFloatOrNull()
                val duration = params["duration"]?.toLongOrNull() ?: 700L

                if (target.isNotBlank()) {
                    val node = AutonomousUiNavigator.scrollAndFind(target, direction = "down", service = service)
                    if (node != null) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        val pressed = service.longPressCoordinatesAsync(rect.centerX().toFloat(), rect.centerY().toFloat(), duration)
                        if (pressed) {
                            return ToolResult.Success("Long-pressed on '$target'.")
                        }
                    }
                }

                val pressed = if (normX != null && normY != null) {
                    val displayMetrics = service.resources.displayMetrics
                    service.longPressCoordinatesAsync(normX * displayMetrics.widthPixels, normY * displayMetrics.heightPixels, duration)
                } else if (x != null && y != null) {
                    service.longPressCoordinatesAsync(x, y, duration)
                } else {
                    return ToolResult.Failed("Target element or coordinates required for long_press.")
                }

                if (pressed) {
                    ToolResult.Success("Long-pressed successfully.")
                } else {
                    ToolResult.Failed("Failed to execute long-press gesture.")
                }
            }

            "double_tap", "double_click" -> {
                val x = params["x"]?.toFloatOrNull()
                val y = params["y"]?.toFloatOrNull()
                if (target.isNotBlank()) {
                    val node = AutonomousUiNavigator.scrollAndFind(target, direction = "down", service = service)
                    if (node != null) {
                        val rect = android.graphics.Rect()
                        node.getBoundsInScreen(rect)
                        val tapped = service.doubleTapCoordinatesAsync(rect.centerX().toFloat(), rect.centerY().toFloat())
                        if (tapped) return ToolResult.Success("Double-tapped '$target'.")
                    }
                }
                if (x != null && y != null) {
                    val tapped = service.doubleTapCoordinatesAsync(x, y)
                    if (tapped) return ToolResult.Success("Double-tapped at ($x, $y).")
                }
                ToolResult.Failed("Failed to double-tap target or coordinates.")
            }

            "toggle_neighbor", "click_neighbor" -> {
                val label = target.ifBlank { params["label"] ?: "" }
                if (label.isBlank()) return ToolResult.Failed("Specify target label to find neighbor for.")
                val neighborDirection = params["neighbor_direction"] ?: params["dir"] ?: "right"
                val root = service.rootInActiveWindow
                val neighbor = AutonomousUiNavigator.findNeighborNode(label, neighborDirection, root)
                if (neighbor != null) {
                    val clicked = service.clickNode(neighbor)
                    if (clicked) {
                        ToolResult.Success("Clicked neighbor ($neighborDirection) of '$label'.")
                    } else {
                        ToolResult.Failed("Found neighbor of '$label' but click failed.")
                    }
                } else {
                    ToolResult.Failed("Could not find neighbor ($neighborDirection) of '$label'.")
                }
            }

            "assert_visible", "verify_visible" -> {
                if (target.isBlank()) return ToolResult.Failed("Specify target text to assert visibility.")
                val root = service.rootInActiveWindow
                val node = AutonomousUiNavigator.findNodeFuzzy(target, root)
                if (node != null) {
                    ToolResult.Success("Target '$target' is visible on screen.")
                } else {
                    ToolResult.Failed("Target '$target' was NOT found on the active screen.")
                }
            }

            "click", "tap" -> {
                if (target.isBlank()) {
                    return ToolResult.Failed("Specify target text or button to click.")
                }

                var node = AutonomousUiNavigator.scrollAndFind(target, direction = "down", service = service)
                if (node == null) {
                    // Try self-healing: dismiss popup and re-search
                    val popupDismissed = AutonomousUiNavigator.dismissIntrusiveDialogs(service)
                    if (popupDismissed) {
                        node = AutonomousUiNavigator.scrollAndFind(target, direction = "down", service = service)
                    }
                }

                if (node == null) {
                    return ToolResult.Failed("Could not locate on-screen UI element matching '$target'.")
                }

                val clicked = service.clickNode(node)
                if (clicked) {
                    ToolResult.Success("Clicked '$target' successfully.")
                } else {
                    ToolResult.Failed("Node matching '$target' was found, but click action could not be performed.")
                }
            }

            else -> ToolResult.Failed("Unknown UI Autopilot action '$action'. Use click, type, fill_form, scroll, click_coords, long_press, double_tap, toggle_neighbor, assert_visible, or dismiss_popup.")
        }
    }

    private fun parseFormData(raw: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        try {
            val json = JSONObject(raw)
            for (key in json.keys()) {
                map[key] = json.getString(key)
            }
            return map
        } catch (_: Exception) {}

        // Fallback: parse key: value pairs
        val pairs = raw.split(",")
        for (p in pairs) {
            val parts = p.split(":", limit = 2)
            if (parts.size == 2) {
                map[parts[0].trim()] = parts[1].trim()
            }
        }
        return map
    }
}
