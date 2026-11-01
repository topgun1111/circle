package com.example.circlesearch

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ClipData
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.provider.MediaStore
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
    private var suppressed = false // true while a capture / the selection overlay is showing
    private var hiddenByApp = false
    private var currentPkg: String? = null
    private var hiddenApps: Set<String> = emptySet()
    private var lastTrigger = 0L
    private var backTap: BackTapDetector? = null

    override fun onServiceConnected() {
        instance = this
        try {
            hiddenApps = Prefs.hiddenApps(this)
            applyFloating()
        } catch (e: Exception) {
            Diag.save(this, "onServiceConnected failed", e)
            toast("Circle to Search: startup error, see Settings > Diagnostics")
        }
        refreshKeepAlive()
        applyBackTap()
    }

    /** Starts/stops double-tap-on-back detection and applies sensitivity. Safe to call any time. */
    fun applyBackTap() {
        if (!Prefs.backTap(this)) { backTap?.stop(); backTap = null; removeTouchWatch(); return }
        val d = backTap ?: BackTapDetector(this) { trigger(150) }.also { backTap = it }
        d.sensitivity = Prefs.backTapLevel(this)
        d.maxGapMs = Prefs.backTapGap(this)
        d.start()
        ensureTouchWatch()
        d.screenGuard = touchWatch != null
    }

    // ---- Screen-touch guard -----------------------------------------------
    // A 1x1 invisible window that gets ACTION_OUTSIDE for every touch elsewhere on the screen. Only the time of
    // the touch is used, so back-tap jolts caused by tapping the screen can be told apart from real back taps.
    private var touchWatch: View? = null

    private fun ensureTouchWatch() {
        if (touchWatch != null) return
        try {
            val v = View(this)
            v.setOnTouchListener { _, ev ->
                val off = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()
                backTap?.noteScreenTouch((off + ev.eventTime) * 1_000_000L)
                false
            }
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            (getSystemService(WINDOW_SERVICE) as WindowManager).addView(v, lp)
            touchWatch = v
        } catch (e: Exception) {
            Diag.save(this, "touch watch failed", e)
        }
    }

    private fun removeTouchWatch() {
        backTap?.screenGuard = false
        val v = touchWatch ?: return
        touchWatch = null
        try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v) } catch (_: Exception) {}
    }

    // ---- Keep-alive notification ------------------------------------------
    // HyperOS kills background processes; an ongoing foreground-service notification makes
    // Android (and the security app) treat this process as important.

    private var inForeground = false

    /** Starts or stops the foreground notification according to the setting. Safe to call any time. */
    fun refreshKeepAlive() {
        if (Prefs.keepAliveNotif(this)) startKeepAlive() else stopKeepAlive()
    }

    private fun startKeepAlive() {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel("keepalive", "Running in background", NotificationManager.IMPORTANCE_LOW)
                    .apply { setShowBadge(false) }
            )
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
            )
            val n = Notification.Builder(this, "keepalive")
                .setSmallIcon(R.drawable.ic_circle)
                .setContentTitle("Circle to Search is ready")
                .setContentText("Keeps the service alive. Tap to open settings.")
                .setContentIntent(open)
                .setOngoing(true)
                .build()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(1, n)
            }
            inForeground = true
        } catch (e: Exception) {
            // Not allowed right now (e.g. started from the background). It is retried whenever the
            // setup screen is opened.
            Log.w("CircleService", "startForeground failed", e)
        }
    }

    private fun stopKeepAlive() {
        if (!inForeground) return
        inForeground = false
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (_: Exception) {
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            // Ignore our own windows, system UI and the keyboard, so they don't count as "the current app".
            if (pkg == packageName || pkg == "com.android.systemui" || pkg == imePackage()) return
            if (pkg == currentPkg) return
            currentPkg = pkg
            updateAppHidden()
        } catch (e: Exception) {
            Diag.save(this, "onAccessibilityEvent failed", e)
        }
    }

    private fun imePackage(): String? =
        Settings.Secure.getString(contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)?.substringBefore('/')

    private fun updateAppHidden() {
        hiddenByApp = currentPkg != null && currentPkg in hiddenApps
        syncFloating()
    }

    /** Called by the setup screen after the "hide in these apps" list changed. */
    fun reloadHiddenApps() {
        hiddenApps = Prefs.hiddenApps(this)
        updateAppHidden()
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        backTap?.stop(); backTap = null
        removeTouchWatch()
        stopKeepAlive()
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
        suppressFloating() // hide before the screenshot is taken
        main.postDelayed({ capture() }, delayMs)
    }

    private fun capture() {
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
        suppressFloating()
        val view = OverlayView(
            context = this,
            shot = shot,
            onAction = { action, crop ->
                when (action) {
                    OverlayView.ACTION_SAVE -> save(crop)
                    OverlayView.ACTION_SHARE -> search(crop, forceChooser = true)
                    else -> search(crop, forceChooser = false)
                }
            },
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
        val size = (Prefs.floatSize(this) * d).toInt()
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
            edgeMode = Prefs.floatEdge(this),
            baseAlpha = Prefs.floatOpacity(this) / 100f,
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
            syncFloating()
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

    private fun suppressFloating() {
        suppressed = true
        syncFloating()
    }

    private fun restoreFloating() {
        suppressed = false
        syncFloating()
    }

    private fun syncFloating() {
        floating?.visibility = if (suppressed || hiddenByApp) View.INVISIBLE else View.VISIBLE
    }

    /** Rebuilds the bubble after size / opacity / edge-mode settings changed. */
    fun refreshFloating() {
        hideFloating()
        applyFloating()
    }

    // ---- Search hand-off --------------------------------------------------

    private fun search(crop: Bitmap, forceChooser: Boolean) {
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
                if (file == null) toast("Could not save the cropped image") else launchSearch(file, forceChooser)
                // Remove the overlay only after the search app was started, so we still
                // have a visible window when launching it from the background.
                removeOverlay()
            }
        }
    }

    private fun save(crop: Bitmap) {
        io.execute {
            val ok = try {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Circle_${System.currentTimeMillis()}.jpg")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/CircleSearch")
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no uri")
                contentResolver.openOutputStream(uri)!!.use { crop.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                true
            } catch (_: Exception) {
                false
            }
            main.post {
                toast(if (ok) "Saved to Pictures/CircleSearch" else "Could not save the image")
                removeOverlay()
            }
        }
    }

    private fun launchSearch(file: File, forceChooser: Boolean) {
        val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (!forceChooser && !Prefs.useShareSheet(this)) {
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
