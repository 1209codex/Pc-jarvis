package com.jarvis.ui.screens

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.View
import android.widget.*
import com.google.android.material.materialswitch.MaterialSwitch
import com.jarvis.ai.LlmConfig
import com.jarvis.ui.components.Ui
import com.jarvis.ui.data.UiPreferencesStore
import com.jarvis.ui.dialogs.VoiceEnrollmentDialog
import com.jarvis.ui.dialogs.OwnerVoiceEnrollmentDialog
import com.jarvis.ui.model.AppSettings
import com.jarvis.wakeword.UserVoiceProfile

class SettingsScreen(
    context: Context,
    private val store: UiPreferencesStore,
    initial: AppSettings,
    private val onDirtyChanged: (Boolean) -> Unit,
    private val onSaved: (() -> Unit)? = null
) : ScrollView(context) {
    private var settings = initial
    private var voiceProfile: UserVoiceProfile = store.loadVoiceProfile()
    private var ownerVoiceProfile: com.jarvis.wakeword.OwnerVoiceProfile? = store.loadOwnerVoiceProfile()
    private val themeSpinner = Spinner(context)
    private val wakeWord = EditText(context)
    private val wakeAckSpinner = Spinner(context)
    private val ttsProviderSpinner = Spinner(context)
    private val ttsVoiceSpinner = Spinner(context)
    private val llmModel = EditText(context)
    private val continuousConvSwitch = MaterialSwitch(context)
    private val ownerVoiceSwitch = MaterialSwitch(context)
    private val bargeInSwitch = MaterialSwitch(context)
    private val offlineAsrSwitch = MaterialSwitch(context)
    private val batteryAnnouncementsSwitch = MaterialSwitch(context)
    private val proactiveCalendarSwitch = MaterialSwitch(context)
    private var currentPreBriefingMinutes: Int = initial.proactivePreBriefingMinutes
    private val preBriefingMinutesBtn = TextView(context)
    private val proactiveBatterySwitch = MaterialSwitch(context)
    private val dailyBriefingSwitch = MaterialSwitch(context)
    private val briefingTimeBtn = TextView(context)
    private var currentBriefingTime: String = initial.dailyBriefingTime
    private val lockScreenSwitch = MaterialSwitch(context)
    private val deviceUnlockPinInput = EditText(context)
    private val autoLearnSwitch = MaterialSwitch(context)
    private val whatsAppAutoReturnSwitch = MaterialSwitch(context)
    private val mediaDuckingSwitch = MaterialSwitch(context)
    private var currentDuckingPercent: Int = initial.mediaDuckingPercent
    private val duckingPercentBtn = TextView(context)
    private val acousticFeedbackSwitch = MaterialSwitch(context)
    private val hapticFeedbackSwitch = MaterialSwitch(context)
    private val bluetoothScoSwitch = MaterialSwitch(context)
    private val headsetHookSwitch = MaterialSwitch(context)
    private val wearableSyncSwitch = MaterialSwitch(context)
    private val floatingOverlaySwitch = MaterialSwitch(context)
    private val customKey = EditText(context)
    private val customValue = EditText(context)
    private val customList = LinearLayout(context)
    private var dirty = false
    private var suppressDirty = true

    private val logView = TextView(context)

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
            text = "SYSTEM CONFIG & LOGS"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.12f
        })
        brand.addView(TextView(context).apply {
            text = "DEVICE POLICIES • WAKE ENGINE • DIAGNOSTICS"
            textSize = 9.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.MUTED)
            letterSpacing = 0.08f
        })
        header.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(Ui.hudBadge(context, "ACTIVE", Ui.CYAN))
        root.addView(header)

        root.addView(Ui.divider(context))

        // System Default Digital Assistant Card
        root.addView(Ui.sectionLabel(context, "System Default Digital Assistant"))
        val isDefaultAssist = com.jarvis.assistant.AssistantSettingsHelper.isDefaultAssistant(context)
        val assistCard = Ui.glassCard(context, if (isDefaultAssist) Ui.BORDER_GLOW else Ui.BORDER)
        val assistInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val assistRow = Ui.row(context)
        assistRow.addView(TextView(context).apply {
            text = "DEFAULT DIGITAL ASSISTANT"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val assistBadge = Ui.hudBadge(
            context,
            if (isDefaultAssist) "DEFAULT ASSISTANT" else "NOT DEFAULT",
            if (isDefaultAssist) Ui.CYAN else Ui.WARNING
        )
        assistRow.addView(assistBadge)
        assistInner.addView(assistRow)

        assistInner.addView(TextView(context).apply {
            text = "Set Jarvis as your Android Default Digital Assistant app to trigger with Long-Press Home, corner swipe, or power button from anywhere. When triggered, the interactive Floating Orb overlay automatically appears on screen in listening mode."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 10))
        })

        val assistBtn = Ui.button(context, if (isDefaultAssist) "MANAGE DEFAULT ASSISTANT SETTINGS" else "SET AS DEFAULT DIGITAL ASSISTANT") {
            com.jarvis.assistant.AssistantSettingsHelper.openDefaultAssistantSettings(context)
        }
        assistInner.addView(assistBtn)

        val hasOverlay = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.M || android.provider.Settings.canDrawOverlays(context)
        val overlayRow = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 10), 0, Ui.dp(context, 4))
        }
        overlayRow.addView(TextView(context).apply {
            text = "POWER BUTTON FLOATING ORB"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val overlayBadge = Ui.hudBadge(
            context,
            if (hasOverlay) "READY" else "PERMISSION REQUIRED",
            if (hasOverlay) Ui.CYAN else Ui.WARNING
        )
        overlayRow.addView(overlayBadge)
        assistInner.addView(overlayRow)

        if (!hasOverlay) {
            val grantOverlayBtn = Ui.button(context, "GRANT OVERLAY PERMISSION") {
                val intent = android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    android.net.Uri.parse("package:${context.packageName}")
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                try {
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val fallback = android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                }
            }
            assistInner.addView(grantOverlayBtn)
        }

        assistCard.addView(assistInner)
        root.addView(assistCard)

        // 0. Device Automation & Accessibility Card
        root.addView(Ui.sectionLabel(context, "Device Automation & Accessibility"))
        val accCard = Ui.glassCard(context, if (com.jarvis.accessibility.UiAutomationManager.isEnabled()) Ui.BORDER_GLOW else Ui.BORDER)
        val accInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val accRow = Ui.row(context)
        accRow.addView(TextView(context).apply {
            text = "ACCESSIBILITY SERVICE"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val isAccActive = com.jarvis.accessibility.UiAutomationManager.isEnabled()
        val accBadge = Ui.hudBadge(
            context,
            if (isAccActive) "ACTIVE" else "ENABLE IN SETTINGS",
            if (isAccActive) Ui.CYAN else Ui.WARNING
        )
        accRow.addView(accBadge)
        accInner.addView(accRow)

        accInner.addView(TextView(context).apply {
            text = "Powers autonomous UI navigation: auto-clicking buttons, typing text, scrolling feeds (YouTube/Shorts), and multi-step WhatsApp automation."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 10))
        })

        val accBtn = Ui.button(context, if (isAccActive) "OPEN ACCESSIBILITY SETTINGS" else "GRANT ACCESSIBILITY PERMISSION") {
            com.jarvis.accessibility.UiAutomationManager.openAccessibilitySettings(context)
        }
        accInner.addView(accBtn)

        accCard.addView(accInner)
        root.addView(accCard)

        // Screen Vision & Multimodal Understanding Card
        root.addView(Ui.sectionLabel(context, "Screen Vision & Multimodal Understanding"))
        val visionCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val visionInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val visionRow = Ui.row(context)
        visionRow.addView(TextView(context).apply {
            text = "MULTIMODAL SCREEN VISION"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val isVisionNative = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R && isAccActive
        val visionBadge = Ui.hudBadge(
            context,
            if (isVisionNative) "ACTIVE (API 30+)" else "UI TREE FALLBACK",
            if (isVisionNative) Ui.CYAN else Ui.TEXT
        )
        visionRow.addView(visionBadge)
        visionInner.addView(visionRow)

        visionInner.addView(TextView(context).apply {
            text = "Enables Jarvis to see, read, and summarize active on-screen content using Groq Vision (llama-3.2-11b-vision-preview). Ask: \"What is on my screen?\" or \"Is screen pe kya hai?\"."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 6), 0, 0)
        })

        visionCard.addView(visionInner)
        root.addView(visionCard)

        // Proactive Notifications & Device Health Card
        root.addView(Ui.sectionLabel(context, "Proactive Notifications & Device Health"))
        val notifCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val notifInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val notifRow = Ui.row(context)
        notifRow.addView(TextView(context).apply {
            text = "NOTIFICATION LISTENER ACCESS"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val isNotifActive = com.jarvis.notification.JarvisNotificationListenerService.isNotificationAccessGranted(context)
        val notifBadge = Ui.hudBadge(
            context,
            if (isNotifActive) "ACTIVE" else "ENABLE IN SETTINGS",
            if (isNotifActive) Ui.CYAN else Ui.WARNING
        )
        notifRow.addView(notifBadge)
        notifInner.addView(notifRow)

        notifInner.addView(TextView(context).apply {
            text = "Allows Jarvis to read incoming WhatsApp, SMS, and app notifications upon request (\"What notifications do I have?\") or during daily briefings."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 10))
        })

        val notifBtn = Ui.button(context, if (isNotifActive) "OPEN NOTIFICATION ACCESS SETTINGS" else "GRANT NOTIFICATION ACCESS") {
            com.jarvis.notification.JarvisNotificationListenerService.openNotificationAccessSettings(context)
        }
        notifInner.addView(notifBtn)

        proactiveCalendarSwitch.apply {
            text = "Calendar Event Pre-Briefings"
            setTextColor(Ui.TEXT)
            isChecked = initial.proactiveCalendarAlertsEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        notifInner.addView(proactiveCalendarSwitch)

        val preBriefRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 6))
            val label = TextView(context).apply {
                text = "Pre-Briefing Lead Time"
                setTextColor(Ui.MUTED)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(label)
        }

        preBriefingMinutesBtn.apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN, 8)
            setPadding(Ui.dp(context, 10), Ui.dp(context, 5), Ui.dp(context, 10), Ui.dp(context, 5))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currentPreBriefingMinutes = when (currentPreBriefingMinutes) {
                    10 -> 15
                    15 -> 30
                    else -> 10
                }
                updatePreBriefingMinutesBtnText()
                markDirty()
            }
        }
        updatePreBriefingMinutesBtnText()
        preBriefRow.addView(preBriefingMinutesBtn)
        notifInner.addView(preBriefRow)

        proactiveBatterySwitch.apply {
            text = "Critical Low Battery Alert (<= 15%)"
            setTextColor(Ui.TEXT)
            isChecked = initial.proactiveBatteryAlertsEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        notifInner.addView(proactiveBatterySwitch)

        batteryAnnouncementsSwitch.apply {
            text = "Full Battery Announcement (100% Charged)"
            setTextColor(Ui.TEXT)
            isChecked = initial.batteryAnnouncementsEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        notifInner.addView(batteryAnnouncementsSwitch)

        notifCard.addView(notifInner)
        root.addView(notifCard)

        // Smart Automation Routines Card
        root.addView(Ui.sectionLabel(context, "Smart Automation Routines"))
        val routineCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val routineInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val routineTitleRow = Ui.row(context)
        routineTitleRow.addView(TextView(context).apply {
            text = "CONTEXT AUTOMATION PIPELINES"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        routineTitleRow.addView(Ui.hudBadge(context, "ACTIVE", Ui.SUCCESS))
        routineInner.addView(routineTitleRow)

        routineInner.addView(TextView(context).apply {
            text = "Automations fire on hardware triggers (Wi-Fi, battery level, charging state, schedules) with built-in cooldown protection."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
        })

        val sampleRoutines = listOf(
            Triple("Low Battery Saver", "Trigger: Battery < 20% • Dims volume, speaks alert, turns off torch", true),
            Triple("Bedtime Wind-Down", "Trigger: Charger plugged at night • Vibrate ringer, 10% vol, stop media", true),
            Triple("Work / Office Arrival", "Trigger: Connects to Office Wi-Fi • Sets ringer to vibrate, 15% vol", true),
            Triple("Home Arrival", "Trigger: Connects to Home Wi-Fi • Sets volume to 75%, announces briefing", true)
        )

        sampleRoutines.forEach { (name, desc, defaultActive) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 8)
                setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 6) }
            }

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(context).apply {
                text = name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.CYAN)
            })
            col.addView(TextView(context).apply {
                text = desc
                textSize = 10f
                setTextColor(Ui.MUTED)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val rSwitch = MaterialSwitch(context).apply {
                isChecked = defaultActive
                setOnClickListener { markDirty() }
            }
            row.addView(rSwitch)
            routineInner.addView(row)
        }

        routineCard.addView(routineInner)
        root.addView(routineCard)

        // Autonomous App Navigation & UI Macros Card
        root.addView(Ui.sectionLabel(context, "Autonomous App Navigation & UI Macros"))
        val macroCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val macroInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val macroTitleRow = Ui.row(context)
        macroTitleRow.addView(TextView(context).apply {
            text = "MULTI-STEP APP NAVIGATION MACROS"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        macroTitleRow.addView(Ui.hudBadge(context, "AUTOMATION", Ui.CYAN))
        macroInner.addView(macroTitleRow)

        macroInner.addView(TextView(context).apply {
            text = "Autonomous UI sequences that navigate apps, click elements, enter text, and perform global gestures with closed-loop verification."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
        })

        val sampleMacros = listOf(
            Triple("Clear All Background Apps", "4 steps: Recents -> Delay -> Click Close/Clear all", "system"),
            Triple("YouTube Quick Search", "4 steps: Launch YouTube -> Wait -> Click Search -> Type query", "youtube"),
            Triple("Check Software Update", "5 steps: Open Settings -> Scroll down -> Click Software update", "settings"),
            Triple("Return Home & Shade", "3 steps: Home screen -> Delay -> Pull notification shade", "system")
        )

        sampleMacros.forEach { (name, desc, targetPkg) ->
            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 8)
                setPadding(Ui.dp(context, 10), Ui.dp(context, 8), Ui.dp(context, 10), Ui.dp(context, 8))
                layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = Ui.dp(context, 6) }
            }

            val col = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            col.addView(TextView(context).apply {
                text = name
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.CYAN)
            })
            col.addView(TextView(context).apply {
                text = desc
                textSize = 10f
                setTextColor(Ui.MUTED)
            })
            row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))

            val pkgBadge = Ui.hudBadge(context, targetPkg.uppercase(), Ui.TEXT)
            row.addView(pkgBadge)
            macroInner.addView(row)
        }

        macroCard.addView(macroInner)
        root.addView(macroCard)

        // Deep Automation & Morning Briefing Card
        root.addView(Ui.sectionLabel(context, "Deep Automation & Morning Briefing"))
        val autoCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val autoInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val autoTitleRow = Ui.row(context)
        autoTitleRow.addView(TextView(context).apply {
            text = "DEEP DEVICE AUTOMATION & SCHEDULES"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))
        autoTitleRow.addView(Ui.hudBadge(context, "ACTIVE", Ui.CYAN))
        autoInner.addView(autoTitleRow)

        autoInner.addView(TextView(context).apply {
            text = "Hands-free scheduled briefings, screen wake-up above lock screen, and WhatsApp background macro flows."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 8))
        })

        // 1. Daily Briefing Switch
        dailyBriefingSwitch.apply {
            text = "Automated Morning Briefing"
            setTextColor(Ui.TEXT)
            isChecked = initial.dailyBriefingEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        autoInner.addView(dailyBriefingSwitch)

        // 2. Scheduled Time Row
        val timeRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 8))
        }
        timeRow.addView(TextView(context).apply {
            text = "Briefing Scheduled Time"
            textSize = 12f
            setTextColor(Ui.MUTED)
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        briefingTimeBtn.apply {
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN, 8)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                val parts = currentBriefingTime.split(":")
                val curH = parts.getOrNull(0)?.toIntOrNull() ?: 8
                val curM = parts.getOrNull(1)?.toIntOrNull() ?: 0
                android.app.TimePickerDialog(context, { _, hourOfDay, minute ->
                    currentBriefingTime = String.format(java.util.Locale.US, "%02d:%02d", hourOfDay, minute)
                    updateBriefingTimeBtnText()
                    markDirty()
                }, curH, curM, false).show()
            }
        }
        updateBriefingTimeBtnText()
        timeRow.addView(briefingTimeBtn)
        autoInner.addView(timeRow)

        // 3. Lock-Screen HUD Switch
        lockScreenSwitch.apply {
            text = "Wake Screen & Show HUD on Lock-Screen"
            setTextColor(Ui.TEXT)
            isChecked = initial.lockScreenVoiceEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        autoInner.addView(lockScreenSwitch)

        // 3.5. Device Unlock PIN Input
        val pinRow = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 6))
        }
        pinRow.addView(TextView(context).apply {
            text = "Device Unlock PIN / Passcode"
            textSize = 12f
            setTextColor(Ui.MUTED)
        })
        deviceUnlockPinInput.apply {
            hint = "e.g. 1234 (used for auto-unlock)"
            setText(initial.deviceUnlockPin)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            setTextColor(Ui.TEXT)
            setHintTextColor(Ui.MUTED)
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { markDirty() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        pinRow.addView(deviceUnlockPinInput)
        autoInner.addView(pinRow)

        // 3.8. Auto-Learn Mode Switch
        autoLearnSwitch.apply {
            text = "Auto-Learn Mode (Capture Screen & Log Workflows)"
            setTextColor(Ui.TEXT)
            isChecked = initial.autoLearnEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        autoInner.addView(autoLearnSwitch)

        // 4. WhatsApp Auto-Return Switch
        whatsAppAutoReturnSwitch.apply {
            text = "Auto-Return to Home After Sending Messages"
            setTextColor(Ui.TEXT)
            isChecked = initial.whatsAppAutoReturnEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        autoInner.addView(whatsAppAutoReturnSwitch)

        autoCard.addView(autoInner)
        root.addView(autoCard)

        // 1. Wake Engine & Voice Card
        root.addView(Ui.sectionLabel(context, "Wake Engine & Audio Contract"))
        val wakeCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val wakeInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        addField(wakeInner, "Wake Word (TFLite KWS)", wakeWord, initial.wakeWord)
        addField(wakeInner, "Preferred Groq Model", llmModel, initial.preferredLlmModel)

        ownerVoiceSwitch.apply {
            text = "Only wake for my trained voice"
            setTextColor(Ui.TEXT)
            isEnabled = ownerVoiceProfile != null
            isChecked = initial.ownerVoiceWakeEnabled && ownerVoiceProfile != null
            setOnCheckedChangeListener { _, checked ->
                if (checked && ownerVoiceProfile == null) isChecked = false else markDirty()
            }
        }
        wakeInner.addView(ownerVoiceSwitch)
        wakeInner.addView(TextView(context).apply {
            text = "Requires the local neural wake model and a trained speaker template. Missing or low-confidence verification never activates voice wake; tap-to-talk stays available. This is not anti-spoof security."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, 0, 0, Ui.dp(context, 8))
        })

        val ackOptions = listOf("CHIME", "VOICE", "BOTH", "SILENT")
        wakeAckSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ackOptions)
        val selectedAck = ackOptions.indexOf(initial.wakeAcknowledgment.uppercase()).coerceAtLeast(0)
        wakeAckSpinner.setSelection(selectedAck)
        wakeAckSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) { markDirty() }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        val ackRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
            val label = TextView(context).apply {
                text = "Wake Response Style"
                setTextColor(Ui.TEXT)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(label)
            addView(wakeAckSpinner)
        }
        wakeInner.addView(ackRow)

        val ttsProviders = listOf("Groq Neural (PlayAI)", "Android Platform TTS")
        ttsProviderSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ttsProviders)
        ttsProviderSpinner.setSelection(if (initial.ttsProvider.equals("android", ignoreCase = true)) 1 else 0)
        ttsProviderSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) { markDirty() }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        val ttsProviderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
            val label = TextView(context).apply {
                text = "TTS Voice Engine"
                setTextColor(Ui.TEXT)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(label)
            addView(ttsProviderSpinner)
        }
        wakeInner.addView(ttsProviderRow)

        val ttsVoices = listOf("Fritz-PlayAI", "Aaliyah-PlayAI", "Angelo-PlayAI", "Arista-PlayAI", "Atlas-PlayAI")
        ttsVoiceSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, ttsVoices)
        val selectedVoiceIdx = ttsVoices.indexOf(initial.ttsVoice).coerceAtLeast(0)
        ttsVoiceSpinner.setSelection(selectedVoiceIdx)
        ttsVoiceSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p0: AdapterView<*>?, p1: View?, p2: Int, p3: Long) { markDirty() }
            override fun onNothingSelected(p0: AdapterView<*>?) {}
        }
        val ttsVoiceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
            val label = TextView(context).apply {
                text = "Groq Neural Voice"
                setTextColor(Ui.TEXT)
                textSize = 12f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
            }
            addView(label)
            addView(ttsVoiceSpinner)
        }
        wakeInner.addView(ttsVoiceRow)

        continuousConvSwitch.text = "Continuous Conversation Mode"
        continuousConvSwitch.setTextColor(Ui.TEXT)
        continuousConvSwitch.isChecked = initial.continuousConversation
        continuousConvSwitch.setOnCheckedChangeListener { _, _ -> markDirty() }
        wakeInner.addView(continuousConvSwitch)

        bargeInSwitch.text = "Barge-in Speech Interruption"
        bargeInSwitch.setTextColor(Ui.TEXT)
        bargeInSwitch.isChecked = initial.bargeInEnabled
        bargeInSwitch.setOnCheckedChangeListener { _, _ -> markDirty() }
        wakeInner.addView(bargeInSwitch)

        offlineAsrSwitch.text = "Prefer Offline Low-Latency Neural ASR"
        offlineAsrSwitch.setTextColor(Ui.TEXT)
        offlineAsrSwitch.isChecked = initial.offlineAsrPreferred
        offlineAsrSwitch.setOnCheckedChangeListener { _, _ -> markDirty() }
        wakeInner.addView(offlineAsrSwitch)

        // Media Ducking Switch
        mediaDuckingSwitch.apply {
            text = "Automated Background Media Ducking"
            setTextColor(Ui.TEXT)
            isChecked = initial.mediaDuckingEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        wakeInner.addView(mediaDuckingSwitch)

        // Ducking Level Row
        val duckRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, Ui.dp(context, 4), 0, Ui.dp(context, 4))
        }
        duckRow.addView(TextView(context).apply {
            text = "Media Ducking Volume Level"
            setTextColor(Ui.MUTED)
            textSize = 12f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })
        duckingPercentBtn.apply {
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN, 8)
            setPadding(Ui.dp(context, 10), Ui.dp(context, 5), Ui.dp(context, 10), Ui.dp(context, 5))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                currentDuckingPercent = when (currentDuckingPercent) {
                    25 -> 45
                    45 -> 65
                    else -> 25
                }
                updateDuckingPercentBtnText()
                markDirty()
            }
        }
        updateDuckingPercentBtnText()
        duckRow.addView(duckingPercentBtn)
        wakeInner.addView(duckRow)

        // Acoustic Feedback Switch
        acousticFeedbackSwitch.apply {
            text = "Acoustic Earcons & Audio Feedback"
            setTextColor(Ui.TEXT)
            isChecked = initial.acousticFeedbackEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        wakeInner.addView(acousticFeedbackSwitch)

        // Haptic Feedback Switch
        hapticFeedbackSwitch.apply {
            text = "Haptic Tactile Pulse on Barge-in"
            setTextColor(Ui.TEXT)
            isChecked = initial.hapticFeedbackEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        wakeInner.addView(hapticFeedbackSwitch)

        // Voice Calibration Profile Sub-Section
        val profileDivider = View(context).apply {
            setBackgroundColor(Ui.BORDER)
            layoutParams = LinearLayout.LayoutParams(-1, Ui.dp(context, 1)).apply {
                topMargin = Ui.dp(context, 12)
                bottomMargin = Ui.dp(context, 12)
            }
        }
        wakeInner.addView(profileDivider)

        val profileHeaderRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(context, 6))
        }
        profileHeaderRow.addView(TextView(context).apply {
            text = "WAKE-WORD SENSITIVITY CALIBRATION"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
            letterSpacing = 0.05f
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        })

        val profileBadge = TextView(context)
        profileHeaderRow.addView(profileBadge)
        wakeInner.addView(profileHeaderRow)

        val profileDetailsView = TextView(context).apply {
            textSize = 11f
            setTextColor(Ui.MUTED)
            setLineSpacing(0f, 1.15f)
            setPadding(0, 0, 0, Ui.dp(context, 10))
        }
        wakeInner.addView(profileDetailsView)

        val profileButtonsRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, Ui.dp(context, 8))
        }

        val enrollVoiceBtn = com.google.android.material.button.MaterialButton(context).apply {
            text = "🎙 ENROLL / RE-CALIBRATE"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.BG)
            cornerRadius = Ui.dp(context, 8)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.CYAN)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
        }

        val resetVoiceBtn = com.google.android.material.button.MaterialButton(context).apply {
            text = "↺ RESET TO DEFAULT"
            textSize = 11f
            setTextColor(Ui.MUTED)
            strokeColor = android.content.res.ColorStateList.valueOf(Ui.BORDER)
            strokeWidth = Ui.dp(context, 1)
            cornerRadius = Ui.dp(context, 8)
            backgroundTintList = android.content.res.ColorStateList.valueOf(Ui.SURFACE_2)
            setPadding(Ui.dp(context, 12), Ui.dp(context, 6), Ui.dp(context, 12), Ui.dp(context, 6))
            layoutParams = LinearLayout.LayoutParams(-2, -2).apply {
                marginStart = Ui.dp(context, 8)
            }
        }

        fun updateVoiceProfileCard() {
            if (voiceProfile.isEnrolled) {
                profileBadge.text = "CALIBRATED"
                profileBadge.textSize = 9.5f
                profileBadge.typeface = Typeface.DEFAULT_BOLD
                profileBadge.setTextColor(Ui.CYAN)
                profileBadge.background = Ui.rounded(context, Ui.SURFACE_2, Ui.CYAN, 6)
                profileBadge.setPadding(Ui.dp(context, 8), Ui.dp(context, 3), Ui.dp(context, 8), Ui.dp(context, 3))
                profileDetailsView.text = "Wake-word confidence threshold: ${String.format(java.util.Locale.US, "%.2f", voiceProfile.calibratedThreshold)} • ${voiceProfile.sampleCount} acoustic samples • RMS floor ${voiceProfile.calibratedMinEnergyRms.toInt()}. This does not identify the speaker."
                resetVoiceBtn.visibility = View.VISIBLE
            } else {
                profileBadge.text = "FACTORY DEFAULT"
                profileBadge.textSize = 9.5f
                profileBadge.typeface = Typeface.DEFAULT_BOLD
                profileBadge.setTextColor(Ui.MUTED)
                profileBadge.background = Ui.rounded(context, Ui.SURFACE_2, Ui.BORDER, 6)
                profileBadge.setPadding(Ui.dp(context, 8), Ui.dp(context, 3), Ui.dp(context, 8), Ui.dp(context, 3))
                profileDetailsView.text = "Default DS-CNN TFLite Acoustic Model • Threshold: 0.82 • Min Energy: 110.0 RMS"
                resetVoiceBtn.visibility = View.GONE
            }
        }

        enrollVoiceBtn.setOnClickListener {
            VoiceEnrollmentDialog(context) { newProfile ->
                store.saveVoiceProfile(newProfile)
                voiceProfile = newProfile
                updateVoiceProfileCard()
                onSaved?.invoke()
            }.show()
        }

        resetVoiceBtn.setOnClickListener {
            store.resetVoiceProfile()
            voiceProfile = store.loadVoiceProfile()
            updateVoiceProfileCard()
            onSaved?.invoke()
        }

        updateVoiceProfileCard()
        profileButtonsRow.addView(enrollVoiceBtn)
        profileButtonsRow.addView(resetVoiceBtn)
        wakeInner.addView(profileButtonsRow)

        val ownerStatus = TextView(context).apply {
            textSize = 10.5f
            setTextColor(if (ownerVoiceProfile == null) Ui.MUTED else Ui.SUCCESS)
            setPadding(0, Ui.dp(context, 8), 0, Ui.dp(context, 6))
        }
        fun updateOwnerStatus() {
            ownerStatus.text = if (ownerVoiceProfile == null) "OWNER VOICE: NOT TRAINED — sensitivity calibration above does not identify the speaker." else "OWNER VOICE: TRAINED • 3 local samples • encrypted 256-value speaker template"
            ownerStatus.setTextColor(if (ownerVoiceProfile == null) Ui.MUTED else Ui.SUCCESS)
        }
        updateOwnerStatus()
        wakeInner.addView(ownerStatus)
        val ownerButtons = LinearLayout(context).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
        ownerButtons.addView(com.google.android.material.button.MaterialButton(context).apply {
            text = if (ownerVoiceProfile == null) "🎙 TRAIN MY VOICE" else "🎙 RETRAIN MY VOICE"
            setOnClickListener {
                OwnerVoiceEnrollmentDialog(context) { profile ->
                    if (store.saveOwnerVoiceProfile(profile)) {
                        ownerVoiceProfile = profile
                        ownerVoiceSwitch.isEnabled = true
                        ownerVoiceSwitch.isChecked = true
                        updateOwnerStatus()
                        markDirty()
                        text = "🎙 RETRAIN MY VOICE"
                        Toast.makeText(context, "Speaker template saved encrypted. Save config to activate owner-only wake.", Toast.LENGTH_LONG).show()
                    } else Toast.makeText(context, "Could not save owner voice profile securely", Toast.LENGTH_LONG).show()
                }.show()
            }
        })
        if (ownerVoiceProfile != null) ownerButtons.addView(com.google.android.material.button.MaterialButton(context).apply {
            text = "REMOVE VOICE"
            setOnClickListener {
                store.resetOwnerVoiceProfile()
                ownerVoiceProfile = null
                ownerVoiceSwitch.isChecked = false
                ownerVoiceSwitch.isEnabled = false
                settings = settings.copy(ownerVoiceWakeEnabled = false)
                store.saveSettings(settings)
                updateOwnerStatus()
                markDirty()
                Toast.makeText(context, "Owner profile removed and owner-only wake disabled", Toast.LENGTH_SHORT).show()
                onSaved?.invoke()
            }
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = Ui.dp(context, 8) })
        wakeInner.addView(ownerButtons)

        wakeInner.addView(TextView(context).apply {
            text = "Engine: TFLite DS-CNN INT8 • 16 kHz Mono • AudioRecord VOICE_RECOGNITION"
            textSize = 10f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 4), 0, 0)
        })
        wakeCard.addView(wakeInner)
        root.addView(wakeCard)

        // Bluetooth Headsets & Wearable Companion Card
        root.addView(Ui.sectionLabel(context, "Bluetooth Headsets & Wearables"))
        val btCard = Ui.glassCard(context, Ui.BORDER_GLOW)
        val btInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        val btTitleRow = Ui.row(context)
        btTitleRow.addView(TextView(context).apply {
            text = "BLUETOOTH AUDIO & WRIST COMPANION"
            textSize = 12f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            letterSpacing = 0.08f
        }, LinearLayout.LayoutParams(0, -2, 1f))

        val isHeadsetConnected = com.jarvis.service.JarvisForegroundService.instance?.bluetoothHeadsetManager?.isHeadsetConnected == true
        val headsetName = com.jarvis.service.JarvisForegroundService.instance?.bluetoothHeadsetManager?.connectedDeviceName ?: "DISCONNECTED"
        val btBadge = Ui.hudBadge(
            context,
            if (isHeadsetConnected) "CONNECTED: $headsetName" else "NO HEADSET",
            if (isHeadsetConnected) Ui.CYAN else Ui.MUTED
        )
        btTitleRow.addView(btBadge)
        btInner.addView(btTitleRow)

        btInner.addView(TextView(context).apply {
            text = "Routes microphone and speech through connected Bluetooth earbuds (Galaxy Buds, AirPods, neckbands) and listens for earbud hook button presses for physical barge-in."
            textSize = 10.5f
            setTextColor(Ui.MUTED)
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 10))
        })

        bluetoothScoSwitch.apply {
            text = "Bluetooth Earbud Microphone (SCO Audio Routing)"
            setTextColor(Ui.TEXT)
            isChecked = initial.bluetoothScoRoutingEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        btInner.addView(bluetoothScoSwitch)

        headsetHookSwitch.apply {
            text = "Earbud Button Wake / Physical Barge-in Hook"
            setTextColor(Ui.TEXT)
            isChecked = initial.headsetHookActivationEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        btInner.addView(headsetHookSwitch)

        wearableSyncSwitch.apply {
            text = "Smartwatch & WearOS Companion Synchronization"
            setTextColor(Ui.TEXT)
            isChecked = initial.wearableSyncEnabled
            setOnCheckedChangeListener { _, _ -> markDirty() }
        }
        btInner.addView(wearableSyncSwitch)

        floatingOverlaySwitch.apply {
            text = "Floating Multitasking Hologram Bubble (Over Other Apps)"
            setTextColor(Ui.TEXT)
            isChecked = initial.floatingOverlayEnabled
            setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M &&
                        !android.provider.Settings.canDrawOverlays(context)
                    ) {
                        this.isChecked = false
                        Toast.makeText(context, "Please grant Overlay Permission for J.A.R.V.I.S.", Toast.LENGTH_LONG).show()
                        val intent = android.content.Intent(
                            android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:${context.packageName}")
                        ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                        context.startActivity(intent)
                        return@setOnCheckedChangeListener
                    }
                    val intent = android.content.Intent(context, com.jarvis.overlay.JarvisFloatingOverlayService::class.java)
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                } else {
                    val intent = android.content.Intent(context, com.jarvis.overlay.JarvisFloatingOverlayService::class.java).apply {
                        action = com.jarvis.overlay.JarvisFloatingOverlayService.ACTION_STOP
                    }
                    context.stopService(intent)
                }
                markDirty()
            }
        }
        btInner.addView(floatingOverlaySwitch)

        val testWearBtn = Ui.button(context, "SEND TEST WRIST COMPANION NOTIFICATION") {
            com.jarvis.wear.WearableNotificationDispatcher.dispatchWearableCard(
                context,
                title = "J.A.R.V.I.S. Wrist Companion",
                statusText = "Ready • Tap 🎙 Talk or 🛑 Stop"
            )
            Toast.makeText(context, "Dispatched wearable companion card", Toast.LENGTH_SHORT).show()
        }
        btInner.addView(testWearBtn)

        btCard.addView(btInner)
        root.addView(btCard)

        // 2. Preferences & Theme Card
        root.addView(Ui.sectionLabel(context, "Preferences & Network"))
        val prefCard = Ui.glassCard(context, Ui.BORDER)
        val prefInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }

        themeSpinner.adapter = ArrayAdapter(context, android.R.layout.simple_spinner_dropdown_item, listOf("system", "light", "dark"))
        themeSpinner.setSelection(listOf("system", "light", "dark").indexOf(initial.theme).coerceAtLeast(0))
        prefInner.addView(TextView(context).apply { text = "UI Theme"; textSize = 11f; typeface = Typeface.DEFAULT_BOLD; setTextColor(Ui.TEXT) })
        prefInner.addView(themeSpinner)

        prefCard.addView(prefInner)
        root.addView(prefCard)

        // 3. Custom Environment Variables Card
        root.addView(Ui.sectionLabel(context, "Custom Environment Variables"))
        val customCard = Ui.glassCard(context, Ui.BORDER)
        val customInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        addField(customInner, "Variable Name (e.g. USER_NAME)", customKey, "")
        addField(customInner, "Variable Value (e.g. Tony Stark)", customValue, "")
        customInner.addView(Ui.button(context, "+ Add Variable") { addCustomVariable() })
        customList.orientation = LinearLayout.VERTICAL
        customInner.addView(customList)
        renderCustomVariables()
        customCard.addView(customInner)
        root.addView(customCard)

        // 4. Save & Reset Actions
        val saveActions = Ui.row(context).apply {
            setPadding(0, Ui.dp(context, 6), 0, Ui.dp(context, 14))
        }
        saveActions.addView(Ui.primaryButton(context, "SAVE CONFIG") { save() }, LinearLayout.LayoutParams(0, -2, 1f))
        saveActions.addView(Ui.button(context, "RESET DEFAULTS") { reset() })
        root.addView(saveActions)

        // 5. Diagnostics & Event Logs Section
        root.addView(Ui.sectionLabel(context, "Diagnostics & Trace Logs"))
        val logCard = Ui.glassCard(context, Ui.BORDER)
        val logInner = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(Ui.dp(context, 14), Ui.dp(context, 12), Ui.dp(context, 14), Ui.dp(context, 12))
        }
        val logHeader = Ui.row(context)
        logHeader.addView(TextView(context).apply {
            text = "CORRELATION TRACES"
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.CYAN)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        logHeader.addView(Ui.button(context, "Refresh") { renderLogs() })
        logHeader.addView(Ui.dangerButton(context, "Clear") { store.clearLogs(); renderLogs() })
        logInner.addView(logHeader)

        logView.apply {
            setTextColor(Ui.MUTED)
            textSize = 11f
            setLineSpacing(0f, 1.15f)
            setPadding(0, Ui.dp(context, 8), 0, 0)
        }
        logInner.addView(logView)
        logCard.addView(logInner)
        root.addView(logCard)
        renderLogs()

        val editFields = listOf(wakeWord, llmModel, customKey, customValue)
        editFields.forEach { field ->
            field.addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { markDirty() }
                override fun afterTextChanged(s: android.text.Editable?) = Unit
            })
        }
        themeSpinner.onItemSelectedListener = simpleSpinnerListener { markDirty() }
        suppressDirty = false

    }

    private fun addField(root: LinearLayout, hint: String, field: EditText, value: String) {
        field.hint = hint
        field.setText(value)
        field.setTextColor(Color.WHITE)
        field.setHintTextColor(0xFF7E8A97.toInt())
        field.setSingleLine(true)
        root.addView(field, LinearLayout.LayoutParams(-1, Ui.dp(context, 52)).apply { bottomMargin = Ui.dp(context, 8) })
    }

    private fun addCustomVariable() {
        val key = customKey.text?.toString()?.trim().orEmpty(); val value = customValue.text?.toString().orEmpty()
        if (key.isBlank()) return
        store.saveCustomVariable(key, value)
        customKey.text?.clear(); customValue.text?.clear(); renderCustomVariables(); markDirty()
    }

    private fun renderCustomVariables() {
        customList.removeAllViews()
        store.loadCustomVariables().forEach { (key, value) ->
            val row = Ui.row(context)
            row.addView(TextView(context).apply { text = "$key = $value"; setTextColor(Color.WHITE) }, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(Ui.button(context, "Delete") { store.deleteCustomVariable(key); renderCustomVariables(); markDirty() })
            customList.addView(row)
        }
    }

    private fun renderLogs() {
        logView.text = store.logs().takeLast(30).joinToString("\n").ifBlank { "No diagnostics logged yet." }
    }

    private fun save() {
        settings = settings.copy(
            theme = themeSpinner.selectedItem.toString(),
            wakeWord = wakeWord.text.toString().trim().ifBlank { "Jarvis" },
            preferredLlmModel = llmModel.text.toString().trim().ifBlank { LlmConfig.DEFAULT_MODEL },
            continuousConversation = continuousConvSwitch.isChecked,
            bargeInEnabled = bargeInSwitch.isChecked,
            offlineAsrPreferred = offlineAsrSwitch.isChecked,
            batteryAnnouncementsEnabled = batteryAnnouncementsSwitch.isChecked,
            proactiveCalendarAlertsEnabled = proactiveCalendarSwitch.isChecked,
            proactivePreBriefingMinutes = currentPreBriefingMinutes,
            proactiveBatteryAlertsEnabled = proactiveBatterySwitch.isChecked,
            wakeAcknowledgment = wakeAckSpinner.selectedItem.toString(),
            dailyBriefingEnabled = dailyBriefingSwitch.isChecked,
            dailyBriefingTime = currentBriefingTime,
            lockScreenVoiceEnabled = lockScreenSwitch.isChecked,
            deviceUnlockPin = deviceUnlockPinInput.text.toString().trim(),
            autoLearnEnabled = autoLearnSwitch.isChecked,
            ownerVoiceWakeEnabled = ownerVoiceSwitch.isChecked,
            whatsAppAutoReturnEnabled = whatsAppAutoReturnSwitch.isChecked,
            mediaDuckingEnabled = mediaDuckingSwitch.isChecked,
            mediaDuckingPercent = currentDuckingPercent,
            acousticFeedbackEnabled = acousticFeedbackSwitch.isChecked,
            hapticFeedbackEnabled = hapticFeedbackSwitch.isChecked,
            bluetoothScoRoutingEnabled = bluetoothScoSwitch.isChecked,
            headsetHookActivationEnabled = headsetHookSwitch.isChecked,
            wearableSyncEnabled = wearableSyncSwitch.isChecked,
            floatingOverlayEnabled = floatingOverlaySwitch.isChecked,
            ttsProvider = if (ttsProviderSpinner.selectedItemPosition == 1) "android" else "groq",
            ttsVoice = ttsVoiceSpinner.selectedItem?.toString() ?: "Fritz-PlayAI"
        )
        store.saveSettings(settings)

        // Re-arm or cancel the scheduled daily morning briefing
        if (dailyBriefingSwitch.isChecked) {
            val parts = currentBriefingTime.split(":")
            val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
            val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
            com.jarvis.automation.DailyBriefingScheduler.scheduleNextBriefing(context, hour = h, minute = m)
        } else {
            com.jarvis.automation.DailyBriefingScheduler.cancelBriefing(context)
        }

        dirty = false; onDirtyChanged(false)
        onSaved?.invoke()
    }

    private fun reset() {
        settings = AppSettings()
        store.saveSettings(settings)
        proactiveCalendarSwitch.isChecked = settings.proactiveCalendarAlertsEnabled
        currentPreBriefingMinutes = settings.proactivePreBriefingMinutes
        updatePreBriefingMinutesBtnText()
        proactiveBatterySwitch.isChecked = settings.proactiveBatteryAlertsEnabled
        batteryAnnouncementsSwitch.isChecked = settings.batteryAnnouncementsEnabled
        dailyBriefingSwitch.isChecked = settings.dailyBriefingEnabled
        currentBriefingTime = settings.dailyBriefingTime
        updateBriefingTimeBtnText()
        lockScreenSwitch.isChecked = settings.lockScreenVoiceEnabled
        deviceUnlockPinInput.setText(settings.deviceUnlockPin)
        autoLearnSwitch.isChecked = settings.autoLearnEnabled
        store.resetOwnerVoiceProfile()
        ownerVoiceProfile = null
        ownerVoiceSwitch.isEnabled = false
        ownerVoiceSwitch.isChecked = false
        whatsAppAutoReturnSwitch.isChecked = settings.whatsAppAutoReturnEnabled
        mediaDuckingSwitch.isChecked = settings.mediaDuckingEnabled
        currentDuckingPercent = settings.mediaDuckingPercent
        updateDuckingPercentBtnText()
        acousticFeedbackSwitch.isChecked = settings.acousticFeedbackEnabled
        hapticFeedbackSwitch.isChecked = settings.hapticFeedbackEnabled
        bluetoothScoSwitch.isChecked = settings.bluetoothScoRoutingEnabled
        headsetHookSwitch.isChecked = settings.headsetHookActivationEnabled
        wearableSyncSwitch.isChecked = settings.wearableSyncEnabled
        floatingOverlaySwitch.isChecked = settings.floatingOverlayEnabled
        com.jarvis.automation.DailyBriefingScheduler.scheduleNextBriefing(context, hour = 8, minute = 0)
        dirty = false; onDirtyChanged(false)
        onSaved?.invoke()
        removeAllViews(); addView(TextView(context).apply { text = "Settings reset. Re-open Settings to edit."; setTextColor(Color.WHITE) })
    }

    private fun formatTimeForDisplay(timeStr: String): String {
        val parts = timeStr.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 8
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0
        val amPm = if (h < 12) "AM" else "PM"
        val displayHour = when {
            h == 0 -> 12
            h > 12 -> h - 12
            else -> h
        }
        return String.format(java.util.Locale.US, "%02d:%02d %s", displayHour, m, amPm)
    }

    private fun updateBriefingTimeBtnText() {
        briefingTimeBtn.text = formatTimeForDisplay(currentBriefingTime)
    }

    private fun updateDuckingPercentBtnText() {
        duckingPercentBtn.text = "$currentDuckingPercent% Vol"
    }

    private fun updatePreBriefingMinutesBtnText() {
        preBriefingMinutesBtn.text = "$currentPreBriefingMinutes min before"
    }

    private fun markDirty() { if (suppressDirty) return; if (!dirty) { dirty = true; onDirtyChanged(true) } }
    fun hasUnsavedChanges(): Boolean = dirty

    fun saveSettings() = save()

    private fun simpleSpinnerListener(action: () -> Unit) = object : AdapterView.OnItemSelectedListener {
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) { action() }
    }
}
