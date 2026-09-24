package com.jarvis.location

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure proximity logic: decides whether the device is at/near a known place.
 * SSID evidence is preferred (works indoors, zero battery), GPS is the
 * fallback when there is no trusting place's SSID (or none recorded).
 */
object NearbyPlaceCheck {

    data class Place(
        val name: String,
        val lat: Double,
        val lon: Double,
        val radiusMeters: Double = 150.0,
        val ssids: List<String> = emptyList()
    )

    data class Evidence(
        val ssid: String? = null,
        val lat: Double? = null,
        val lon: Double? = null
    )

    data class Verdict(
        val isNearby: Boolean,
        val placeName: String? = null,
        val method: String = "ssid", // "ssid" | "gps" | "none"
        val distanceMeters: Double? = null
    )

    private val EARTH_RADIUS_KM = 6371.0

    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(a))
    }

    /** Nearest known place, preferring an exact SSID hit, else GPS within any place's radius. */
    fun nearestPlace(evidence: Evidence, places: List<Place>): Verdict {
        val currentSsid = evidence.ssid?.trim()?.lowercase()
        if (!currentSsid.isNullOrBlank()) {
            val ssidMatch = places.firstOrNull { place ->
                place.ssids.any { it.trim().lowercase() == currentSsid }
            }
            if (ssidMatch != null) {
                return Verdict(isNearby = true, placeName = ssidMatch.name, method = "ssid", distanceMeters = 0.0)
            }
        }

        val lat = evidence.lat
        val lon = evidence.lon
        if (lat != null && lon != null) {
            var nearest: Place? = null
            var nearestDist = Double.MAX_VALUE
            for (place in places) {
                val d = haversineKm(lat, lon, place.lat, place.lon) * 1000.0
                if (d < nearestDist) {
                    nearestDist = d
                    nearest = place
                }
            }
            if (nearest != null && nearestDist <= nearest.radiusMeters) {
                return Verdict(isNearby = true, placeName = nearest.name, method = "gps", distanceMeters = nearestDist)
            }
            return Verdict(isNearby = false, method = "gps", distanceMeters = nearestDist)
        }

        return Verdict(isNearby = false, method = "none")
    }
}