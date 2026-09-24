package com.jarvis.ui.reliability

import android.util.Base64
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.ui.model.ApiConfig
import com.jarvis.ui.model.AuthMethod
import com.jarvis.ui.security.SecureCredentialStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class ApiManager(private val store: UiPreferencesStore, private val credentials: SecureCredentialStore) {
    fun lastKnownStatus(apiId: String): String? = store.lastApiStatus(apiId)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build() // OkHttp supplies connection pooling for concurrent requests.
    private val activeCalls = Collections.newSetFromMap(ConcurrentHashMap<Call, Boolean>())

    fun list(): List<ApiConfig> = store.loadApis().sortedBy { it.name.lowercase() }

    fun save(config: ApiConfig, credential: String?) {
        if (credential != null) credentials.put(config.id, credential)
        val existing = store.loadApis().filterNot { it.id == config.id }
        val saved = if (credential != null) config.copy(credentialId = config.id) else config
        store.saveApis(existing + saved)
    }

    fun delete(id: String) {
        credentials.delete(id)
        store.saveApis(store.loadApis().filterNot { it.id == id })
    }

    suspend fun testConnection(config: ApiConfig, maxRetries: Int): Result<Int> {
        val validated = runCatching { validateUrl(config.baseUrl) }
        if (validated.isFailure) return Result.failure(validated.exceptionOrNull()!!)
        return try {
            Result.success(
                withContext(Dispatchers.IO) {
                    var lastErr: Throwable? = null
                    repeat(maxRetries.coerceAtLeast(1)) {
                        try {
                            return@withContext executeOnce(config)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Throwable) {
                            lastErr = e
                        }
                    }
                    throw lastErr ?: IOException("Test connection failed")
                }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    private suspend fun executeOnce(config: ApiConfig): Int = withContext(Dispatchers.IO) {
        ensureActive()
        val requestBuilder = Request.Builder().url(config.baseUrl.trim()).get()
        applyAuth(requestBuilder, config)
        val request = requestBuilder.header("X-Jarvis-Correlation-Id", java.util.UUID.randomUUID().toString()).build()
        val call = client.newCall(request)
        activeCalls.add(call)
        try {
            coroutineContext.job.invokeOnCompletion { call.cancel() }
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}: ${response.message}")
                store.saveLastApiStatus(config.id, response.code, response.message)
                response.code
            }
        } finally {
            activeCalls.remove(call)
        }
    }

    private fun applyAuth(builder: Request.Builder, config: ApiConfig) {
        val secret = config.credentialId?.let(credentials::get).orEmpty()
        when (config.authMethod) {
            AuthMethod.API_KEY -> if (secret.isNotBlank()) {
                val header = config.apiKeyHeader.ifBlank { "Authorization" }
                builder.header(header, secret)
            }
            AuthMethod.OAUTH2, AuthMethod.BEARER -> if (secret.isNotBlank()) {
                builder.header("Authorization", if (secret.startsWith("Bearer ")) secret else "Bearer $secret")
            }
            AuthMethod.BASIC -> if (secret.isNotBlank()) {
                builder.header("Authorization", "Basic ${Base64.encodeToString(secret.toByteArray(), Base64.NO_WRAP)}")
            }
        }
    }

    fun validateUrl(url: String): okhttp3.HttpUrl {
        val parsed = url.trim().toHttpUrlOrNull() ?: throw IllegalArgumentException("Enter a valid http(s) endpoint URL.")
        require(parsed.scheme == "http" || parsed.scheme == "https") { "Only HTTP and HTTPS endpoints are allowed." }
        require(parsed.host.isNotBlank()) { "Endpoint host is required." }
        return parsed
    }

    fun cancelAll() { activeCalls.forEach(Call::cancel) }
}