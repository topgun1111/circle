package com.example.circlesearch

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/** Tiny programmatic UI kit: cards, switch rows, sliders, tonal/primary buttons. Light + dark aware. */
class Ui(private val c: Context) {
    private val d = c.resources.displayMetrics.density
    val dark = (c.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES

    val bg = if (dark) 0xFF111215.toInt() else 0xFFF2F3F7.toInt()
    private val cardBg = if (dark) 0xFF1D1F24.toInt() else Color.WHITE
    private val fg = if (dark) 0xFFECEDF1.toInt() else 0xFF17181C.toInt()
    private val sub = if (dark) 0xFF9A9FAB.toInt() else 0xFF5E6370.toInt()
    val accent = if (dark) 0xFF7AA7FF.toInt() else 0xFF2F66F0.toInt()
    val ok = 0xFF2E9E5B.toInt()
    val warn = 0xFFD98E04.toInt()
    val bad = 0xFFD64545.toInt()

    fun dp(v: Int) = (v * d).toInt()

    private fun shape(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = r * d }

    private fun ripple(color: Int, r: Int) =
        RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x22000000), shape(color, r), null)

    fun text(s: String, size: Float = 14f, color: Int = sub, bold: Boolean = false) = TextView(c).apply {
        text = s; textSize = size; setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    fun pageTitle(s: String) = text(s, 28f, fg, true)
    fun heading(s: String) = text(s, 16f, fg, true)
    fun hint(s: String) = text(s, 12.5f, sub).apply { setLineSpacing(0f, 1.1f) }
    fun mono(s: String = "") = text(s, 12f, sub).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }

    /** Adds [v] with a top margin. */
    fun LinearLayout.add(v: View, top: Int = 10): View {
        addView(v, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(top) })
        return v
    }

    fun page(build: LinearLayout.() -> Unit): ScrollView {
        val col = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(28))
            build()
        }
        return ScrollView(c).apply { setBackgroundColor(bg); isFillViewport = true; addView(col) }
    }

    fun LinearLayout.card(build: LinearLayout.() -> Unit): LinearLayout {
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            background = shape(cardBg, 18)
            setPadding(dp(16), dp(14), dp(16), dp(16))
            build()
        }
        add(box, 12)
        return box
    }

    fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(c).apply {
        text = label; isAllCaps = false; textSize = 14f
        stateListAnimator = null
        minHeight = 0; minimumHeight = dp(44)
        setPadding(dp(14), 0, dp(14), 0)
        setTextColor(if (primary) Color.WHITE else accent)
        background = ripple(if (primary) accent else (accent and 0x00FFFFFF) or 0x22000000, 12)
        setOnClickListener { onClick() }
    }

    /** Buttons side by side, equal width. */
    fun LinearLayout.buttons(vararg bs: Button, top: Int = 10) {
        val row = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        bs.forEachIndexed { i, b ->
            row.addView(b, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                .apply { if (i > 0) marginStart = dp(8) })
        }
        add(row, top)
    }

    fun pill(tv: TextView, color: Int) {
        tv.setTextColor(color)
        tv.setTypeface(tv.typeface, Typeface.BOLD)
        tv.setPadding(dp(12), dp(6), dp(12), dp(6))
        tv.background = shape((color and 0x00FFFFFF) or 0x26000000, 20)
    }

    fun switchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Switch, Boolean) -> Unit): Pair<LinearLayout, Switch> {
        val sw = Switch(c).apply { isChecked = checked }
        val texts = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(text(title, 15.5f, fg, true))
            if (subtitle != null) addView(hint(subtitle))
        }
        val row = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(12) })
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
        sw.setOnCheckedChangeListener { b, on -> onChange(b as Switch, on) }
        return row to sw
    }

    fun slider(label: String, unit: String, min: Int, max: Int, value: Int, onDone: (Int) -> Unit): LinearLayout {
        val v = text("$value$unit", 13f, accent, true)
        val bar = SeekBar(c).apply {
            this.max = max - min
            progress = value - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) { v.text = "${p + min}$unit" }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) { onDone((sb?.progress ?: 0) + min) }
            })
        }
        return LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(text(label, 13.5f, sub), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(v)
            })
            addView(bar)
        }
    }

    fun divider() = View(c).apply { setBackgroundColor(if (dark) 0x22FFFFFF else 0x14000000) }

    fun LinearLayout.divide() {
        addView(divider(), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1))
            .apply { topMargin = dp(14) })
    }

    /** Card whose body is hidden until the header is tapped. */
    fun LinearLayout.expandable(titleText: String, subtitle: String, build: LinearLayout.() -> Unit): LinearLayout {
        val body = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; build() }
        val arrow = text("▾", 18f, sub)
        return card {
            val head = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(c).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(heading(titleText)); addView(hint(subtitle))
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(arrow)
                setOnClickListener {
                    val open = body.visibility != View.VISIBLE
                    body.visibility = if (open) View.VISIBLE else View.GONE
                    arrow.text = if (open) "▴" else "▾"
                }
            }
            addView(head)
            addView(body)
        }
    }

    /** Call after setContentView: the insets controller does not exist before that. */
    fun style(a: Activity) {
        try {
            a.window.statusBarColor = bg
            a.window.navigationBarColor = bg
            val m = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            a.window.decorView.windowInsetsController?.setSystemBarsAppearance(if (dark) 0 else m, m)
        } catch (_: Throwable) {
        }
    }
}
