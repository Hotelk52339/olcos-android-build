package io.olcos.app.core

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks at the public releases once a day: a release is `v<version>.<build>`
 * with the APK as its asset, like the iOS app's `v<version>.<build>` tags;
 * the build segment decides, so a tag without one never reads as newer.
 */
class UpdateChecker(context: Context) {
    data class Release(val tag: String, val build: Int, val url: String, val apkURL: String?)

    private val prefs: SharedPreferences = context.getSharedPreferences("olcos.updates", Context.MODE_PRIVATE)
    private val installedBuild: Int = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        (if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()).toInt()
    }.getOrDefault(0)

    private val _latest = MutableStateFlow(stored())
    /** The newest release known, newer than this install or not. */
    val latest: StateFlow<Release?> = _latest

    val available: Release? get() = _latest.value?.takeIf { it.build > installedBuild }

    /** Asks GitHub unless it did within a day (or `force`); keeps the answer. */
    suspend fun check(force: Boolean = false): Release? {
        val now = System.currentTimeMillis()
        if (!force && now - prefs.getLong(KEY_CHECKED, 0) < DAY_MS) return available
        refresh()
        return available
    }

    /** Asks GitHub now. True when it answered (the answer is kept); false when it could not be reached, and the day's clock is not touched. */
    suspend fun refresh(): Boolean {
        val release = withContext(Dispatchers.IO) { runCatching { fetch() }.getOrNull() } ?: return false
        // The check time counts only once an answer came, so an outage is tried again next launch.
        prefs.edit().putLong(KEY_CHECKED, System.currentTimeMillis()).apply()
        _latest.value = release
        prefs.edit().putString(KEY_TAG, release.tag).putInt(KEY_BUILD, release.build)
            .putString(KEY_URL, release.url).putString(KEY_APK, release.apkURL).apply()
        AppLog.i("olcos.update", "latest release ${release.tag}, installed build $installedBuild")
        return true
    }

    private fun fetch(): Release? {
        val connection = (URL(API).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "olcOS-android")
        }
        try {
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8))
            val tag = json.optString("tag_name")
            val build = buildOf(tag) ?: return null
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    if (asset.optString("name").endsWith(".apk")) apk = asset.optString("browser_download_url")
                }
            }
            return Release(tag, build, json.optString("html_url", RELEASES_PAGE), apk)
        } finally {
            connection.disconnect()
        }
    }

    private fun stored(): Release? {
        val tag = prefs.getString(KEY_TAG, null) ?: return null
        return Release(tag, prefs.getInt(KEY_BUILD, 0), prefs.getString(KEY_URL, RELEASES_PAGE) ?: RELEASES_PAGE, prefs.getString(KEY_APK, null))
    }

    companion object {
        const val REPO = "Hotelk52339/olcos-android"
        const val RELEASES_PAGE = "https://github.com/$REPO/releases/latest"
        private const val API = "https://api.github.com/repos/$REPO/releases/latest"
        private const val DAY_MS = 24 * 60 * 60_000L
        private const val KEY_CHECKED = "checkedAt"
        private const val KEY_TAG = "tag"
        private const val KEY_BUILD = "build"
        private const val KEY_URL = "url"
        private const val KEY_APK = "apk"

        /** PURE: the build segment of `v3.0.12`; null for a tag without one. */
        fun buildOf(tag: String): Int? {
            val parts = tag.removePrefix("v").split('.')
            if (parts.size < 3) return null
            return parts.last().toIntOrNull()
        }
    }
}
