package com.jarvis.ui.screens

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.lifecycle.LifecycleOwner
import com.jarvis.macro.UiMacro
import com.jarvis.network.JarvisLanServer
import com.jarvis.routine.CustomMacroRepository
import com.jarvis.service.JarvisForegroundService
import com.jarvis.ui.components.Ui
import com.jarvis.ui.dialogs.CreateRoutineDialog
import com.jarvis.ui.reliability.BackgroundTaskRunner

class SkillsScreen(
    context: Context,
    private val owner: LifecycleOwner,
    private val runner: BackgroundTaskRunner,
    private val onExecuteCommand: (String) -> Unit
) : ScrollView(context) {

    private var boundService: JarvisForegroundService? = null

    init {
        setBackgroundColor(Ui.BG)
        isFillViewport = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 16), Ui.dp(context, 14), Ui.dp(context, 16), Ui.dp(context, 24))
        }
        addView(root)

        // 1. Header
        val header = Ui.row(context)
        val titleBox = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        titleBox.addView(TextView(context).apply {
            text = "SKILLS & PROTOCOLS"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.12f
        })
        titleBox.addView(TextView(context).apply {
            text = "ONE-TOUCH CAPABILITIES & AUTOMATION"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
            letterSpacing = 0.08f
        })
        header.addView(titleBox, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.hudBadge(context, "ACTIVE", Ui.SUCCESS))
        root.addView(header)

        root.addView(Ui.divider(context))

        // 2. Custom Voice Routines & Macros Card
        root.addView(buildCustomRoutinesCard(context))

        // 3. Dynamic & Evolved Skills (Database) Card
        root.addView(buildDynamicSkillsCard(context))

        // 5. Deep Web & Browser Agent Card
        root.addView(buildBrowserCard(context))

        // 6. Desktop Companion & LAN Bridge Card
        root.addView(buildDesktopBridgeCard(context))

        // 7. Music & Media Protocol Card
        root.addView(buildMediaCard(context))

        // 8. Phone & Telephony Agent Card
        root.addView(buildTelecomCard(context))

        // 9. WhatsApp & Smart Messaging Card
        root.addView(buildCommCard(context))

        // 10. Camera Vision & Optical Perception Card
        root.addView(buildOpticalCard(context))

        // 11. Situational & Autopilot Modes Card
        root.addView(buildSentinelCard(context))

        // 12. System & Device Settings Card
        root.addView(buildSystemCard(context))
    }

    fun bindService(service: JarvisForegroundService) {
        boundService = service
    }

    // --- Card 1: Music & Media Protocol ---
    private fun buildMediaCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "🎵 MUSIC & ENTERTAINMENT"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(Ui.hudBadge(context, "SYNCED", Ui.SUCCESS))
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Universal audio playback with Spotify, YouTube Music, or YMusic."
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 10))
        })

        // Transport Controls Row: [⏮ Prev] [▶ Play] [⏸ Pause] [⏭ Next] [⏹ Stop]
        val transportRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 38)).apply { bottomMargin = Ui.dp(context, 10) }
        }
        val prevBtn = Ui.button(context, "⏮ PREV") { onExecuteCommand("previous song") }
        val playBtn = Ui.primaryButton(context, "▶ PLAY") { onExecuteCommand("resume music") }
        val pauseBtn = Ui.button(context, "⏸ PAUSE") { onExecuteCommand("pause music") }
        val nextBtn = Ui.button(context, "⏭ NEXT") { onExecuteCommand("next track") }
        val stopBtn = Ui.dangerButton(context, "⏹") { onExecuteCommand("stop music") }
        transportRow.addView(prevBtn, LinearLayout.LayoutParams(0, -1, 1.2f))
        transportRow.addView(playBtn, LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginStart = Ui.dp(context, 4) })
        transportRow.addView(pauseBtn, LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginStart = Ui.dp(context, 4) })
        transportRow.addView(nextBtn, LinearLayout.LayoutParams(0, -1, 1.2f).apply { marginStart = Ui.dp(context, 4) })
        transportRow.addView(stopBtn, LinearLayout.LayoutParams(Ui.dp(context, 38), -1).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(transportRow)

        // Quick Genre Chips
        val presets = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Synthwave", "Lo-Fi Chill", "Deep Focus", "Top Hits", "Workout Mix").forEach { genre ->
            val chip = Ui.techChip(context, genre, "▶") {
                onExecuteCommand("play $genre music on spotify")
            }
            presetRow.addView(chip)
        }
        presets.addView(presetRow)
        inner.addView(presets)

        // App Launchers row
        val appRow = Ui.row(context).apply { setPadding(0, Ui.dp(context, 10), 0, 0) }
        appRow.addView(Ui.button(context, "Spotify") { launchPackageOrMarket("com.spotify.music") })
        appRow.addView(Ui.button(context, "YT Music") { launchPackageOrMarket("com.google.android.apps.youtube.music") })
        appRow.addView(Ui.button(context, "YMusic") { launchPackageOrMarket("com.kapp.youtube.final") })
        inner.addView(appRow)

        card.addView(inner)
        return card
    }

    // --- Card 2: Phone & Telephony Agent ---
    private fun buildTelecomCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "📞 PHONE CALLS & TELEPHONY"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#00E676"))
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(Ui.hudBadge(context, "HANDS-FREE", Color.parseColor("#00E676")))
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Auto-announces callers, voice answer/reject, and automated OTP retrieval."
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 10))
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 38))
        }
        val answerCallBtn = Ui.button(context, "🟢 ANSWER") { onExecuteCommand("answer call") }
        val rejectCallBtn = Ui.button(context, "🔴 REJECT") { onExecuteCommand("reject call") }
        val readSmsBtn = Ui.button(context, "✉️ READ SMS") { onExecuteCommand("read sms") }
        val getOtpBtn = Ui.button(context, "🔑 GET OTP") { onExecuteCommand("what is my otp") }

        btnRow.addView(answerCallBtn, LinearLayout.LayoutParams(0, -1, 1f))
        btnRow.addView(rejectCallBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        btnRow.addView(readSmsBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        btnRow.addView(getOtpBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(btnRow)

        card.addView(inner)
        return card
    }

    // --- Card 3: WhatsApp & Smart Messaging ---
    private fun buildCommCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "💬 WHATSAPP & MESSAGING"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.parseColor("#25D366"))
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(Ui.hudBadge(context, "ONLINE", Color.parseColor("#25D366")))
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Background notification quick-reply, unread aggregation, and auto-responders."
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 10))
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 38))
        }
        val readUnreadBtn = Ui.button(context, "💬 READ UNREAD") {
            onExecuteCommand("read my whatsapp messages")
        }
        val autoReplyDrivingBtn = Ui.button(context, "🚗 AUTO-REPLY (DRIVE)") {
            onExecuteCommand("turn on whatsapp auto reply driving mode")
        }
        val autoReplyOffBtn = Ui.button(context, "⏹ OFF") {
            onExecuteCommand("turn off whatsapp auto reply")
        }
        btnRow.addView(readUnreadBtn, LinearLayout.LayoutParams(0, -1, 1.2f))
        btnRow.addView(autoReplyDrivingBtn, LinearLayout.LayoutParams(0, -1, 1.4f).apply { marginStart = Ui.dp(context, 4) })
        btnRow.addView(autoReplyOffBtn, LinearLayout.LayoutParams(Ui.dp(context, 54), -1).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(btnRow)

        card.addView(inner)
        return card
    }

    // --- Card 4: Camera Vision & Optical Perception ---
    private fun buildOpticalCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "👁️ CAMERA VISION & PERCEPTION"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(Ui.hudBadge(context, "LENS READY", Ui.CYAN))
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Real-time scene recognition, document/sign OCR, and selfie visual inspection."
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 10))
        })

        val btnRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 38))
        }
        val lookBtn = Ui.button(context, "👁️ WHAT IS THIS?") { onExecuteCommand("what am I looking at") }
        val readSignBtn = Ui.button(context, "📖 READ TEXT") { onExecuteCommand("camera se padh ke batao") }
        val selfieBtn = Ui.button(context, "🤳 SELFIE SCAN") { onExecuteCommand("what am I looking at front camera") }

        btnRow.addView(lookBtn, LinearLayout.LayoutParams(0, -1, 1.2f))
        btnRow.addView(readSignBtn, LinearLayout.LayoutParams(0, -1, 1.1f).apply { marginStart = Ui.dp(context, 4) })
        btnRow.addView(selfieBtn, LinearLayout.LayoutParams(0, -1, 1.1f).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(btnRow)

        card.addView(inner)
        return card
    }

    // --- Card 5: Situational & Autopilot Modes Deck ---
    private fun buildSentinelCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.CYAN_DIM)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        // Header: Title + Live Status Badge
        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "🛡️ AUTONOMOUS MODES & SENTINEL DECK"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(Ui.hudBadge(context, "AUTOPILOT READY", Ui.SUCCESS))
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Context awareness routines, hands-free automotive modes, and intelligent silence profiles."
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 10))
        })

        // Grid Row 1: Driving, Meeting, Focus
        val row1 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 38)).apply {
                bottomMargin = Ui.dp(context, 6)
            }
        }
        val drivingModeBtn = Ui.button(context, "🚗 DRIVING") { onExecuteCommand("driving mode on") }
        val meetingModeBtn = Ui.button(context, "📅 MEETING") { onExecuteCommand("meeting mode on") }
        val focusModeBtn = Ui.button(context, "🧠 FOCUS") { onExecuteCommand("focus mode on") }

        row1.addView(drivingModeBtn, LinearLayout.LayoutParams(0, -1, 1f))
        row1.addView(meetingModeBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        row1.addView(focusModeBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(row1)

        // Grid Row 2: Night, Workout, Auto Context
        val row2 = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 38)).apply {
                bottomMargin = Ui.dp(context, 8)
            }
        }
        val nightModeBtn = Ui.button(context, "🌙 NIGHT") { onExecuteCommand("night mode on") }
        val workoutModeBtn = Ui.button(context, "🏋️ WORKOUT") { onExecuteCommand("workout mode on") }
        val autoModeBtn = Ui.button(context, "🔄 AUTO") { onExecuteCommand("auto mode on") }

        row2.addView(nightModeBtn, LinearLayout.LayoutParams(0, -1, 1f))
        row2.addView(workoutModeBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        row2.addView(autoModeBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(row2)

        // Autopilot Control Row: Toggle Autopilot, Core Status, Decision Logs
        val controlRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 36))
        }
        val toggleAutopilotBtn = Ui.secondaryButton(context, "⚡ AUTOPILOT ON") { onExecuteCommand("enable autopilot") }
        val statusBtn = Ui.button(context, "🤖 STATUS") { onExecuteCommand("autonomous status") }
        val logsBtn = Ui.button(context, "📜 LOGS") { onExecuteCommand("decision logs") }

        controlRow.addView(toggleAutopilotBtn, LinearLayout.LayoutParams(0, -1, 1.2f))
        controlRow.addView(statusBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        controlRow.addView(logsBtn, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(controlRow)

        card.addView(inner)
        return card
    }

    // --- Card 6: System & Device Settings ---
    private fun buildSystemCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "⚡ DEVICE CONTROLS & SHORTCUTS"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(Ui.hudBadge(context, "SYSTEM", Ui.CYAN))
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Quick system toggles for connectivity, battery, and hardware preferences."
            textSize = 11f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 10))
        })

        val sysRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(context, 38))
        }
        sysRow.addView(Ui.button(context, "📶 WI-FI") {
            runCatching { context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, LinearLayout.LayoutParams(0, -1, 1f))
        sysRow.addView(Ui.button(context, "📡 BLUETOOTH") {
            runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, LinearLayout.LayoutParams(0, -1, 1.1f).apply { marginStart = Ui.dp(context, 4) })
        sysRow.addView(Ui.button(context, "🔋 BATTERY") {
            runCatching { context.startActivity(Intent(Settings.ACTION_BATTERY_SAVER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = Ui.dp(context, 4) })
        inner.addView(sysRow)

        card.addView(inner)
        return card
    }

    // --- Card: Custom Voice Routines & Macros ---
    private fun buildCustomRoutinesCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "⚡ CUSTOM VOICE ROUTINES"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val repo = CustomMacroRepository(context)
        val routineListContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        fun refreshRoutines() {
            routineListContainer.removeAllViews()
            val list = repo.getAll()
            if (list.isEmpty()) {
                routineListContainer.addView(TextView(context).apply {
                    text = "No custom routines configured. Tap '+ NEW ROUTINE' to create one."
                    textSize = 11.5f
                    setTextColor(Ui.MUTED)
                    setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
                })
            } else {
                list.forEach { routine ->
                    val rCard = Ui.glassCard(context, Ui.BORDER).apply {
                        setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
                    }
                    val rInner = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                    }
                    val rHeader = Ui.row(context)
                    rHeader.addView(TextView(context).apply {
                        text = routine.name
                        textSize = 13.5f
                        typeface = Typeface.DEFAULT_BOLD
                        setTextColor(Ui.TEXT)
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    rHeader.addView(Ui.hudBadge(context, "${routine.steps.size} STEPS", Ui.CYAN))
                    rInner.addView(rHeader)

                    if (routine.description.isNotBlank()) {
                        rInner.addView(TextView(context).apply {
                            text = routine.description
                            textSize = 11f
                            setTextColor(Ui.MUTED)
                            setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 2))
                        })
                    }

                    if (routine.triggers.isNotEmpty()) {
                        rInner.addView(TextView(context).apply {
                            text = "🗣️ \"${routine.triggers.joinToString("\", \"")}\""
                            textSize = 10.5f
                            setTextColor(Ui.CYAN_DIM)
                            setTypeface(null, Typeface.ITALIC)
                        })
                    }

                    val btnRow = Ui.row(context).apply {
                        setPadding(0, Ui.dp(context, 6), 0, 0)
                    }
                    btnRow.addView(Ui.primaryButton(context, "RUN") {
                        onExecuteCommand("run macro ${routine.name}")
                    })
                    btnRow.addView(Ui.button(context, "EDIT") {
                        CreateRoutineDialog(context, existingMacro = routine) { updated ->
                            repo.save(updated)
                            refreshRoutines()
                        }.show()
                    })
                    btnRow.addView(Ui.dangerButton(context, "DELETE") {
                        repo.delete(routine.id)
                        refreshRoutines()
                    })
                    rInner.addView(btnRow)

                    rCard.addView(rInner)
                    routineListContainer.addView(rCard)
                }
            }
        }

        val addBtn = Ui.primaryButton(context, "+ NEW ROUTINE") {
            CreateRoutineDialog(context) { newMacro ->
                repo.save(newMacro)
                refreshRoutines()
            }.show()
        }
        topRow.addView(addBtn)
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Chain multiple app actions, gestures, and settings triggered by voice."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 8))
        })

        refreshRoutines()
        inner.addView(routineListContainer)
        card.addView(inner)
        return card
    }

    // --- Card: Deep Web & Browser Agent ---
    private fun buildBrowserCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "🌐 DEEP WEB & BROWSER AGENT"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        topRow.addView(Ui.hudBadge(context, "ONLINE", Ui.SUCCESS))
        inner.addView(topRow)

        inner.addView(TextView(context).apply {
            text = "Performs real-time web research, Wikipedia knowledge extraction, and autonomous browser navigation."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 8))
        })

        val queryInput = Ui.input(context, "Search query or URL (e.g. quantum computing, github.com)")
        inner.addView(queryInput.first)

        val btnRow = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 6), 0, 0)
        }
        btnRow.addView(Ui.primaryButton(context, "RESEARCH WEB") {
            val q = queryInput.second.text.toString().trim()
            if (q.isNotBlank()) {
                onExecuteCommand("search web for $q")
            } else {
                Toast.makeText(context, "Please enter a search query", Toast.LENGTH_SHORT).show()
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(Ui.button(context, "OPEN BROWSER") {
            val q = queryInput.second.text.toString().trim()
            if (q.isNotBlank()) {
                onExecuteCommand("browse to $q")
            } else {
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(context, 6) })
        inner.addView(btnRow)

        card.addView(inner)
        return card
    }

    // --- Card: Desktop Companion & LAN Bridge ---
    private fun buildDesktopBridgeCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14), Ui.dp(context, 14))
        }

        val topRow = Ui.row(context)
        topRow.addView(TextView(context).apply {
            text = "💻 DESKTOP COMPANION & LAN BRIDGE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        val statusBadge = Ui.hudBadge(
            context,
            if (JarvisLanServer.isServerRunning) "RUNNING" else "STANDBY",
            if (JarvisLanServer.isServerRunning) Ui.SUCCESS else Ui.MUTED
        )
        topRow.addView(statusBadge)
        inner.addView(topRow)

        val lanServer = JarvisLanServer(context)
        val ip = lanServer.getLocalIpAddress()
        val pin = lanServer.pairingPin

        inner.addView(TextView(context).apply {
            text = "Connect any PC/Mac browser on your local Wi-Fi for remote terminal control & clipboard sync."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 8))
        })

        val detailsCard = Ui.glassCard(context, Ui.BORDER).apply {
            setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
        }
        val detailsInner = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        detailsInner.addView(TextView(context).apply {
            text = "🌐 Web Console: http://$ip:8888"
            textSize = 12.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        })
        detailsInner.addView(TextView(context).apply {
            text = "🔑 Pairing PIN: $pin"
            textSize = 11.5f
            setTextColor(Ui.TEXT)
            setPadding(0, Ui.dp(context, 4), 0, 0)
        })
        detailsCard.addView(detailsInner)
        inner.addView(detailsCard)

        val btnRow = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 8), 0, 0)
        }
        val toggleBtn = Ui.primaryButton(context, if (JarvisLanServer.isServerRunning) "STOP SERVER" else "START SERVER") {
            if (JarvisLanServer.isServerRunning) {
                lanServer.stop()
                Toast.makeText(context, "Desktop Bridge stopped", Toast.LENGTH_SHORT).show()
            } else {
                lanServer.start()
                Toast.makeText(context, "Desktop Bridge active on http://$ip:8888", Toast.LENGTH_LONG).show()
            }
        }
        btnRow.addView(toggleBtn, LinearLayout.LayoutParams(0, -2, 1f))
        btnRow.addView(Ui.button(context, "COPY URL") {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
            cm?.setPrimaryClip(android.content.ClipData.newPlainText("Jarvis LAN URL", "http://$ip:8888"))
            Toast.makeText(context, "Copied URL to clipboard", Toast.LENGTH_SHORT).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(context, 6) })
        inner.addView(btnRow)

        card.addView(inner)
        return card
    }

    // --- Card: Dynamic & Evolved Skills (Database) ---
    private fun buildDynamicSkillsCard(context: Context): View {
        val card = Ui.glassCard(context, Ui.BORDER_GLOW)
        val inner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        val headerRow = Ui.row(context)
        headerRow.addView(TextView(context).apply {
            text = "🧠 DYNAMIC & EVOLVED SKILLS (DB)"
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        headerRow.addView(Ui.hudBadge(context, "SQLITE DB", Ui.SUCCESS))
        inner.addView(headerRow)

        inner.addView(TextView(context).apply {
            text = "Observes new actions and activities, auto-updates skills, and saves them to jarvis_skills.db."
            textSize = 11.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 10))
        })

        val btnRow = Ui.row(context)
        btnRow.addView(Ui.button(context, "⚡ DECISION SYNTHESIS") {
            onExecuteCommand("make a decision on optimal background performance and battery")
        }, LinearLayout.LayoutParams(0, -2, 1f))

        btnRow.addView(Ui.button(context, "🔍 CHECK DB SKILLS") {
            val store = com.jarvis.skills.db.PersistentSkillStore(context)
            val skills = store.getAllSkills()
            Toast.makeText(context, "Database contains ${skills.size} skills.", Toast.LENGTH_LONG).show()
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = Ui.dp(context, 6) })
        inner.addView(btnRow)

        card.addView(inner)
        return card
    }

    private fun launchPackageOrMarket(packageName: String) {
        val pm = context.packageManager
        val launchIntent = pm.getLaunchIntentForPackage(packageName)
        if (launchIntent != null) {
            context.startActivity(launchIntent)
        } else {
            runCatching {
                val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(marketIntent)
            }
        }
    }

    override fun onDetachedFromWindow() {
        boundService = null
        super.onDetachedFromWindow()
    }
}
