package org.webgpt.app.webview

import android.os.IBinder
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * InvocationHandler for the proxied IBinder stored in
 * `ServiceManager.sCache["webviewupdate"]`.
 *
 * Every IBinder method is delegated to the original binder EXCEPT
 * `queryLocalInterface("android.webkit.IWebViewUpdateService")`, which returns
 * an [IWebViewUpdateServiceProxy]. That proxy intercepts
 * `waitForAndGetProvider()` and swaps the packageInfo.
 */
class IServiceManagerProxy(private val remoteBinder: IBinder) : InvocationHandler {

    override fun invoke(proxy: Any?, method: Method, args: Array<out Any?>?): Any? {
        if (method.name == "queryLocalInterface" &&
            args?.firstOrNull()?.toString() == UPDATE_SERVICE_INTERFACE
        ) {
            return Proxy.newProxyInstance(
                remoteBinder.javaClass.classLoader,
                arrayOf(Class.forName(UPDATE_SERVICE_INTERFACE)),
                IWebViewUpdateServiceProxy(remoteBinder, Class.forName("$UPDATE_SERVICE_INTERFACE\$Stub"))
            )
        }
        // transact, pingInterface, getInterfaceDescriptor, asBinder, isBinderAlive, ...
        return method.invokeOn(remoteBinder, args)
    }

    private companion object {
        const val UPDATE_SERVICE_INTERFACE = "android.webkit.IWebViewUpdateService"
    }
}
