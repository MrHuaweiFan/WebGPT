package org.webgpt.app.webview

import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Calls this method on [target] and rethrows the callee's own exception.
 *
 * Plain `Method.invoke` wraps every exception in an [InvocationTargetException].
 * Inside a dynamic proxy that wrapper is not declared by the interface, so the
 * caller would see an `UndeclaredThrowableException` instead of the real error
 * (for example a `RemoteException`). The three proxy handlers in this package
 * all delegate through this function.
 */
internal fun Method.invokeOn(target: Any, args: Array<out Any?>?): Any? =
    try {
        invoke(target, *(args ?: emptyArray<Any?>()))
    } catch (e: InvocationTargetException) {
        throw e.targetException ?: e
    }
