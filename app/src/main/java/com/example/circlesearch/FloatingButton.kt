package com.example.circlesearch

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.OvershootInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Small draggable bubble. Tap = start Circle to Search.
 * Long-press = it turns into a red X; tap the X to close the bubble (it reverts by itself after 3 s).
 *
 * Edge mode, docked at a side: hold the bubble and pull it towards the screen centre. It stretches like
 * rubber but stays anchored to the edge; past the arming distance (it turns bigger + haptic tick) let go
 * and it snaps back like a rubber band; once it has settled the capture starts. Dragging up/down slides it along the edge.
 *
 * Optional edge mode: it stays where you drop it; after the configured idle timeout it glides to the
 * nearest screen edge, tucks mostly off-screen (~70%) and fades. Touching it brings it back.
 */
@SuppressLint("ViewConstructor")
class FloatingButton(
    context: Context,
    private val wm: WindowManager,
    private val lp: WindowManager.LayoutParams,
    private val edgeMode: Boolean,
    private val baseAlpha: Float,
    private val edgeDelayMs: Long,
    private val chromeStyle: Boolean,
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

    // Rubber-band pull state
    private val baseSize = lp.width
    private val maxPull = 150 * d
    private val armDist = 0.45f * maxPull
    private var mode = MODE_FREE
    private var rubber = false
    private var armed = false
    private var pullX = 0f
    private var pullY = 0f
    private var rubberAnim: ValueAnimator? = null
    private val neck = Path()
    private val neckPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
    private val redFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xF0D93025.toInt() }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * d
    }
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val oval = RectF()
    private val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.5f * d
        strokeCap = Paint.Cap.ROUND
        color = 0xFFFFFFFF.toInt()
    }

    private val longPress = Runnable {
        longFired = true
        closeMode = true
        untuck()
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

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (changedView === this && visibility == VISIBLE) scheduleIdle()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        ring.shader = LinearGradient(
            0f, 0f, baseSize.toFloat(), baseSize.toFloat(),
            intArrayOf(0xFF4285F4.toInt(), 0xFFEA4335.toInt(), 0xFFFBBC05.toInt(), 0xFF34A853.toInt()),
            null, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        // The window is centred on the anchor (the docked bubble), also while it is enlarged for a pull.
        val ax = width / 2f
        val ay = height / 2f
        if (rubber && (pullX != 0f || pullY != 0f)) drawNeck(canvas, ax, ay, ax + pullX, ay + pullY)
        canvas.save()
        canvas.translate(ax + pullX - baseSize / 2f, ay + pullY - baseSize / 2f)
        if (armed) canvas.scale(1.12f, 1.12f, baseSize / 2f, baseSize / 2f)
        drawBubble(canvas)
        canvas.restore()
    }

    private fun drawNeck(canvas: Canvas, ax: Float, ay: Float, hx: Float, hy: Float) {
        val len = hypot(hx - ax, hy - ay)
        if (len < 1f) return
        val ux = (hx - ax) / len
        val uy = (hy - ay) / len
        val px = -uy
        val py = ux
        val r = baseSize / 2f - 2 * d
        val ra = r * 0.95f
        val rh = r * 0.6f
        val rm = r * (0.5f - 0.25f * (len / maxPull).coerceAtMost(1f))
        val mx = (ax + hx) / 2f
        val my = (ay + hy) / 2f
        neckPaint.color = if (armed) 0xF034A853.toInt() else 0xE6202124.toInt()
        neck.reset()
        neck.moveTo(ax + px * ra, ay + py * ra)
        neck.quadTo(mx + px * rm, my + py * rm, hx + px * rh, hy + py * rh)
        neck.lineTo(hx - px * rh, hy - py * rh)
        neck.quadTo(mx - px * rm, my - py * rm, ax - px * ra, ay - py * ra)
        neck.close()
        canvas.drawPath(neck, neckPaint)
        canvas.drawCircle(ax, ay, ra, neckPaint)
    }

    private fun drawBubble(canvas: Canvas) {
        val w = baseSize.toFloat()
        val cx = w / 2f
        val cy = w / 2f
        val r = w / 2f - 2 * d
        if (closeMode) {
            canvas.drawCircle(cx, cy, r, redFill)
            val s = w * 0.146f
            canvas.drawLine(cx - s, cy - s, cx + s, cy + s, icon)
            canvas.drawLine(cx - s, cy + s, cx + s, cy - s, icon)
        } else if (chromeStyle) {
            oval.set(cx - r, cy - r, cx + r, cy + r)
            canvas.drawCircle(cx, cy, r + d, arcPaint.apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() })
            arcPaint.color = 0xFFEA4335.toInt(); canvas.drawArc(oval, 210f, 120f, true, arcPaint)
            arcPaint.color = 0xFFFBBC05.toInt(); canvas.drawArc(oval, 330f, 120f, true, arcPaint)
            arcPaint.color = 0xFF34A853.toInt(); canvas.drawArc(oval, 90f, 120f, true, arcPaint)
            arcPaint.color = 0xFFFFFFFF.toInt(); canvas.drawCircle(cx, cy, r * 0.46f, arcPaint)
            arcPaint.color = 0xFF4285F4.toInt(); canvas.drawCircle(cx, cy, r * 0.36f, arcPaint)
        } else {
            canvas.drawCircle(cx, cy, r, fill)
            canvas.drawCircle(cx, cy, r, ring)
            canvas.drawCircle(cx, cy, w * 0.17f, icon)
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
        animateX(if (left) -(lp.width * 0.7f).toInt() else screenW() - (lp.width * 0.3f).toInt())
        animate().alpha(baseAlpha * 0.35f).setDuration(200).start()
    }

    private fun untuck() {
        if (!tucked) return
        tucked = false
        anim?.cancel()
        lp.x = if (lp.x + lp.width / 2 < screenW() / 2) 0 else screenW() - lp.width
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
        animate().alpha(baseAlpha).setDuration(120).start()
    }

    // ---- Rubber pull ----------------------------------------------------------

    private fun isDocked() = edgeMode && (tucked || lp.x <= 0 || lp.x >= screenW() - lp.width)

    private fun startRubber() {
        untuck()
        rubber = true
        armed = false
        pullX = 0f
        pullY = 0f
        val r = maxPull.toInt()
        lp.width = baseSize + 2 * r
        lp.height = baseSize + 2 * r
        lp.x -= r
        lp.y -= r
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
    }

    private fun updateRubber(dx: Float, dy: Float) {
        val m = hypot(dx, dy)
        val k = maxPull * 0.9f
        val s = if (m == 0f) 0f else k * (1f - exp(-m / k))
        pullX = if (m == 0f) 0f else dx / m * s
        pullY = if (m == 0f) 0f else dy / m * s
        val nowArmed = s >= armDist
        if (nowArmed != armed) {
            armed = nowArmed
            if (armed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        invalidate()
    }

    private fun finishRubber() {
        rubberAnim?.cancel()
        if (!rubber) return
        rubber = false
        armed = false
        pullX = 0f
        pullY = 0f
        val r = maxPull.toInt()
        lp.width = baseSize
        lp.height = baseSize
        lp.x += r
        lp.y += r
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
        invalidate()
        scheduleIdle()
    }

    private fun releaseRubber() {
        val fire = armed
        val sx = pullX
        val sy = pullY
        armed = false
        if (fire) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
        rubberAnim?.cancel()
        rubberAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 240
            interpolator = OvershootInterpolator(2.5f)
            addUpdateListener {
                val t = it.animatedValue as Float
                pullX = sx * (1f - t)
                pullY = sy * (1f - t)
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: android.animation.Animator) { cancelled = true }
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (cancelled) return
                    finishRubber() // stretch gone and window back to normal size first...
                    if (fire) onTap() // ...then capture, so no stretch ends up in the screenshot
                }
            })
            start()
        }
    }

    // ---- Touch --------------------------------------------------------------

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                finishRubber()
                h.removeCallbacks(idle)
                anim?.cancel()
                downRawX = e.rawX
                downRawY = e.rawY
                startX = lp.x
                startY = lp.y
                dragging = false
                longFired = false
                mode = MODE_FREE
                h.postDelayed(longPress, 500)
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downRawX
                val dy = e.rawY - downRawY
                if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                    dragging = true
                    h.removeCallbacks(longPress)
                    if (isDocked() && !closeMode) {
                        val left = lp.x + lp.width / 2 < screenW() / 2
                        val inward = if (left) dx else -dx
                        mode = if (inward > abs(dy)) MODE_RUBBER else MODE_SLIDE
                        if (tucked) untuck()
                        startX = lp.x
                        startY = lp.y
                        downRawX = e.rawX
                        downRawY = e.rawY
                        if (mode == MODE_RUBBER) startRubber()
                        return true
                    }
                }
                if (dragging) {
                    when (mode) {
                        MODE_RUBBER -> updateRubber(dx, dy)
                        MODE_SLIDE -> {
                            lp.y = startY + dy.toInt()
                            clamp()
                            try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                        }
                        else -> {
                            lp.x = startX + dx.toInt()
                            lp.y = startY + dy.toInt()
                            clamp()
                            try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
                        }
                    }
                }
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(longPress)
                if (dragging) {
                    dragging = false
                    if (mode == MODE_RUBBER) {
                        releaseRubber()
                    } else {
                        Prefs.savePos(context, if (edgeMode) edgeX() else lp.x, lp.y)
                        scheduleIdle()
                    }
                } else if (!longFired) {
                    if (closeMode) {
                        h.removeCallbacks(revert)
                        onClose()
                    } else {
                        if (tucked) untuck()
                        onTap()
                        scheduleIdle()
                    }
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                h.removeCallbacks(longPress)
                dragging = false
                if (mode == MODE_RUBBER) releaseRubber() else scheduleIdle()
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
        finishRubber()
        tucked = false
        alpha = baseAlpha
        clamp() // fold/unfold or rotate: keep the bubble on screen
        try { wm.updateViewLayout(this, lp) } catch (_: Exception) {}
        scheduleIdle()
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        rubberAnim?.cancel()
        h.removeCallbacksAndMessages(null)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val MODE_FREE = 0
        const val MODE_SLIDE = 1
        const val MODE_RUBBER = 2
    }
}
