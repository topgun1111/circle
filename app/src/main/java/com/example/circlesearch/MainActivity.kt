package com.example.circlesearch

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var keyInfo: TextView
    private lateinit var floatBox: CheckBox
    private var syncing = false
    private lateinit var hiddenInfo: TextView
    private lateinit var rootInfo: TextView
    private val h = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val pad = (20 * d).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad * 2, pad, pad)
        }

        fun label(s: String, size: Float = 15f, bold: Boolean = false) = TextView(this).apply {
            text = s
            textSize = size
            if (bold) setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (10 * d).toInt(), 0, (4 * d).toInt())
        }

        fun button(s: String, onClick: () -> Unit) = Button(this).apply {
            text = s
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(label("Circle to Search", 26f, true))
        root.addView(button("Settings and updates (v${Updater.currentName(this)})") {
            startActivity(Intent(this, SettingsActivity::class.java))
        })

        // Step 1
        root.addView(label("1. Turn on the service", 17f, true))
        status = label("")
        root.addView(status)
        root.addView(button("Open accessibility settings") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(
            label(
                "Find “Circle to Search” under Downloaded / Installed apps and switch it on. " +
                    "If the switch is greyed out (sideloaded app): Settings → Apps → Circle to Search → ⋮ → Allow restricted settings, then try again.",
                13f
            )
        )

        // Keep alive (HyperOS / MIUI kills background apps; Android then marks the service "crashed")
        root.addView(label("Keep it running (HyperOS)", 17f, true))
        root.addView(
            label(
                "HyperOS kills background apps. When that happens Android shows the service as " +
                    "“not working” and never restarts it by itself. Do these once:",
                13f
            )
        )
        root.addView(button("1) Autostart: switch ON for this app") { KeepAlive.openAutostart(this) })
        root.addView(button("2) Battery saver: choose “No restrictions”") { KeepAlive.openBatterySaver(this) })
        root.addView(label("3) Open the recents screen, long-press this app's card and tap the lock icon.", 13f))
        root.addView(CheckBox(this).apply {
            text = "Show a small “running” notification (recommended)"
            isChecked = Prefs.keepAliveNotif(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setKeepAliveNotif(this@MainActivity, on)
                if (on && this@MainActivity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED
                ) {
                    this@MainActivity.requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                }
                CircleService.instance?.refreshKeepAlive()
            }
        })
        root.addView(
            label(
                "Rooted? The watchdog restarts the service within seconds whenever it is killed, " +
                    "and again after every reboot (needs Magisk, KernelSU or APatch).",
                13f
            )
        )
        rootInfo = label("", 12f)
        rootInfo.setTextIsSelectable(true)
        root.addView(rootInfo)
        root.addView(button("Install root watchdog") {
            runRoot("Installing…") { KeepAlive.installWatchdog(this) }
        })
        root.addView(button("Restart service now (root)") {
            runRoot("Restarting…") { KeepAlive.reviveNow(this) }
        })
        root.addView(button("Watchdog status / log") {
            runRoot("Reading…") { KeepAlive.Result(true, KeepAlive.watchdogLog()) }
        })
        root.addView(button("Remove root watchdog") {
            runRoot("Removing…") { KeepAlive.removeWatchdog() }
        })

        // Step 2
        root.addView(label("2. Connect your side button", 17f, true))
        keyInfo = label("")
        root.addView(keyInfo)
        root.addView(button("Learn side button") {
            if (!serviceOn()) {
                toast("Turn on the service first")
            } else {
                Prefs.startLearning(this)
                refresh()
                h.postDelayed({ refresh() }, 20_500)
            }
        })
        root.addView(button("Forget saved button") {
            Prefs.clearKey(this)
            refresh()
        })
        root.addView(
            label(
                "Tap “Learn”, then press the button within 20 seconds. If nothing is captured, the button is not " +
                    "sending a normal key event. In that case set the button's action in your phone's settings to open an app or shortcut and " +
                    "choose “Circle to Search” (the invisible launcher entry), or use the Quick Settings tile.",
                13f
            )
        )

        // Step 3
        root.addView(label("3. Search with", 17f, true))
        val group = RadioGroup(this)
        val rbGoogle = RadioButton(this).apply {
            text = "Google Lens (falls back to the share sheet)"
            id = View.generateViewId()
        }
        val rbShare = RadioButton(this).apply {
            text = "Share sheet (pick any app)"
            id = View.generateViewId()
        }
        group.addView(rbGoogle)
        group.addView(rbShare)
        group.check(if (Prefs.useShareSheet(this)) rbShare.id else rbGoogle.id)
        group.setOnCheckedChangeListener { _, id -> Prefs.setUseShareSheet(this, id == rbShare.id) }
        root.addView(group)

        root.addView(label("Selection style", 15f, true))
        val shapeGroup = RadioGroup(this)
        val rbCircle = RadioButton(this).apply { text = "Freehand circle"; id = View.generateViewId() }
        val rbRect = RadioButton(this).apply { text = "Rectangle (drag a box)"; id = View.generateViewId() }
        shapeGroup.addView(rbCircle)
        shapeGroup.addView(rbRect)
        shapeGroup.check(if (Prefs.rectMode(this)) rbRect.id else rbCircle.id)
        shapeGroup.setOnCheckedChangeListener { _, id -> Prefs.setRectMode(this, id == rbRect.id) }
        root.addView(shapeGroup)
        root.addView(label("You can also switch while selecting: tap the shape icon at the top left of the screenshot.", 13f))

        // Power button / assistant
        root.addView(label("Power button long-press (assistant)", 17f, true))
        root.addView(button("Set as default digital assistant") {
            try { startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) }
            catch (_: Exception) { startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)) }
        })
        root.addView(
            label(
                "Pick “Circle to Search” as Digital assistant app. Then, in system settings, set “Press and hold power button” " +
                    "to Digital assistant (on some phones: Settings > Gestures / Button shortcuts). The assist gesture also works.",
                13f
            )
        )

        // Step 4
        root.addView(label("4. Quick Settings button", 17f, true))
        root.addView(button("Add button to Quick Settings") { addTile() })
        root.addView(
            label(
                "Shows a system prompt to add “Circle to Search” to your Quick Settings panel. " +
                    "If it does not appear, swipe down, tap the pencil icon and drag the tile in.",
                13f
            )
        )

        // Double tap on the back
        root.addView(label("Double tap on back of phone", 17f, true))
        root.addView(CheckBox(this).apply {
            text = "Trigger Circle to Search by double-tapping the back"
            isChecked = Prefs.backTap(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setBackTap(this@MainActivity, on)
                CircleService.instance?.applyBackTap()
            }
        })
        val tapLabel = label("Sensitivity: ${Prefs.backTapLevel(this)} / 20")
        root.addView(tapLabel)
        root.addView(SeekBar(this).apply {
            max = 19 // 1..20
            progress = Prefs.backTapLevel(this@MainActivity) - 1
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    tapLabel.text = "Sensitivity: ${p + 1} / 20"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setBackTapLevel(this@MainActivity, (sb?.progress ?: 14) + 1)
                    CircleService.instance?.applyBackTap()
                }
            })
        })
        val gapLabel = label("Tap interval: ${Prefs.backTapGap(this)} ms (max pause between the two taps)")
        root.addView(gapLabel)
        root.addView(SeekBar(this).apply {
            max = 16 // 200..1000 ms in 50 ms steps
            progress = ((Prefs.backTapGap(this@MainActivity) - 200) / 50).coerceIn(0, 16)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    gapLabel.text = "Tap interval: ${200 + p * 50} ms (max pause between the two taps)"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setBackTapGap(this@MainActivity, 200 + (sb?.progress ?: 5) * 50)
                    CircleService.instance?.applyBackTap()
                }
            })
        })
        root.addView(label("Tap twice, quickly, near the middle of the back. Works with the screen on; raise sensitivity if it misses, lower it if it fires by accident. Keep the keep-alive notification on.", 13f))

        // Floating button
        root.addView(label("5. Floating button", 17f, true))
        floatBox = CheckBox(this).apply {
            text = "Show a floating button on screen"
            isChecked = Prefs.floating(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                if (syncing) return@setOnCheckedChangeListener
                if (on && CircleService.instance == null) {
                    toast("Turn on the service first")
                    isChecked = false
                } else {
                    Prefs.setFloating(this@MainActivity, on)
                    CircleService.instance?.applyFloating()
                }
            }
        }
        root.addView(floatBox)

        fun applyFloat() = CircleService.instance?.refreshFloating()

        val sizeLabel = label("Size: ${Prefs.floatSize(this)} dp")
        root.addView(sizeLabel)
        root.addView(SeekBar(this).apply {
            max = 36 // 36..72 dp
            progress = Prefs.floatSize(this@MainActivity) - 36
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    sizeLabel.text = "Size: ${p + 36} dp"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setFloatSize(this@MainActivity, (sb?.progress ?: 12) + 36)
                    applyFloat()
                }
            })
        })

        val opacityLabel = label("Opacity: ${Prefs.floatOpacity(this)}%")
        root.addView(opacityLabel)
        root.addView(SeekBar(this).apply {
            max = 70 // 30..100 %
            progress = Prefs.floatOpacity(this@MainActivity) - 30
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    opacityLabel.text = "Opacity: ${p + 30}%"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setFloatOpacity(this@MainActivity, (sb?.progress ?: 70) + 30)
                    applyFloat()
                }
            })
        })

        root.addView(CheckBox(this).apply {
            text = "Chrome-style icon"
            isChecked = Prefs.floatChrome(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setFloatChrome(this@MainActivity, on)
                applyFloat()
            }
        })

        val edgeLabel = label("Move to side after: ${Prefs.floatEdgeDelay(this)} s")
        val edgeSeek = SeekBar(this).apply {
            max = 29 // 1..30 s
            progress = Prefs.floatEdgeDelay(this@MainActivity) - 1
            isEnabled = Prefs.floatEdge(this@MainActivity)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    edgeLabel.text = "Move to side after: ${p + 1} s"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setFloatEdgeDelay(this@MainActivity, (sb?.progress ?: 2) + 1)
                    applyFloat()
                }
            })
        }
        root.addView(CheckBox(this).apply {
            text = "Move to screen edge and fade when idle"
            isChecked = Prefs.floatEdge(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setFloatEdge(this@MainActivity, on)
                edgeSeek.isEnabled = on
                applyFloat()
            }
        })
        root.addView(edgeLabel)
        root.addView(edgeSeek)

        hiddenInfo = label("")
        root.addView(hiddenInfo)
        root.addView(button("Hide button in these apps…") { pickHiddenApps() })
        root.addView(
            label(
                "Tap the bubble to search, drag it anywhere. To close it: press and hold until it turns into a red X, " +
                    "then tap the X (or untick this box). Switch it back on here any time.",
                13f
            )
        )

        // Edge pull zones
        root.addView(label("Edge pull zones", 17f, true))
        root.addView(CheckBox(this).apply {
            text = "Pull from the side of the screen to capture"
            isChecked = Prefs.zones(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setZones(this@MainActivity, on)
                CircleService.instance?.applyZones()
            }
        })
        val zwLabel = label("Edge strip width: ${Prefs.zoneWidth(this)} dp")
        root.addView(zwLabel)
        root.addView(SeekBar(this).apply {
            max = 42 // 6..48 dp
            progress = Prefs.zoneWidth(this@MainActivity) - 6
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, p: Int, user: Boolean) {
                    zwLabel.text = "Edge strip width: ${p + 6} dp"
                }
                override fun onStartTrackingTouch(sb: SeekBar?) {}
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    Prefs.setZoneWidth(this@MainActivity, (sb?.progress ?: 6) + 6)
                    CircleService.instance?.applyZones()
                }
            })
        })
        root.addView(button("Set capture locations on screen…") {
            startActivity(Intent(this, ZoneEditorActivity::class.java))
        })
        root.addView(
            label(
                "Press inside a zone (by default the top 30% of each side) and pull towards the middle. " +
                    "When it turns green, let go to capture. A zone is a thin strip, so it takes over touches " +
                    "there, and on gesture navigation the system back swipe can win at the very edge.",
                13f
            )
        )

        // Test
        root.addView(label("Try it", 17f, true))
        root.addView(button("Test now (starts in 1 second)") {
            val s = CircleService.instance
            if (s == null) {
                toast("Turn on the service first")
            } else {
                moveTaskToBack(true)
                h.postDelayed({ s.trigger(300) }, 700)
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        CircleService.onKeyLearned = { code, scan ->
            runOnUiThread {
                refresh()
                toast("Saved ${KeyEvent.keyCodeToString(code)} (scan $scan)")
            }
        }
        CircleService.onFloatingClosed = { runOnUiThread { refresh() } }
        CircleService.instance?.refreshKeepAlive()
        refresh()
    }

    override fun onPause() {
        CircleService.onKeyLearned = null
        CircleService.onFloatingClosed = null
        super.onPause()
    }

    private fun refresh() {
        syncing = true
        floatBox.isChecked = Prefs.floating(this)
        syncing = false
        val n = Prefs.hiddenApps(this).size
        hiddenInfo.text = if (n == 0) "Button shows in every app" else "Button is hidden in $n app(s)"
        status.text = when {
            !serviceOn() -> "✗ Service is off"
            CircleService.instance == null ->
                "⚠ Service is enabled but not running (killed by the system). " +
                    "Use “Restart service now (root)”, or switch it off and on in accessibility settings."
            else -> "✓ Service is on"
        }
        keyInfo.text = when {
            Prefs.isLearning(this) -> "Press your side button now…"
            Prefs.hasKey(this) ->
                "Saved: ${KeyEvent.keyCodeToString(Prefs.keyCode(this))} " +
                    "(key ${Prefs.keyCode(this)}, scan ${Prefs.scanCode(this)})"
            else -> "No button saved yet"
        }
    }

    private fun serviceOn(): Boolean = KeepAlive.enabledInSettings(this)

    /** Runs a root action off the main thread and shows its result under the buttons. */
    private fun runRoot(busy: String, action: () -> KeepAlive.Result) {
        rootInfo.text = busy
        Thread {
            val r = try { action() } catch (e: Exception) { KeepAlive.Result(false, e.toString()) }
            runOnUiThread {
                rootInfo.text = (if (r.ok) "✓ " else "✗ ") + r.output
                refresh()
            }
        }.start()
    }

    private fun pickHiddenApps() {
        val pm = packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .filter { it.first != packageName }
            .distinctBy { it.first }
            .sortedBy { it.second.lowercase() }
        if (apps.isEmpty()) {
            toast("Could not list apps")
            return
        }
        val hidden = Prefs.hiddenApps(this).toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in hidden }
        android.app.AlertDialog.Builder(this)
            .setTitle("Hide the button in…")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, i, on ->
                if (on) hidden.add(apps[i].first) else hidden.remove(apps[i].first)
            }
            .setPositiveButton("Done") { _, _ ->
                Prefs.setHiddenApps(this, hidden)
                CircleService.instance?.reloadHiddenApps()
                refresh()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun addTile() {
        try {
            getSystemService(android.app.StatusBarManager::class.java).requestAddTileService(
                android.content.ComponentName(this, CircleTileService::class.java),
                getString(R.string.trigger_label),
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_circle),
                mainExecutor
            ) { }
        } catch (_: Exception) {
            toast("Swipe down, tap the pencil icon and drag “Circle to Search” in")
        }
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
