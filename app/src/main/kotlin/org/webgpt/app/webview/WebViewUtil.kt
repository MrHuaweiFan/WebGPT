package org.webgpt.app.webview

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.WebViewFeature
import org.webgpt.app.appPrefs

/**
 * WebView provider registry and selection logic.
 *
 * On API 26+ Android lets an app ask which WebView package the system would
 * load right now ([WebView.getCurrentWebViewPackage]). This object captures
 * that as [defaultProvider], then scans [ALLOWED_PROVIDERS] to discover what
 * else is installed ([availableProviders]).
 *
 * The user's choice is stored in SharedPreferences under [PREF_WEBVIEW_IMPL].
 * When a non-default provider is selected, [Hooker.hookServiceManagerService]
 * swaps the cached "webviewupdate" binder so that
 * `IWebViewUpdateService.waitForAndGetProvider()` returns the chosen package.
 *
 * The hook is process-scoped and does not affect any other app on the device.
 */
object WebViewUtil {

    private const val TAG = "WebViewUtil"

    /** SharedPreferences key for the user's chosen WebView package name. */
    const val PREF_WEBVIEW_IMPL = "webview_implementation"

    /** Meta-data key Android itself uses to recognise a WebView provider. */
    private const val WEBVIEW_LIBRARY_META = "com.android.webview.WebViewLibrary"

    /**
     * Packages recognised as WebView providers. MUST be kept in sync with the
     * `<queries>` block in AndroidManifest.xml, otherwise `getPackageInfo()`
     * throws NameNotFoundException on Android 11+.
     */
    val ALLOWED_PROVIDERS = listOf(
        "com.google.android.webview",
        "com.google.android.webview.beta",
        "com.google.android.webview.dev",
        "com.google.android.webview.canary",
        "com.android.webview",
        "com.android.chrome",
        "com.chrome.beta",
        "com.chrome.dev",
        "com.chrome.canary",
        "com.amazon.webview.chromium",
        "com.huawei.webview",
        "us.spotco.mulch_wv",
        "com.thorium.webview",
    )

    /**
     * Flags for `getPackageInfo` (value 1216, same as BetterXC):
     *  - GET_META_DATA: needed to read the `com.android.webview.WebViewLibrary`
     *    meta-data that marks a package as a WebView provider.
     *  - GET_SHARED_LIBRARY_FILES: CRITICAL. `WebViewFactory.getProviderByPath()`
     *    builds the WebView ClassLoader from `applicationInfo.sourceDir` plus
     *    `applicationInfo.sharedLibraryFiles`. Without the flag that field is
     *    null and loading fails with a ClassNotFoundException.
     *  - GET_SIGNATURES: kept for parity with BetterXC; some framework paths
     *    may verify the WebView signature before loading.
     */
    @Suppress("DEPRECATION")
    private val PROVIDER_QUERY_FLAGS = PackageManager.GET_META_DATA or
        PackageManager.GET_SHARED_LIBRARY_FILES or
        PackageManager.GET_SIGNATURES

    /** LinkedHashMap so the order shown in the picker is stable. */
    private val providers = LinkedHashMap<String, PackageInfo>()
    private var initialized = false

    /** What Android would use by default. */
    @JvmStatic
    var defaultProvider: PackageInfo? = null
        private set

    /** The user's choice, or null when the default is used. */
    @JvmStatic
    var customProvider: PackageInfo? = null
        private set

    /** What is actually in use right now. */
    @JvmStatic
    val currentProvider: PackageInfo?
        get() = customProvider ?: defaultProvider

    @JvmStatic
    val availableProviders: Map<String, PackageInfo>
        get() = providers

    /**
     * Captures the default provider, scans for installed alternatives, reads
     * the user's selection and, if a non-default provider was chosen, installs
     * the ServiceManager hook.
     *
     * Must run in Application.onCreate() before any WebView is constructed.
     * No-op below API 26, where `getCurrentWebViewPackage` does not exist.
     */
    @JvmStatic
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        initialized = true

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.i(TAG, "API < 26; WebView switcher disabled.")
            return
        }

        val default = WebView.getCurrentWebViewPackage()
        if (default == null) {
            Log.w(TAG, "WebView.getCurrentWebViewPackage() returned null")
            return
        }
        defaultProvider = default
        reloadProviderList(context)

        var selected = context.appPrefs.getString(PREF_WEBVIEW_IMPL, default.packageName)
        if (selected == "default") selected = default.packageName

        // An unknown or default selection needs no hook.
        val custom = selected?.takeIf { it != default.packageName }?.let { providers[it] }
        if (custom != null) {
            customProvider = custom
            try {
                Hooker.hookServiceManagerService()
            } catch (t: Throwable) {
                Log.e(TAG, "hookServiceManagerService failed; falling back to default", t)
                customProvider = null
                Toast.makeText(context, "Failed to switch WebView: ${t.message}", Toast.LENGTH_LONG).show()
            }
        }
        Log.i(TAG, "Selected WebView: ${currentProvider?.packageName ?: "?"}")
    }

    /** Rescans installed providers. Call when the user may have installed or removed one. */
    @JvmStatic
    @Suppress("DEPRECATION")
    fun reloadProviderList(context: Context) {
        providers.clear()
        defaultProvider?.let { providers[it.packageName] = it }
        for (pkg in ALLOWED_PROVIDERS) {
            val info = try {
                context.packageManager.getPackageInfo(pkg, PROVIDER_QUERY_FLAGS)
            } catch (e: PackageManager.NameNotFoundException) {
                continue // not installed
            }
            val app = info.applicationInfo ?: continue
            if (!app.enabled) continue
            if (app.metaData?.getString(WEBVIEW_LIBRARY_META) == null) continue
            providers[pkg] = info
        }
    }

    /** True if the current WebView supports DOCUMENT_START_SCRIPT (a proxy for "new enough"). */
    @JvmStatic
    fun isSupported(): Boolean =
        try {
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        } catch (t: Throwable) {
            false
        }

    /** The provider's app label, or its package name if the label cannot be read. */
    @JvmStatic
    fun label(context: Context, info: PackageInfo): CharSequence =
        runCatching { info.applicationInfo?.let { context.packageManager.getApplicationLabel(it) } }
            .getOrNull() ?: info.packageName
}
