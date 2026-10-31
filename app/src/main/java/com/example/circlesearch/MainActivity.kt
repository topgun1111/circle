package com.example.circlesearch

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast

class MainActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var status: TextView
    private lateinit var keyInfo: TextView
    private lateinit var floatBox: Switch
    private lateinit var floatGroup: View
    private lateinit var tapGroup: View
    private lateinit var tapInfo: TextView
    private var syncing = false
    private lateinit var hiddenInfo: TextView
    private lateinit var rootInfo: TextView
    private val h = Handler(Looper.getMainLooper())

    private val poll = object : Runnable {
        override fun run() {
            tapInfo.text = CircleService.instance?.backTapStats() ?: "Turn on the service first"
            h.postDelayed(this, 300)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        try {
            setContentView(buildUi())
            built = true
            ui.style(this)
        } catch (e: Throwable) {
            // Never close instantly: show what went wrong so it can be fixed.
            Diag.save(this, "MainActivity build failed", e)
            setContentView(android.widget.ScrollView(this).apply {
                addView(TextView(this@MainActivity).apply {
                    text = "UI failed to build:\n\n" + android.util.Log.getStackTraceString(e)
                    setTextIsSelectable(true); textSize = 11f; setPadding(32, 64, 32, 32)
                })
            })
        }
    }

    private var built = false

    private fun buildUi(): View {
        return with(ui) {
            page {
                add(pageTitle("Circle to Search"), 4)
                add(hint("Circle anything on screen and search it."), 2)

                // ---- Service status ----
                card {
                    status = text("")
                    add(status, 0)
                    buttons(button("Accessibility settings", primary = true) {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }, button("Settings & updates") {
                        startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    })
                    add(hint("Find “Circle to Search” under Installed apps and switch it on. If it is greyed out: " +
                        "Settings → Apps → Circle to Search → ⋮ → Allow restricted settings."))
                }

                // ---- Triggers ----
                add(heading("Triggers"), 20)

                card {
                    add(heading("Side button"), 0)
                    keyInfo = text("")
                    add(keyInfo, 4)
                    buttons(button("Learn button") {
                        if (!serviceOn()) toast("Turn on the service first") else {
                            Prefs.startLearning(this@MainActivity)
                            refresh()
                            h.postDelayed({ refresh() }, 20_500)
                        }
                    }, button("Forget") { Prefs.clearKey(this@MainActivity); refresh() })
                    add(hint("Tap Learn, then press the button within 20 seconds. If nothing is captured, the button " +
                        "sends no key event: set its action to open the “Circle to Search” shortcut, or use the tile."))
                }

                card {
                    val (row, _) = switchRow("Double tap on back", "Tap twice on the back of the phone",
                        Prefs.backTap(this@MainActivity)) { _, on ->
                        Prefs.setBackTap(this@MainActivity, on)
                        CircleService.instance?.applyBackTap()
                        tapGroup.visibility = if (on) View.VISIBLE else View.GONE
                    }
                    addView(row)
                    tapGroup = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = if (Prefs.backTap(this@MainActivity)) View.VISIBLE else View.GONE
                        add(slider("Sensitivity", " / 10", 1, 10, Prefs.backTapLevel(this@MainActivity)) {
                            Prefs.setBackTapLevel(this@MainActivity, it)
                            CircleService.instance?.applyBackTap()
                        }, 12)
                        tapInfo = mono()
                        add(tapInfo, 8)
                        add(hint("Tap near the middle of the back with the screen on. If “last jolt” stays below " +
                            "“needs”, raise sensitivity. Keep the running notification on."), 8)
                    }
                    add(tapGroup, 0)
                }

                card {
                    val (row, sw) = switchRow("Floating button", "A draggable bubble on screen",
                        Prefs.floating(this@MainActivity)) { s, on ->
                        if (syncing) return@switchRow
                        if (on && CircleService.instance == null) {
                            toast("Turn on the service first"); s.isChecked = false
                        } else {
                            Prefs.setFloating(this@MainActivity, on)
                            CircleService.instance?.applyFloating()
                            floatGroup.visibility = if (on) View.VISIBLE else View.GONE
                        }
                    }
                    floatBox = sw
                    addView(row)
                    floatGroup = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        visibility = if (Prefs.floating(this@MainActivity)) View.VISIBLE else View.GONE
                        fun applyFloat() = CircleService.instance?.refreshFloating()
                        add(slider("Size", " dp", 36, 72, Prefs.floatSize(this@MainActivity)) {
                            Prefs.setFloatSize(this@MainActivity, it); applyFloat()
                        }, 12)
                        add(slider("Opacity", "%", 30, 100, Prefs.floatOpacity(this@MainActivity)) {
                            Prefs.setFloatOpacity(this@MainActivity, it); applyFloat()
                        }, 8)
                        add(switchRow("Snap to edge", "Fades when idle", Prefs.floatEdge(this@MainActivity)) { _, on ->
                            Prefs.setFloatEdge(this@MainActivity, on); applyFloat()
                        }.first, 12)
                        hiddenInfo = hint("")
                        add(hiddenInfo, 12)
                        buttons(button("Hide in apps…") { pickHiddenApps() })
                        add(hint("Drag to move. Press and hold until it turns into a red X, then tap the X to close it."), 8)
                    }
                    add(floatGroup, 0)
                }

                card {
                    add(heading("Quick Settings tile"), 0)
                    add(hint("Adds a “Circle to Search” tile to the swipe-down panel."), 2)
                    buttons(button("Add tile") { addTile() })
                }

                // ---- Behaviour ----
                add(heading("Search"), 20)
                card {
                    add(text("Search with", 13.5f), 0)
                    val group = RadioGroup(this@MainActivity)
                    val rbGoogle = RadioButton(this@MainActivity).apply {
                        text = "Google Lens (falls back to share sheet)"; id = View.generateViewId()
                    }
                    val rbShare = RadioButton(this@MainActivity).apply {
                        text = "Share sheet (pick any app)"; id = View.generateViewId()
                    }
                    group.addView(rbGoogle); group.addView(rbShare)
                    group.check(if (Prefs.useShareSheet(this@MainActivity)) rbShare.id else rbGoogle.id)
                    group.setOnCheckedChangeListener { _, id -> Prefs.setUseShareSheet(this@MainActivity, id == rbShare.id) }
                    add(group, 4)
                    divide()
                    add(text("Selection style", 13.5f), 12)
                    val shapeGroup = RadioGroup(this@MainActivity)
                    val rbCircle = RadioButton(this@MainActivity).apply { text = "Freehand circle"; id = View.generateViewId() }
                    val rbRect = RadioButton(this@MainActivity).apply { text = "Rectangle (drag a box)"; id = View.generateViewId() }
                    shapeGroup.addView(rbCircle); shapeGroup.addView(rbRect)
                    shapeGroup.check(if (Prefs.rectMode(this@MainActivity)) rbRect.id else rbCircle.id)
                    shapeGroup.setOnCheckedChangeListener { _, id -> Prefs.setRectMode(this@MainActivity, id == rbRect.id) }
                    add(shapeGroup, 4)
                    add(hint("You can also switch while selecting with the shape icon at the top left."), 4)
                }

                card {
                    buttons(button("Test now (1 second delay)", primary = true) {
                        val s = CircleService.instance
                        if (s == null) toast("Turn on the service first") else {
                            moveTaskToBack(true)
                            h.postDelayed({ s.trigger(300) }, 700)
                        }
                    }, top = 0)
                }

                // ---- Advanced ----
                add(heading("Advanced"), 20)
                expandable("Keep it running", "Vivo / Xiaomi kill background apps; do these once") {
                    add(hint("When that happens Android shows the service as “not working” and does not restart it."), 10)
                    buttons(button("Autostart ON") { KeepAlive.openAutostart(this@MainActivity) },
                        button("Battery: no limits") { KeepAlive.openBatterySaver(this@MainActivity) })
                    add(hint("Also: open recents, long-press this app's card and tap the lock icon. On vivo: i Manager → Apps & permissions → Autostart, and Battery → Background power consumption → Allow."), 10)
                    divide()
                    val (row, _) = switchRow("Running notification", "Recommended; also needed for back-tap",
                        Prefs.keepAliveNotif(this@MainActivity)) { _, on ->
                        Prefs.setKeepAliveNotif(this@MainActivity, on)
                        if (on && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        ) requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 7)
                        CircleService.instance?.refreshKeepAlive()
                    }
                    add(row, 14)
                }
                expandable("Root watchdog", "Restarts the service after it is killed or after reboot") {
                    add(hint("Needs Magisk, KernelSU or APatch."), 10)
                    rootInfo = mono()
                    add(rootInfo, 6)
                    buttons(button("Install") { runRoot("Installing…") { KeepAlive.installWatchdog(this@MainActivity) } },
                        button("Restart now") { runRoot("Restarting…") { KeepAlive.reviveNow(this@MainActivity) } })
                    buttons(button("Status / log") { runRoot("Reading…") { KeepAlive.Result(true, KeepAlive.watchdogLog()) } },
                        button("Remove") { runRoot("Removing…") { KeepAlive.removeWatchdog() } }, top = 8)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!built) return
        CircleService.onKeyLearned = { code, scan ->
            runOnUiThread {
                refresh()
                toast("Saved ${KeyEvent.keyCodeToString(code)} (scan $scan)")
            }
        }
        CircleService.onFloatingClosed = { runOnUiThread { refresh() } }
        CircleService.instance?.refreshKeepAlive()
        refresh()
        poll.run()
    }

    override fun onPause() {
        h.removeCallbacks(poll)
        CircleService.onKeyLearned = null
        CircleService.onFloatingClosed = null
        super.onPause()
    }

    private fun refresh() {
        syncing = true
        floatBox.isChecked = Prefs.floating(this)
        syncing = false
        floatGroup.visibility = if (Prefs.floating(this)) View.VISIBLE else View.GONE
        val n = Prefs.hiddenApps(this).size
        hiddenInfo.text = if (n == 0) "Shown in every app" else "Hidden in $n app(s)"
        val (msg, color) = when {
            !serviceOn() -> "✗  Service is off" to ui.bad
            CircleService.instance == null ->
                "⚠  Enabled but not running (killed by the system). Switch it off and on in accessibility settings." to ui.warn
            else -> "✓  Service is on" to ui.ok
        }
        status.text = msg
        ui.pill(status, color)
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
