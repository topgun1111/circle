package com.example.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * Invisible touch target over the front camera cut-out. Double tap and/or press-and-hold
 * (each can be switched on separately) starts a capture.
 */
@SuppressLint("ViewConstructor")
class CameraZoneView(
    context: Context,
    private val doubleTap: Boolean,
    private val hold: Boolean,
    private val onCapture: () -> Unit
) : View(context) {

    private val h = Handler(Looper.getMainLooper())
    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val tapTimeout = ViewConfiguration.getTapTimeout().toLong().coerceAtLeast(200L)
    private val gap = ViewConfiguration.getDoubleTapTimeout().toLong()

    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var lastTapAt = 0L
    private var fired = false
    private val holdRunnable = Runnable { fire() }

    private fun fire() {
        if (fired) return
        fired = true
        lastTapAt = 0L
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        onCapture()
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; downAt = e.eventTime; fired = false
                if (hold) h.postDelayed(holdRunnable, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(e.rawX - downX, e.rawY - downY) > slop) {
                h.removeCallbacks(holdRunnable)
                lastTapAt = 0L
            }
            MotionEvent.ACTION_UP -> {
                h.removeCallbacks(holdRunnable)
                val quick = !fired && e.eventTime - downAt <= tapTimeout &&
                    hypot(e.rawX - downX, e.rawY - downY) <= slop
                if (doubleTap && quick) {
                    if (lastTapAt != 0L && e.eventTime - lastTapAt <= gap) fire() else lastTapAt = e.eventTime
                } else if (!fired) lastTapAt = 0L
            }
            MotionEvent.ACTION_CANCEL -> { h.removeCallbacks(holdRunnable); lastTapAt = 0L }
        }
        return true
    }

    override fun onDetachedFromWindow() {
        h.removeCallbacks(holdRunnable)
        super.onDetachedFromWindow()
    }
}
