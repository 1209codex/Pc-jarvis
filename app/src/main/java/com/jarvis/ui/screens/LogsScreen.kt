package com.jarvis.ui.screens

import android.content.Context
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import com.jarvis.data.JarvisDatabase
import com.jarvis.data.repository.TaskRepository
import com.jarvis.logs.LogReaderEngine
import com.jarvis.ui.components.Ui
import com.jarvis.ui.data.UiPreferencesStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LogsScreen(
    context: Context,
    private val store: UiPreferencesStore
) : ScrollView(context) {

    private val taskRepo = TaskRepository(JarvisDatabase.getInstance(context))
    private var activeTab = 0 // 0 = Task Audit Logs, 1 = Live Logcat, 2 = UI Diagnostics

    private val tabContainer = LinearLayout(context)
    private val contentContainer = LinearLayout(context)

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 20))
        }
        addView(root)

        // Header
        val header = Ui.row(context)
        val brand = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(context).apply {
            text = "TELEMETRY & LOGS"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.10f
        })
        brand.addView(TextView(context).apply {
            text = "TASK AUDIT • LOGCAT TRACES • CORRELATION LOGS"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        val refreshBtn = Ui.primaryButton(context, "SYNC") { render() }
        header.addView(refreshBtn)
        root.addView(header)

        root.addView(Ui.divider(context))

        // Tabs
        tabContainer.orientation = LinearLayout.HORIZONTAL
        tabContainer.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(context, 12)
        }
        root.addView(tabContainer)
        setupTabStrip()

        // Content
        contentContainer.orientation = LinearLayout.VERTICAL
        root.addView(contentContainer)

        render()
    }

    private fun setupTabStrip() {
        tabContainer.removeAllViews()
        val tabTitles = listOf("1. TASK AUDITS", "2. LOGCAT STREAM", "3. UI DIAGNOSTICS")
        tabTitles.forEachIndexed { index, title ->
            val tabButton = Button(context).apply {
                text = title
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (index == activeTab) Ui.BG else Ui.CYAN)
                setBackgroundColor(if (index == activeTab) Ui.CYAN else Ui.SURFACE_2)
                setPadding(Ui.dp(context, 10), Ui.dp(context, 6), Ui.dp(context, 10), Ui.dp(context, 6))
                setOnClickListener {
                    if (activeTab != index) {
                        activeTab = index
                        setupTabStrip()
                        render()
                    }
                }
            }
            tabContainer.addView(tabButton, LinearLayout.LayoutParams(0, Ui.dp(context, 38), 1f).apply {
                if (index < tabTitles.size - 1) marginEnd = Ui.dp(context, 4)
            })
        }
    }

    private fun render() {
        contentContainer.removeAllViews()
        when (activeTab) {
            0 -> renderTaskAuditsTab()
            1 -> renderLogcatTab()
            2 -> renderUiDiagnosticsTab()
        }
    }

    private fun renderTaskAuditsTab() {
        contentContainer.addView(Ui.sectionLabel(context, "Durable Task Execution & Step Audits"))

        val tasks = taskRepo.getActiveTasks()
        if (tasks.isEmpty()) {
            contentContainer.addView(TextView(context).apply {
                text = "No active tasks in persistent ledger."
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
            })
        } else {
            tasks.forEach { task ->
                val card = Ui.glassCard(context, Ui.BORDER_GLOW)
                val inner = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
                }

                val row = Ui.row(context)
                row.addView(TextView(context).apply {
                    text = "TASK #${task.id}: ${task.goal}"
                    textSize = 12f
                    typeface = Typeface.DEFAULT_BOLD
                    setTextColor(Ui.CYAN)
                }, LinearLayout.LayoutParams(0, -2, 1f))

                val badgeColor = when (task.status) {
                    "COMPLETED" -> Ui.SUCCESS
                    "FAILED", "CANCELLED" -> Ui.ERROR
                    else -> Ui.WARNING
                }
                row.addView(Ui.hudBadge(context, task.status, badgeColor))
                inner.addView(row)

                val auditLogs = taskRepo.getAuditLogsForTask(task.id)
                if (auditLogs.isNotEmpty()) {
                    inner.addView(TextView(context).apply {
                        text = "Execution Steps (${auditLogs.size}):"
                        textSize = 10f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Ui.MUTED)
                        setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 2))
                    })

                    auditLogs.forEach { log ->
                        val logText = "[${log.actionType}] ${log.status} • Risk: ${log.riskLevel} • ${log.details ?: log.reason.orEmpty()}"
                        inner.addView(TextView(context).apply {
                            text = "• $logText"
                            textSize = 9.5f
                            typeface = Typeface.MONOSPACE
                            setTextColor(if (log.status == "EXECUTED" || log.status == "VERIFIED") Ui.TEXT else Ui.WARNING)
                        })
                    }
                }

                card.addView(inner)
                contentContainer.addView(card)
            }
        }
    }

    private fun renderLogcatTab() {
        contentContainer.addView(Ui.sectionLabel(context, "Real-Time System Logcat Stream"))

        val logcatEngine = LogReaderEngine.instance ?: LogReaderEngine()
        val lines = logcatEngine.readLogcat(maxLines = 40)

        val logCard = Ui.glassCard(context, Ui.CYAN_DIM)
        val logInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
        }

        val logTextView = TextView(context).apply {
            text = lines.joinToString("\n").ifBlank { "No recent log entries." }
            textSize = 10f
            typeface = Typeface.MONOSPACE
            setTextColor(Ui.TEXT)
            setLineSpacing(0f, 1.2f)
        }
        logInner.addView(logTextView)
        logCard.addView(logInner)
        contentContainer.addView(logCard)
    }

    private fun renderUiDiagnosticsTab() {
        contentContainer.addView(Ui.sectionLabel(context, "UI Correlation Logs & Failure Traces"))

        val actions = Ui.row(context)
        actions.addView(Ui.primaryButton(context, "CLEAR TRACES") {
            store.clearLogs()
            render()
        })
        contentContainer.addView(actions)

        val diagCard = Ui.glassCard(context, Ui.BORDER)
        val diagInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
        }

        val diagTextView = TextView(context).apply {
            text = store.logs().joinToString("\n").ifBlank { "No diagnostic traces recorded." }
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFD5DEE8.toInt())
        }
        diagInner.addView(diagTextView)
        diagCard.addView(diagInner)
        contentContainer.addView(diagCard)
    }
}
