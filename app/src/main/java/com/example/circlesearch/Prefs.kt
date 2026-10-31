package com.example.circlesearch

import android.content.Context

object Prefs {
    private fun p(c: Context) = c.getSharedPreferences("circle_search", Context.MODE_PRIVATE)

    fun hasKey(c: Context) = p(c).getBoolean("has_key", false)
    fun keyCode(c: Context) = p(c).getInt("key", 0)
    fun scanCode(c: Context) = p(c).getInt("scan", 0)

    fun saveKey(c: Context, key: Int, scan: Int) =
        p(c).edit().putBoolean("has_key", true).putInt("key", key).putInt("scan", scan).apply()

    fun clearKey(c: Context) =
        p(c).edit().remove("has_key").remove("key").remove("scan").apply()

    // Learning mode expires on its own so it can never get stuck consuming keys.
    fun startLearning(c: Context) =
        p(c).edit().putLong("learn_until", System.currentTimeMillis() + 20_000).apply()

    fun stopLearning(c: Context) = p(c).edit().remove("learn_until").apply()

    fun isLearning(c: Context) = System.currentTimeMillis() < p(c).getLong("learn_until", 0)

    /** Small ongoing notification that makes Android treat the app as a foreground service. */
    fun keepAliveNotif(c: Context) = p(c).getBoolean("keep_notif", true)
    fun setKeepAliveNotif(c: Context, v: Boolean) = p(c).edit().putBoolean("keep_notif", v).apply()

    fun useShareSheet(c: Context) = p(c).getBoolean("share_sheet", false)
    fun setUseShareSheet(c: Context, v: Boolean) = p(c).edit().putBoolean("share_sheet", v).apply()


    fun floating(c: Context) = p(c).getBoolean("floating", false)
    fun setFloating(c: Context, v: Boolean) = p(c).edit().putBoolean("floating", v).apply()
    fun floatX(c: Context) = p(c).getInt("float_x", -1)
    fun floatY(c: Context) = p(c).getInt("float_y", -1)
    fun savePos(c: Context, x: Int, y: Int) = p(c).edit().putInt("float_x", x).putInt("float_y", y).apply()

    fun backTap(c: Context) = p(c).getBoolean("back_tap", false)
    fun setBackTap(c: Context, v: Boolean) = p(c).edit().putBoolean("back_tap", v).apply()
    fun backTapLevel(c: Context) = p(c).getInt("back_tap_level", 5)
    fun setBackTapLevel(c: Context, v: Int) = p(c).edit().putInt("back_tap_level", v).apply()

    fun rectMode(c: Context) = p(c).getBoolean("rect_mode", false)
    fun setRectMode(c: Context, v: Boolean) = p(c).edit().putBoolean("rect_mode", v).apply()

    fun floatSize(c: Context) = p(c).getInt("float_size", 48)
    fun setFloatSize(c: Context, v: Int) = p(c).edit().putInt("float_size", v).apply()
    fun floatOpacity(c: Context) = p(c).getInt("float_opacity", 100)
    fun setFloatOpacity(c: Context, v: Int) = p(c).edit().putInt("float_opacity", v).apply()
    fun floatEdge(c: Context) = p(c).getBoolean("float_edge", false)
    fun setFloatEdge(c: Context, v: Boolean) = p(c).edit().putBoolean("float_edge", v).apply()
    fun hiddenApps(c: Context): Set<String> = HashSet(p(c).getStringSet("hidden_apps", emptySet()) ?: emptySet())
    fun setHiddenApps(c: Context, v: Set<String>) = p(c).edit().putStringSet("hidden_apps", HashSet(v)).apply()
}
