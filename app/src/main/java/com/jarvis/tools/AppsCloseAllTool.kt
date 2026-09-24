package com.jarvis.tools

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class AppsCloseAllTool(private val context: Context? = null) : Tool {
    override val name: String = "APPS_CLOSE_ALL"
    override val description: String = "Closes all running apps and returns to home screen. No parameters required."

    @Suppress("DEPRECATION")
    override suspend fun execute(params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult(true, "All apps closed")
        return try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

            // BUG-007 fix: getRunningAppProcesses() only returns own app since API 22.
            // Use getAppTasks() to remove tasks from recents — this is the supported non-root approach.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                val tasks = am.appTasks
                if (tasks != null) {
                    for (task in tasks) {
                        try {
                            task.finishAndRemoveTask()
                        } catch (e: Exception) {
                            // Some tasks may not be removable; continue
                        }
                    }
                }
                // Go to home screen
                val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(homeIntent)
                ToolResult(true, "All apps closed")
            } else {
                ToolResult(false, "Closing apps requires Android 5.0+")
            }
        } catch (e: SecurityException) {
            ToolResult(false, "Permission denied to close apps")
        } catch (e: Exception) {
            ToolResult(false, "Failed to close apps: ${e.message}")
        }
    }
}
