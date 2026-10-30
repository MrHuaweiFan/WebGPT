package org.webgpt.app.webview

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FilenameFilter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Checks GitHub releases for a newer version of the app.
 *
 * When an update is available, the caller opens the release page in the user's
 * browser. There is no in-app APK download or install: the browser downloads
 * and the system package installer installs, which is more reliable than doing
 * it in-app (PackageInstaller and FileProvider installs crash on some Huawei
 * and Xiaomi devices).
 *
 * Releases page: https://github.com/MrHuaweiFan/WebGPT/releases
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val REPO_API = "https://api.github.com/repos/MrHuaweiFan/WebGPT/releases"
    private const val USER_AGENT = "WebGPT-Android-Updater/6.0"
    private const val APK_ASSET = "app-release.apk"

    fun interface Callback {
        /**
         * @param updateAvailable true if a newer release exists on GitHub
         * @param latestVersion normalized version string (e.g. "6.18")
         * @param releaseUrl URL to open in the browser (direct APK or release page)
         * @param errorMessage non-null if the check failed
         */
        fun onResult(updateAvailable: Boolean, latestVersion: String, releaseUrl: String?, errorMessage: String?)
    }

    /**
     * Fetches the GitHub releases and compares the latest stable tag with
     * [currentVersion]. [cb] is invoked on a worker thread.
     */
    @JvmStatic
    fun checkForUpdates(currentVersion: String, cb: Callback) {
        thread(name = "update-check") {
            val releases = try {
                JSONArray(download())
            } catch (e: Exception) {
                Log.e(TAG, "checkForUpdates failed", e)
                cb.onResult(false, currentVersion, null, e.message)
                return@thread
            }
            if (releases.length() == 0) {
                cb.onResult(false, currentVersion, null, "No releases found")
                return@thread
            }
            val latest = (0 until releases.length())
                .mapNotNull { releases.optJSONObject(it) }
                .firstOrNull { !it.optBoolean("prerelease", false) }
            if (latest == null) {
                // Only pre-releases exist: report "no update" rather than nudging users onto them.
                cb.onResult(false, currentVersion, null, null)
                return@thread
            }
            val latestVersion = normalizeVersion(latest.optString("tag_name", ""))
            cb.onResult(compareVersions(latestVersion, currentVersion) > 0, latestVersion, downloadUrl(latest), null)
        }
    }

    private fun download(): String {
        val conn = URL(REPO_API).openConnection() as HttpURLConnection
        try {
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    /** The direct `app-release.apk` link if the release has one, otherwise the release page. */
    private fun downloadUrl(release: JSONObject): String {
        val page = release.optString("html_url", "")
        val assets = release.optJSONArray("assets") ?: return page
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            if (asset.optString("name", "") != APK_ASSET) continue
            val url = asset.optString("browser_download_url", "")
            if (url.isNotEmpty()) return url
        }
        return page
    }

    /**
     * Finds a leftover APK from the old in-app download flow. SettingsActivity
     * uses it to clean up stale files; the current flow creates none.
     */
    @JvmStatic
    fun findDownloadedApk(context: Context): File? =
        context.getExternalFilesDir(null)
            ?.listFiles(FilenameFilter { _, name -> name.startsWith("v") && name.endsWith(".apk") })
            ?.firstOrNull()

    /** "v6.18-release" -> "6.18", "v6.18" -> "6.18", "6.18" -> "6.18". */
    @JvmStatic
    fun normalizeVersion(tag: String?): String {
        var t = tag.orEmpty().trim()
        if (t.startsWith("v") || t.startsWith("V")) t = t.substring(1)
        val dash = t.indexOf('-')
        if (dash > 0) t = t.substring(0, dash)
        return t.trim()
    }

    /** Compares dotted version numbers component by component. Missing components count as 0. */
    @JvmStatic
    fun compareVersions(a: String, b: String): Int {
        val left = normalizeVersion(a).split('.')
        val right = normalizeVersion(b).split('.')
        for (i in 0 until maxOf(left.size, right.size)) {
            val l = left.getOrNull(i)?.let(::leadingNumber) ?: 0
            val r = right.getOrNull(i)?.let(::leadingNumber) ?: 0
            if (l != r) return l.compareTo(r)
        }
        return 0
    }

    /** "18rc1" -> 18, "x" -> 0. */
    private fun leadingNumber(s: String): Int = s.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
}
