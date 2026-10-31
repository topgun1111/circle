package com.example.circlesearch

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.KeyEvent
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
        /** Called when the bubble is closed from its own X, so the setup screen can untick it. */
        @Volatile
        var onFloatingClosed: (() -> Unit)? = null

        @Volatile
        var onKeyLearned: ((keyCode: Int, scanCode: Int) -> Unit)? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var overlay: OverlayView? = null
    private var lastTrigger = 0L

    override fun onServiceConnected() {
        instance = this
        applyFloating()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        hideFloating()
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

    // ---- Capture ----------------------------------------------------------

    fun trigger(delayMs: Long = 150) {
        val now = SystemClock.uptimeMillis()
        if (now - lastTrigger < 700) return
        lastTrigger = now

        if (overlay != null) { // pressing again closes it
            removeOverlay()
            return
        }
        floating?.visibility = View.INVISIBLE // hide before the screenshot is taken
        main.postDelayed({ capture() }, delayMs)
    }

    private fun capture() {
        floating?.visibility = View.INVISIBLE // keep it out of the screenshot
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                // Copy the (large) bitmap off the main thread.
                io.execute {
                    val buffer = result.hardwareBuffer
                    val bitmap = try {
                        Bitmap.wrapHardwareBuffer(buffer, result.colorSpace)
                            ?.copy(Bitmap.Config.ARGB_8888, false)
                    } catch (_: Exception) {
                        null
                    } finally {
                        buffer.close()
                    }
                    main.post {
                        if (bitmap != null) showOverlay(bitmap) else { toast("Screenshot came back empty"); restoreFloating() }
                    }
                }
            }

            override fun onFailure(errorCode: Int) {
                toast("Could not capture the screen (error $errorCode). Protected apps block screenshots.")
                restoreFloating()
            }
        })
    }

    // ---- Overlay ----------------------------------------------------------

    private fun showOverlay(shot: Bitmap) {
        removeOverlay()
        floating?.visibility = View.INVISIBLE
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
        restoreFloating()
    }

    // ---- Floating button --------------------------------------------------

    private var floating: FloatingButton? = null

    fun applyFloating() {
        if (Prefs.floating(this)) showFloating() else hideFloating()
    }

    private fun showFloating() {
        if (floating != null) return
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val d = resources.displayMetrics.density
        val size = (48 * d).toInt()
        val b = wm.currentWindowMetrics.bounds
        val lp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = if (Prefs.floatX(this@CircleService) >= 0) Prefs.floatX(this@CircleService) else b.width() - size - (8 * d).toInt()
            y = if (Prefs.floatY(this@CircleService) >= 0) Prefs.floatY(this@CircleService) else b.height() / 2
        }
        val v = FloatingButton(
            context = this,
            wm = wm,
            lp = lp,
            onTap = { trigger(150) },
            onClose = {
                Prefs.setFloating(this, false)
                hideFloating()
                onFloatingClosed?.invoke()
            }
        )
        try {
            wm.addView(v, lp)
            floating = v
        } catch (e: Exception) {
            toast("Could not show the floating button: ${e.message}")
        }
    }

    private fun hideFloating() {
        val v = floating ?: return
        floating = null
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
    }

    private fun restoreFloating() {
        floating?.visibility = View.VISIBLE
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
