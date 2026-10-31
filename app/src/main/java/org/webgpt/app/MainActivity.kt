package org.webgpt.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.res.Configuration
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.splashscreen.SplashScreenViewProvider
import org.webgpt.app.webview.CrashTracker
import org.webgpt.app.webview.WebViewManagerDialog
import org.webgpt.app.webview.WebViewUtil
import org.webgpt.app.webview.WelcomeDialog
import java.util.Locale

class MainActivity : Activity() {

    internal lateinit var webView: WebView
        private set

    /** Null until onCreate has built the UI (the activity can receive intents before that). */
    internal val webViewOrNull: WebView?
        get() = if (::webView.isInitialized) webView else null

    internal lateinit var rootLayout: ViewGroup
        private set

    internal lateinit var loadingScreen: LoadingScreen
        private set

    /** Masks the black flash when the GPU surface is recreated on resume. Null if it could not be attached. */
    internal var resumeSnapshot: ResumeSnapshot? = null
        private set

    /**
     * True once the initial page load has completed and the loading overlay has
     * started fading out. After that, SPA navigations (which still fire
     * onPageStarted/onPageFinished, e.g. ChatGPT's settings tabs) must NOT show
     * the loading overlay again.
     */
    internal var initialLoadComplete = false

    internal val share = ShareHandler(this)

    /** Popup windows (sign-in chain, share menus, external links). */
    internal val popups = PopupManager(this)

    /** File picker, camera capture and the permission prompts that go with them. */
    internal val fileChooser = FileChooser(this)

    /** Camera and microphone requests from the site. */
    internal val webPermissions = WebPermissions(this)

