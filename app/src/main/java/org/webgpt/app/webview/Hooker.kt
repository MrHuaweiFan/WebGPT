package org.webgpt.app.webview

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.os.IBinder
import android.util.Log
import org.webgpt.app.BuildConfig
import java.lang.reflect.Proxy

/**
 * The three runtime hooks that make the in-app WebView switcher work.
 *
 *  1. [hookServiceManagerService] is THE SWITCH. It replaces the cached IBinder for
 *     "webviewupdate" in `android.os.ServiceManager.sCache` with a Proxy. When
 *     WebViewFactory asks for `android.webkit.IWebViewUpdateService`, the proxy
 *     returns another Proxy that swaps the `packageInfo` returned by
 *     `waitForAndGetProvider()` with the user's chosen PackageInfo.
 *  2. [hookPackageManager] is always installed. It replaces
 *     `ActivityThread.sPackageManager` with a Proxy (see [IPackageManagerProxy]).
 *  3. [hookInstallContentProviders] is optional. It installs a synthetic provider
 *     with authority `<webview-package>.DeveloperModeContentProvider`, which
 *     Chromium WebView queries for developer-mode flag overrides.
 *
 * All hooks are process-scoped and do not affect any other app.
 */
object Hooker {

    private const val TAG = "Hooker"
    private const val WEBVIEW_UPDATE_SERVICE_NAME = "webviewupdate"
    private const val ACTIVITY_THREAD = "android.app.ActivityThread"

    // 1. The WebView switch

    @JvmStatic
    fun hookServiceManagerService() {
        try {
            val serviceManager = Class.forName("android.os.ServiceManager")
            val originalBinder = serviceManager.getMethod("getService", String::class.java)
                .invoke(null, WEBVIEW_UPDATE_SERVICE_NAME) as IBinder?
            if (originalBinder == null) {
                Log.w(TAG, "getService($WEBVIEW_UPDATE_SERVICE_NAME) returned null; not hooking")
                return
            }
            val binderProxy = Proxy.newProxyInstance(
                serviceManager.classLoader,
                arrayOf(IBinder::class.java),
                IServiceManagerProxy(originalBinder)
            )
            @Suppress("UNCHECKED_CAST")
            val cache = serviceManager.getDeclaredField("sCache")
                .apply { isAccessible = true }
                .get(null) as MutableMap<String, Any>
            cache[WEBVIEW_UPDATE_SERVICE_NAME] = binderProxy
            Log.i(TAG, "ServiceManager hooked for $WEBVIEW_UPDATE_SERVICE_NAME")
        } catch (t: Throwable) {
            Log.e(TAG, "hookServiceManagerService failed", t)
            throw RuntimeException("Failed to hook ServiceManager", t)
        }
    }

    // 2. PackageManager proxy

    @JvmStatic
    fun hookPackageManager(context: Context) {
        try {
            val activityThreadClass = Class.forName(ACTIVITY_THREAD)
            val activityThread = activityThreadClass.getDeclaredMethod("currentActivityThread").invoke(null)

            val sPackageManager = activityThreadClass.getDeclaredField("sPackageManager")
                .apply { isAccessible = true }
            val originalPM = sPackageManager.get(activityThread)
            if (originalPM == null) {
                Log.w(TAG, "sPackageManager is null; not hooking")
                return
            }

            val iPackageManager = Class.forName("android.content.pm.IPackageManager")
            val pmProxy = Proxy.newProxyInstance(
                iPackageManager.classLoader,
                arrayOf(iPackageManager),
                IPackageManagerProxy(context, originalPM)
            )
            sPackageManager.set(activityThread, pmProxy)

            // Also overwrite mPM on the ApplicationPackageManager held by `context`.
            val pm = context.packageManager
            try {
                pm.javaClass.getDeclaredField("mPM").apply { isAccessible = true }.set(pm, pmProxy)
            } catch (e: NoSuchFieldException) {
                // Some Android versions rename this field. Best effort, not fatal.
                Log.w(TAG, "mPM field not found on ${pm.javaClass.name}", e)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "hookPackageManager failed", t)
        }
    }

    // 3. Synthetic DeveloperModeContentProvider

    @JvmStatic
    fun hookInstallContentProviders(context: Context) {
        try {
            val activityThreadClass = Class.forName(ACTIVITY_THREAD)
            val activityThread = activityThreadClass.getDeclaredField("sCurrentActivityThread")
                .apply { isAccessible = true }
                .get(null)
            if (activityThread == null) {
                Log.w(TAG, "sCurrentActivityThread is null; not hooking provider")
                return
            }

            val provider = WebViewUtil.currentProvider
            if (provider == null) {
                Log.w(TAG, "No current provider; not hooking provider")
                return
            }
            val authority = "${provider.packageName}.DeveloperModeContentProvider"

            activityThreadClass
                .getDeclaredMethod("installContentProviders", Context::class.java, List::class.java)
                .apply { isAccessible = true }
                .invoke(activityThread, context, listOf(createProviderInfo(context, authority)))
            Log.i(TAG, "Installed DeveloperModeContentProvider for $authority")
        } catch (t: Throwable) {
            Log.e(TAG, "hookInstallContentProviders failed", t)
        }
    }

    private fun createProviderInfo(context: Context, authority: String) = ProviderInfo().apply {
        name = DeveloperModeContentProvider::class.java.name
        packageName = BuildConfig.APPLICATION_ID
        this.authority = authority
        applicationInfo = try {
            context.packageManager.getApplicationInfo(context.packageName, 0)
        } catch (e: PackageManager.NameNotFoundException) {
            // Should never happen: we are asking about our own package.
            Log.wtf(TAG, "Our own package not found?", e)
            context.applicationInfo
        }
    }
}
