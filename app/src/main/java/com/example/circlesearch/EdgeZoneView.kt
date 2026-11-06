package com.example.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Thin invisible strip along one screen edge. A quick swipe in from the edge, like the back gesture,
 * starts a capture as soon as it has moved far enough. No stretch effect, no waiting for release.
 */
@SuppressLint("ViewConstructor")
class EdgeZoneView(
    context: Context,
    private val left: Boolean,
    private val onCapture: () -> Unit
) : View(context) {

    private val d = resources.displayMetrics.density
    private val trigger = 20 * d // inward travel that fires the capture

    private var downX = 0f
    private var downY = 0f
    private var fired = false

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX
                downY = e.rawY
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = e.rawX - downX
                val dy = e.rawY - downY
                val inward = if (left) dx else -dx
                if (inward >= trigger && inward > abs(dy)) {
                    fired = true
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onCapture()
                }
            }
        }
        return true
    }
}
