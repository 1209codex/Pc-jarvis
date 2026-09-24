package com.jarvis.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.*
import com.google.android.material.card.MaterialCardView
import com.jarvis.memory.MemoryGarbageCollector
import com.jarvis.memory.MemoryItem
import com.jarvis.memory.MemoryStore
import com.jarvis.memory.MemoryType
import com.jarvis.retrieval.engine.RaphaelRetrievalManager
import com.jarvis.retrieval.model.CandidateSource
import com.jarvis.retrieval.model.DetailedRetrievalResult
import com.jarvis.retrieval.model.EvaluatedCandidate
import com.jarvis.ui.components.Ui
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Android UI Retrieval Visualizer & Memory Inspector Screen for Raphael.
 *
 * Provides real-time interactive inspection for:
 * 1. Local SQLite v4 Memory Store (with schema v4 fields: version, confirmed, supersedes_id).
 * 2. Half-life decay score visualization & Garbage Collector execution.
 * 3. Raphael Retrieval Pipeline Simulator & Cross-Encoder candidate re-ranking debugger.
 */
class MemoryScreen(context: Context) : ScrollView(context) {
    private val store = MemoryStore(context.applicationContext)
    private val gc = MemoryGarbageCollector(store)
    private val retrievalManager = RaphaelRetrievalManager(memoryStore = store)

    private var activeTab = 0 // 0 = Memory Store, 1 = Retrieval Benchmark, 2 = GC & Decay HUD
    private var selectedCategoryFilter: MemoryType? = null
    private var searchQuery: String = ""
    private var lastBenchmarkQuery: String = "What is my favorite music genre?"
    private var gcStatusMessage: String = ""
    private var selectedType = MemoryType.USER_PREFERENCE

    // Layout Containers
    private val tabContainer = LinearLayout(context)
    private val contentContainer = LinearLayout(context)

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        addView(root)

        // Main Header
        val header = Ui.row(context)
        val brand = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        brand.addView(TextView(context).apply {
            text = "RAPHAEL • RETRIEVAL & MEMORY"
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.10f
        })
        brand.addView(TextView(context).apply {
            text = "LOCAL SQLITE V4 • RAG / MAG / CAG FUSION • HALF-LIFE GC DECAY"
            textSize = 9f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
            letterSpacing = 0.06f
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.hudBadge(context, "SQLITE V4", Ui.SUCCESS))
        val refresh = Ui.primaryButton(context, "SYNC") { render() }
        header.addView(refresh)
        root.addView(header)

        root.addView(Ui.divider(context))

        // Tab Navigation Strip
        tabContainer.orientation = LinearLayout.HORIZONTAL
        tabContainer.layoutParams = LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = Ui.dp(context, 12)
        }
        root.addView(tabContainer)
        setupTabStrip()

        // Main Content Container
        contentContainer.orientation = LinearLayout.VERTICAL
        root.addView(contentContainer)

