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
        val ui = Ui(this)
        setContentView(with(ui) {
            page {
                add(pageTitle("Settings"), 4)

                card {
                    add(heading("About"), 0)
                    add(text("Version ${Updater.currentName(this@SettingsActivity)} (build ${Updater.currentBuild(this@SettingsActivity)})"), 6)
                    add(hint("Updates come from github.com/${Updater.OWNER}/${Updater.REPO}"), 2)
                }

                card {
                    add(heading("Updates"), 0)
                    add(hint("Opens GitHub in your browser. A higher build number than " +
                        "${Updater.currentBuild(this@SettingsActivity)} is newer. After the download, tap the APK to install over the current version."), 4)
                    buttons(button("Check for updates", primary = true) { open(Updater.RELEASES_PAGE) },
                        button("Download APK") { open(Updater.LATEST_APK) })
                }

                card {
                    add(heading("Diagnostics"), 0)
                    add(hint("Why the app process ended (newest first). SIGNALED / OTHER in the background means HyperOS killed it; CRASH means a real bug."), 4)
                    exitInfo = mono()
                    add(exitInfo, 10)
                    crashInfo = mono()
                    add(crashInfo, 8)
                    buttons(button("Copy last error") {
                        val t = Diag.read(this@SettingsActivity)
                        if (t == null) {
                            Toast.makeText(this@SettingsActivity, "Nothing recorded", Toast.LENGTH_SHORT).show()
                        } else {
                            val cm = getSystemService(android.content.ClipboardManager::class.java)
                            cm.setPrimaryClip(android.content.ClipData.newPlainText("Circle error", t))
                            Toast.makeText(this@SettingsActivity, "Copied", Toast.LENGTH_SHORT).show()
                        }
                    }, button("Clear") {
                        Diag.clear(this@SettingsActivity)
                        showCrash()
                    })
                }
            }
        })
        ui.style(this)
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
