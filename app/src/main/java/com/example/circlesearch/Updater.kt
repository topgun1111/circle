package com.example.circlesearch

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** Checks GitHub Releases for a newer build, downloads the APK and hands it to the system installer. */
object Updater {

    const val OWNER = "topgun1111"
    const val REPO = "circle"
    const val ACTION_INSTALL_RESULT = "com.example.circlesearch.INSTALL_RESULT"

    data class Release(
        val tag: String,
        val build: Long,
        val title: String,
        val notes: String,
        val apkUrl: String,
        val apkSize: Long
    )

    fun currentName(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    fun currentBuild(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode

    /** Blocking. Returns the latest release, or throws with a readable message. */
    fun fetchLatest(): Release {
        val conn = URL("https://api.github.com/repos/$OWNER/$REPO/releases/latest")
            .openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "CircleSearch-Updater")
            when (val code = conn.responseCode) {
                200 -> {}
                404 -> throw Exception("No releases found on GitHub yet (or the repository is private).")
                403 -> throw Exception("GitHub refused the request (rate limit?). Try again in a few minutes.")
                else -> throw Exception("GitHub answered with HTTP $code.")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val tag = json.getString("tag_name")
            val build = Regex("(\\d+)\\D*$").find(tag)?.groupValues?.get(1)?.toLongOrNull()
                ?: throw Exception("Could not read a build number from release tag \"$tag\".")
            val assets = json.getJSONArray("assets")
            var url: String? = null
            var size = 0L
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk", ignoreCase = true)) {
                    url = a.getString("browser_download_url")
                    size = a.optLong("size", 0)
                    break
                }
            }
            return Release(
                tag = tag,
                build = build,
                title = json.optString("name", tag),
                notes = json.optString("body", ""),
                apkUrl = url ?: throw Exception("Release $tag has no APK attached."),
                apkSize = size
            )
        } finally {
            conn.disconnect()
        }
    }

    fun isNewer(c: Context, r: Release) = r.build > currentBuild(c)

    /** Blocking. Downloads the APK into the app's cache and returns the file. */
    fun download(c: Context, r: Release, onProgress: (done: Long, total: Long) -> Unit): File {
        val dir = File(c.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "circle-${r.tag}.apk")
        val conn = URL(r.apkUrl).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "CircleSearch-Updater")
            if (conn.responseCode != 200) throw Exception("Download failed (HTTP ${conn.responseCode}).")
            val total = conn.contentLengthLong.takeIf { it > 0 } ?: r.apkSize
            var done = 0L
            var lastReport = 0L
            conn.inputStream.use { input ->
                file.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val now = System.currentTimeMillis()
                        if (now - lastReport > 150) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
            }
            onProgress(done, total)
            if (total > 0 && done != total) throw Exception("Download was cut short. Try again.")
        } catch (e: Exception) {
            file.delete()
            throw e
        } finally {
            conn.disconnect()
        }
        return file
    }

    /** Hands the APK to the system installer; Android then asks the user to confirm. */
    fun install(c: Context, file: File) {
        val installer = c.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            file.inputStream().use { input ->
                session.openWrite("circle.apk", 0, file.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(c, InstallResultReceiver::class.java).setAction(ACTION_INSTALL_RESULT)
            val pi = PendingIntent.getBroadcast(c, id, intent, PendingIntent.FLAG_MUTABLE)
            session.commit(pi.intentSender)
        }
    }
}
