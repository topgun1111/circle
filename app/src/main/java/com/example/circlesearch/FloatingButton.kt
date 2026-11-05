package com.example.circlesearch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import kotlin.math.abs

/**
 * Small draggable bubble. Tap = start Circle to Search.
 * Long-press = it turns into a red X; tap the X to close the bubble (it reverts by itself after 3 s).
 *
 * Optional edge mode: it stays where you drop it; after the configured idle timeout it glides to the
 * nearest screen edge, tucks half off-screen and fades. Touching it brings it back.
 */
@SuppressLint("ViewConstructor")
class FloatingButton(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val edgeMode: Boolean,
    private val baseAlpha: Float,
    private val edgeDelayMs: Long,
    private val onTap: () -> Unit,
    private val onClose: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val h = Handler(Looper.getMainLooper())

    private var closeMode = false
    private var dragging = false
    private var longFired = false
    private var tucked = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var startX = 0
    private var startY = 0
    private var anim: ValueAnimator? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0D93025.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
    }
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val longPress = Runnable {
        longFired = true
        closeMode = true
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        invalidate()
        h.postDelayed(revert, 3000)
    }
    private val revert = Runnable {
        closeMode = false
        invalidate()
        scheduleIdle()
    }
    private val idle = Runnable {
        if (edgeMode && !closeMode && !dragging) tuck()
    }

    init {
        alpha = baseAlpha
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        scheduleIdle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ring.shader = LinearGradient(
            0f, 0f, w.toFloat(), h.toFloat(),
            intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt()),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = width / 2f - 2 * d
        if (closeMode) {
            canvas.drawCircle(cx, cy, r, redFill)
            val s = width * 0.146f
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, icon)
            canvas.drawLine(cx - s, cy + s, cx + s, cy - s, icon)
        } else {
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, width * 0.17f, icon)
        }
    }

    // ---- Edge mode ----------------------------------------------------------

    private fun screenW() = wm.currentWindowMetrics.bounds.width()

    private fun scheduleIdle() {
        h.removeCallbacks(idle)
        if (edgeMode) h.postDelayed(idle, edgeDelayMs)
    }

    private fun edgeX(): Int = if (lp.x + lp.width / 2 < screenW() / 2) 0 else screenW() - lp.width

    private fun animateX(to: Int) {
        anim?.cancel()
        anim = ValueAnimator.ofInt(lp.x, to).apply {
            duration = 160
            addUpdateListener {
                lp.x = it.animatedValue as Int
                try { wm.updateViewLayout(this@FloatingButton, lp) } catch (_: Exception) {}
            }
            start()
        }
    }

    private fun tuck() {
        if (tucked) return
        tucked = true
        val left = lp.x + lp.width / 2 < screenW() / 2
        animateX(if (left) -lp.width / 2 else screenW() - lp.width / 2)
        animate().alpha(baseAlpha * 0.5f).setDuration(200).start()
    }

    private fun untuck() {
        if (!tucked) return
        tucked = false
        anim?.cancel()
        lp.x = if (lp.x + lp.width / 2 < screenW() / 2) 0 else screenW() - lp.width
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
        animate().alpha(baseAlpha).setDuration(120).start()
    }

    // ---- Touch --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                h.removeCallbacks(idle)
                anim?.cancel()
                untuck()
                downRawX = e.rawX
                downRawY = e.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
                longFired = false
                h.postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    h.removeCallbacks(longPress)
                }
                if (dragging) {
                    lp.x = startX + dx.toInt()
                    lp.y = startY + dy.toInt()
                    clamp()
                    try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longPress)
                if (dragging) {
                    Prefs.savePos(context, if (edgeMode) edgeX() else lp.x, lp.y)
                    scheduleIdle()
                } else if (!longFired) {
                    if (closeMode) {
                        h.removeCallbacks(revert)
                        onClose()
                    } else {
                        onTap()
                        scheduleIdle()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(longPress)
                scheduleIdle()
            }
        }
        return true
    }

    private fun clamp() {
        val b = wm.currentWindowMetrics.bounds
        lp.x = lp.x.coerceIn(0, (b.width() - lp.width).coerceAtLeast(0))
        lp.y = lp.y.coerceIn(0, (b.height() - lp.height).coerceAtLeast(0))
    }

    override fun onConfigurationChanged(newConfig: Configuration?) {
        super.onConfigurationChanged(newConfig)
        tucked = false
        alpha = baseAlpha
        clamp() // fold/unfold or rotate: keep the bubble on screen
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
        scheduleIdle()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        h.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }
}
