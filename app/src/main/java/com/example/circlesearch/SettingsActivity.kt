package com.example.circlesearch

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

class SettingsActivity : Activity() {

    private lateinit var crashInfo: TextView
    private lateinit var exitInfo: TextView


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

        root.addView(label("Settings", 26f, true))

        root.addView(label("About", 17f, true))
        root.addView(label("Version ${Updater.currentName(this)} (build ${Updater.currentBuild(this)})"))
        root.addView(label("Updates come from github.com/${Updater.OWNER}/${Updater.REPO}", 13f))

        root.addView(label("Updates", 17f, true))
        root.addView(
            label(
                "Opens GitHub in your browser. Compare the number at the end of the version " +
                    "(you have build ${Updater.currentBuild(this)}); a higher number is newer.",
                13f
            )
        )
        root.addView(Button(this).apply {
            text = "Check for updates"
            isAllCaps = false
            setOnClickListener { open(Updater.RELEASES_PAGE) }
        })
        root.addView(Button(this).apply {
            text = "Download latest APK"
            isAllCaps = false
            setOnClickListener { open(Updater.LATEST_APK) }
        })
        root.addView(
            label(
                "After the download finishes, tap it in your browser or Files app to install over the current version.",
                13f
            )
        )

        root.addView(label("Diagnostics", 17f, true))
        root.addView(label("Why the app process ended (newest first). SIGNALED / OTHER while in the background means HyperOS killed it; CRASH means a real bug.", 13f))
        exitInfo = label("", 12f)
        exitInfo.setTextIsSelectable(true)
        root.addView(exitInfo)
        crashInfo = label("", 12f)
        crashInfo.setTextIsSelectable(true)
        root.addView(crashInfo)
        root.addView(Button(this).apply {
            text = "Copy last error"
            isAllCaps = false
            setOnClickListener {
                val t = Diag.read(this@SettingsActivity)
                if (t == null) {
                    Toast.makeText(this@SettingsActivity, "Nothing recorded", Toast.LENGTH_SHORT).show()
                } else {
                    val cm = getSystemService(android.content.ClipboardManager::class.java)
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Circle error", t))
                    Toast.makeText(this@SettingsActivity, "Copied", Toast.LENGTH_SHORT).show()
                }
            }
        })
        root.addView(Button(this).apply {
            text = "Clear"
            isAllCaps = false
            setOnClickListener {
                Diag.clear(this@SettingsActivity)
                showCrash()
            }
        })

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        showCrash()
    }

    private fun showCrash() {
        exitInfo.text = KeepAlive.readExitReasons(this) ?: "No process exits recorded yet."
        val t = Diag.read(this)
        crashInfo.text = if (t == null) "No error recorded." else t.take(1500)
    }

    private fun open(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, "No browser found", Toast.LENGTH_SHORT).show()
        }
    }
}
