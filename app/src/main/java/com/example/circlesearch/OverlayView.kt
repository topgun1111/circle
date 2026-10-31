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
 * Shows the frozen screenshot (dimmed), lets the user draw a freehand circle,
 * then returns the bounding-box crop of whatever was circled.
 */
@SuppressLint("ViewConstructor")
class OverlayView(
    context: Context,
    private val shot: Bitmap,
    private val onCrop: (Bitmap) -> Unit,
    private val onCancel: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val loc = IntArray(2)
    private val path = Path()
    private val bounds = RectF()
    private var hasPath = false
    private var done = false

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

    override fun onDraw(canvas: Canvas) {
        // Draw the screenshot exactly aligned with the real screen.
        getLocationOnScreen(loc)
        canvas.drawBitmap(shot, -loc[0].toFloat(), -loc[1].toFloat(), bmpPaint)

        // Dim everything except the area inside the circle.
        canvas.save()
        if (hasPath) canvas.clipOutPath(path)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), dimPaint)
        canvas.restore()

        if (hasPath) {
            canvas.drawPath(path, glow)
            canvas.drawPath(path, line)
        }

        // Hint pill
        val label = "Circle anything to search"
        val pw = textPaint.measureText(label) + 36 * d
        val ph = 40 * d
        val cx = width / 2f
        val cy = hintY()
        canvas.drawRoundRect(cx - pw / 2, cy - ph / 2, cx + pw / 2, cy + ph / 2, ph / 2, ph / 2, pillPaint)
        canvas.drawText(label, cx, cy + textPaint.textSize * 0.35f, textPaint)

        // Close button
        val bx = closeX()
        canvas.drawCircle(bx, cy, 18 * d, pillPaint)
        val s = 6 * d
        canvas.drawLine(bx - s, cy - s, bx + s, cy + s, xPaint)
        canvas.drawLine(bx - s, cy + s, bx + s, cy - s, xPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (done) return true
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (hypot(e.x - closeX(), e.y - hintY()) < 28 * d) {
                    done = true
                    onCancel()
                    return true
                }
                path.reset()
                path.moveTo(e.x, e.y)
                bounds.set(e.x, e.y, e.x, e.y)
                hasPath = true
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until e.historySize) add(e.getHistoricalX(i), e.getHistoricalY(i))
                add(e.x, e.y)
            }
            MotionEvent.ACTION_UP -> {
                add(e.x, e.y)
                finishDrawing()
            }
            MotionEvent.ACTION_CANCEL -> {
                path.reset()
                hasPath = false
            }
        }
        invalidate()
        return true
    }

    private fun add(x: Float, y: Float) {
        path.lineTo(x, y)
        bounds.union(x, y)
    }

    private fun finishDrawing() {
        // Too small: treat as an accidental tap and let the user try again.
        if (bounds.width() < 36 * d || bounds.height() < 36 * d) {
            path.reset()
            hasPath = false
            return
        }
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
        onCrop(Bitmap.createBitmap(shot, l, t, rt - l, bt - t))
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
