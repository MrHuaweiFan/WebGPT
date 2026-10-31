package org.webgpt.app.webview

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import org.webgpt.app.appPrefs

/**
 * Crash recovery safety net.
 *
 * Bumps a crash counter in SharedPreferences on every cold start.
 * [org.webgpt.app.MainActivity] resets it once the WebView finishes its first
 * page load, when the activity is paused (a background process kill is normal
 * lifecycle, not a crash), and on a clean destroy. If the counter reaches
 * [MAX_CRASHES], MainActivity sends the user to the WebView Manager picker
 * with a toast asking them to pick a different WebView.
 *
 * Without this, a bad WebView choice (a native lib that does not support the
 * device ABI, or a version that is too old) would crash-loop the app with no
 * way out.
 */
object CrashTracker {

    private const val TAG = "CrashTracker"
    private const val PREF_KEY = "crash_count"
    private const val MAX_CRASHES = 3

    private var prefs: SharedPreferences? = null

    /** Called from Application.onCreate(). Counts this launch as a possible crash. */
    @JvmStatic
    fun init(context: Context) {
        prefs = context.appPrefs
        increase()
        Log.i(TAG, "Crash count = $count")
    }

    @JvmStatic
    fun increase() {
        prefs?.edit()?.putInt(PREF_KEY, count + 1)?.apply()
    }

    @JvmStatic
    val count: Int
        get() = prefs?.getInt(PREF_KEY, 0) ?: 0

    @JvmStatic
    fun hasCrashes(): Boolean = count >= MAX_CRASHES

    /**
     * Resets the counter. Called after a successful page load and on a clean
     * activity destroy, so abandoned launches (no signal, swipe-away) do not
     * count as crashes.
     */
    @JvmStatic
    fun reset() {
        prefs?.edit()?.putInt(PREF_KEY, 0)?.apply()
    }
}
