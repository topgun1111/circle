package com.example.circlesearch

import android.annotation.SuppressLint
import android.content.Context
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs

/**
 * Small invisible square in one top screen corner. A swipe that starts in it and travels down
 * (or sideways towards the screen centre) starts a capture as soon as it has moved far enough.
 */
@SuppressLint("ViewConstructor")
class CornerZoneView(
    context: Context,
    private val left: Boolean,
    private val onCapture: () -> Unit
) : View(context) {

    private val trigger = 40 * resources.displayMetrics.density

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
                if ((dy >= trigger && dy > abs(dx)) || (inward >= trigger && inward > abs(dy))) {
                    fired = true
                    performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    onCapture()
                }
            }
        }
        return true
    }
}
