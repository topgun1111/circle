package com.example.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot

/**
 * Shows the frozen screenshot (dimmed). The user draws a freehand circle or drags a rectangle.
 * After drawing, the selection stays: rectangles can be moved and resized with their corner handles,
 * and an action bar offers Search / Share / Save.
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    private val shot: Bitmap,
    private val onAction: (action: Int, crop: Bitmap) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    companion object {
        const val ACTION_SEARCH = 0
        const val ACTION_SHARE = 1
        const val ACTION_SAVE = 2
        private val LABELS = arrayOf("Search", "Share", "Save")
    }

    private enum class Drag { NONE, NEW, MOVE, RESIZE, BAR }

    private val d = resources.displayMetrics.density
    private val loc = IntArray(2)
    private val path = Path()
    private val bounds = RectF()
    private var hasPath = false
    private var selected = false
    private var done = false
    private var rectMode = Prefs.rectMode(context)

    private var drag = Drag.NONE
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var barIndex = -1

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0x99000000.toInt() }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 5 * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 14 * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        alpha = 90
    }
    private val pillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC202124.toInt() }
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 15 * d
        textAlign = Paint.Align.CENTER
    }
    private val xPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
        strokeCap = Paint.Cap.ROUND
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF4285F4.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2 * d
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt()),
            null, Shader.TileMode.CLAMP
        )
        line.shader = shader
        glow.shader = shader
    }

    private fun hintY() = 72 * d
    private fun closeX() = width - 36 * d
    private fun modeX() = 36 * d

    // Action bar geometry
    private fun barW() = 3 * 92 * d
    private fun barH() = 52 * d
    private fun barLeft() = (width - barW()) / 2f
    private fun barTop() = height - 132 * d

    private fun corners() = arrayOf(
        floatArrayOf(bounds.left, bounds.top),
        floatArrayOf(bounds.right, bounds.top),
        floatArrayOf(bounds.left, bounds.bottom),
        floatArrayOf(bounds.right, bounds.bottom)
    )

    override fun onDraw(canvas: Canvas) {
        // Draw the screenshot exactly aligned with the real screen.
        getLocationOnScreen(loc)
        canvas.drawBitmap(shot, -loc[0].toFloat(), -loc[1].toFloat(), bmpPaint)

        // Dim everything except the area inside the selection.
        canvas.save()
        if (hasPath) canvas.clipOutPath(path)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()

        if (hasPath) {
            canvas.drawPath(path, glow)
            canvas.drawPath(path, line)
        }

        // Corner handles (rectangle only)
        if (selected && rectMode) {
            for (c in corners()) {
                canvas.drawCircle(c[0], c[1], 9 * d, handleFill)
                canvas.drawCircle(c[0], c[1], 9 * d, handleEdge)
            }
        }

        // Hint pill
        val label = when {
            selected -> "Adjust, or pick an action"
            rectMode -> "Drag a box to search"
            else -> "Circle anything to search"
        }
        val pw = textPaint.measureText(label) + 36 * d
        val ph = 40 * d
        val cx = width / 2f
        val cy = hintY()
        canvas.drawRoundRect(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2, ph / 2, ph / 2, pillPaint)
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Shape toggle (left): shows the current shape, tap to switch
        val mx = modeX()
        canvas.drawCircle(mx, cy, 18 * d, pillPaint)
        if (rectMode) {
            canvas.drawRoundRect(mx - 7 * d, cy - 5 * d, mx + 7 * d, cy + 5 * d, 2 * d, 2 * d, xPaint)
        } else {
            canvas.drawCircle(mx, cy, 7 * d, xPaint)
        }

        // Close button
        val bx = closeX()
        canvas.drawCircle(bx, cy, 18 * d, pillPaint)
        val s = 6 * d
        canvas.drawLine(bx - s, cy - s, bx + s, cy + s, xPaint)
        canvas.drawLine(bx - s, cy + s, bx + s, cy - s, xPaint)

        // Action bar
        if (selected) {
            val l = barLeft()
            val t = barTop()
            canvas.drawRoundRect(l, t, l + barW(), t + barH(), barH() / 2, barH() / 2, barPaint)
            val slot = barW() / 3
            for (i in 0..2) {
                canvas.drawText(LABELS[i], l + slot * i + slot / 2, t + barH() / 2 + textPaint.textSize * 0.35f, textPaint)
                if (i > 0) canvas.drawLine(l + slot * i, t + 14 * d, l + slot * i, t + barH() - 14 * d, xPaint.apply { alpha = 80 })
            }
            xPaint.alpha = 255
        }
    }

    private fun barHit(x: Float, y: Float): Int {
        if (!selected) return -1
        val l = barLeft()
        val t = barTop()
        if (x < l || x > l + barW() || y < t || y > t + barH()) return -1
        return ((x - l) / (barW() / 3)).toInt().coerceIn(0, 2)
    }

    private fun cornerHit(x: Float, y: Float): Int {
        if (!selected || !rectMode) return -1
        corners().forEachIndexed { i, c ->
            if (hypot(x - c[0], y - c[1]) < 32 * d) return i
        }
        return -1
    }

    private fun rebuildRect() {
        path.reset()
        path.addRoundRect(bounds, 8 * d, 8 * d, Path.Direction.CW)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        // Only the first finger drives the selection.
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN || e.actionMasked == MotionEvent.ACTION_POINTER_UP) return true

        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                drag = Drag.NONE
                if (hypot(e.x - closeX(), e.y - hintY()) < 28 * d) {
                    done = true
                    onCancel()
                    return true
                }
                if (hypot(e.x - modeX(), e.y - hintY()) < 28 * d) {
                    rectMode = !rectMode
                    Prefs.setRectMode(context, rectMode)
                    path.reset()
                    hasPath = false
                    selected = false
                    invalidate()
                    return true // drag stays NONE, so the rest of this touch is ignored
                }
                val bar = barHit(e.x, e.y)
                if (bar >= 0) {
                    drag = Drag.BAR
                    barIndex = bar
                    return true
                }
                val corner = cornerHit(e.x, e.y)
                if (corner >= 0) {
                    val cs = corners()
                    val opposite = cs[3 - corner] // 0<->3, 1<->2
                    anchorX = opposite[0]
                    anchorY = opposite[1]
                    drag = Drag.RESIZE
                    return true
                }
                if (selected && rectMode && bounds.contains(e.x, e.y)) {
                    drag = Drag.MOVE
                    lastX = e.x
                    lastY = e.y
                    return true
                }
                // Start a new selection.
                selected = false
                drag = Drag.NEW
                startX = e.x
                startY = e.y
                path.reset()
                path.moveTo(e.x, e.y)
                bounds.set(e.x, e.y, e.x, e.y)
                hasPath = true
            }

            MotionEvent.ACTION_MOVE -> when (drag) {
                Drag.NEW -> {
                    if (rectMode) {
                        bounds.set(minOf(startX, e.x), minOf(startY, e.y), maxOf(startX, e.x), maxOf(startY, e.y))
                        rebuildRect()
                    } else {
                        for (i in 0 until e.historySize) add(e.getHistoricalX(i), e.getHistoricalY(i))
                        add(e.x, e.y)
                    }
                }
                Drag.RESIZE -> {
                    val min = 36 * d
                    val x = e.x.coerceIn(0f, width.toFloat())
                    val y = e.y.coerceIn(0f, height.toFloat())
                    val nx = if (x >= anchorX) maxOf(x, anchorX + min) else minOf(x, anchorX - min)
                    val ny = if (y >= anchorY) maxOf(y, anchorY + min) else minOf(y, anchorY - min)
                    bounds.set(minOf(anchorX, nx), minOf(anchorY, ny), maxOf(anchorX, nx), maxOf(anchorY, ny))
                    rebuildRect()
                }
                Drag.MOVE -> {
                    var dx = e.x - lastX
                    var dy = e.y - lastY
                    dx = dx.coerceIn(-bounds.left, width - bounds.right)
                    dy = dy.coerceIn(-bounds.top, height - bounds.bottom)
                    bounds.offset(dx, dy)
                    lastX = e.x
                    lastY = e.y
                    rebuildRect()
                }
                else -> {}
            }

            MotionEvent.ACTION_UP -> {
                when (drag) {
                    Drag.NEW -> {
                        if (rectMode) {
                            bounds.set(minOf(startX, e.x), minOf(startY, e.y), maxOf(startX, e.x), maxOf(startY, e.y))
                            rebuildRect()
                        } else add(e.x, e.y)
                        // Too small: treat as an accidental tap and let the user try again.
                        if (bounds.width() < 36 * d || bounds.height() < 36 * d) {
                            path.reset()
                            hasPath = false
                        } else {
                            selected = true
                        }
                    }
                    Drag.BAR -> {
                        if (barHit(e.x, e.y) == barIndex) perform(barIndex)
                    }
                    else -> {}
                }
                drag = Drag.NONE
            }

            MotionEvent.ACTION_CANCEL -> {
                if (drag == Drag.NEW) {
                    path.reset()
                    hasPath = false
                    selected = false
                }
                drag = Drag.NONE
            }
        }
        invalidate()
        return true
    }

    private fun add(x: Float, y: Float) {
        path.lineTo(x, y)
        bounds.union(x, y)
    }

    private fun perform(action: Int) {
        done = true
        getLocationOnScreen(loc)
        val pad = 14 * d
        val r = RectF(bounds).apply {
            inset(-pad, -pad)
            offset(loc[0].toFloat(), loc[1].toFloat())
        }
        val l = r.left.toInt().coerceIn(0, shot.width - 1)
        val t = r.top.toInt().coerceIn(0, shot.height - 1)
        val rt = r.right.toInt().coerceIn(l + 1, shot.width)
        val bt = r.bottom.toInt().coerceIn(t + 1, shot.height)
        onAction(action, Bitmap.createBitmap(shot, l, t, rt - l, bt - t))
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.action == KeyEvent.ACTION_UP && !done) {
                done = true
                onCancel()
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }
}
