package com.jarvis.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.Build
import android.os.PowerManager
import com.jarvis.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

object UiAutomationManager {
    private const val TAG = "UiAutomationManager"

    fun isEnabled(): Boolean = JarvisAccessibilityService.isEnabled

    fun openAccessibilitySettings(context: Context) {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    suspend fun waitForPackage(packageName: String, timeoutMs: Long = 3000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val fg = JarvisAccessibilityService.currentForegroundPackage
            if (fg != null && fg.contains(packageName, ignoreCase = true)) {
                return true
            }
            delay(150)
        }
        return false
    }

    suspend fun waitForNode(
        timeoutMs: Long = 3000,
        finder: (JarvisAccessibilityService) -> AccessibilityNodeInfo?
    ): AccessibilityNodeInfo? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val service = JarvisAccessibilityService.instance ?: return null
            val node = finder(service)
            if (node != null) return node
            delay(150)
        }
        return null
    }

    suspend fun click(target: String): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility Service is not enabled. Please enable Jarvis Automation in Settings.")

        val lowerTarget = target.lowercase().trim()

        // 1. Try finding by View ID
        var node = service.findNodeByViewId(target)

        // 2. Try finding by Content Description
        if (node == null) {
            node = service.findNodeByContentDescription(lowerTarget, exact = false)
        }

        // 3. Try finding by Text
        if (node == null) {
            node = service.findNodeByText(lowerTarget, exact = false)
        }

        if (node != null) {
            val success = service.clickNode(node)
            return if (success) {
                ToolResult(true, "Clicked element '$target'")
            } else {
                ToolResult(false, "Found element '$target' but click action failed")
            }
        }

        return ToolResult(false, "Element '$target' not found on screen")
    }

    suspend fun clickCoordinates(x: Float, y: Float): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility Service is not enabled")

        val success = service.clickCoordinatesAsync(x, y)
        return if (success) {
            ToolResult(true, "Clicked coordinates ($x, $y)")
        } else {
            ToolResult(false, "Failed to click coordinates ($x, $y)")
        }
    }

    suspend fun type(text: String, target: String? = null): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility Service is not enabled. Please enable Jarvis Automation in Settings.")

        val node: AccessibilityNodeInfo? = if (!target.isNullOrBlank()) {
            service.findNodeByViewId(target)
                ?: service.findNodeByContentDescription(target)
                ?: service.findNodeByText(target)
                ?: service.findFirstEditableNode()
        } else {
            service.findFirstEditableNode()
        }

        if (node == null) {
            return ToolResult(false, "No input field found to type text")
        }

        val success = service.setNodeText(node, text)
        return if (success) {
            ToolResult(true, "Typed '$text' successfully")
        } else {
            ToolResult(false, "Failed to enter text into target field")
        }
    }

    suspend fun scroll(context: Context, direction: String): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility Service is not enabled. Please enable Jarvis Automation in Settings.")

        // Screen geometry calculation
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm?.defaultDisplay?.getRealMetrics(metrics)
        val width = if (metrics.widthPixels > 0) metrics.widthPixels.toFloat() else 1080f
        val height = if (metrics.heightPixels > 0) metrics.heightPixels.toFloat() else 2400f

        val centerX = width / 2f
        val startY: Float
        val endY: Float

        when (direction.lowercase().trim()) {
            "down", "niche", "forward", "next" -> {
                // Swipe upwards to scroll content down
                startY = height * 0.75f
                endY = height * 0.25f
            }
            "up", "uper", "backward", "previous" -> {
                // Swipe downwards to scroll content up
                startY = height * 0.25f
                endY = height * 0.75f
            }
            else -> {
                startY = height * 0.75f
                endY = height * 0.25f
            }
        }

        val success = service.swipeAsync(centerX, startY, centerX, endY, durationMs = 280)
        return if (success) {
            ToolResult(true, "Scrolled $direction successfully")
        } else {
            // Fallback to node scroll
            val scrollNode = service.findFirstScrollableNode()
            if (scrollNode != null) {
                val action = if (direction.contains("up")) {
                    AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                } else {
                    AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                }
                val nodeScrolled = scrollNode.performAction(action)
                if (nodeScrolled) return ToolResult(true, "Scrolled container $direction")
            }
            ToolResult(false, "Failed to perform scroll gesture")
        }
    }

    fun performGlobalAction(actionName: String): ToolResult {
        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(false, "Accessibility Service is not enabled. Please enable Jarvis Automation in Settings.")

        val actionInt = when (actionName.lowercase().trim()) {
            "back", "piche", "piche jao", "back jao" -> AccessibilityService.GLOBAL_ACTION_BACK
            "home", "ghar", "home screen" -> AccessibilityService.GLOBAL_ACTION_HOME
            "recents", "recent apps", "overview", "switch apps" -> AccessibilityService.GLOBAL_ACTION_RECENTS
            "notifications", "notification", "shade" -> AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS
            "quick_settings", "settings shade" -> AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS
            "screenshot", "capture screen", "screen shot" -> AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT
            "lock", "lock_screen", "screen_lock", "lock screen" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN
                } else {
                    -1
                }
            }
            else -> return ToolResult(false, "Unknown global action: $actionName")
        }

        if (actionInt == -1) {
            return ToolResult(false, "Screen lock global action requires Android 9.0+ (API 28)")
        }

        val success = service.performGlobalAction(actionInt)
        return if (success) {
            ToolResult(true, "Performed global action: $actionName")
        } else {
            ToolResult(false, "Failed to execute global action: $actionName")
        }
    }

    suspend fun lockScreen(context: Context): ToolResult {
        val service = JarvisAccessibilityService.instance
        if (service != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val success = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            if (success) return ToolResult(true, "Screen locked successfully.")
        }
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
        if (dpm != null) {
            try {
                dpm.lockNow()
                return ToolResult(true, "Screen locked via Device Policy Manager.")
            } catch (e: Exception) {
                Log.d(TAG, "DevicePolicyManager.lockNow() failed: ${e.message}")
            }
        }
        return ToolResult(false, "Locking screen requires Jarvis Accessibility Service (Android 9+) or Device Administrator permission.")
    }

    suspend fun unlockScreen(context: Context, pin: String? = null): ToolResult {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            @Suppress("DEPRECATION")
            val wakeLock = pm?.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Jarvis::UnlockWake"
            )
            wakeLock?.acquire(5000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquisition failed: ${e.message}")
        }

        val service = JarvisAccessibilityService.instance
            ?: return ToolResult(true, "Screen display woken up.")

        delay(300)
        val displayMetrics = service.resources.displayMetrics
        val width = displayMetrics.widthPixels.toFloat()
        val height = displayMetrics.heightPixels.toFloat()
        val swiped = service.swipeAsync(width / 2f, height * 0.8f, width / 2f, height * 0.2f, 250)

        if (!pin.isNullOrBlank()) {
            delay(400)
            val cleanPin = pin.trim()
            for (char in cleanPin) {
                if (char.isDigit()) {
                    val digitNode = service.findNodeByText(char.toString(), exact = true)
                    if (digitNode != null) {
                        service.clickNode(digitNode)
                    }
                    delay(150)
                }
            }
            val enterNode = service.findNodeByText("Enter", exact = false)
                ?: service.findNodeByContentDescription("Enter", exact = false)
                ?: service.findNodeByContentDescription("Done", exact = false)
            enterNode?.let { service.clickNode(it) }
            return ToolResult(true, "Screen woken up, swipe performed, and PIN '$cleanPin' entered.")
        }

        return if (swiped) {
            ToolResult(true, "Screen woken up and lock screen dismissed.")
        } else {
            ToolResult(true, "Screen display woken up.")
        }
    }

    fun inspectScreen(): List<UiNodeInfo> {
        val service = JarvisAccessibilityService.instance ?: return emptyList()
        return service.dumpInteractiveTree()
    }
}