    // These keep state across a renderer crash, so they are created once and
    // re-attached to the replacement WebView.
    private val webClients = WebClients(this)
    internal val downloads = Downloads(this)
    private val imageActions = ImageActions(this)

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        installSplash()
        if (isFinishing) return
        if (relayForeignTaskShare()) return
        if (!passesWebViewPreLaunchChecks()) return

        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.activity_main_webview)
        rootLayout = webView.parent as ViewGroup
        loadingScreen = LoadingScreen(this)
        // Bar visibility follows the Settings toggle; re-synced whenever the loading screen appears.
        loadingScreen.syncProgressBar()

        // Pin window and root backgrounds to the colors the page uses. Without
        // this, the one-frame GPU-surface gap on resume shows the theme's pure
        // black colorBackground through the transparent root: the "black flash".
        // The snapshot overlay is a stronger mask but races PixelCopy's async callback.
        applyBackgroundColors()
        applySystemBarColors()

        logDisplayModes()
        resumeSnapshot = ResumeSnapshot.attachTo(this)

        loadingScreen.updateLogoForTheme()
        loadingScreen.startLogoAnimation()

        // Also called again if the renderer ever dies (see recreateMainWebView).
        setupMainWebView()

        CacheCleaner.sweep(this)
        WelcomeDialog.showIfNeeded(this)

        val restored = savedInstanceState != null && restoreWebViewState(savedInstanceState)
        if (!restored) {
            webView.loadSiteUrl(SiteConfig.HOME_URL)
            // Handled after the initial load starts, so shared text does not trigger a second
            // navigation. Skipped when restored: the launch intent is stale, and re-processing it
            // after a rotation or process restart would re-copy an old share to the clipboard.
            share.handleShareIntent(intent)
        }
    }

    /**
     * Android 12+ shows a system splash screen. Its exit is a 300 ms crossfade
     * into the loading screen, which is painted in the same background color, so
     * the only visible change is the logo fading in. Without a listener some OEMs
     * play their own exit animation (an icon that zooms to fill the screen).
     * Below Android 12 only the theme swap from Theme.AppSplash to AppTheme applies.
     */
    private fun installSplash() {
        val splash = installSplashScreen()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        splash.setOnExitAnimationListener(object : SplashScreen.OnExitAnimationListener {
            override fun onSplashScreenExit(splashScreenViewProvider: SplashScreenViewProvider) {
                ObjectAnimator.ofFloat(splashScreenViewProvider.view, View.ALPHA, 1f, 0f).apply {
                    duration = SPLASH_FADE_MS
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationEnd(animation: Animator) {
                            // The system expects the app to remove the view itself.
                            splashScreenViewProvider.remove()
                        }
                    })
                    start()
                }
            }
        })
    }

    /**
     * Some OEM share paths launch this singleTask activity inside the SENDER's
     * task. Detected by "not the root of our task". The share is handed to
     * [ShareRelayActivity], which forwards it into our own task once it is
     * resumed (see its documentation for why the order matters).
     * Shares with a file stream are excluded: a re-launch would drop their URI grants.
     *
     * @return true if the relay took over and this instance finished.
     */
    private fun relayForeignTaskShare(): Boolean {
        val boot = intent ?: return false
        if (boot.getBooleanExtra(ShareRelayActivity.EXTRA_RELAUNCHED, false) ||
            isTaskRoot ||
            boot.streamUri() != null
        ) {
            return false
        }
        return try {
            // Same task, no flags: a plain start the system always honors.
            startActivity(
                Intent(this, ShareRelayActivity::class.java)
                    .putExtra(ShareRelayActivity.EXTRA_SHARE_INTENT, boot)
            )
            finish()
            true
        } catch (t: Throwable) {
            // Never expected. Boot normally in this task: visible and functional, if in the wrong task.
            Log.e(TAG, "share relay start failed; booting in place", t)
            false
        }
    }

    /**
     * WebView switcher checks that run before any UI is built.
     *  1. A WebView too old for DOCUMENT_START_SCRIPT opens the WebView Manager
     *     non-cancelable. The user must pick or install a newer one.
     *  2. If the last launches all crashed, the chosen WebView is assumed broken
     *     on this device and the user is sent to Settings to pick another.
     *
     * @return false if the checks took over and onCreate must stop.
     */
    private fun passesWebViewPreLaunchChecks(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return true

        if (!WebViewUtil.isSupported()) {
            lateinit var dialog: WebViewManagerDialog
            dialog = WebViewManagerDialog(this) {
                if (dialog.changedWebView()) restartApp() else finish()
            }
            dialog.setCancelable(false)
            dialog.show()
            return false
        }

        if (CrashTracker.hasCrashes()) {
            Log.w(TAG, "Crash threshold reached; bouncing to WebView Manager")
            Toast.makeText(this, R.string.webview_pick_another, Toast.LENGTH_LONG).show()
            CrashTracker.reset()
            startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish()
            return false
        }
        return true
    }

    /**
     * Restores the WebView's back/forward list after the activity was recreated
     * or the process killed, so the open conversation survives. The page is
     * navigated again explicitly because restoreState alone loads without the
     * extra headers, which trips ChatGPT's "install the app" banner.
     *
     * @return true if a valid history was restored.
     */
    private fun restoreWebViewState(saved: Bundle): Boolean = try {
        val history = webView.restoreState(saved)
        val url = history?.currentItem?.url
        // A blob:, about: or data: current entry cannot be loaded again, so it does not
        // count as restored: the caller then opens the home page instead of a blank one.
        if (history != null && history.size > 0 && url != null &&
            (url.startsWith("http://") || url.startsWith("https://"))
        ) {
            webView.loadSiteUrl(url)
            true
        } else {
            false
        }
    } catch (t: Throwable) {
        Log.e(TAG, "WebView state restore failed", t)
        false
    }

    /**
     * Logcat-only: the panel's refresh-rate situation, to tell whether an OEM
     * throttles third-party apps to 60 Hz while Chrome runs at 90/120 Hz.
     */
    @Suppress("DEPRECATION")
    private fun logDisplayModes() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        try {
            val display = windowManager.defaultDisplay
            val current = display.mode
            val supported = display.supportedModes.joinToString(" ") {
                String.format(Locale.US, "%dx%d@%.0f", it.physicalWidth, it.physicalHeight, it.refreshRate)
            }
            Log.d(
                TAG,
                String.format(
                    Locale.US, "display: current mode %dx%d@%.0fHz, supported: %s",
                    current.physicalWidth, current.physicalHeight, current.refreshRate, supported
                )
            )
        } catch (t: Throwable) {
            Log.e(TAG, "display mode query failed", t)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        share.handleShareIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        // Leaving the app invalidates a pending shared file so it can never
        // silently hijack a later file-picker invocation.
        share.pendingShareFileUri = null
    }

    /**
     * Wires up the main WebView: the initial instance from the layout, or the
     * fresh one after a renderer crash.
     */
    private fun setupMainWebView() {
        configureWebView(webView.settings)
        webClients.attachTo(webView)
        downloads.attachTo(webView)
        imageActions.attachTo(webView)
        PageScripts.installAtDocumentStart(webView)
    }

    /** Replaces a dead main WebView with a fresh one (renderer crash path). */
    internal fun recreateMainWebView() {
        val dead = webView
        try {
            rootLayout.removeView(dead)
            // destroy() is deferred: this runs inside onRenderProcessGone of the
            // dead WebView, and destroying it from within that callback crashes
            // some Chromium builds (Android 15).
            rootLayout.post {
                try {
                    dead.destroy()
                } catch (t: Throwable) {
                    Log.e(TAG, "deferred main WebView destroy failed", t)
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "Error destroying dead WebView", t)
        }
        webView = WebView(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
        rootLayout.addView(webView, 0)
        setupMainWebView()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(settings: WebSettings) {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            blockNetworkImage = false

            // Explicit security posture instead of platform defaults, which shift
            // with targetSdk: no local file or content access from the page, Safe
            // Browsing on, and no http:// subresources inside the HTTPS page
            // (chatgpt.com and every auth host are HTTPS-only).
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) safeBrowsingEnabled = true

            cacheMode = WebSettings.LOAD_DEFAULT
            mediaPlaybackRequiresUserGesture = false

            // Needed so window.open() from sign-in and share menus reaches onCreateWindow.
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true

            userAgentString = SiteConfig.USER_AGENT

            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            setSupportZoom(true)
            displayZoomControls = false
        }

        webView.requestFocusFromTouch()
        applyWebViewBackground(webView)

        // Privacy: no third-party cookies on the main page (cross-site trackers
        // embedded in the site). Sign-in popups keep them enabled, because the
        // Google redirect chain needs them to complete.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)

        // chrome://inspect only in experimental builds, never in release.
        if (BuildConfig.EXPERIMENTAL) WebView.setWebContentsDebuggingEnabled(true)

        webView.addJavascriptInterface(WebAppBridge(this, webView), WebAppBridge.NAME)
    }

    /**
     * Solid WebView background matching the theme. The default is opaque white,
     * which flashes on every reload, renderer rebuild and popup navigation
     * before the site's CSS paints. Only that pre-paint flash depends on it.
     */
    internal fun applyWebViewBackground(target: WebView) {
        target.setBackgroundColor(pageBackground)
    }

    /**
     * Pins the window and root layout to the page background. Without it, the
     * one-frame gap when the GPU surface is recreated on resume shows the
     * theme's pure black colorBackground through the transparent root (the
     * "black flash"). This is the reliable first defense; [ResumeSnapshot] is
     * the stronger mask on top when its PixelCopy finishes in time.
     */
    private fun applyBackgroundColors() {
        val color = pageBackground
        try {
            window.setBackgroundDrawable(ColorDrawable(color))
        } catch (t: Throwable) {
            Log.e(TAG, "window setBackgroundDrawable threw", t)
        }
        if (::rootLayout.isInitialized) rootLayout.setBackgroundColor(color)
    }

    /**
     * Re-applies the status and navigation bar colors and icon appearance from
     * the current configuration. The theme sets them once at window creation,
     * and uiMode is in configChanges (to keep the WebView and its conversation
     * alive when the system toggles dark mode), so the activity is not recreated
     * and the bars would keep the old mode. Idempotent when the mode did not change.
     */
    @Suppress("DEPRECATION")
    private fun applySystemBarColors() {
        try {
            val barColor = ContextCompat.getColor(this, R.color.status_bar_bg)
            val lightBars = resources.getBoolean(R.bool.window_light_status_bar)
            window.statusBarColor = barColor
            window.navigationBarColor = barColor

            val decor = window.decorView
            var flags = decor.systemUiVisibility
            flags = flags.withFlag(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR, lightBars)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags = flags.withFlag(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR, lightBars)
            }
            decor.systemUiVisibility = flags
        } catch (t: Throwable) {
            Log.e(TAG, "applySystemBarColors failed", t)
        }
    }

    private fun Int.withFlag(flag: Int, enabled: Boolean): Int =
        if (enabled) this or flag else this and flag.inv()

    /** The color the page itself paints behind its content, per theme. */
    private val pageBackground: Int
        get() = if (isDarkMode) DARK_BACKGROUND else LIGHT_BACKGROUND

    override fun onPause() {
        super.onPause()
        // Reaching pause proves this launch was real and interactive. A process
        // death while backgrounded is then normal lifecycle (OEM task managers
        // kill background processes aggressively), not a WebView crash, so the
        // crash counter is reset here to stop background kills from adding up
        // to a WebView-picker bounce. A genuine crash loop still trips it,
        // because those launches die in the foreground before the first pause.
        CrashTracker.reset()
        // Pause JS timers and layout so a streaming chat does not drain the battery in the background.
        webViewOrNull?.onPause()
        popups.pauseAll()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webViewOrNull?.onResume()
        popups.resumeAll()
    }

    /** Persists the back/forward list so [restoreWebViewState] can reopen the conversation. */
    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        try {
            webViewOrNull?.saveState(outState)
        } catch (t: Throwable) {
            Log.e(TAG, "WebView saveState failed", t)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            // About to be backgrounded: capture now, while the surface is alive, to mask the resume flash.
            resumeSnapshot?.capture()
        } else {
            // Give the WebView time to repaint before the snapshot goes away.
            resumeSnapshot?.scheduleFadeOut(ResumeSnapshot.FADE_OUT_DELAY_MS)
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // uiMode is in configChanges, so the activity and WebView survive a dark-mode
        // switch. Everything that was themed at creation is re-applied by hand.
        if (::loadingScreen.isInitialized) loadingScreen.updateLogoForTheme()
        applyBackgroundColors()
        applySystemBarColors()
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        when {
            popups.closeTop() -> Unit
            webViewOrNull?.canGoBack() == true -> webView.goBack()
            else -> super.onBackPressed()
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!fileChooser.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        fileChooser.onRequestPermissionsResult(requestCode, grantResults)
        webPermissions.onRequestPermissionsResult(requestCode)
        downloads.onRequestPermissionsResult(requestCode, grantResults)
    }

    override fun onDestroy() {
        // A clean destroy is not a crash. Resetting here stops abandoned launches
        // (swipe-away) from counting toward the "pick another WebView" bounce.
        CrashTracker.reset()
        popups.destroyAll()
        webClients.dismissOfflineDialog()
        // Not initialized when onCreate bailed out early (share relay, pre-launch checks).
        if (::loadingScreen.isInitialized) loadingScreen.stopLogoAnimation()
        if (::webView.isInitialized) {
            // Documented teardown order: detach, remove child views, destroy.
            try {
                rootLayout.removeView(webView)
                webView.removeAllViews()
                webView.destroy()
            } catch (e: Exception) {
                Log.e(TAG, "Error destroying WebView", e)
            }
        }
        fileChooser.release()
        share.clearPending()
        super.onDestroy()
    }

    private companion object {
        const val TAG = SiteConfig.LOG_TAG
        const val SPLASH_FADE_MS = 300L

        // ChatGPT's own surface colors (see SiteConfig for the other site specifics).
        const val DARK_BACKGROUND = 0xFF0D0D0D.toInt()
        const val LIGHT_BACKGROUND = 0xFFFFFFFF.toInt()
    }
}
