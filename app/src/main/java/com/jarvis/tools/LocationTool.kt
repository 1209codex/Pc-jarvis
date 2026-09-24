package com.jarvis.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.widget.Toast
import androidx.core.app.ActivityCompat
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import com.jarvis.location.NearbyPlaceCheck

/**
 * Location pack: current position, neighbourhood naming and home/work
 * proximity. Proximity answers use the live WiFi SSID when available, with a
 * GPS fix as fallback. Nothing is faked — a missing permission/.- fix returns
 * an honest failure.
 */
class LocationTool(private val context: Context? = null) : Tool {
    override val name: String = "LOCATION"
    override val description: String =
        "Knows where you are and whether you're home at work. Actions: 'where_am_i', 'get_location', 'nearby' (home/work), 'ssid'."

    val metadata = ToolMetadata(
        name = "LOCATION",
        description = "Gets GPS coordinates, network neighbourhood and home/work proximity.",
        parameters = emptyList(),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase() ?: "where_am_i"
        return when (action) {
            "ssid" -> { val ssid = currentSsid(); ToolResult.Success(ssid, mapOf("ssid" to ssid)) }
            "get_location" -> {
                val (text, data) = getLocation()
                if (data.isNotEmpty()) ToolResult.Success(text, data)
                else ToolResult.Failed(text)
            }
            "where_am_i" -> whereAmI()
            "nearby" -> nearby(params)
            "set_home" -> {
                val ssid = params["ssid"]?.ifBlank { null } ?: currentSsid()
                com.jarvis.context.AmbientContextEngine.instance?.setHomeSsid(ssid)
                ToolResult.Success("Saved home location WiFi as '$ssid'.")
            }
            "set_work" -> {
                val ssid = params["ssid"]?.ifBlank { null } ?: currentSsid()
                com.jarvis.context.AmbientContextEngine.instance?.setWorkSsid(ssid)
                ToolResult.Success("Saved work location WiFi as '$ssid'.")
            }
            "check_context", "ambient_mode" -> {
                val engine = com.jarvis.context.AmbientContextEngine.instance
                val ctx = engine?.evaluateCurrentContext() ?: com.jarvis.context.AmbientContext.UNKNOWN
                ToolResult.Success("Current ambient context is ${ctx.name}.", mapOf("context" to ctx.name))
            }
            "remind_at", "geofence_reminder" -> {
                val target = params["target"]?.lowercase() ?: "home"
                val text = params["reminder"] ?: params["text"] ?: ""
                val targetCtx = if (target.contains("work")) com.jarvis.context.AmbientContext.WORK else com.jarvis.context.AmbientContext.HOME
                com.jarvis.context.AmbientContextEngine.instance?.addGeofenceReminder(targetCtx, text)
                ToolResult.Success("I will remind you to '$text' when you reach ${targetCtx.name.lowercase()}.")
            }
            else -> ToolResult.Failed("Unknown location action '$action'. Supported: where_am_i, get_location, nearby, ssid, set_home, set_work, check_context, remind_at.")
        }
    }

