package com.jarvis.tools

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class AssistantJarvisProtocolsTest {

    @Test
    fun testJarvisProtocolTool() = runBlocking {
        val protocolTool = JarvisProtocolTool()

        // 1. Diagnostics test
        val diagRes = protocolTool.execute(mapOf("action" to "diagnostics"))
        assertTrue("Diagnostics should succeed", diagRes.success)
        assertTrue(diagRes.message.contains("operational") || diagRes.message.contains("diagnostic"))

        // 2. Stealth Protocol
        val stealthRes = protocolTool.execute(mapOf("action" to "protocol", "protocol" to "stealth"))
        assertTrue("Stealth protocol should succeed", stealthRes.success)
        assertTrue(stealthRes.message.contains("stealth", ignoreCase = true))

        // 3. Work Protocol
        val workRes = protocolTool.execute(mapOf("protocol" to "work"))
        assertTrue("Work protocol should succeed", workRes.success)
        assertTrue(workRes.message.contains("work", ignoreCase = true))

        // 4. Emergency Protocol
        val emRes = protocolTool.execute(mapOf("action" to "protocol", "protocol" to "emergency"))
        assertTrue("Emergency protocol should succeed", emRes.success)
        assertTrue(emRes.message.contains("emergency", ignoreCase = true))

        // 5. Normal Protocol
        val normRes = protocolTool.execute(mapOf("protocol" to "normal"))
        assertTrue("Normal protocol should succeed", normRes.success)
        assertTrue(normRes.message.contains("normal", ignoreCase = true) || normRes.message.contains("standard", ignoreCase = true))
    }

    @Test
    fun testNavigationTool() = runBlocking {
        val navTool = NavigationTool()

        // 1. Destination navigation
        val navRes = navTool.execute(mapOf("action" to "navigate", "destination" to "India Gate, New Delhi"))
        assertTrue("Navigation dispatch should succeed", navRes.success)
        assertTrue(navRes.message.contains("India Gate"))

        // 2. Nearby search
        val nearbyRes = navTool.execute(mapOf("action" to "nearby", "place_type" to "petrol pump"))
        assertTrue("Nearby search should succeed", nearbyRes.success)
        assertTrue(nearbyRes.message.contains("petrol pump"))

        // 3. Missing destination failure check
        val failRes = navTool.execute(mapOf("action" to "navigate"))
        assertFalse("Missing destination should fail gracefully", failRes.success)
    }

    @Test
    fun testUnitCurrencyConverterTool() = runBlocking {
        val converter = UnitCurrencyConverterTool()

        // 1. Currency Conversion (USD to INR)
        val usdToInr = converter.execute(mapOf("from" to "USD", "to" to "INR", "amount" to "10"))
        assertTrue("USD to INR conversion should succeed", usdToInr.success)
        val inrData = (usdToInr as ToolResult.Success).data["result"] as Double
        assertTrue("10 USD should be > 500 INR", inrData > 500.0)

        // 2. Length Conversion (km to miles)
        val kmToMiles = converter.execute(mapOf("from" to "km", "to" to "miles", "amount" to "5"))
        assertTrue("km to miles should succeed", kmToMiles.success)
        val miles = (kmToMiles as ToolResult.Success).data["result"] as Double
        assertEquals(3.1068, miles, 0.01)

        // 3. Temperature (Celsius to Fahrenheit)
        val cToF = converter.execute(mapOf("from" to "c", "to" to "f", "amount" to "100"))
        assertTrue("100 C to F should succeed", cToF.success)
        val tempF = (cToF as ToolResult.Success).data["result"] as Double
        assertEquals(212.0, tempF, 0.1)

        // 4. Weight/Mass (kg to lbs)
        val kgToLbs = converter.execute(mapOf("from" to "kg", "to" to "lbs", "amount" to "1"))
        assertTrue("1 kg to lbs should succeed", kgToLbs.success)
        val lbs = (kgToLbs as ToolResult.Success).data["result"] as Double
        assertEquals(2.2046, lbs, 0.01)

        // 5. Data storage (GB to MB)
        val gbToMb = converter.execute(mapOf("from" to "gb", "to" to "mb", "amount" to "2"))
        assertTrue("2 GB to MB should succeed", gbToMb.success)
        val mb = (gbToMb as ToolResult.Success).data["result"] as Double
        assertEquals(2048.0, mb, 0.1)
    }

    @Test
    fun testToolRegistryResolutionForNewTools() {
        val registry = ToolRegistry()
        registry.register(JarvisProtocolTool())
        registry.register(NavigationTool())
        registry.register(UnitCurrencyConverterTool())

        assertEquals("JARVIS_PROTOCOL", registry.resolveCanonicalToolName("DIAGNOSTICS"))
        assertEquals("JARVIS_PROTOCOL", registry.resolveCanonicalToolName("STEALTH_MODE"))
        assertEquals("NAVIGATION", registry.resolveCanonicalToolName("NAVIGATE"))
        assertEquals("NAVIGATION", registry.resolveCanonicalToolName("NEARBY"))
        assertEquals("UNIT_CONVERTER", registry.resolveCanonicalToolName("CURRENCY"))
        assertEquals("UNIT_CONVERTER", registry.resolveCanonicalToolName("CONVERT"))
    }
}
