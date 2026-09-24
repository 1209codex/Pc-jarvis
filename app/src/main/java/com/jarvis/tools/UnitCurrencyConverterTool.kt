package com.jarvis.tools

import android.content.Context
import android.util.Log
import com.jarvis.foundation.ParameterSchema
import com.jarvis.foundation.RiskLevel
import com.jarvis.foundation.ToolMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Currency and Scientific Unit Conversion Tool.
 * Supports live global currency exchange rates (with offline fallbacks)
 * and full-precision scientific unit conversions (length, mass, temperature, speed, data, volume).
 */
class UnitCurrencyConverterTool(private val context: Context? = null) : Tool {
    private val TAG = "UnitCurrencyConverter"

    override val name: String = "UNIT_CONVERTER"
    override val description: String =
        "Converts currencies and scientific units. Parameters: 'action' ('currency', 'unit'), 'amount' (number), 'from' (e.g. 'USD', 'km', 'celsius', 'kg'), 'to' (e.g. 'INR', 'miles', 'fahrenheit', 'lbs')."
    override val policy: ToolPolicy = ToolPolicy(idempotent = true, retryable = true, riskLevel = RiskLevel.LOW, timeoutMs = 10_000L)

    val metadata = ToolMetadata(
        name = "UNIT_CONVERTER",
        description = "Live currency exchange rates and physical/scientific unit conversions.",
        parameters = listOf(
            ParameterSchema("action", "string", "Conversion type: 'currency' or 'unit'", required = false),
            ParameterSchema("amount", "number", "Numeric quantity to convert", required = true),
            ParameterSchema("from", "string", "Source unit or currency code (e.g. USD, km, kg, C)", required = true),
            ParameterSchema("to", "string", "Target unit or currency code (e.g. INR, miles, lbs, F)", required = true)
        ),
        riskLevel = RiskLevel.LOW
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    // Cached rates with timestamp: "USD" -> map of currency to rate relative to USD
    private val cachedRates = ConcurrentHashMap<String, Double>()
    private var lastRateFetchTime: Long = 0L

    // Offline fallback rates relative to 1 USD
    private val fallbackUsdRates = mapOf(
        "USD" to 1.0,
        "INR" to 86.80,
        "EUR" to 0.95,
        "GBP" to 0.79,
        "JPY" to 154.50,
        "AED" to 3.67,
        "CAD" to 1.41,
        "AUD" to 1.56,
        "CHF" to 0.89,
        "CNY" to 7.24,
        "SGD" to 1.35
    )

    override suspend fun execute(params: Map<String, String>): ToolResult = withContext(Dispatchers.IO) {
        val rawAmount = params["amount"] ?: params["value"] ?: params["quantity"] ?: "1.0"
        val amount = rawAmount.toDoubleOrNull() ?: 1.0
        val fromRaw = (params["from"] ?: params["source"] ?: "").trim()
        val toRaw = (params["to"] ?: params["target"] ?: "").trim()

        if (fromRaw.isBlank() || toRaw.isBlank()) {
            return@withContext ToolResult.Failed("Please provide both 'from' and 'to' units or currencies.")
        }

        val action = params["action"]?.trim()?.lowercase() ?: detectAction(fromRaw, toRaw)

        return@withContext when (action) {
            "currency" -> convertCurrency(amount, fromRaw.uppercase(), toRaw.uppercase())
            "unit" -> convertUnit(amount, fromRaw.lowercase(), toRaw.lowercase())
            else -> convertUnit(amount, fromRaw.lowercase(), toRaw.lowercase())
        }
    }

    private fun detectAction(from: String, to: String): String {
        val f = from.uppercase()
        val t = to.uppercase()
        val currencyCodes = setOf("USD", "INR", "EUR", "GBP", "JPY", "AED", "CAD", "AUD", "CHF", "CNY", "SGD", "RUB", "BRL", "KRW", "SAR")
        return if (currencyCodes.contains(f) || currencyCodes.contains(t) || f.contains("DOLLAR") || f.contains("RUPEE")) {
            "currency"
        } else {
            "unit"
        }
    }

    private fun convertCurrency(amount: Double, fromCode: String, toCode: String): ToolResult {
        val from = normalizeCurrencyCode(fromCode)
        val to = normalizeCurrencyCode(toCode)

        // Try to fetch or use cached rates
        val rates = getExchangeRates()
        val fromRateInUsd = rates[from] ?: fallbackUsdRates[from]
        val toRateInUsd = rates[to] ?: fallbackUsdRates[to]

        if (fromRateInUsd == null || toRateInUsd == null) {
            return ToolResult.Failed("Unsupported currency conversion between '$from' and '$to'.")
        }

        // 1 USD = fromRateInUsd FROM_CURRENCY => 1 FROM_CURRENCY = (1 / fromRateInUsd) USD
        // => targetAmount = amount * (toRateInUsd / fromRateInUsd)
        val converted = amount * (toRateInUsd / fromRateInUsd)
        val formattedConverted = String.format("%,.2f", converted)
        val formattedAmount = String.format("%,.2f", amount)

        val message = "$formattedAmount $from = $formattedConverted $to (Rate: 1 $from = ${String.format("%.4f", toRateInUsd / fromRateInUsd)} $to)"
        return ToolResult.Success(
            message = message,
            data = mapOf(
                "amount" to amount,
                "from" to from,
                "to" to to,
                "result" to converted,
                "rate" to (toRateInUsd / fromRateInUsd)
            )
        )
    }

    private fun normalizeCurrencyCode(code: String): String {
        return when (code.uppercase()) {
            "RUPEE", "RUPEES", "RS", "INR" -> "INR"
            "DOLLAR", "DOLLARS", "USD", "$" -> "USD"
            "EURO", "EUROS", "EUR", "€" -> "EUR"
            "POUND", "POUNDS", "GBP", "£" -> "GBP"
            "DIRHAM", "DIRHAMS", "AED" -> "AED"
            "YEN", "JPY", "¥" -> "JPY"
            else -> code.uppercase()
        }
    }

    private fun getExchangeRates(): Map<String, Double> {
        val now = System.currentTimeMillis()
        if (cachedRates.isNotEmpty() && (now - lastRateFetchTime < 3600_000L)) {
            return cachedRates
        }

        try {
            val req = Request.Builder()
                .url("https://open.er-api.com/v6/latest/USD")
                .build()

            httpClient.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    if (body.isNotBlank()) {
                        val json = JSONObject(body)
                        val ratesObj = json.optJSONObject("rates")
                        if (ratesObj != null) {
                            cachedRates.clear()
                            val keys = ratesObj.keys()
                            while (keys.hasNext()) {
                                val k = keys.next()
                                cachedRates[k.uppercase()] = ratesObj.optDouble(k, 1.0)
                            }
                            lastRateFetchTime = now
                            return cachedRates
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch live exchange rates, falling back to cached/default", e)
        }

        return if (cachedRates.isNotEmpty()) cachedRates else fallbackUsdRates
    }

    private fun convertUnit(amount: Double, fromUnit: String, toUnit: String): ToolResult {
        val from = fromUnit.lowercase().trim()
        val to = toUnit.lowercase().trim()

        // 1. Temperature conversions
        if (isTemperatureUnit(from) && isTemperatureUnit(to)) {
            val result = convertTemperature(amount, from, to)
            return ToolResult.Success(
                message = "$amount ${from.uppercase()} = ${String.format("%.2f", result)} ${to.uppercase()}",
                data = mapOf("amount" to amount, "from" to from, "to" to to, "result" to result)
            )
        }

        // 2. Length (standardized to meters)
        val lengthToMeters = mapOf(
            "m" to 1.0, "meter" to 1.0, "meters" to 1.0,
            "km" to 1000.0, "kilometer" to 1000.0, "kilometers" to 1000.0,
            "cm" to 0.01, "centimeter" to 0.01, "centimeters" to 0.01,
            "mm" to 0.001, "millimeter" to 0.001, "millimeters" to 0.001,
            "mi" to 1609.344, "mile" to 1609.344, "miles" to 1609.344,
            "ft" to 0.3048, "foot" to 0.3048, "feet" to 0.3048,
            "in" to 0.0254, "inch" to 0.0254, "inches" to 0.0254,
            "yd" to 0.9144, "yard" to 0.9144, "yards" to 0.9144
        )
        if (lengthToMeters.containsKey(from) && lengthToMeters.containsKey(to)) {
            val meters = amount * lengthToMeters[from]!!
            val result = meters / lengthToMeters[to]!!
            return ToolResult.Success(
                message = "$amount $from = ${String.format("%.4f", result)} $to",
                data = mapOf("amount" to amount, "from" to from, "to" to to, "result" to result)
            )
        }

        // 3. Mass / Weight (standardized to kilograms)
        val massToKg = mapOf(
            "kg" to 1.0, "kilogram" to 1.0, "kilograms" to 1.0,
            "g" to 0.001, "gram" to 0.001, "grams" to 0.001,
            "mg" to 0.000001, "milligram" to 0.000001,
            "lb" to 0.45359237, "lbs" to 0.45359237, "pound" to 0.45359237, "pounds" to 0.45359237,
            "oz" to 0.0283495, "ounce" to 0.0283495, "ounces" to 0.0283495,
            "ton" to 1000.0, "tonne" to 1000.0, "tons" to 1000.0
        )
        if (massToKg.containsKey(from) && massToKg.containsKey(to)) {
            val kg = amount * massToKg[from]!!
            val result = kg / massToKg[to]!!
            return ToolResult.Success(
                message = "$amount $from = ${String.format("%.4f", result)} $to",
                data = mapOf("amount" to amount, "from" to from, "to" to to, "result" to result)
            )
        }

        // 4. Speed (standardized to km/h)
        val speedToKmh = mapOf(
            "km/h" to 1.0, "kmh" to 1.0, "kph" to 1.0,
            "mph" to 1.609344, "miles/hour" to 1.609344,
            "m/s" to 3.6, "mps" to 3.6,
            "knot" to 1.852, "knots" to 1.852
        )
        if (speedToKmh.containsKey(from) && speedToKmh.containsKey(to)) {
            val kmh = amount * speedToKmh[from]!!
            val result = kmh / speedToKmh[to]!!
            return ToolResult.Success(
                message = "$amount $from = ${String.format("%.2f", result)} $to",
                data = mapOf("amount" to amount, "from" to from, "to" to to, "result" to result)
            )
        }

        // 5. Digital Storage (standardized to bytes)
        val dataToBytes = mapOf(
            "b" to 1.0, "byte" to 1.0, "bytes" to 1.0,
            "kb" to 1024.0, "kilobyte" to 1024.0,
            "mb" to 1024.0 * 1024.0, "megabyte" to 1024.0 * 1024.0,
            "gb" to 1024.0 * 1024.0 * 1024.0, "gigabyte" to 1024.0 * 1024.0 * 1024.0,
            "tb" to 1024.0 * 1024.0 * 1024.0 * 1024.0, "terabyte" to 1024.0 * 1024.0 * 1024.0 * 1024.0
        )
        if (dataToBytes.containsKey(from) && dataToBytes.containsKey(to)) {
            val bytes = amount * dataToBytes[from]!!
            val result = bytes / dataToBytes[to]!!
            return ToolResult.Success(
                message = "$amount $from = ${String.format("%.4f", result)} $to",
                data = mapOf("amount" to amount, "from" to from, "to" to to, "result" to result)
            )
        }

        return ToolResult.Failed("Unsupported unit conversion between '$from' and '$to'. Supported categories: length, mass, temperature, speed, storage, currency.")
    }

    private fun isTemperatureUnit(u: String): Boolean {
        return u in listOf("c", "celsius", "f", "fahrenheit", "k", "kelvin")
    }

    private fun convertTemperature(amount: Double, from: String, to: String): Double {
        // Convert from source to Celsius first
        val c = when (from) {
            "c", "celsius" -> amount
            "f", "fahrenheit" -> (amount - 32.0) * (5.0 / 9.0)
            "k", "kelvin" -> amount - 273.15
            else -> amount
        }
        // Convert from Celsius to target
        return when (to) {
            "c", "celsius" -> c
            "f", "fahrenheit" -> (c * (9.0 / 5.0)) + 32.0
            "k", "kelvin" -> c + 273.15
            else -> c
        }
    }
}
