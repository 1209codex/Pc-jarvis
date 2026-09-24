package com.jarvis.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

class ResponsiveNav(context: Context, private val onSelect: (String) -> Unit) : LinearLayout(context) {

    data class NavItem(val id: String, val label: String, val icon: String)

    private val navButtons = mutableMapOf<String, Triple<View, TextView, TextView>>()
    private var selectedRoute = "Dashboard"

    init {
        orientation = HORIZONTAL
        setBackgroundColor(0xFF071017.toInt())
        setPadding(0, Ui.dp(context, 2), 0, Ui.dp(context, 4))
        elevation = Ui.dp(context, 8).toFloat()
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, Ui.dp(context, 58))

        listOf(
            NavItem("Dashboard", "Home", "⌂"),
            NavItem("Skills", "Skills", "⚡"),
            NavItem("Memory", "Memory", "◈"),
            NavItem("APIs", "APIs", "◌"),
            NavItem("Settings", "Settings", "⚙")
        ).forEach { item ->
            val tab = LinearLayout(context).apply {
                orientation = VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                background = Ui.rounded(context, Color.TRANSPARENT, Color.TRANSPARENT, 8)
                contentDescription = "Open ${item.label}"
            }
            val indicator = View(context).apply {
                setBackgroundColor(Ui.CYAN)
                layoutParams = LayoutParams(Ui.dp(context, 32), Ui.dp(context, 2)).apply { bottomMargin = Ui.dp(context, 3) }
                visibility = if (item.id == selectedRoute) View.VISIBLE else View.INVISIBLE
            }
            val icon = TextView(context).apply {
                text = item.icon
                textSize = 16f
                gravity = Gravity.CENTER
                setTextColor(if (item.id == selectedRoute) Ui.CYAN else Ui.MUTED)
            }
            val label = TextView(context).apply {
                text = item.label
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                letterSpacing = 0.02f
                setTextColor(if (item.id == selectedRoute) Ui.CYAN else Ui.MUTED)
            }
            tab.addView(indicator)
            tab.addView(icon)
            tab.addView(label)
            tab.setOnClickListener { setRoute(item.id); onSelect(item.id) }
            navButtons[item.id] = Triple(indicator, icon, label)
            addView(tab, LayoutParams(0, LayoutParams.MATCH_PARENT, 1f))
        }
    }

    fun setRoute(route: String) {
        selectedRoute = route
        navButtons.forEach { (id, views) ->
            val selected = id == route
            views.first.visibility = if (selected) View.VISIBLE else View.INVISIBLE
            views.second.setTextColor(if (selected) Ui.CYAN else Ui.MUTED)
            views.third.setTextColor(if (selected) Ui.CYAN else Ui.MUTED)
        }
    }
}
