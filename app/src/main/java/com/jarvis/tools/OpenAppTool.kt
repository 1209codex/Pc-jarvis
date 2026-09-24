package com.jarvis.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

class OpenAppTool(private val context: Context? = null) : Tool {
    override val name: String = "OPEN_APP"
    override val description: String = "Opens an installed Android application. Parameter: app (string, e.g. 'youtube', 'whatsapp', 'instagram', 'chrome', 'settings', 'camera')."

    private fun isPackageInstalled(pkgName: String): Boolean {
        val ctx = context ?: return true
        return try {
            ctx.packageManager.getPackageInfo(pkgName, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            false
        }
    }

    private fun getLaunchIntent(pkgName: String): Intent? {
        val ctx = context ?: return Intent(Intent.ACTION_MAIN)
        // Try standard launch intent first
        val intent = ctx.packageManager.getLaunchIntentForPackage(pkgName)
        if (intent != null) return intent

        // BUG-010 fix: Ensure we query for activities that specifically handle the LAUNCHER category
        // instead of blindly picking arbitrary exported activities (which may be internal/settings screens)
        return try {
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage(pkgName)
            }
            val resolveInfos = ctx.packageManager.queryIntentActivities(mainIntent, 0)
            if (resolveInfos.isNotEmpty()) {
                val activityInfo = resolveInfos[0].activityInfo
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_LAUNCHER)
                    setClassName(activityInfo.packageName, activityInfo.name)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val appKey = params["app"]?.lowercase()?.trim() ?: return ToolResult(false, "App name missing")
        val pkgName = AppRegistry.ALLOWED_PACKAGES[appKey]
            ?: (if (context != null) AppRegistry.resolvePackage(context, appKey) else null)
            ?: return ToolResult(false, "App '$appKey' not in allowlist")

        if (!isPackageInstalled(pkgName)) {
            return ToolResult(false, "App '$appKey' is not installed on this device")
        }

        val intent = getLaunchIntent(pkgName)
            ?: return ToolResult(false, "Cannot launch '$appKey' - no launchable activity found")

        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context?.startActivity(intent)
        return ToolResult(true, "Opened $appKey successfully")
    }
}

class CloseAppTool(private val context: Context? = null) : Tool {
    override val name: String = "CLOSE_APP"
    override val description: String = "Closes a running application. Parameter: app (string, e.g. 'youtube', 'whatsapp', 'instagram')."

    @Suppress("DEPRECATION")
    override suspend fun execute(params: Map<String, String>): ToolResult {
        val appKey = params["app"]?.lowercase()?.trim() ?: return ToolResult(false, "App name missing")
        val pkgName = AppRegistry.ALLOWED_PACKAGES[appKey]
            ?: (if (context != null) AppRegistry.resolvePackage(context, appKey) else null)
            ?: return ToolResult(false, "App '$appKey' not in allowlist")

        val ctx = context ?: return ToolResult(true, "Closed $appKey successfully")
        return try {
            // If accessibility service is active and target app is foreground, press HOME
            com.jarvis.accessibility.JarvisAccessibilityService.instance?.let { service ->
                val root = service.rootInActiveWindow
                if (root?.packageName == pkgName) {
                    service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
                }
            }

            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                am.killBackgroundProcesses(pkgName)
                ToolResult(true, "Closed $appKey successfully")
            } else {
                ToolResult(false, "Closing apps requires Android 5.0+")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Permission denied to close $appKey. Grant KILL_BACKGROUND_PROCESSES permission.")
        } catch (e: Exception) {
            ToolResult(false, "Failed to close $appKey: ${e.message}")
        }
    }
}