    private fun currentSsid(): String {
        val ctx = context ?: return ""
        return try {
            val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
            wifi?.connectionInfo?.ssid?.replace("\"", "")?.takeIf { it.isNotBlank() && it != "<unknown ssid>" } ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun hasPermission(): Boolean {
        val ctx = context ?: return false
        return ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private fun gpsFix(): Location? {
        if (!hasPermission()) return null
        return try {
            val ctx = context ?: return null
            val manager = ctx.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
            val best = manager.getProviders(true).mapNotNull { provider ->
                try { manager.getLastKnownLocation(provider) } catch (e: SecurityException) { null }
            }.maxByOrNull { it.accuracy }
            best?.takeIf { System.currentTimeMillis() - it.time < 2 * 60 * 60 * 1000L }
        } catch (e: Exception) {
            null
        }
    }

    private fun addressText(lat: Double, lon: Double): String {
        val ctx = context ?: return ""
        return try {
            val geocoder = android.location.Geocoder(ctx)
            val results = geocoder.getFromLocation(lat, lon, 1)
            if (results.isNullOrEmpty()) "" else results[0].getAddressLine(0).takeIf { !it.isNullOrBlank() }
                ?: results[0].locality ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    private fun getLocation(): kotlin.Pair<String, Map<String, String>> {
        val fix = gpsFix()
        if (fix == null) {
            val permission = hasPermission()
            return kotlin.Pair(
                if (permission) "No recent GPS fix available. Make sure location is enabled."
                else "Location permission is not granted to Jarvis.",
                emptyMap()
            )
        }
        val data = mapOf(
            "latitude" to fix.latitude.toString(),
            "longitude" to fix.longitude.toString(),
            "accuracy_m" to fix.accuracy.toInt().toString(),
            "provider" to (fix.provider ?: "gps")
        )
        return kotlin.Pair("", data)
    }

    private fun whereAmI(): ToolResult {
        val ssid = currentSsid()
        val fix = gpsFix()
        if (fix == null && ssid.isBlank()) {
            return ToolResult.Failed(
                if (!hasPermission()) "Location permission is not granted to Jarvis."
                else "No recent GPS fix and no Wi-Fi — I can't tell where you are right now."
            )
        }
        val data = mutableMapOf<String, String>()
        if (fix != null) {
            data["latitude"] = fix.latitude.toString()
            data["longitude"] = fix.longitude.toString()
            data["accuracy_m"] = fix.accuracy.toInt().toString()
        }
        if (ssid.isNotBlank()) data["ssid"] = ssid

        val near = nearby("")
        val placeHint = if (near != null && near.isNearby) " You're at ${near.placeName}." else ""

        val address = if (fix != null) addressText(fix.latitude, fix.longitude).ifBlank { "" } else ""
        val ssidPart = if (ssid.isNotBlank()) " Connected to Wi-Fi '$ssid'." else ""
        val coordPart = if (fix != null) " Coordinates ${"%.4f".format(fix.latitude)}, ${"%.4f".format(fix.longitude)}." else ""
        val msg = buildString {
            append("You are")
            if (address.isNotBlank()) append(" near $address")
            append(".")
            append(placeHint)
            if (coordPart.isNotBlank() || ssidPart.isNotBlank()) {
                append(coordPart)
                append(ssidPart)
            }
            if (coordPart.isBlank() && ssidPart.isNotBlank()) append(" I'm using the Wi-Fi to guess your place; GPS accuracy limited.")
        }
        return ToolResult.Success(msg.trim(), data)
    }

    private fun nearby(params: Map<String, String>): ToolResult {
        val placeName = params["place"] ?: params["value"] ?: ""
        val near = nearby(placeName)
        return when {
            near == null -> ToolResult.Failed("No GPS fix and no Wi-Fi — can't determine proximity.")
            near.isNearby -> ToolResult.Success("You are at ${near.placeName}.", mapOf("place" to (near.placeName ?: ""), "method" to near.method, "distance_m" to (near.distanceMeters ?: 0.0).toInt().toString()))
            near.method == "gps" -> ToolResult.Success(
                "You are not at ${near.placeName} — about ${(near.distanceMeters ?: 0.0).toInt()} m away.",
                mapOf("place" to "", "method" to "gps")
            )
            else -> ToolResult.Failed("I don't know that known place and have no fix to locate you.")
        }
    }

    private fun nearby(placeName: String): NearbyPlaceCheck.Verdict? {
        val ssid = currentSsid()
        val fix = gpsFix()
        val evidence = NearbyPlaceCheck.Evidence(
            ssid = ssid,
            lat = fix?.latitude,
            lon = fix?.longitude
        )
        val places = if (placeName.isNotBlank()) {
            KNOWN_PLACES.filter { it.name.equals(placeName, ignoreCase = true) }.ifEmpty { KNOWN_PLACES }
        } else {
            KNOWN_PLACES
        }
        return NearbyPlaceCheck.nearestPlace(evidence, places)
    }

    companion object {
        val KNOWN_PLACES = listOf(
            NearbyPlaceCheck.Place(
                name = "home", lat = 22.3119, lon = 73.1806,
                ssids = listOf("JarvisHome", "MyHomeWiFi")
            ),
            NearbyPlaceCheck.Place(
                name = "work", lat = 23.0306, lon = 72.5804,
                ssids = listOf("Office", "CorpWiFi")
            )
        )
    }
}