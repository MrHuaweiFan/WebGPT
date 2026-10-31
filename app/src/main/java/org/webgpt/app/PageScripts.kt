package org.webgpt.app

import android.content.Context
import android.util.Log
import android.webkit.WebView
import androidx.webkit.WebViewCompat
import org.webgpt.app.webview.WebViewUtil

/**
 * The JavaScript the app injects into the site. The sources live in
 * assets/js so they can be edited and diffed as JavaScript instead of as
 * escaped Kotlin strings.
 */
internal object PageScripts {

    private const val TAG = SiteConfig.LOG_TAG

    /** Patches clipboard, share, blob downloads and window.open for the WebView. */
    private const val PAGE_OVERRIDES = "js/page_overrides.js"

    /** Reports to the app when the site's composer has really rendered. */
    private const val PAGE_READY_WATCHER = "js/page_ready_watcher.js"

    /** Stops the site from focusing the composer (and opening the keyboard) by itself. */
    private const val FOCUS_GUARD = "js/focus_guard.js"

    private val cache = HashMap<String, String>()

    @Synchronized
    private fun load(context: Context, path: String): String =
        cache.getOrPut(path) {
            context.applicationContext.assets.open(path).bufferedReader().use { it.readText() }
        }

    fun pageOverrides(context: Context): String = load(context, PAGE_OVERRIDES)

    fun pageReadyWatcher(context: Context): String = load(context, PAGE_READY_WATCHER)

    fun focusGuard(context: Context): String = load(context, FOCUS_GUARD)

    /**
     * Registers the scripts to run at document start in every frame (main frame
     * and iframes) before any site script, on every navigation. This matters
     * for the site's export and share features, which run inside iframes:
     * evaluateJavascript at page finish only reaches the main frame.
     *
     * The order is significant. The ready watcher's fallback reads
     * window.__webgptLoad, which the overrides script installs, and document
     * start scripts run in registration order.
     */
    fun installAtDocumentStart(webView: WebView, mainWindow: Boolean = true) {
        if (!WebViewUtil.isSupported()) return
        val context = webView.context
        try {
            val everywhere = setOf("*")
            WebViewCompat.addDocumentStartJavaScript(webView, pageOverrides(context), everywhere)
            if (mainWindow) {
                WebViewCompat.addDocumentStartJavaScript(webView, pageReadyWatcher(context), everywhere)
                WebViewCompat.addDocumentStartJavaScript(webView, focusGuard(context), everywhere)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "addDocumentStartJavaScript failed", t)
        }
    }
}
