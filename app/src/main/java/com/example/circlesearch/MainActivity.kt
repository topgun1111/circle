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

        root.addView(button("Battery settings (keep it running)") {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        .setData(android.net.Uri.parse("package:$packageName"))
                )
            }
        })
        root.addView(
            label(
                "OriginOS closes background apps aggressively. Allow Circle to Search to auto-start and run in the " +
                    "background, set its battery use to unrestricted, and lock it in the recents screen. " +
                    "Otherwise the button can stop working after a while.",
                13f
            )
        )

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
                    "sending a normal key event. In that case set the button's action in vivo's settings to open an app or shortcut and " +
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
            text = "Snap to screen edge and fade when idle"
            isChecked = Prefs.floatEdge(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setFloatEdge(this@MainActivity, on)
                applyFloat()
            }
        })

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
        status.text = if (serviceOn()) "✓ Service is on" else "✗ Service is off"
        keyInfo.text = when {
            Prefs.isLearning(this) -> "Press your side button now…"
            Prefs.hasKey(this) ->
                "Saved: ${KeyEvent.keyCodeToString(Prefs.keyCode(this))} " +
                    "(key ${Prefs.keyCode(this)}, scan ${Prefs.scanCode(this)})"
            else -> "No button saved yet"
        }
    }

    private fun serviceOn(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.contains(packageName) && it.contains("CircleService") }
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
