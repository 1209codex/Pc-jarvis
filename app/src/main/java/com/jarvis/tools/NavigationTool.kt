package com.jarvis.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata

/**
 * Navigation & Nearby Location Search Tool.
 * Dispatches turn-by-turn navigation intents and nearby amenity searches via Google Maps.
 */
class NavigationTool(private val context: Context? = null) : Tool {
    private val TAG = "NavigationTool"

    override val name: String = "NAVIGATION"
    override val description: String =
        "Provides turn-by-turn GPS navigation and nearby places search. Parameters: 'action' ('navigate', 'nearby', 'search'), 'destination' (e.g. 'Connaught Place Delhi', 'Airport'), 'place_type' (e.g. 'petrol pump', 'hospital', 'atm', 'restaurant')."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW)

    val metadata = ToolMetadata(
        name = "NAVIGATION",
        description = "Turn-by-turn map navigation and nearby point of interest discovery.",
        parameters = listOf(
            ParameterSchema("action", "string", "Action: navigate, nearby, search", required = false),
            ParameterSchema("destination", "string", "Target destination address or landmark", required = false),
            ParameterSchema("place_type", "string", "Type of nearby place to locate: petrol pump, atm, hospital, restaurant", required = false)
        ),
        riskLevel = RiskLevel.LOW
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val action = params["action"]?.trim()?.lowercase()
            ?: if (params.containsKey("place_type") || params.containsKey("type") || params.containsKey("nearby")) "nearby" else "navigate"

        val destination = (params["destination"] ?: params["to"] ?: params["address"] ?: params["location"] ?: "").trim()
        val placeType = (params["place_type"] ?: params["type"] ?: params["query"] ?: params["nearby"] ?: destination).trim()

        return when (action) {
            "navigate", "directions", "route" -> {
                if (destination.isBlank()) {
                    return ToolResult.Failed("Please provide a destination for navigation.")
                }
                launchNavigation(destination)
            }
            "nearby", "find_nearby", "places" -> {
                val query = if (placeType.isNotBlank()) placeType else "places to visit"
                launchNearbySearch(query)
            }
            "search", "explore" -> {
                val query = destination.ifBlank { placeType }.ifBlank { "nearby places" }
                launchNearbySearch(query)
            }
            else -> {
                if (destination.isNotBlank()) {
                    launchNavigation(destination)
                } else {
                    launchNearbySearch(placeType.ifBlank { "nearby places" })
                }
            }
        }
    }

    private fun launchNavigation(destination: String): ToolResult {
        val ctx = context ?: return ToolResult.Success(
            message = "Simulated turn-by-turn navigation started to '$destination'.",
            data = mapOf("destination" to destination, "status" to "simulated")
        )

        try {
            // Try Google Navigation URI first
            val navUri = Uri.parse("google.navigation:q=" + Uri.encode(destination))
            val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                setPackage("com.google.android.apps.maps")
            }

            if (mapIntent.resolveActivity(ctx.packageManager) != null) {
                ctx.startActivity(mapIntent)
            } else {
                // Fallback to generic geo URI
                val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(destination))
                val fallbackIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                ctx.startActivity(fallbackIntent)
            }

            return ToolResult.Success(
                message = "Initiating navigation to $destination, Sir. Route calculated and displayed on Google Maps.",
                data = mapOf("destination" to destination, "launched" to true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch navigation", e)
            return ToolResult.Failed("Unable to open navigation: ${e.message}")
        }
    }

    private fun launchNearbySearch(query: String): ToolResult {
        val ctx = context ?: return ToolResult.Success(
            message = "Simulated search for nearby '$query' executed.",
            data = mapOf("query" to query, "status" to "simulated")
        )

        try {
            val geoUri = Uri.parse("geo:0,0?q=" + Uri.encode(query))
            val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            ctx.startActivity(mapIntent)

            return ToolResult.Success(
                message = "Searching for nearby $query on Maps, Sir.",
                data = mapOf("query" to query, "launched" to true)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to search nearby places", e)
            return ToolResult.Failed("Unable to open Maps for nearby search: ${e.message}")
        }
    }
}
