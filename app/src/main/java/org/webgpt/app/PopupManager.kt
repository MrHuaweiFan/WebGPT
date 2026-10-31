package org.webgpt.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.os.Message
import android.util.Log
import android.view.ViewGroup
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import java.util.Locale

/**
 * Owns the extra WebViews the site opens with window.open(): the Google / OpenAI
 * sign-in chain, the share and export menus, and links that turn out to belong
 * in the system browser.
 *
 * A popup is created detached and attached to the layout only when it is known
 * to render something in-app. Attaching it at creation time flashed a fullscreen
 * frame between the tap and the browser opening for every external link.
 */
internal class PopupManager(private val activity: MainActivity) {

    private val views = mutableListOf<WebView>()

    /** Per-popup sign-in progress. */
    private class PopupState {
        /** True once the popup has been on the sign-in chain and the main WebView still needs a refresh. */
        var loginChain = false
    }

    /**
     * Builds the popup WebView for [resultMsg] (from onCreateWindow) and hands it to the
     * WebView transport. Returns the value for onCreateWindow.
     */
    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    fun create(resultMsg: Message): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false
        val mainView = activity.webViewOrNull ?: return false

        val popup = WebView(activity)
        popup.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        )
        // Pages repaint over this right away. It only removes the white flash in dark mode.
        activity.applyWebViewBackground(popup)

        popup.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
            userAgentString = mainView.settings.userAgentString
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = false
            allowContentAccess = false
        }

        // Third-party cookies stay enabled here: the accounts.google.com / auth.openai.com
        // redirect chain needs them.
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true)

        // Kept so the bridge's trust can be revoked when the popup lands on a foreign origin.
        val bridge = WebAppBridge(activity, popup)
        popup.addJavascriptInterface(bridge, "AndroidBridge")

        // Same document-start overrides as the main WebView: popup frames (sign-in,
        // share menus) need the blob and share hooks too. The ready watcher stays
        // out of popups.
        PageScripts.installAtDocumentStart(popup, mainWindow = false)

        val state = PopupState()
        popup.webViewClient = popupWebViewClient(popup, bridge, state)
        popup.webChromeClient = popupChromeClient(popup, state)

        views.add(popup)
        transport.webView = popup
        resultMsg.sendToTarget()
        return true
    }

    /** Closes the newest popup. Returns false if there is none. */
    fun closeTop(): Boolean {
        val top = views.lastOrNull() ?: return false
        remove(top)
        return true
    }

    fun pauseAll() {
        for (popup in views.toList()) {
            try {
                popup.onPause()
            } catch (_: Throwable) {
            }
        }
    }

    fun resumeAll() {
        for (popup in views.toList()) {
            try {
                popup.onResume()
            } catch (_: Throwable) {
            }
        }
    }

    /** For onDestroy: the activity is going away, so destroy inline. */
    fun destroyAll() {
        for (popup in views.toList()) remove(popup, immediate = true)
    }

    private fun popupWebViewClient(popup: WebView, bridge: WebAppBridge, state: PopupState) =
        object : WebViewClient() {

            @Deprecated("Legacy callback for API < 24")
            @Suppress("OVERRIDE_DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (NavigationPolicy.isLoginChainUrl(url)) state.loginChain = true
                val result = NavigationPolicy.decideFrame(url).apply(activity, url)
                if (result) removeForExternalLink(popup, state, url) else attachOnce(popup)
                return result
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                val mainFrame = request.isForMainFrame
                if (mainFrame && NavigationPolicy.isLoginChainUrl(url)) state.loginChain = true
                val decision = if (!mainFrame) {
                    NavigationPolicy.decideFrame(url)
                } else {
                    // state.loginChain was just set if this URL is a sign-in page;
                    // otherwise the popup's current page decides.
                    NavigationPolicy.decideMainFrame(
                        url,
                        state.loginChain || NavigationPolicy.isSignInContextUrl(view.url),
                    )
                }
                val result = decision.apply(activity, url)
                if (result && mainFrame) removeForExternalLink(popup, state, url)
                else if (!result) attachOnce(popup)
                return result
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // onPageStarted is main-frame only and also fires for redirect hops, which
                // skip shouldOverrideUrlLoading. It is the one reliable place to see the
                // popup land on a foreign origin and revoke its bridge trust.
                revokeTrustIfForeign(bridge, url)
                if (NavigationPolicy.isLoginChainUrl(url)) state.loginChain = true
                // blob: and about:blank popups (the share menu's window) never reach
                // shouldOverrideUrlLoading, so this is their only attach trigger.
                attachOnce(popup)
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                remove(popup)
                reloadMainAfterLogin(state)
                return true
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                view.evaluateJavascript(PageScripts.pageOverrides(activity), null)
                if (url == null) return
                if (NavigationPolicy.isLoginFinishedUrl(url)) {
                    state.loginChain = false // handled here, so no second reload
                    activity.webViewOrNull?.loadSiteUrl(url)
                    remove(popup)
                }
            }
        }

    private fun popupChromeClient(popup: WebView, state: PopupState) =
        object : WebChromeClient() {

            override fun onCloseWindow(window: WebView?) {
                remove(popup)
                reloadMainAfterLogin(state)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (BuildConfig.EXPERIMENTAL) {
                    Log.d(TAG, "[popup] ${consoleMessage?.message()}")
                }
                return true
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                activity.webPermissions.handle(request)
            }

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams?,
            ): Boolean = activity.fileChooser.open(webView, filePathCallback)
        }

    /**
     * Attaches a deferred popup the first time it will render in-app content.
     * Safe to call repeatedly. A popup that was already torn down is never
     * re-attached by a stale callback.
     */
    private fun attachOnce(popup: WebView) {
        if (popup.parent != null) return
        if (popup !in views) return
        try {
            activity.rootLayout.addView(popup)
        } catch (t: Throwable) {
            Log.e(TAG, "attachOnce failed", t)
        }
    }

    /**
     * The policy sent a main-frame URL to the browser, so the popup has nothing to
     * render. Remove it while it is still invisible, then refresh the main WebView
     * if the popup was part of a sign-in.
     */
    private fun removeForExternalLink(popup: WebView, state: PopupState, url: String?) {
        if (url == null) return
        val host = try {
            Uri.parse(url).host
        } catch (_: Exception) {
            null
        }
        // Only called after the policy routed this URL out of the app, so any host qualifies.
        if (host != null) {
            remove(popup)
            reloadMainAfterLogin(state)
        }
    }

    /**
     * Safety net: a sign-in popup that ends without its own onPageFinished leaves the
     * main WebView on the login page. Flush the cookie jar and reload it so a
     * cookie-based login is picked up. Runs at most once per popup, and only for
     * popups that were on the sign-in chain.
     */
    private fun reloadMainAfterLogin(state: PopupState) {
        if (!state.loginChain) return
        state.loginChain = false
        try {
            CookieManager.getInstance().flush()
            activity.webViewOrNull?.loadSiteUrl(SiteConfig.HOME_URL)
        } catch (t: Throwable) {
            Log.e(TAG, "reloadMainAfterLogin failed", t)
        }
    }

    /**
     * Revokes the popup's bridge trust, permanently, when its top frame is on a
     * non-allowlisted http(s) origin. Blank-shell URLs (null, about:, blob:, data:)
     * never revoke: that is the site's own share or export window.
     */
    private fun revokeTrustIfForeign(bridge: WebAppBridge, url: String?) {
        if (url.isNullOrEmpty()) return
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return
            if (scheme != "http" && scheme != "https") return
            val host = uri.host
            if (host != null && !NavigationPolicy.isAllowedHost(host)) {
                Log.w(TAG, "popup reached non-allowlisted origin; bridge trust revoked: $url")
                bridge.revokeTrust()
            }
        } catch (_: Throwable) {
            // Trust bookkeeping must never crash a navigation.
        }
    }

    /**
     * Detaches a popup and destroys it.
     *
     * With immediate = false (the default), used from inside WebView callbacks, the
     * view is detached now and destroy() is posted to the next message-loop pass.
     * Calling destroy() from inside shouldOverrideUrlLoading, onPageFinished,
     * onRenderProcessGone or a download listener crashes newer Chromium builds
     * (it killed the app on Android 15 when a login popup was torn down).
     *
     * immediate = true is for onDestroy(), where no further loop pass is useful.
     */
    private fun remove(popup: WebView, immediate: Boolean = false) {
        try {
            activity.rootLayout.removeView(popup)
            views.remove(popup)
            if (immediate) {
                popup.destroy()
            } else {
                activity.rootLayout.post {
                    try {
                        popup.destroy()
                    } catch (t: Throwable) {
                        Log.e(TAG, "deferred popup destroy failed", t)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing popup", e)
        }
    }

    private companion object {
        const val TAG = SiteConfig.LOG_TAG
    }
}
