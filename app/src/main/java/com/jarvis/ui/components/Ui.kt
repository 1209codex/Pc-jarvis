package com.jarvis.ui.components

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.CircularProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

object Ui {
    // Stitch Cyber-Slate & Holographic Design Tokens
    const val BG = 0xFF05090D.toInt()
    const val SURFACE = 0xFF0B141E.toInt()
    const val SURFACE_2 = 0xFF101A23.toInt()
    const val SURFACE_CONTAINER = 0xFF18202A.toInt()
    const val SURFACE_CONTAINER_HIGH = 0xFF222B35.toInt()
    const val BORDER = 0xFF203442.toInt()
    const val BORDER_GLOW = 0xFF3C494B.toInt()
    const val CYAN = 0xFF63EFFF.toInt()
    const val CYAN_DIM = 0xFF2BA8B8.toInt()
    const val BLUE_PRIMARY = 0xFF0266FF.toInt()
    const val TEXT = 0xFFDAE3F1.toInt()
    const val MUTED = 0xFF869395.toInt()
    const val SUCCESS = 0xFF70F0B0.toInt()
    const val WARNING = 0xFFFFC857.toInt()
    const val ERROR = 0xFFFF6B6B.toInt()

    @JvmStatic
    fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).toInt()

    @JvmStatic
    fun column(context: Context): LinearLayout = column(context, 16)

    @JvmStatic
    fun column(context: Context, pad: Int): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(context, pad), dp(context, pad), dp(context, pad), dp(context, pad))
        layoutParams = ViewGroup.LayoutParams(-1, -1)
    }

    @JvmStatic
    fun row(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    @JvmStatic
    fun title(context: Context, text: String): TextView = title(context, text, 22f)

    @JvmStatic
    fun title(context: Context, text: String, size: Float): TextView = TextView(context).apply {
        this.text = text
        textSize = size
        setTextColor(TEXT)
        typeface = Typeface.create("sans-serif-medium", Typeface.BOLD)
        letterSpacing = 0.04f
        setPadding(0, 0, 0, dp(context, 4))
    }

    @JvmStatic
    fun subtitle(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text
        textSize = 12f
        setTextColor(MUTED)
        setLineSpacing(0f, 1.15f)
        setPadding(0, 0, 0, dp(context, 10))
    }

    @JvmStatic
    fun sectionLabel(context: Context, text: String): TextView = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 10.5f
        setTextColor(CYAN)
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.12f
        setPadding(0, dp(context, 6), 0, dp(context, 8))
    }

    @JvmStatic
    fun card(context: Context): MaterialCardView = card(context, BORDER)

    @JvmStatic
    fun card(context: Context, accent: Int): MaterialCardView = MaterialCardView(context).apply {
        radius = dp(context, 14).toFloat()
        setCardBackgroundColor(SURFACE)
        strokeColor = accent
        strokeWidth = dp(context, 1)
        cardElevation = 0f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) }
    }

    @JvmStatic
    fun glassCard(context: Context, strokeColor: Int = BORDER): MaterialCardView = MaterialCardView(context).apply {
        radius = dp(context, 16).toFloat()
        setCardBackgroundColor(SURFACE_CONTAINER)
        this.strokeColor = strokeColor
        strokeWidth = dp(context, 1)
        cardElevation = 0f
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 12) }
    }

    @JvmStatic
    fun badge(context: Context, text: String): TextView = badge(context, text, CYAN)

    @JvmStatic
    fun badge(context: Context, text: String, accent: Int): TextView = TextView(context).apply {
        this.text = text.uppercase()
        textSize = 10f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(accent)
        background = rounded(context, SURFACE_2, accent, 999)
        setPadding(dp(context, 10), dp(context, 4), dp(context, 10), dp(context, 4))
    }

    @JvmStatic
    fun hudBadge(context: Context, text: String, accent: Int = CYAN): TextView = TextView(context).apply {
        this.text = "● ${text.uppercase()}"
        textSize = 9.5f
        typeface = Typeface.DEFAULT_BOLD
        letterSpacing = 0.08f
        gravity = Gravity.CENTER
        setTextColor(accent)
        background = rounded(context, 0x1A63EFFF, accent, 6)
        setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    @JvmStatic
    fun techChip(context: Context, label: String, icon: String, onClick: () -> Unit): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(context, SURFACE_2, BORDER_GLOW, 8)
        setPadding(dp(context, 12), dp(context, 8), dp(context, 14), dp(context, 8))
        isClickable = true
        isFocusable = true
        setOnClickListener { onClick() }

        val iconView = TextView(context).apply {
            text = icon
            textSize = 14f
            setTextColor(CYAN)
            gravity = Gravity.CENTER
            setPadding(0, 0, dp(context, 6), 0)
        }
        val labelView = TextView(context).apply {
            text = label
            textSize = 11.5f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(TEXT)
            letterSpacing = 0.04f
        }
        addView(iconView)
        addView(labelView)
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(context, 8) }
    }

    @JvmStatic
    fun button(context: Context, text: String, onClick: () -> Unit): MaterialButton = MaterialButton(context).apply {
        this.text = text
        setTextColor(TEXT)
        textSize = 11.5f
        minHeight = dp(context, 42)
        minWidth = 0
        cornerRadius = dp(context, 10)
        strokeWidth = dp(context, 1)
        strokeColor = android.content.res.ColorStateList.valueOf(BORDER)
        backgroundTintList = android.content.res.ColorStateList.valueOf(SURFACE_2)
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-2, dp(context, 42)).apply { marginEnd = dp(context, 8) }
    }

    @JvmStatic
    fun primaryButton(context: Context, text: String, onClick: () -> Unit): MaterialButton = button(context, text, onClick).apply {
        setTextColor(BG)
        typeface = Typeface.DEFAULT_BOLD
        strokeColor = android.content.res.ColorStateList.valueOf(CYAN)
        backgroundTintList = android.content.res.ColorStateList.valueOf(CYAN)
    }

    @JvmStatic
    fun secondaryButton(context: Context, text: String, onClick: () -> Unit): MaterialButton = button(context, text, onClick).apply {
        setTextColor(CYAN)
        typeface = Typeface.DEFAULT_BOLD
        strokeColor = android.content.res.ColorStateList.valueOf(CYAN_DIM)
        backgroundTintList = android.content.res.ColorStateList.valueOf(SURFACE_2)
    }

    @JvmStatic
    fun dangerButton(context: Context, text: String, onClick: () -> Unit): MaterialButton = button(context, text, onClick).apply {
        setTextColor(ERROR)
        strokeColor = android.content.res.ColorStateList.valueOf(0xFF6E3038.toInt())
        backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF291318.toInt())
    }

    @JvmStatic
    fun input(context: Context, hint: String): Pair<TextInputLayout, TextInputEditText> {
        val til = TextInputLayout(context).apply {
            this.hint = hint
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxStrokeColor = CYAN_DIM
            setHintTextColor(android.content.res.ColorStateList.valueOf(MUTED))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(context, 10) }
        }
        val edit = TextInputEditText(context).apply {
            setSingleLine(true)
            setTextColor(TEXT)
            setHintTextColor(MUTED)
        }
        til.addView(edit)
        return til to edit
    }

    @JvmStatic
    fun divider(context: Context): View = View(context).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(-1, dp(context, 1)).apply { topMargin = dp(context, 8); bottomMargin = dp(context, 8) }
    }

    @JvmStatic
    fun valuePair(context: Context, label: String, value: String): LinearLayout = row(context).apply {
        val l = TextView(context).apply { text = label; textSize = 11f; setTextColor(MUTED) }
        val v = TextView(context).apply { text = value; textSize = 12f; setTextColor(TEXT); gravity = Gravity.END }
        addView(l, LinearLayout.LayoutParams(0, -2, 1f))
        addView(v)
    }

    @JvmStatic
    fun rounded(context: Context, fill: Int, stroke: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = dp(context, radiusDp).toFloat()
        setStroke(dp(context, 1), stroke)
    }

    @JvmStatic
    fun progress(context: Context): CircularProgressIndicator = CircularProgressIndicator(context).apply {
        isIndeterminate = true
        visibility = View.GONE
    }
}
