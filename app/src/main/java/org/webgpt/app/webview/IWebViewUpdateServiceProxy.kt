package org.webgpt.app.webview

import android.os.IBinder
import android.util.Log
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method

/**
 * InvocationHandler for `android.webkit.IWebViewUpdateService`.
 *
 * The constructor turns the original IBinder into the real service handle with
 * `IWebViewUpdateService.Stub.asInterface(binder)`. Every method is delegated
 * to that real service EXCEPT `waitForAndGetProvider()`: the `packageInfo`
 * field of its result is overwritten with the user's chosen PackageInfo, so
 * WebViewFactory loads the chosen WebView APK instead of the system default.
 */
class IWebViewUpdateServiceProxy(remoteBinder: IBinder, stubClass: Class<*>) : InvocationHandler {

    private val remoteService: Any =
        runCatching {
            stubClass.getMethod("asInterface", IBinder::class.java).invoke(null, remoteBinder)
        }.onFailure {
            Log.e(TAG, "asInterface failed; will delegate raw calls to the IBinder", it)
        }.getOrNull() ?: remoteBinder

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        // Pass `args` straight through: methods with parameters (equals, hashCode,
        // getWebViewPackagesForUser(int), ...) must keep working.
        val result = method.invokeOn(remoteService, args)
        if (method.name == "waitForAndGetProvider" && result != null) {
            swapPackageInfo(result)
        }
        return result
    }

    private fun swapPackageInfo(response: Any) {
        val chosen = WebViewUtil.getCustomProvider()
        if (chosen == null) {
            Log.w(TAG, "waitForAndGetProvider: no custom provider chosen, returning default")
            return
        }
        try {
            response.javaClass.getDeclaredField("packageInfo").apply { isAccessible = true }.set(response, chosen)
            Log.i(TAG, "Swapped packageInfo to ${chosen.packageName}")
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to swap packageInfo", t)
        }
    }

    private companion object {
        const val TAG = "IWebViewUpdateServiceProxy"
    }
}
