package com.example.circlesearch

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.TouchInteractionController
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class CircleService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CircleService? = null

        /** Set by the setup screen while it is open, to show which key was learned. */
        @Volatile
        var onKeyLearned: ((keyCode: Int, scanCode: Int) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var overlay: OverlayView? = null
    private var lastTrigger = 0L

    override fun onServiceConnected() {
        instance = this
        applyTouchGesture()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        disableTouchGesture()
        removeOverlay()
        return super.onUnbind(intent)
    }

    // ---- Button detection -------------------------------------------------

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (Prefs.isLearning(this)) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
                KeyEvent.KEYCODE_APP_SWITCH, KeyEvent.KEYCODE_MENU -> return false
            }
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Prefs.saveKey(this, event.keyCode, event.scanCode)
                Prefs.stopLearning(this)
                val cb = onKeyLearned
                main.post { cb?.invoke(event.keyCode, event.scanCode) }
            }
            return true
        }

        if (!Prefs.hasKey(this)) return false
        if (event.keyCode != Prefs.keyCode(this) || event.scanCode != Prefs.scanCode(this)) return false

        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            main.post { trigger() }
        }
        return true // swallow both DOWN and UP so the button's default action does not also fire
    }

    // ---- Four-finger touch gesture ------------------------------------------
    //
    // Needs touch-exploration mode so the system hands us the raw touch stream.
    // Every new touch waits ~90 ms to see whether four fingers land; if not, the
    // touch is passed through to the app untouched (requestDelegating). Touches
    // that end inside that window are replayed with dispatchGesture.

    private var touchController: TouchInteractionController? = null
    private var fourFired = false
    private var touchDecided = false
    private var maxPointers = 0
    private var downUptime = 0L
    private val touchPath = Path()
    private val decideRunnable = Runnable { delegateTouch() }

    private val touchCallback = object : TouchInteractionController.Callback {
        override fun onMotionEvent(event: MotionEvent) {
            try {
                handleTouch(event)
            } catch (e: Exception) {
                failTouch(e)
            }
        }

        override fun onStateChanged(state: Int) {}
    }

    fun applyTouchGesture() {
        disableTouchGesture()
        if (!Prefs.fourFinger(this)) return
        val info = serviceInfo ?: return
        info.flags = info.flags or AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE
        serviceInfo = info
        // Give the system a moment to apply the new flag before we register.
        main.postDelayed({ registerTouch() }, 400)
    }

    private fun registerTouch() {
        if (!Prefs.fourFinger(this)) return
        try {
            val c = getTouchInteractionController(Display.DEFAULT_DISPLAY)
            c.registerCallback(mainExecutor, touchCallback)
            touchController = c
        } catch (e: Exception) {
            failTouch(e)
        }
    }

    private fun disableTouchGesture() {
        main.removeCallbacks(decideRunnable)
        touchController?.let {
            try {
                it.unregisterCallback(touchCallback)
            } catch (_: Exception) {
            }
        }
        touchController = null
        val info = serviceInfo ?: return
        if (info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE != 0) {
            info.flags = info.flags and AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE.inv()
            serviceInfo = info
        }
    }

    private fun failTouch(e: Exception) {
        toast("Four-finger gesture switched off: ${e.message}")
        Prefs.setFourFinger(this, false)
        disableTouchGesture()
    }

    private fun handleTouch(e: MotionEvent) {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                fourFired = false
                touchDecided = false
                maxPointers = 1
                downUptime = SystemClock.uptimeMillis()
                touchPath.reset()
                touchPath.moveTo(e.x, e.y)
                // While our own overlay is up, never delay touches.
                if (overlay != null) delegateTouch() else main.postDelayed(decideRunnable, 90)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!touchDecided && !fourFired && e.pointerCount == 1) touchPath.lineTo(e.x, e.y)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                maxPointers = maxOf(maxPointers, e.pointerCount)
                if (!touchDecided && !fourFired && e.pointerCount >= 4) {
                    fourFired = true
                    main.removeCallbacks(decideRunnable)
                    trigger(250)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                main.removeCallbacks(decideRunnable)
                if (e.actionMasked == MotionEvent.ACTION_UP && !touchDecided && !fourFired) replayGesture()
            }
        }
    }

    private fun delegateTouch() {
        touchDecided = true
        val c = touchController ?: return
        try {
            if (c.state == TouchInteractionController.STATE_TOUCH_INTERACTING) c.requestDelegating()
        } catch (e: Exception) {
            failTouch(e)
        }
    }

    /** A touch that ended before we decided (tap or quick flick): re-inject it for the app. */
    private fun replayGesture() {
        if (maxPointers != 1) return
        val duration = (SystemClock.uptimeMillis() - downUptime).coerceIn(1L, 200L)
        try {
            val stroke = GestureDescription.StrokeDescription(Path(touchPath), 0, duration)
            dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), null, null)
        } catch (_: Exception) {
        }
    }

    // ---- Capture ----------------------------------------------------------

    fun trigger(delayMs: Long = 150) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTrigger < 700) return
        lastTrigger = now

        if (overlay != null) { // pressing again closes it
            removeOverlay()
            return
        }
        main.postDelayed({ capture() }, delayMs)
    }

    private fun capture() {
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val buffer = result.hardwareBuffer
                val bitmap = try {
                    Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } finally {
                    buffer.close()
                }
                if (bitmap != null) showOverlay(bitmap) else toast("Screenshot came back empty")
            }

            override fun onFailure(errorCode: Int) {
                toast("Could not capture the screen (error $errorCode). Protected apps block screenshots.")
            }
        })
    }

    // ---- Overlay ----------------------------------------------------------

    private fun showOverlay(shot: Bitmap) {
        removeOverlay()
        val view = OverlayView(
            context = this,
            shot = shot,
            onCrop = { crop -> search(crop) },
            onCancel = { removeOverlay() }
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        wm.addView(view, lp)
        overlay = view
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        overlay = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    // ---- Search hand-off --------------------------------------------------

    private fun search(crop: Bitmap) {
        io.execute {
            val file = try {
                val dir = File(cacheDir, "shared").apply { mkdirs() }
                dir.listFiles()?.forEach {
                    if (System.currentTimeMillis() - it.lastModified() > 600_000) it.delete()
                }
                File(dir, "circle_${System.currentTimeMillis()}.jpg").also { f ->
                    f.outputStream().use { crop.compress(Bitmap.CompressFormat.JPEG, 92, it) }
                }
            } catch (_: Exception) {
                null
            }
            main.post {
                if (file == null) toast("Could not save the cropped image") else launchSearch(file)
                // Remove the overlay only after the search app was started, so we still
                // have a visible window when launching it from the background.
                removeOverlay()
            }
        }
    }

    private fun launchSearch(file: File) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!Prefs.useShareSheet(this)) {
            try {
                startActivity(Intent(send).setPackage("com.google.android.googlequicksearchbox"))
                return
            } catch (_: Exception) {
                // Google app missing or has no share target: fall back to the share sheet
            }
        }
        try {
            startActivity(
                Intent.createChooser(send, "Search with…")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        } catch (e: Exception) {
            toast("No app could open the image")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
}
