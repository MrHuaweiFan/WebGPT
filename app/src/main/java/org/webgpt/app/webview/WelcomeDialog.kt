package org.webgpt.app.webview

import android.app.Activity
import android.content.Context
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.webgpt.app.R
import org.webgpt.app.appPrefs

/**
 * First-launch welcome dialog.
 *
 * Explains that the app has a hidden Settings menu (where the WebView switcher
 * lives). It is shown once: after the user taps "Understood" the preference
 * `welcome_shown` is set and the dialog never appears again.
 *
 * Call [showIfNeeded] from MainActivity.onCreate() after the WebView switcher
 * pre-launch checks pass.
 */
object WelcomeDialog {

    private const val PREF_KEY = "welcome_shown"

    /** True if the welcome dialog has never been shown to this user. */
    @JvmStatic
    fun needsToShow(context: Context): Boolean = !context.appPrefs.getBoolean(PREF_KEY, false)

    /**
     * Shows the dialog if it has not been shown yet. It is non-cancelable: the
     * user must tap "Understood", which is what marks it as shown.
     */
    @JvmStatic
    fun showIfNeeded(activity: Activity) {
        if (!needsToShow(activity)) return
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.welcome_title)
            .setMessage(R.string.welcome_body)
            .setCancelable(false)
            .setPositiveButton(R.string.understood) { dialog, _ ->
                activity.appPrefs.edit().putBoolean(PREF_KEY, true).apply()
                dialog.dismiss()
            }
            .show()
    }
}
