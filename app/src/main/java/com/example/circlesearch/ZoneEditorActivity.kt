package com.example.circlesearch

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.Button
import android.widget.CheckBox
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

/** Full-screen editor: drag the left / right edge zones up or down, drag their ends to resize. */
class ZoneEditorActivity : Activity() {

    private lateinit var editor: Editor

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        window.setDecorFitsSystemWindows(false)
        window.attributes = window.attributes.apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        window.insetsController?.hide(WindowInsets.Type.systemBars())

        editor = Editor(this)

        fun check(text: String, side: Int) = CheckBox(this).apply {
            this.text = text
            setTextColor(Color.WHITE)
            isChecked = Prefs.zoneOn(this@ZoneEditorActivity, side)
            setOnCheckedChangeListener { _, on -> editor.on[side] = on; editor.invalidate() }
        }

        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt())
            setBackgroundColor(0xCC202124.toInt())
        }
        panel.addView(TextView(this).apply {
            text = "Edge pull zones"
            setTextColor(Color.WHITE); textSize = 18f
        })
        panel.addView(TextView(this).apply {
            text = "Drag a zone up or down to move it.\nDrag its top or bottom end to resize it."
            setTextColor(0xFFC8CBD0.toInt()); textSize = 13f
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        })
        panel.addView(check("Left zone", 0))
        panel.addView(check("Right zone", 1))
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply { text = "Reset"; setOnClickListener {
            editor.resetDefaults()
        } })
        row.addView(Button(this).apply { text = "Cancel"; setOnClickListener { finish() } })
        row.addView(Button(this).apply { text = "Save"; setOnClickListener { save(); finish() } })
        panel.addView(row)

        val root = FrameLayout(this)
        root.addView(editor, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        root.addView(panel, FrameLayout.LayoutParams(
            (300 * d).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        setContentView(root)

        // Best effort: stop the system back-swipe from eating drags that start at the screen edge.
        root.post {
            root.systemGestureExclusionRects = listOf(Rect(0, 0, root.width, root.height))
        }
    }

    override fun onResume() {
        super.onResume()
        CircleService.instance?.setZonesPaused(true) // the live strips would steal the editor's touches
    }

    override fun onPause() {
        CircleService.instance?.setZonesPaused(false)
        super.onPause()
    }

    private fun save() {
        for (s in 0..1) Prefs.saveZone(this, s, editor.on[s], editor.top[s], editor.bottom[s])
        CircleService.instance?.applyZones()
    }

    private class Editor(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        val on = booleanArrayOf(Prefs.zoneOn(ctx, 0), Prefs.zoneOn(ctx, 1))
        val top = floatArrayOf(Prefs.zoneTop(ctx, 0), Prefs.zoneTop(ctx, 1))
        val bottom = floatArrayOf(Prefs.zoneBottom(ctx, 0), Prefs.zoneBottom(ctx, 1))
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d }
        private val txt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 13 * d; textAlign = Paint.Align.CENTER }
        private val r = RectF()
        private val minFrac = 0.08f

        private var side = -1
        private var part = 0 // 0 move, 1 top, 2 bottom
        private var lastY = 0f

        fun resetDefaults() {
            for (s in 0..1) { on[s] = true; top[s] = 0f; bottom[s] = 0.30f }
            invalidate()
        }

        private fun rect(s: Int): RectF {
            val w = Prefs.zoneWidth(context) * d
            val x0 = if (s == 0) 0f else width - w
            r.set(x0, top[s] * height, x0 + w, bottom[s] * height)
            return r
        }

        override fun onDraw(canvas: Canvas) {
            canvas.drawColor(0xFF101114.toInt())
            for (s in 0..1) {
                val rc = rect(s)
                fill.color = if (on[s]) 0x8834A853.toInt() else 0x33888888
                stroke.color = if (on[s]) 0xFF34A853.toInt() else 0x66888888
                canvas.drawRoundRect(rc, 8 * d, 8 * d, fill)
                canvas.drawRoundRect(rc, 8 * d, 8 * d, stroke)
                // resize knobs
                fill.color = if (on[s]) 0xFFFFFFFF.toInt() else 0x88FFFFFF.toInt()
                canvas.drawCircle(rc.centerX(), rc.top, 7 * d, fill)
                canvas.drawCircle(rc.centerX(), rc.bottom, 7 * d, fill)
                val pct = ((bottom[s] - top[s]) * 100).toInt()
                canvas.save()
                canvas.rotate(if (s == 0) -90f else 90f, rc.centerX(), rc.centerY())
                canvas.drawText("${if (s == 0) "LEFT" else "RIGHT"}  $pct%", rc.centerX(), rc.centerY() + 5 * d, txt)
                canvas.restore()
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    side = if (e.x < width / 2f) 0 else 1
                    val rc = rect(side)
                    val tol = 28 * d
                    part = when {
                        abs(e.y - rc.top) < tol -> 1
                        abs(e.y - rc.bottom) < tol -> 2
                        e.y > rc.top && e.y < rc.bottom -> 0
                        else -> -1
                    }
                    lastY = e.y
                    if (part == -1) side = -1
                    return side != -1
                }
                MotionEvent.ACTION_MOVE -> if (side != -1) {
                    val df = (e.y - lastY) / height
                    lastY = e.y
                    var t = top[side]
                    var b = bottom[side]
                    when (part) {
                        0 -> {
                            val h = b - t
                            t = (t + df).coerceIn(0f, 1f - h)
                            b = t + h
                        }
                        1 -> t = (t + df).coerceIn(0f, b - minFrac)
                        2 -> b = (b + df).coerceIn(t + minFrac, 1f)
                    }
                    top[side] = t
                    bottom[side] = b
                    invalidate()
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> side = -1
            }
            return true
        }
    }
}
