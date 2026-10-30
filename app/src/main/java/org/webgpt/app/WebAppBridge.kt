package org.webgpt.app

import android.net.Uri
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import java.util.Locale

/**
 * The JavaScript bridge, exposed to pages as `window.AndroidBridge`.
 *
 * Every call comes from web content, so every argument is untrusted and may be
 * null. Methods reject bad input instead of throwing.
 *
 * Origin gate ([hostAllowed]): the bridge serves only pages on allowlisted hosts.
 * - Main WebView: allowed while its top-level URL is allowlisted. addJavascriptInterface
 *   does not tell the Java side which frame is calling, so this checks the top-level page only.
 * - Popup WebView: allowed while the popup's own top-level URL is allowlisted, or while
 *   it is a blank shell (null, about:, blob:). The site's share and export UI runs in
 *   blob: popups, which have no origin of their own and keep the trust they got from being
 *   opened by our page. Once the popup's top frame is seen on a foreign http(s) origin,
 *   [revokeTrust] ends that trust for good.
 * - A WebView never inherits trust from a different one.
 *
 * Sharing is not gated on purpose: the system share sheet is the consent screen, and a
 * gate would break sharing from the site's popup, whose URL is blob: or about:blank.
 */
internal class WebAppBridge(
    private val activity: MainActivity,
    private val hostWebView: WebView,
) {

    /**
     * Set only on popups. Redirect hops skip shouldOverrideUrlLoading but always fire
     * onPageStarted, so the popup's onPageStarted calls [revokeTrust] when it sees a
     * foreign origin.
     */
    @Volatile
    private var trustRevoked = false

    /** Called from the popup's onPageStarted once its top frame is on a foreign origin. */
    fun revokeTrust() {
        trustRevoked = true
        debugLog("bridge trust revoked for ${hostWebView.url}")
    }

    private fun hostAllowed(): Boolean = try {
        val url = hostWebView.url
        when {
            trustRevoked -> {
                debugLog("bridge blocked: trust revoked")
                false
            }
            isAllowedPageUrl(url) -> true
            hostWebView !== activity.webViewOrNull && isBlankShellUrl(url) -> true
            else -> {
                debugLog("bridge blocked: $url")
                false
            }
        }
    } catch (t: Throwable) {
        false
    }

    /** Called by the page-ready watcher when the SPA has really rendered. */
    @JavascriptInterface
    fun pageReady() {
        // hostAllowed() reads WebView state, which belongs to the UI thread.
        activity.runOnUiThread {
            if (hostAllowed()) activity.loadingScreen.onDomReady()
        }
    }

    /** navigator.share({ text }) fallback: copies silently, the site shows its own "Copied!". */
    @JavascriptInterface
    fun copyToClipboard(text: String?) {
        if (text == null) return
        // hostAllowed() reads WebView state, so the check runs on the UI thread.
        activity.runOnUiThread { if (hostAllowed()) activity.copyText(text) }
    }

    /** navigator.share({ text, url }): opens the system share sheet. */
    @JavascriptInterface
    fun shareText(text: String?, url: String?) {
        debugLog("shareText invoked")
        activity.runOnUiThread { activity.shareText(text, url) }
    }

    /** navigator.share({ files }): shares one file, given as a data URL. */
    @JavascriptInterface
    fun shareFile(title: String?, dataUrl: String?, fileName: String?, mime: String?) {
        debugLog("shareFile invoked: $fileName")
        activity.runOnUiThread { activity.shareFile(title, dataUrl, fileName, mime) }
    }

    /** Blob-download callback: the in-page read finished and delivers a data URL. */
    @JavascriptInterface
    fun onBlobResult(dataUrl: String?) {
        if (dataUrl == null) return
        activity.runOnUiThread { if (hostAllowed()) activity.downloads.handleBlobResult(dataUrl) }
    }

    /** Blob-download callback: the in-page fetch or read failed. */
    @JavascriptInterface
    fun onBlobFailed() {
        // Gated because it changes shared download state. The gate runs on the UI thread.
        activity.runOnUiThread { if (hostAllowed()) activity.downloads.handleBlobFailed() }
    }

    /** Direct blob download from the anchor-click hook, while the blob URL is still alive. */
    @JavascriptInterface
    fun onBlobDownload(name: String?, dataUrl: String?) {
        if (name == null || dataUrl == null) return
        activity.runOnUiThread { if (hostAllowed()) activity.downloads.handleBlobDownload(name, dataUrl) }
    }

    /** One piece of a large export. The last piece assembles and saves the file. */
    @JavascriptInterface
    fun onBlobChunk(name: String?, mime: String?, index: Int, total: Int, data: String?) {
        // Runs entirely on the UI thread (posts keep their order), so the gate and the
        // chunk buffer are never touched from the JavaBridge thread.
        activity.runOnUiThread {
            if (!hostAllowed()) return@runOnUiThread
            val payload = activity.downloads.blobTransfers.append(name, index, total, data)
                ?: return@runOnUiThread
            val dataUrl = "data:${safeMime(mime)};base64,$payload"
            activity.downloads.handleBlobDownload(name.orEmpty(), dataUrl)
        }
    }

    /** Result of the file-drop injection: clears the manual fallback or prompts the user. */
    @JavascriptInterface
    fun onFileDropResult(ok: Boolean, detail: String?) {
        // Gated so an unserved frame cannot confirm or cancel the user's pending share.
        // The gate reads WebView state, so it runs on the UI thread, not the JavaBridge thread.
        activity.runOnUiThread { if (hostAllowed()) activity.share.handleFileDropResult(ok, detail) }
    }

    /** Page-side diagnostics. Logcat only, in experimental builds. Never a toast. */
    @JavascriptInterface
    fun debugLog(msg: String?) {
        if (BuildConfig.EXPERIMENTAL) Log.d(TAG, "bridge: $msg")
    }

    companion object {
        /** The name pages see: `window.AndroidBridge`. */
        const val NAME = "AndroidBridge"

        private const val TAG = "WebGPTApp"
        private const val MAX_MIME_LENGTH = 64
        private val MIME_SHAPE = Regex("[\\w.+-]+/[\\w.+-]+")

        /**
         * The MIME type ends up inside a data URL, so it is reduced to a short safe
         * token. Anything else becomes a generic type.
         */
        private fun safeMime(mime: String?): String {
            val m = mime?.trim()?.lowercase(Locale.ROOT).orEmpty()
            return if (m.length <= MAX_MIME_LENGTH && MIME_SHAPE.matches(m)) m else FileNames.DEFAULT_MIME
        }

        private fun isAllowedPageUrl(url: String?): Boolean {
            if (url == null) return false
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            return (scheme == "https" || scheme == "http") && NavigationPolicy.isAllowedHost(uri.host)
        }

        /**
         * A popup that has not navigated yet, or one of the blob:/about:blank windows the
         * site opens for share and export. data: URLs are not blank shells: the site never
         * uses them for this, and they run scripts under an opaque origin we have no reason
         * to serve.
         */
        private fun isBlankShellUrl(url: String?): Boolean {
            if (url.isNullOrEmpty()) return true
            return runCatching {
                when (Uri.parse(url).scheme?.lowercase(Locale.ROOT)) {
                    null, "about", "blob" -> true
                    else -> false
                }
            }.getOrDefault(false)
        }
    }
}
