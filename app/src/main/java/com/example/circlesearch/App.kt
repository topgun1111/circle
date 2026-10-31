package com.example.circlesearch

import android.app.Application
import android.content.Context
import android.util.Log
import java.io.File

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            Diag.save(this, "Crash in thread \"${thread.name}\"", e)
            previous?.uncaughtException(thread, e)
        }
        // Remember why the previous process died (killed by the system vs. a real crash).
        KeepAlive.recordExitReasons(this)
    }
}

/** Keeps the last error on disk so the Settings screen can show it (no computer needed). */
object Diag {
    private fun file(c: Context) = File(c.filesDir, "last_crash.txt")

    fun save(c: Context, title: String, e: Throwable) {
        try {
            val v = try { "${Updater.currentName(c)} (build ${Updater.currentBuild(c)})" } catch (_: Exception) { "?" }
            file(c).writeText("$title\nVersion $v\nTime ${java.util.Date()}\n\n${Log.getStackTraceString(e)}")
        } catch (_: Exception) {
        }
    }

    fun read(c: Context): String? = try { file(c).takeIf { it.exists() }?.readText() } catch (_: Exception) { null }

    fun clear(c: Context) {
        try { file(c).delete() } catch (_: Exception) {}
    }
}
