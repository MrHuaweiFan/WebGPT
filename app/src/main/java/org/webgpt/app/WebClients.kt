package org.webgpt.app

import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Message
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.webgpt.app.webview.CrashTracker

/**
 * The WebChromeClient and WebViewClient of the main WebView. Popup WebViews get
 * their own clients from [PopupManager]; this class only serves the main one.
 */
internal class WebClients(private val activity: MainActivity) {

    private var offlineDialog: AlertDialog? = null

    /** Dismisses the offline dialog if it is up (the activity is being destroyed). */
    fun dismissOfflineDialog() {
        offlineDialog?.dismiss()
        offlineDialog = null
    }

    /** Installs both clients on [webView] (the initial instance or the one rebuilt after a renderer crash). */
    fun attachTo(webView: WebView) {
        webView.webChromeClient = chromeClient()
        webView.webViewClient = viewClient()
    }

    private fun chromeClient() = object : WebChromeClient() {

        override fun onConsoleMessage(cm: ConsoleMessage): Boolean {
            // The site's console can contain chat fragments, so release builds
            // must not mirror it into logcat.
            if (BuildConfig.EXPERIMENTAL) {
                Log.d(TAG, "${cm.message()} -- line ${cm.lineNumber()} of ${cm.sourceId()}")
            }
            return true
        }

        override fun onProgressChanged(view: WebView, newProgress: Int) {
            activity.loadingScreen.onPageProgress(newProgress.coerceIn(0, 100))
        }

        override fun onPermissionRequest(request: PermissionRequest) {
            activity.webPermissions.handle(request)
        }

        override fun onShowFileChooser(
            webView: WebView,
            filePathCallback: ValueCallback<Array<Uri>>,
            fileChooserParams: FileChooserParams,
        ): Boolean = activity.fileChooser.open(webView, filePathCallback)

        override fun onCreateWindow(
            view: WebView,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message,
        ): Boolean {
            // Routing happens in the popup's own shouldOverrideUrlLoading:
            // internal pages load in the popup, external hosts go to the browser.
            return activity.popups.create(resultMsg)
        }
    }

    private fun viewClient() = object : WebViewClient() {

        @Deprecated("Legacy callback for API < 24")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
            // Legacy callback (fires for all frames): URLs without a host
            // (blob:, data:) must load, or iframe apps break.
            return NavigationPolicy.decideFrame(url).apply(activity, url)
        }

        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url.toString()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !request.isForMainFrame) {
                return NavigationPolicy.decideFrame(url).apply(activity, url)
            }
            val signInContext = NavigationPolicy.isSignInContextUrl(view.url)
            return NavigationPolicy.decideMainFrame(url, signInContext).apply(activity, url)
        }

        @Deprecated("Legacy callback for API < 23")
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onReceivedError(
            view: WebView,
            errorCode: Int,
            description: String?,
            failingUrl: String?,
        ) {
            // The legacy callback fires for the main frame only.
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) showOfflineDialog()
        }

        override fun onReceivedError(
            view: WebView,
            request: WebResourceRequest,
            error: WebResourceError,
        ) {
            // Main frame only, so a failed subresource of the SPA does not raise the dialog.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && request.isForMainFrame) {
                showOfflineDialog()
            }
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            val isMain = view === activity.webViewOrNull
            val reason = if (detail.didCrash()) "CRASH" else "OOM_KILL"
            Log.e(TAG, "WebView renderer gone; recreating WebView (main=$isMain, reason=$reason)")
            if (isMain) {
                activity.initialLoadComplete = false
                // The overlay is not shown here. onPageStarted shows it after
                // 200 ms, so a reload from cache never flashes the loading screen.
                activity.recreateMainWebView()
                activity.webView.loadSiteUrl(SiteConfig.HOME_URL)
            }
            // Popups have their own client in PopupManager.
            return true
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // Only the initial load shows the overlay. SPA navigations fire
            // onPageStarted too and must not bring it back.
            val loadingScreen = activity.loadingScreen
            if (activity.initialLoadComplete || loadingScreen.overlay.visibility == android.view.View.VISIBLE) return
            // Delayed by 200 ms: a page that loads from cache within that
            // window never shows the loading screen.
            activity.webView.postDelayed({
                if (!activity.initialLoadComplete &&
                    loadingScreen.overlay.visibility != android.view.View.VISIBLE
                ) {
                    loadingScreen.overlay.visibility = android.view.View.VISIBLE
                    loadingScreen.updateLogoForTheme()
                    loadingScreen.startLogoAnimation()
                    loadingScreen.syncProgressBar()
                }
            }, OVERLAY_SHOW_DELAY_MS)
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // A finished load marks this launch as non-crashing.
            CrashTracker.reset()
            CookieManager.getInstance().flush()
            injectAllOverrides(view)
            activity.share.kickPendingSharePipelines()
            if (activity.initialLoadComplete) return
            // Fallback only. The primary signal is the DOM-ready watcher
            // (WebAppBridge.pageReady). This timer keeps the overlay from
            // getting stuck if the watcher never ran.
            activity.webView.postDelayed({
                if (!activity.initialLoadComplete) activity.loadingScreen.hideNow()
            }, OVERLAY_FALLBACK_HIDE_MS)
        }
    }

    /** onPageFinished fallback, and re-injection after SPA navigations. */
    private fun injectAllOverrides(view: WebView) {
        view.evaluateJavascript(PageScripts.pageOverrides(activity), null)
        // Fallback for WebViews without document-start script support.
        // Main WebView only: the watcher must never run in OAuth or share popups.
        if (view === activity.webViewOrNull) {
            view.evaluateJavascript(PageScripts.pageReadyWatcher(activity), null)
        }
    }

    /** Offline dialog with retry, shown for main-frame load failures. */
    private fun showOfflineDialog() {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed || offlineDialog != null) return@runOnUiThread
            val loadingScreen = activity.loadingScreen
            loadingScreen.overlay.visibility = android.view.View.GONE
            // The overlay is hidden, so its infinite animators would only tick under the dialog.
            loadingScreen.stopLogoAnimation()
            offlineDialog = MaterialAlertDialogBuilder(activity)
                .setTitle("Connection problem")
                .setMessage("Couldn't load ${SiteConfig.HOME_HOST}. Check your internet connection and try again.")
                .setCancelable(false)
                .setPositiveButton("Retry") { _, _ ->
                    offlineDialog = null
                    activity.initialLoadComplete = false
                    // The overlay was hidden while the dialog was up: restart
                    // the logo animation and re-sync the progress bar.
                    loadingScreen.overlay.visibility = android.view.View.VISIBLE
                    loadingScreen.startLogoAnimation()
                    loadingScreen.syncProgressBar()
                    activity.webView.loadSiteUrl(SiteConfig.HOME_URL)
                }
                .setNegativeButton("Close app") { _, _ ->
                    offlineDialog = null
                    activity.finishAffinity()
                }
                .show()
        }
    }

    private companion object {
        const val TAG = SiteConfig.LOG_TAG
        const val OVERLAY_SHOW_DELAY_MS = 200L
        const val OVERLAY_FALLBACK_HIDE_MS = 3_500L
    }
}