        render()
    }

    private fun setupTabStrip() {
        tabContainer.removeAllViews()
        val tabTitles = listOf("1. MEMORY STORE", "2. RETRIEVAL INSPECTOR", "3. GC & DECAY HUD")
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
            0 -> renderMemoryStoreTab()
            1 -> renderRetrievalInspectorTab()
            2 -> renderGcAndDecayTab()
        }
    }

    // ==========================================
    // TAB 0: MEMORY STORE & INSPECTOR
    // ==========================================
    private fun renderMemoryStoreTab() {
        val allActive = store.getAllActiveMemories()
        val preferencesCount = allActive.count { it.type == MemoryType.USER_PREFERENCE }
        val confirmedCount = allActive.count { it.isConfirmed }
        val decayedCount = allActive.count { gc.computeDecayedImportance(it) < 0.05 }

        // Metrics Row
        val statsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            listOf(
                metric(context, allActive.size.toString(), "ACTIVE"),
                metric(context, preferencesCount.toString(), "PREFERENCES"),
                metric(context, confirmedCount.toString(), "VERIFIED"),
                metric(context, decayedCount.toString(), "DECAYED")
            ).forEach { card ->
                addView(card, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(context, 6) })
            }
        }
        contentContainer.addView(Ui.sectionLabel(context, "Knowledge Health Metrics"))
        contentContainer.addView(statsRow)

        // Add Memory Card
        contentContainer.addView(Ui.sectionLabel(context, "Store New Memory (Schema v4)"))
        contentContainer.addView(buildAddMemoryCard())

        // Search & Category Filter
        contentContainer.addView(Ui.sectionLabel(context, "Filter & Search SQLite Memory"))
        contentContainer.addView(buildSearchAndFilterCard())

        // Memory List
        contentContainer.addView(Ui.sectionLabel(context, "Stored Active Memories (${allActive.size})"))
        val memoriesList = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val itemsToDisplay = if (searchQuery.isNotBlank()) {
            store.queryRelevantMemories(searchQuery, MemoryType.values().toSet(), limit = 20)
        } else if (selectedCategoryFilter != null) {
            allActive.filter { it.type == selectedCategoryFilter }
        } else {
            allActive.take(25)
        }

        if (itemsToDisplay.isEmpty()) {
            memoriesList.addView(TextView(context).apply {
                text = "No memories found matching criteria."
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, Ui.dp(context, 12), 0, Ui.dp(context, 12))
            })
        } else {
            itemsToDisplay.forEach { item ->
                memoriesList.addView(buildMemoryCard(item))
            }
        }
        contentContainer.addView(memoriesList)
    }

    private fun buildAddMemoryCard(): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }

        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 8)
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 40)).apply { bottomMargin = Ui.dp(context, 6) }
        }
        val addKeyInput = EditText(context).apply {
            hint = "Memory key (e.g. home_location, user_favorite_music)"
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        row1.addView(addKeyInput)
        inner.addView(row1)

        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 8)
            setPadding(Ui.dp(context, 8), 0, Ui.dp(context, 8), 0)
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 40)).apply { bottomMargin = Ui.dp(context, 6) }
        }
        val addContentInput = EditText(context).apply {
            hint = "Memory content (e.g. Bangalore, Synthwave & Cyberpunk)"
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            setBackgroundColor(Color.TRANSPARENT)
            setSingleLine(true)
            layoutParams = LinearLayout.LayoutParams(-1, -1)
        }
        row2.addView(addContentInput)
        inner.addView(row2)

        // Type selection & Confirmed Checkbox row
        val row3 = Ui.row(context)
        val addConfirmedCheckBox = CheckBox(context).apply {
            text = "Mark Confirmed (Protected)"
            textSize = 10.5f
            setTextColor(Ui.CYAN)
            isChecked = true
        }
        row3.addView(addConfirmedCheckBox, LinearLayout.LayoutParams(0, -2, 1f))

        val addBtn = Ui.primaryButton(context, "SAVE MEMORY") {
            val key = addKeyInput.text?.toString()?.trim().orEmpty()
            val content = addContentInput.text?.toString()?.trim().orEmpty()
            if (key.isNotBlank() && content.isNotBlank()) {
                val newItem = MemoryItem(
                    type = selectedType,
                    key = key,
                    content = content,
                    provenance = "user_manual_ui",
                    importance = 0.9,
                    isConfirmed = addConfirmedCheckBox.isChecked
                )
                store.saveMemoryItem(newItem)
                render()
            }
        }
        row3.addView(addBtn)
        inner.addView(row3)

        card.addView(inner)
        return card
    }

    private fun buildSearchAndFilterCard(): View {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }

        val searchRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN_DIM, 8)
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 6), 0)
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 42)).apply { bottomMargin = Ui.dp(context, 8) }
        }
        val searchInput = EditText(context).apply {
            hint = "Search memory by key, content, or FTS query..."
            setSingleLine(true)
            textSize = 12f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            setBackgroundColor(Color.TRANSPARENT)
            if (searchQuery.isNotBlank()) setText(searchQuery)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        searchRow.addView(searchInput)
        searchRow.addView(Ui.primaryButton(context, "SEARCH") {
            searchQuery = searchInput.text?.toString()?.trim().orEmpty()
            render()
        })
        container.addView(searchRow)

        // Filter chips
        val chipsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, Ui.dp(context, 8))
        }

        val categories = listOf(
            "ALL" to null,
            "PREF" to MemoryType.USER_PREFERENCE,
            "FACT" to MemoryType.STRUCTURED_FACT,
            "HABIT" to MemoryType.LEARNED_PATTERN,
            "DOC" to MemoryType.DOCUMENT_SNIPPET
        )

        categories.forEach { (label, type) ->
            val isSelected = selectedCategoryFilter == type
            val btn = Button(context).apply {
                text = label
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(if (isSelected) Ui.BG else Ui.CYAN)
                setBackgroundColor(if (isSelected) Ui.CYAN else Ui.SURFACE)
                setOnClickListener {
                    selectedCategoryFilter = type
                    render()
                }
            }
            chipsRow.addView(btn, LinearLayout.LayoutParams(0, Ui.dp(context, 32), 1f).apply {
                marginEnd = Ui.dp(context, 4)
            })
        }
        container.addView(chipsRow)

        return container
    }

    private fun buildMemoryCard(item: MemoryItem): View {
        val card = Ui.glassCard(context, if (item.isConfirmed) Ui.BORDER_GLOW else Ui.BORDER)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }

        // Top Header
        val top = Ui.row(context)
        val titleText = TextView(context).apply {
            text = "${item.key} (v${item.version})"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        }
        top.addView(titleText, LinearLayout.LayoutParams(0, -2, 1f))

        val confirmedBadge = if (item.isConfirmed) {
            Ui.hudBadge(context, "VERIFIED", Ui.SUCCESS)
        } else {
            Ui.hudBadge(context, "UNCONFIRMED", Ui.MUTED)
        }
        top.addView(confirmedBadge)
        top.addView(Ui.hudBadge(context, item.type.name, Ui.CYAN))
        inner.addView(top)

        // Supersedes link banner
        if (!item.supersedesId.isNull_or_blank()) {
            inner.addView(TextView(context).apply {
                text = "SUPERSEDES: ${item.supersedesId}"
                textSize = 9.5f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.WARNING)
                setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 2))
            })
        }

        // Content Text
        inner.addView(TextView(context).apply {
            text = item.content
            textSize = 12f
            setTextColor(Ui.TEXT)
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
        })

        // Decayed score calculation
        val decayedScore = gc.computeDecayedImportance(item)
        val scoreColor = if (decayedScore < 0.05 && !item.isConfirmed) Ui.ERROR else Ui.SUCCESS

        val metricsRow = Ui.row(context)
        metricsRow.addView(TextView(context).apply {
            text = String.format(
                Locale.US,
                "Imp: %.2f  •  Decayed: %.2f  •  Source: %s",
                item.importance, decayedScore, item.provenance
            )
            textSize = 9.5f
            setTextColor(scoreColor)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        inner.addView(metricsRow)

        // Footer Actions
        val footer = Ui.row(context)
        footer.setPadding(0, Ui.dp(context, 6), 0, 0)
        footer.addView(TextView(context).apply {
            text = "Created: ${formatTime(item.createdAt)}"
            textSize = 9f
            setTextColor(Ui.MUTED)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val toggleConfirmBtn = TextView(context).apply {
            text = if (item.isConfirmed) "UNVERIFY" else "VERIFY"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (item.isConfirmed) Ui.WARNING else Ui.SUCCESS)
            isClickable = true
            setPadding(Ui.dp(context, 6), 0, Ui.dp(context, 6), 0)
            setOnClickListener {
                store.setConfirmed(item.id, !item.isConfirmed)
                render()
            }
        }
        footer.addView(toggleConfirmBtn)

        val deleteBtn = TextView(context).apply {
            text = "ARCHIVE"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.ERROR)
            isClickable = true
            setPadding(Ui.dp(context, 6), 0, 0, 0)
            setOnClickListener {
                store.archiveMemory(item.id)
                render()
            }
        }
        footer.addView(deleteBtn)
        inner.addView(footer)

        card.addView(inner)
        return card
    }

    // String helper for null safety
    private fun String?.isNull_or_blank(): Boolean = this == null || this.trim().isEmpty()

    // ==========================================
    // TAB 1: RETRIEVAL & ROUTING INSPECTOR
    // ==========================================
    private fun renderRetrievalInspectorTab() {
        contentContainer.addView(Ui.sectionLabel(context, "Raphael Pipeline Query Simulator"))

        val benchmarkResultsContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val queryRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN, 8)
            setPadding(Ui.dp(context, 10), 0, Ui.dp(context, 6), 0)
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 44)).apply { bottomMargin = Ui.dp(context, 10) }
        }
        val benchmarkQueryInput = EditText(context).apply {
            hint = "e.g. What is my favorite music album?, Turn on flight mode"
            setSingleLine(true)
            textSize = 12f
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            setBackgroundColor(Color.TRANSPARENT)
            setText(lastBenchmarkQuery)
            layoutParams = LinearLayout.LayoutParams(0, -1, 1f)
        }
        queryRow.addView(benchmarkQueryInput)

        val runBtn = Ui.primaryButton(context, "BENCHMARK") {
            lastBenchmarkQuery = benchmarkQueryInput.text?.toString()?.trim().orEmpty()
            runRetrievalBenchmark(lastBenchmarkQuery, benchmarkResultsContainer)
        }
        queryRow.addView(runBtn)
        contentContainer.addView(queryRow)
        contentContainer.addView(benchmarkResultsContainer)

        if (lastBenchmarkQuery.isNotBlank()) {
            runRetrievalBenchmark(lastBenchmarkQuery, benchmarkResultsContainer)
        }
    }

    private fun runRetrievalBenchmark(query: String, resultsContainer: LinearLayout) {
        if (query.isBlank()) return

        val detailedResult: DetailedRetrievalResult = retrievalManager.retrieveDetailed(query)
        resultsContainer.removeAllViews()

        // 1. Overview Card
        val overviewCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val overviewInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 12), Ui.dp(context, 10), Ui.dp(context, 12), Ui.dp(context, 10))
        }

        overviewInner.addView(TextView(context).apply {
            text = "RETRIEVAL ANALYSIS RESULT"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        })

        val detailsGrid = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
        }

        detailsGrid.addView(buildInfoRow("QUERY INTENT", detailedResult.intent.name, Ui.CYAN))
        detailsGrid.addView(buildInfoRow("RETRIEVAL ROUTE", detailedResult.route.name, Ui.SUCCESS))
        detailsGrid.addView(buildInfoRow("RE-RANKER EXECUTED", detailedResult.rerankerName, Ui.WARNING))
        detailsGrid.addView(buildInfoRow("CANDIDATES RETURNED", "${detailedResult.candidates.size} Candidate(s)", Ui.TEXT))
        detailsGrid.addView(buildInfoRow("LATENCY", "${detailedResult.latencyMs} ms", Ui.CYAN))

        overviewInner.addView(detailsGrid)
        overviewCard.addView(overviewInner)
        resultsContainer.addView(overviewCard)

        // 2. Candidates Breakdown
        resultsContainer.addView(Ui.sectionLabel(context, "Evaluated Candidates & Fusion Ranks"))

        if (detailedResult.candidates.isEmpty()) {
            resultsContainer.addView(TextView(context).apply {
                text = "Zero retrieval route selected for this command/intent."
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
            })
        } else {
            detailedResult.candidates.forEachIndexed { index, candidate ->
                resultsContainer.addView(buildCandidateCard(index + 1, candidate))
            }
        }

        // 3. Formatted Context Preview
        if (detailedResult.structuredContext.isNotBlank()) {
            resultsContainer.addView(Ui.sectionLabel(context, "LLM Context Window Preview"))
            val contextCard = Ui.glassCard(context, Ui.CYAN_DIM)
            val contextInner = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
            }
            contextInner.addView(TextView(context).apply {
                text = detailedResult.structuredContext
                textSize = 10.5f
                typeface = Typeface.MONOSPACE
                setTextColor(Ui.TEXT)
            })
            contextCard.addView(contextInner)
            resultsContainer.addView(contextCard)
        }
    }

    private fun buildInfoRow(label: String, valText: String, valColor: Int): View {
        val row = Ui.row(context)
        row.addView(TextView(context).apply {
            text = label
            textSize = 10f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
        }, LinearLayout.LayoutParams(Ui.dp(context, 140), -2))
        row.addView(TextView(context).apply {
            text = valText
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(valColor)
        })
        return row
    }

    private fun buildCandidateCard(rank: Int, evaluated: EvaluatedCandidate): View {
        val candidate = evaluated.candidate
        val card = Ui.glassCard(context, Ui.BORDER)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
        }

        val top = Ui.row(context)
        top.addView(TextView(context).apply {
            text = "#$rank • ${candidate.source.name}"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        top.addView(Ui.hudBadge(context, String.format(Locale.US, "SCORE: %.2f", evaluated.compositeScore), Ui.SUCCESS))
        inner.addView(top)

        inner.addView(TextView(context).apply {
            text = candidate.text
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
        })

        inner.addView(TextView(context).apply {
            text = String.format(
                Locale.US,
                "Dense: %.2f  •  Lexical: %.2f  •  Importance: %.2f  •  Re-rank: %.2f",
                candidate.denseScore, candidate.lexicalScore, candidate.importanceScore, evaluated.rerankScore
            )
            textSize = 9f
            setTextColor(Ui.MUTED)
        })

        card.addView(inner)
        return card
    }

    // ==========================================
    // TAB 2: GARBAGE COLLECTOR & DECAY HUD
    // ==========================================
    private fun renderGcAndDecayTab() {
        contentContainer.addView(Ui.sectionLabel(context, "Garbage Collector & Memory Maintenance Controls"))

        val gcControlCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val gcInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        gcInner.addView(TextView(context).apply {
            text = "AUTOMATED MEMORY GARBAGE COLLECTION"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        })

        gcInner.addView(TextView(context).apply {
            text = "Purges memories with decayed score < 0.05 (excluding verified/confirmed items) and rebuilds FTS indexing."
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
        })

        val btnRow = Ui.row(context)
        val runGcBtn = Ui.primaryButton(context, "RUN GC PURGE") {
            val purged = gc.runGarbageCollection()
            gcStatusMessage = "GC execution complete: $purged stale/decayed memory item(s) archived & FTS rebuilt."
            render()
        }
        btnRow.addView(runGcBtn, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = Ui.dp(context, 6) })

        val rebuildFtsBtn = Ui.secondaryButton(context, "REBUILD FTS") {
            store.rebuildFtsIndex()
            gcStatusMessage = "FTS4 full text search index rebuilt successfully."
            render()
        }
        btnRow.addView(rebuildFtsBtn)
        gcInner.addView(btnRow)

        if (gcStatusMessage.isNotBlank()) {
            val gcStatusBanner = TextView(context).apply {
                text = gcStatusMessage
                textSize = 11f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.SUCCESS)
                setPadding(0, Ui.dp(context, 8), 0, 0)
            }
            gcInner.addView(gcStatusBanner)
        }
        gcControlCard.addView(gcInner)
        contentContainer.addView(gcControlCard)

        // Decay Watchlist
        contentContainer.addView(Ui.sectionLabel(context, "Half-Life Decay Watchlist (Rotting Memories)"))
        val decayWatchlistContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val activeMemories = store.getAllActiveMemories()
        val sortedByDecay = activeMemories.map { item ->
            Pair(item, gc.computeDecayedImportance(item))
        }.sortedBy { it.second }

        if (sortedByDecay.isEmpty()) {
            decayWatchlistContainer.addView(TextView(context).apply {
                text = "No active memories stored in database."
                textSize = 12f
                setTextColor(Ui.MUTED)
                setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 8))
            })
        } else {
            sortedByDecay.take(15).forEach { (item, decayedScore) ->
                decayWatchlistContainer.addView(buildDecayWatchCard(item, decayedScore))
            }
        }
        contentContainer.addView(decayWatchlistContainer)
    }

    private fun buildDecayWatchCard(item: MemoryItem, decayedScore: Double): View {
        val isCritical = decayedScore < 0.05 && !item.isConfirmed
        val card = Ui.glassCard(context, if (isCritical) Ui.ERROR else Ui.BORDER)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
        }

        val top = Ui.row(context)
        top.addView(TextView(context).apply {
            text = item.key
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val statusText = if (item.isConfirmed) "PROTECTED (VERIFIED)" else if (isCritical) "ACTION REQUIRED (EXPIRING)" else "STABLE"
        val statusColor = if (item.isConfirmed) Ui.SUCCESS else if (isCritical) Ui.ERROR else Ui.MUTED
        top.addView(Ui.hudBadge(context, statusText, statusColor))
        inner.addView(top)

        inner.addView(TextView(context).apply {
            text = item.content
            textSize = 11f
            setTextColor(Ui.TEXT)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
        })

        inner.addView(TextView(context).apply {
            text = String.format(
                Locale.US,
                "Type: %s  •  Raw Imp: %.2f  •  Decayed Score: %.3f",
                item.type.name, item.importance, decayedScore
            )
            textSize = 9.5f
            setTextColor(if (isCritical) Ui.ERROR else Ui.CYAN)
        })

        card.addView(inner)
        return card
    }

    private fun metric(context: Context, value: String, label: String): MaterialCardView {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(Ui.dp(context, 4), Ui.dp(context, 8), Ui.dp(context, 4), Ui.dp(context, 8))
        }
        inner.addView(TextView(context).apply {
            text = value
            textSize = 18f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            gravity = Gravity.CENTER
        })
        inner.addView(TextView(context).apply {
            text = label
            textSize = 8.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
            gravity = Gravity.CENTER
            letterSpacing = 0.06f
        })
        card.addView(inner)
        return card
    }

    private fun formatTime(epoch: Long): String = SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()).format(Date(epoch))
}
