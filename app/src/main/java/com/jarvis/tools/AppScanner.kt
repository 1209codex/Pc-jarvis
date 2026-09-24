package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

data class InstalledAppInfo(
    val label: String,
    val packageName: String,
    val activityName: String?,
    val isSystemApp: Boolean
)

object AppScanner {
    private const val TAG = "AppScanner"
    private val cachedApps = ConcurrentHashMap<String, InstalledAppInfo>()
    @Volatile
    private var isScanned = false

    /**
     * Scans all installed applications on the device that have a launchable MAIN/LAUNCHER intent.
     * Thread-safe and caches results for instant future lookups.
     */
    fun scanInstalledApps(context: Context, forceRefresh: Boolean = false): List<InstalledAppInfo> {
        if (isScanned && !forceRefresh && cachedApps.isNotEmpty()) {
            return cachedApps.values.toList().sortedBy { it.label.lowercase(Locale.ROOT) }
        }

        return try {
            val pm = context.packageManager
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            val resolveInfos = pm.queryIntentActivities(mainIntent, 0)
            val result = mutableListOf<InstalledAppInfo>()

            for (info in resolveInfos) {
                val pkgName = info.activityInfo.packageName
                val activityName = info.activityInfo.name
                val label = try {
                    info.loadLabel(pm).toString()
                } catch (_: Exception) {
                    pkgName.substringAfterLast(".")
                }
                val isSystem = (info.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0

                val appInfo = InstalledAppInfo(
                    label = label,
                    packageName = pkgName,
                    activityName = activityName,
                    isSystemApp = isSystem
                )
                cachedApps[pkgName] = appInfo
                result.add(appInfo)
            }

            isScanned = true
            Log.i(TAG, "Scanned ${result.size} launchable installed applications.")
            result.sortedBy { it.label.lowercase(Locale.ROOT) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to scan installed apps: ${e.message}", e)
            cachedApps.values.toList()
        }
    }

    /**
     * Finds installed apps matching query in either label or package name.
     */
    fun searchApps(context: Context, query: String): List<InstalledAppInfo> {
        val q = query.trim().lowercase(Locale.ROOT)
        if (q.isBlank()) return emptyList()

        val allApps = scanInstalledApps(context)
        return allApps.filter { app ->
            val l = app.label.lowercase(Locale.ROOT)
            val p = app.packageName.lowercase(Locale.ROOT)
            l.contains(q) || q.contains(l) || p.contains(q)
        }
    }

    /**
     * Returns total count of installed launchable apps.
     */
    fun getInstalledAppsCount(context: Context): Int {
        return scanInstalledApps(context).size
    }

    /**
     * Clears cache and forces rescan.
     */
    fun refresh(context: Context): List<InstalledAppInfo> {
        cachedApps.clear()
        isScanned = false
        return scanInstalledApps(context, forceRefresh = true)
    }
}
