package com.example.circlesearch

import android.app.Activity
import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Everything that keeps the accessibility service alive on HyperOS / MIUI:
 *  - records why the process died last (ApplicationExitInfo),
 *  - root helpers: install / remove / run the watchdog script (assets/circle_watchdog.sh),
 *  - shortcuts to Xiaomi's autostart and battery screens.
 */
object KeepAlive {

    private const val SCRIPT_NAME = "circle_watchdog.sh"
    private const val INSTALLED = "/data/adb/service.d/circle_watchdog.sh"
    private const val PIDF = "/data/local/tmp/circle_watchdog.pid"
    private const val LOGF = "/data/local/tmp/circle_watchdog.log"

    class Result(val ok: Boolean, val output: String)

    // ---- Service state ------------------------------------------------------

    /** True when Android lists the service as enabled (it may still be dead / "crashed"). */
    fun enabledInSettings(c: Context): Boolean {
        val enabled = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        return enabled.split(':').any { it.contains(c.packageName) && it.contains("CircleService") }
    }

    // ---- Why did the process die? --------------------------------------------

    private fun exitFile(c: Context) = File(c.filesDir, "exit_reasons.txt")

    /** Called on every process start; keeps the last few exit reasons on disk for the Settings screen. */
    fun recordExitReasons(c: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        try {
            val am = c.getSystemService(ActivityManager::class.java)
            val list = am.getHistoricalProcessExitReasons(c.packageName, 0, 8)
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.US)
            val sb = StringBuilder()
            for (i in list) {
                sb.append(fmt.format(Date(i.timestamp))).append("  ")
                    .append(reasonName(i.reason))
                    .append("  status=").append(i.status)
                    .append("  was=").append(importanceName(i.importance))
                if (!i.description.isNullOrEmpty()) sb.append("  \"").append(i.description).append('"')
                sb.append('\n')
            }
            exitFile(c).writeText(sb.toString())
        } catch (_: Exception) {
        }
    }

    fun readExitReasons(c: Context): String? =
        try { exitFile(c).takeIf { it.exists() }?.readText()?.ifBlank { null } } catch (_: Exception) { null }

    private fun reasonName(r: Int) = when (r) {
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "NATIVE_CRASH"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INIT_FAILURE"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_OTHER -> "OTHER (killed by system / security app)"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED (killed)"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED (force stop)"
        15 -> "PACKAGE_STATE_CHANGE"
        16 -> "PACKAGE_UPDATED"
        else -> "UNKNOWN($r)"
    }

    private fun importanceName(i: Int) = when {
        i <= 100 -> "foreground"
        i <= 125 -> "foreground-service"
        i <= 230 -> "visible/perceptible"
        i <= 300 -> "service"
        i <= 350 -> "top-sleeping"
        i < 1000 -> "cached/background"
        else -> "gone"
    }

    // ---- Root helpers -------------------------------------------------------

    /** Runs a command through su. Call from a background thread only. */
    fun su(cmd: String, timeoutSec: Long = 25): Result {
        return try {
            val p = ProcessBuilder("su", "-c", cmd).redirectErrorStream(true).start()
            val sb = StringBuffer()
            val reader = Thread {
                try {
                    p.inputStream.bufferedReader().forEachLine { sb.append(it).append('\n') }
                } catch (_: Exception) {
                }
            }
            reader.start()
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroy()
                return Result(false, "Timed out. Did you allow root access for this app?")
            }
            reader.join(1000)
            Result(p.exitValue() == 0, sb.toString().trim())
        } catch (e: Exception) {
            Result(false, "Root (su) is not available: ${e.message}")
        }
    }

    private fun unpackScript(c: Context): File? = try {
        val f = File(c.filesDir, SCRIPT_NAME)
        c.assets.open(SCRIPT_NAME).use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        f
    } catch (_: Exception) {
        null
    }

    /** Copies the watchdog into /data/adb/service.d (runs at every boot) and starts it right away. */
    fun installWatchdog(c: Context): Result {
        val tmp = unpackScript(c) ?: return Result(false, "Could not unpack the watchdog script")
        val cmd = "mkdir -p /data/adb/service.d && cp '${tmp.absolutePath}' $INSTALLED && " +
            "chmod 755 $INSTALLED && " +
            "(kill \$(cat $PIDF 2>/dev/null) 2>/dev/null; true) && " +
            "(nohup sh $INSTALLED </dev/null >/dev/null 2>&1 &) && sleep 1 && echo installed"
        val r = su(cmd)
        return if (r.ok) {
            Result(true, "Watchdog installed. It starts by itself after every reboot and restarts the service whenever it dies.")
        } else {
            r
        }
    }

    fun removeWatchdog(): Result {
        val r = su("kill \$(cat $PIDF 2>/dev/null) 2>/dev/null; rm -f $INSTALLED $PIDF; echo removed")
        return if (r.ok) Result(true, "Watchdog removed") else r
    }

    /** Switches the service off and on once (clears Android's "crashed" state). */
    fun reviveNow(c: Context): Result {
        val tmp = unpackScript(c) ?: return Result(false, "Could not unpack the watchdog script")
        val r = su("sh '${tmp.absolutePath}' revive")
        return if (r.ok) Result(true, "Service restarted") else r
    }

    fun watchdogLog(): String {
        val r = su("tail -n 12 $LOGF 2>/dev/null; [ -f $PIDF ] && grep -q circle_watchdog /proc/\$(cat $PIDF)/cmdline 2>/dev/null && echo '[watchdog is running]' || echo '[watchdog is NOT running]'")
        return r.output.ifBlank { "No log yet" }
    }

    // ---- Xiaomi / HyperOS shortcuts -----------------------------------------

    private fun appDetails(a: Activity) {
        a.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${a.packageName}"))
        )
    }

    /** Tries each known OEM screen (Xiaomi, then vivo/iQOO) and falls back to the app details page. */
    private fun openFirst(a: Activity, vararg targets: Pair<String, String>, extras: (Intent) -> Unit = {}) {
        for ((pkg, cls) in targets) {
            try {
                a.startActivity(Intent().setClassName(pkg, cls).also(extras))
                return
            } catch (_: Exception) {
            }
        }
        appDetails(a)
    }

    fun openAutostart(a: Activity) = openFirst(
        a,
        "com.miui.securitycenter" to "com.miui.permcenter.autostart.AutoStartManagementActivity",
        "com.vivo.permissionmanager" to "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        "com.iqoo.secure" to "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"
    )

    /** HyperOS "Battery saver" page for this app (choose "No restrictions"). */
    fun openBatterySaver(a: Activity) = openFirst(
        a,
        "com.miui.powerkeeper" to "com.miui.powerkeeper.ui.HiddenAppsConfigActivity",
        "com.vivo.abe" to "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity",
        "com.iqoo.powersaving" to "com.iqoo.powersaving.PowerSavingManagerActivity"
    ) {
        it.putExtra("package_name", a.packageName).putExtra("package_label", a.getString(R.string.app_name))
    }
}
