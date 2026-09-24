package com.jarvis

import com.jarvis.location.NearbyPlaceCheck
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NearbyPlaceCheckTest {

    private val home = NearbyPlaceCheck.Place("home", lat = 22.3119, lon = 73.1806, ssids = listOf("JarvisHome"))
    private val work = NearbyPlaceCheck.Place("work", lat = 23.0306, lon = 72.5804, ssids = listOf("Office"))
    private val places = listOf(home, work)

    @Test
    fun ssidMatchWinsWithoutGps() {
        val v = NearbyPlaceCheck.nearestPlace(NearbyPlaceCheck.Evidence(ssid = "jarviShome"), places)
        assertTrue(v.isNearby)
        assertEquals("home", v.placeName)
        assertEquals("ssid", v.method)
    }

    @Test
    fun ssidUnmatchedFallsBackToGpsWithinRadius() {
        val nearHome = NearbyPlaceCheck.Evidence(ssid = "SomeCafe", lat = 22.3125, lon = 73.1810)
        val v = NearbyPlaceCheck.nearestPlace(nearHome, places)
        assertTrue(v.isNearby)
        assertEquals("home", v.placeName)
        assertEquals("gps", v.method)
    }

    @Test
    fun gpsTooFarFromEveryPlaceReportsNotNearby() {
        val far = NearbyPlaceCheck.Evidence(lat = 19.0760, lon = 72.8777)
        val v = NearbyPlaceCheck.nearestPlace(far, places)
        assertFalse(v.isNearby)
        assertNull(v.placeName)
        assertEquals("gps", v.method)
    }

    @Test
    fun noEvidenceIsHonestlyUnknown() {
        val v = NearbyPlaceCheck.nearestPlace(NearbyPlaceCheck.Evidence(), places)
        assertFalse(v.isNearby)
        assertEquals("none", v.method)
        assertNull(v.placeName)
    }

    @Test
    fun haversineKnownDistanceDelhiToAhmedabad() {
        // Approx 780 km great-circle between Delhi and Ahmedabad.
        val km = NearbyPlaceCheck.haversineKm(28.6139, 77.2090, 23.0225, 72.5714)
        assertEquals(780.0, km, 40.0)
    }

    @Test
    fun nearestPicksClosestPlace() {
        val e = NearbyPlaceCheck.Evidence(lat = 22.3120, lon = 73.1808)
        assertEquals("home", NearbyPlaceCheck.nearestPlace(e, places).placeName)
        assertNotNull(NearbyPlaceCheck.nearestPlace(e, places).distanceMeters)
    }
}