package com.example.circlesearch

import android.content.Context

/**
 * Update links. This build deliberately holds no INTERNET or install-packages permission: a
 * downloader/installer combined with an accessibility service can make phone security managers
 * switch the service off. The browser does the download, Android does the install.
 */
object Updater {

    const val OWNER = "topgun1111"
    const val REPO = "circle"

    const val RELEASES_PAGE = "https://github.com/$OWNER/$REPO/releases/latest"

    /** The workflow attaches an APK with this fixed name to every release. */
    const val LATEST_APK = "https://github.com/$OWNER/$REPO/releases/latest/download/circle-search.apk"

    fun currentName(c: Context): String =
        c.packageManager.getPackageInfo(c.packageName, 0).versionName ?: "?"

    fun currentBuild(c: Context): Long =
        c.packageManager.getPackageInfo(c.packageName, 0).longVersionCode
}
