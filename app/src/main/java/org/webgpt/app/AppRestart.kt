package org.webgpt.app

import android.app.Activity
import android.content.Intent
import android.os.Process

/**
 * Relaunches the app in a fresh process. A new process is required to load a
 * different WebView implementation, because the WebView provider is fixed for
 * the lifetime of a process.
 */
internal fun Activity.restartApp() {
    packageManager.getLaunchIntentForPackage(packageName)?.let { launch ->
        launch.addFlags(
            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        )
        startActivity(launch)
    }
    finishAffinity()
    Process.killProcess(Process.myPid())
}
