package com.jarvis.interaction

import android.app.ActivityManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.tools.Tool
import com.jarvis.tools.ToolResult

class OsSystemInspectionTool(private val context: Context? = null) : Tool {
    override val name: String = "OS_INSPECT_SYSTEM"

    val metadata = ToolMetadata(
        name = name,
        description = "Inspects OS health, memory, battery, and network status",
        parameters = listOf(
            ParameterSchema("target", "string", "Aspect to inspect: all | battery | memory | network", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val ctx = context ?: return ToolResult(true, "OS health check: System healthy (test mode)")
        val target = params["target"]?.lowercase() ?: "all"
        val sb = StringBuilder()

        if (target == "all" || target == "battery") {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val batLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            sb.append("Battery: $batLevel%\n")
        }

        if (target == "all" || target == "memory") {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            val availMb = memInfo.availMem / (1024 * 1024)
            val totalMb = memInfo.totalMem / (1024 * 1024)
            sb.append("Memory: Available ${availMb}MB / Total ${totalMb}MB\n")
        }

        if (target == "all" || target == "network") {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(network)
            val isWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            val isCell = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
            val isOnline = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
            sb.append("Network: Online=$isOnline, WiFi=$isWifi, Cellular=$isCell\n")
        }

        return ToolResult(true, sb.toString().trim())
    }
}
