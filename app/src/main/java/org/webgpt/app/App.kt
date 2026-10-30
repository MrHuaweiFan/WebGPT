package org.webgpt.app

import android.app.Application
import android.util.Log
import org.webgpt.app.webview.CrashTracker
import org.webgpt.app.webview.Hooker
import org.webgpt.app.webview.WebViewUtil

/**
 * Application subclass.
 *
 * The WebView switcher MUST be initialized here, before any Activity can
 * construct a WebView. Android's WebViewFactory loads the WebView
 * implementation lazily on the first `WebView(ctx)` call. After that the
 * provider is locked in for the process and only a process kill can change it.
 *
 * Boot order:
 *  1. [WebViewUtil.init] captures the default provider, reads the user's choice
 *     and installs the ServiceManager hook if a custom provider is selected.
 *  2. [Hooker.hookPackageManager] is always installed.
 *  3. [Hooker.hookInstallContentProviders] runs only when "Optimize WebView
 *     performance" is on, so the chosen WebView gets GPU blocklist bypass and
 *     SurfaceControl.
 *  4. [CrashTracker.init] bumps the crash counter. It is reset after the first
 *     successful page load.
 *
 * The hooks log and swallow their own failures, so no extra try/catch is needed
 * around them.
 */
class App : Application() {

    override fun onCreate() {
        super.onCreate()

        // No-op below API 26: WebView.getCurrentWebViewPackage() does not exist there.
        runCatching { WebViewUtil.init(applicationContext) }
            .onFailure { Log.e(TAG, "WebViewUtil.init failed", it) }

        Hooker.hookPackageManager(applicationContext)

        if (getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_WEBVIEW_OPTIMIZE, false)) {
            Hooker.hookInstallContentProviders(applicationContext)
        }

        CrashTracker.init(applicationContext)
    }

    companion object {
        private const val TAG = "WebGPTApp"
        const val PREFS_NAME = "webgpt_prefs"
        const val PREF_WEBVIEW_OPTIMIZE = "webview_optimize"
    }
}
