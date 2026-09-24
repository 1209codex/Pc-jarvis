package com.jarvis.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.*
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.ui.components.Ui
import com.jarvis.ui.model.ApiConfig
import com.jarvis.ui.model.AuthMethod
import com.jarvis.ui.reliability.ApiManager
import com.jarvis.ui.reliability.CorrelationLogger
import com.jarvis.ui.security.SecureCredentialStore
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class ApiManagerScreen(
    context: Context,
    private val owner: LifecycleOwner,
    private val manager: ApiManager,
    private val logger: CorrelationLogger,
    private val onChanged: (() -> Unit)? = null
) : ScrollView(context) {
    private val list = LinearLayout(context)
    private var testJob: Job? = null

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        addView(root)

        // Header
        val header = Ui.row(context)
        val brand = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(context).apply {
            text = "API & MODEL MANAGER"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.12f
        })
        brand.addView(TextView(context).apply {
            text = "KEYSTORE AES-GCM HARDWARE ENCRYPTED"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
            letterSpacing = 0.08f
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.hudBadge(context, "SECURED", Ui.SUCCESS))
        root.addView(header)

        root.addView(Ui.divider(context))

        // LLM Provider & Model Presets Card
        root.addView(Ui.sectionLabel(context, "Hybrid multi-provider models"))
        val modelCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val modelInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        val modelScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val modelRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        modelRow.addView(Ui.techChip(context, "gpt-oss-20b (Groq)", "⚡") {
            Toast.makeText(context, "Primary Groq model: openai/gpt-oss-20b", Toast.LENGTH_SHORT).show()
        })
        modelRow.addView(Ui.techChip(context, "Gemini 2.0 Flash", "✨") {
            showEditor(ApiConfig(name = "Google Gemini", baseUrl = "https://generativelanguage.googleapis.com", apiKeyHeader = "x-goog-api-key"))
        })
        modelRow.addView(Ui.techChip(context, "Claude 3.5 Sonnet", "🎭") {
            showEditor(ApiConfig(name = "OpenRouter (Claude)", baseUrl = "https://openrouter.ai/api/v1", apiKeyHeader = "Authorization"))
        })
        modelRow.addView(Ui.techChip(context, "DeepSeek R1", "🧠") {
            showEditor(ApiConfig(name = "OpenRouter (DeepSeek)", baseUrl = "https://openrouter.ai/api/v1", apiKeyHeader = "Authorization"))
        })
        modelRow.addView(Ui.techChip(context, "Ollama (Local)", "🖥️") {
            showEditor(ApiConfig(name = "Ollama Local", baseUrl = "http://10.0.2.2:11434", apiKeyHeader = "Authorization"))
        })
        modelScroll.addView(modelRow)
        modelInner.addView(modelScroll)
        modelCard.addView(modelInner)
        root.addView(modelCard)

        // Actions
        val actionRow = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
        }
        actionRow.addView(TextView(context).apply {
            text = "ACTIVE ENDPOINTS"
            textSize = 10.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.12f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        actionRow.addView(Ui.primaryButton(context, "+ ADD API") { showEditor(null) })
        root.addView(actionRow)

        list.orientation = LinearLayout.VERTICAL
        root.addView(list)
        refresh()
    }

    fun refresh() {
        list.removeAllViews()
        val apis = manager.list()
        if (apis.isEmpty()) {
            list.addView(TextView(context).apply {
                text = "No custom APIs configured. Add Groq or another LLM endpoint."
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, Ui.dp(context, 8), 0, 0)
            })
            return
        }
        apis.forEach { config ->
            val card = Ui.glassCard(context, if (config.active) Ui.CYAN_DIM else Ui.BORDER)
            val inner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
            }
            val header = Ui.row(context)
            header.addView(TextView(context).apply {
                text = config.name
                textSize = 15f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (config.active) Ui.CYAN else Ui.TEXT)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            val active = MaterialSwitch(context).apply {
                isChecked = config.active
                text = if (config.active) "Active" else "Inactive"
                setTextColor(if (config.active) Ui.SUCCESS else Ui.MUTED)
            }
            header.addView(active)
            inner.addView(header)

            inner.addView(TextView(context).apply {
                text = "${config.baseUrl}\n${config.authMethod.name} • ${config.timeoutMs} ms"
                textSize = 11.5f
                setTextColor(Ui.MUTED)
                setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
            })

            val actions = Ui.row(context)
            actions.addView(Ui.primaryButton(context, "TEST PING") { runTest(config) })
            actions.addView(Ui.button(context, "EDIT") { showEditor(config) })
            actions.addView(Ui.dangerButton(context, "DELETE") {
                manager.delete(config.id)
                refresh()
                onChanged?.invoke()
            })
            inner.addView(actions)
            active.setOnCheckedChangeListener { _, checked ->
                manager.save(config.copy(active = checked, updatedAt = System.currentTimeMillis()), null)
                refresh()
                onChanged?.invoke()
            }
            card.addView(inner)
            list.addView(card)
        }
    }

    private fun runTest(config: ApiConfig) {
        testJob?.cancel()
        Toast.makeText(context, "Testing ${config.name}…", Toast.LENGTH_SHORT).show()
        testJob = owner.lifecycleScope.launch {
            val result = manager.testConnection(config, 3)
            result.fold(
                onSuccess = { code -> Toast.makeText(context, "Connection OK (HTTP $code)", Toast.LENGTH_LONG).show() },
                onFailure = { e ->
                    val id = logger.error("ApiManager", "${config.name} test failed", e)
                    val cached = manager.lastKnownStatus(config.id)
                    val fallback = cached?.let { " Last known good: $it." } ?: " No cached success available."
                    Toast.makeText(context, "Failed. Correlation ${id.take(8)}. Check Logs.$fallback", Toast.LENGTH_LONG).show()
                }
            )
        }
    }

    private fun showEditor(existing: ApiConfig?) {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(Ui.dp(context,18), Ui.dp(context,8), Ui.dp(context,18), Ui.dp(context,8)) }
        val name = Ui.input(context, "API name"); val url = Ui.input(context, "Endpoint URL"); val keyHeader = Ui.input(context, "API-key header (default Authorization)"); val secret = Ui.input(context, secretHint(existing?.authMethod ?: AuthMethod.API_KEY))
        container.addView(name.first); container.addView(url.first)
        val auth = Spinner(context).apply { adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, AuthMethod.entries.map { it.name }) }
        container.addView(TextView(context).apply { text = "Authentication"; setTextColor(Color.WHITE) }); container.addView(auth)
        container.addView(keyHeader.first); container.addView(secret.first)
        val active = MaterialSwitch(context).apply { text = "Active"; isChecked = existing?.active ?: true }
        container.addView(active)
        val timeout = Ui.input(context, "Timeout (ms)"); container.addView(timeout.first)

        name.second.setText(existing?.name ?: "")
        url.second.setText(existing?.baseUrl ?: "")
        keyHeader.second.setText(existing?.apiKeyHeader ?: "Authorization")
        timeout.second.setText((existing?.timeoutMs ?: 30_000L).toString())
        if (existing != null) auth.setSelection(AuthMethod.entries.indexOf(existing.authMethod).coerceAtLeast(0))
        auth.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { secret.first.hint = secretHint(AuthMethod.entries[position]) }
        }

        androidx.appcompat.app.AlertDialog.Builder(context)
            .setTitle(if (existing == null) "Add API" else "Edit API")
            .setView(container)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Save") { _, _ ->
                val selected = AuthMethod.entries[auth.selectedItemPosition]
                val base = existing ?: ApiConfig()
                val cfg = base.copy(
                    name = name.second.text?.toString()?.trim().orEmpty().ifBlank { "API" },
                    baseUrl = url.second.text?.toString()?.trim().orEmpty(),
                    authMethod = selected,
                    apiKeyHeader = keyHeader.second.text?.toString()?.trim().orEmpty().ifBlank { "Authorization" },
                    active = active.isChecked,
                    timeoutMs = timeout.second.text?.toString()?.toLongOrNull()?.coerceIn(1_000L, 300_000L) ?: 30_000L,
                    updatedAt = System.currentTimeMillis()
                )
                try {
                    manager.validateUrl(cfg.baseUrl)
                    val secretText = secret.second.text?.toString()
                    manager.save(cfg, secretText?.takeIf { it.isNotEmpty() })
                    refresh()
                    onChanged?.invoke()
                } catch (e: Throwable) {
                    val id = logger.error("ApiManager", "Invalid API configuration", e)
                    Toast.makeText(context, "Not saved: ${e.message} (${id.take(8)})", Toast.LENGTH_LONG).show()
                }
            }
            .show()
    }

    private fun secretHint(method: AuthMethod): String = when (method) {
        AuthMethod.API_KEY -> "API key (stored encrypted)"
        AuthMethod.OAUTH2 -> "OAuth 2.0 access token (stored encrypted)"
        AuthMethod.BEARER -> "Bearer token (stored encrypted)"
        AuthMethod.BASIC -> "Basic auth as username:password (stored encrypted)"
    }
}
