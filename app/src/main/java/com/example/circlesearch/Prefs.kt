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

    fun useShareSheet(c: Context) = p(c).getBoolean("share_sheet", false)
    fun setUseShareSheet(c: Context, v: Boolean) = p(c).edit().putBoolean("share_sheet", v).apply()

    fun fourFinger(c: Context) = p(c).getBoolean("four_finger", false)
    fun setFourFinger(c: Context, v: Boolean) = p(c).edit().putBoolean("four_finger", v).apply()
}
