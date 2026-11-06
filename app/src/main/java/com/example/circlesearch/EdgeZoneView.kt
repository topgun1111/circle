package com.example.circlesearch

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot

/**
 * Thin invisible strip along one screen edge. Press inside it and pull towards the screen centre:
 * a rubber neck stretches from the edge to your finger; past the arming distance it turns green
 * (haptic tick) and letting go starts a capture.
 */
@SuppressLint("ViewConstructor")
class EdgeZoneView(
    context: Context,
    private val wm: WindowManager,
    private val left: Boolean,
    private val onCapture: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val maxPull = 150 * d
    private val armDist = 0.45f * maxPull

    private var downX = 0f
    private var downY = 0f
    private var pulling = false
    private var armed = false
    private var pullX = 0f
    private var pullY = 0f
    private var effect: PullEffect? = null
    private var anim: ValueAnimator? = null

    private inner class PullEffect(ctx: Context) : View(ctx) {
        private val loc = IntArray(2)
        private val neck = Path()
        private val dark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6202124.toInt() }
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = 3 * d
        }

        override fun onDraw(canvas: Canvas) {
            getLocationOnScreen(loc)
            val ax = (if (this@EdgeZoneView.left) 0f else resources.displayMetrics.widthPixels.toFloat()) - loc[0]
            val ay = downY - loc[1]
            val hx = ax + pullX
            val hy = ay + pullY
            val r = 24 * d
            dark.color = if (armed) 0xF034A853.toInt() else 0xE6202124.toInt()

            val len = hypot(hx - ax, hy - ay)
            if (len > 1f) {
                val ux = (hx - ax) / len
                val uy = (hy - ay) / len
                val px = -uy
                val py = ux
                val ra = r * 0.95f
                val rh = r * 0.6f
                val rm = r * (0.5f - 0.25f * (len / maxPull).coerceAtMost(1f))
                val mx = (ax + hx) / 2f
                val my = (ay + hy) / 2f
                neck.reset()
                neck.moveTo(ax + px * ra, ay + py * ra)
                neck.quadTo(mx + px * rm, my + py * rm, hx + px * rh, hy + py * rh)
                neck.lineTo(hx - px * rh, hy - py * rh)
                neck.quadTo(mx - px * rm, my - py * rm, ax - px * ra, ay - py * ra)
                neck.close()
                canvas.drawPath(neck, dark)
            }
            canvas.drawCircle(ax, ay, r * 0.95f, dark)
            val hr = if (armed) r * 1.12f else r
            canvas.drawCircle(hx, hy, hr, dark)
            canvas.drawCircle(hx, hy, hr * 0.45f, ring)
        }
    }

    private fun startEffect() {
        removeEffect()
        val v = PullEffect(context)
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        try {
            wm.addView(v, p)
            effect = v
        } catch (_: Exception) {
        }
    }

    private fun removeEffect() {
        anim?.cancel()
        anim = null
        pulling = false
        armed = false
        pullX = 0f
        pullY = 0f
        effect?.let { try { wm.removeViewImmediate(it) } catch (_: Exception) {} }
        effect = null
    }

    private fun updatePull(dx: Float, dy: Float) {
        val m = hypot(dx, dy)
        val k = maxPull * 0.9f
        val s = if (m == 0f) 0f else k * (1f - exp(-m / k))
        pullX = if (m == 0f) 0f else dx / m * s
        pullY = if (m == 0f) 0f else dy / m * s
        val now = s >= armDist
        if (now != armed) {
            armed = now
            if (armed) performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        effect?.invalidate()
    }

    private fun release(fire: Boolean) {
        if (fire) {
            performHapticFeedback(HapticFeedbackConstants.CONFIRM)
            removeEffect() // gone before the screenshot
            onCapture()
            return
        }
        val sx = pullX
        val sy = pullY
        armed = false
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 180
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val t = it.animatedValue as Float
                pullX = sx * (1f - t)
                pullY = sy * (1f - t)
                effect?.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled) removeEffect() }
            })
            start()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                removeEffect()
                downX = e.rawX
                downY = e.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val inward = if (left) dx else -dx
                if (!pulling && inward > slop && inward > abs(dy)) {
                    pulling = true
                    startEffect()
                }
                if (pulling) updatePull(dx, dy)
            }
            MotionEvent.ACTION_UP -> if (pulling) release(armed) else removeEffect()
            MotionEvent.ACTION_CANCEL -> if (pulling) release(false) else removeEffect()
        }
        return true
    }

    override fun onDetachedFromWindow() {
        removeEffect()
        super.onDetachedFromWindow()
    }
}
