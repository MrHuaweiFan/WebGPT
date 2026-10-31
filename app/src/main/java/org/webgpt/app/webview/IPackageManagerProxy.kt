package org.webgpt.app.webview

import android.content.Context
import android.content.pm.PackageManager
import org.webgpt.app.App
import org.webgpt.app.appPrefs
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * InvocationHandler for `android.content.pm.IPackageManager`.
 *
 * Lies about two things:
 *  - `hasSystemFeature("android.hardware.audio.low_latency")` returns true
 *    (Chromium WebView enables low-latency audio when this is set).
 *  - `getComponentEnabledSetting(...DeveloperModeState...)` returns ENABLED, but
 *    only when the user turned on "Optimize WebView performance" in the picker.
 *
 * Every other method is delegated to the real IPackageManager.
 */
class IPackageManagerProxy(
    private val context: Context,
    private val packageManager: Any
) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        val firstArg = args?.firstOrNull()?.toString()
        when (method.name) {
            "hasSystemFeature" ->
                if (firstArg == FEATURE_LOW_LATENCY_AUDIO) return true
            "getComponentEnabledSetting" ->
                if (firstArg != null && DEVELOPER_MODE_STATE in firstArg && isOptimizeEnabled()) {
                    return PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                }
        }
        return method.invokeOn(packageManager, args)
    }

    private fun isOptimizeEnabled(): Boolean =
        context.appPrefs.getBoolean(App.PREF_WEBVIEW_OPTIMIZE, false)

    private companion object {
        const val FEATURE_LOW_LATENCY_AUDIO = "android.hardware.audio.low_latency"
        const val DEVELOPER_MODE_STATE = "devui.DeveloperModeState"
    }
}
