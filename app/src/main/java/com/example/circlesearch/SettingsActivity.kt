package com.example.circlesearch

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread

class SettingsActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var notes: TextView
    private lateinit var checkBtn: Button
    private lateinit var installBtn: Button
    private lateinit var progress: ProgressBar

    private var latest: Updater.Release? = null
    private var busy = false

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
        status = label("Tap “Check for updates” to look for a newer version.")
        root.addView(status)

        checkBtn = Button(this).apply {
            text = "Check for updates"
            isAllCaps = false
            setOnClickListener { check() }
        }
        root.addView(checkBtn)

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            visibility = View.GONE
            max = 100
        }
        root.addView(progress)

        installBtn = Button(this).apply {
            text = "Download and install"
            isAllCaps = false
            visibility = View.GONE
            setOnClickListener { downloadAndInstall() }
        }
        root.addView(installBtn)

        notes = label("", 13f)
        root.addView(notes)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    private fun ui(block: () -> Unit) = runOnUiThread(block)

    private fun check() {
        if (busy) return
        busy = true
        checkBtn.isEnabled = false
        installBtn.visibility = View.GONE
        notes.text = ""
        status.text = "Checking GitHub…"
        thread {
            try {
                val r = Updater.fetchLatest()
                ui {
                    latest = r
                    if (Updater.isNewer(this, r)) {
                        val mb = if (r.apkSize > 0) " (%.1f MB)".format(r.apkSize / 1_048_576.0) else ""
                        status.text = "Update available: ${r.tag}$mb\nYou have ${Updater.currentName(this)}."
                        installBtn.visibility = View.VISIBLE
                        notes.text = r.notes.trim().take(600)
                    } else {
                        status.text = "You're up to date (${Updater.currentName(this)})."
                    }
                }
            } catch (e: Exception) {
                ui { status.text = "Could not check: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                ui {
                    busy = false
                    checkBtn.isEnabled = true
                }
            }
        }
    }

    private fun downloadAndInstall() {
        val r = latest ?: return
        if (busy) return
        if (!packageManager.canRequestPackageInstalls()) {
            Toast.makeText(
                this,
                "Allow “Install unknown apps” for Circle to Search, then come back and tap again.",
                Toast.LENGTH_LONG
            ).show()
            startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName"))
            )
            return
        }
        busy = true
        checkBtn.isEnabled = false
        installBtn.isEnabled = false
        progress.progress = 0
        progress.visibility = View.VISIBLE
        status.text = "Downloading ${r.tag}…"
        thread {
            try {
                val file = Updater.download(this, r) { done, total ->
                    if (total > 0) ui { progress.progress = (done * 100 / total).toInt() }
                }
                ui { status.text = "Downloaded. Android will ask you to confirm the update." }
                Updater.install(this, file)
            } catch (e: Exception) {
                ui { status.text = "Update failed: ${e.message ?: e.javaClass.simpleName}" }
            } finally {
                ui {
                    busy = false
                    checkBtn.isEnabled = true
                    installBtn.isEnabled = true
                    progress.visibility = View.GONE
                }
            }
        }
    }
}
