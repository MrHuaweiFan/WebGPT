package com.webgpt.app.webview;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Crash recovery safety net.
 *
 * Bumps a crash counter in SharedPreferences on every cold start. The
 * {@link com.webgpt.app.MainActivity} resets it once the WebView finishes
 * its first page load, when the activity is paused (background process
 * kills are normal lifecycle, not crashes), and on a clean destroy. If the
 * counter exceeds {@link #MAX_CRASHES}, MainActivity bails to the WebView
 * Manager picker with a toast prompting the user to pick a different
 * WebView.
 *
 * Without this, a bad WebView choice (one whose native lib doesn't support
 * the device's ABI, or whose version is too old) would crash-loop the user
 * out of the app with no recovery path.
 */
public final class CrashTracker {

    private static final String TAG = "CrashTracker";
    private static final String PREF_KEY = "crash_count";
    private static final int MAX_CRASHES = 3;

    private static SharedPreferences prefs;

    private CrashTracker() {}

    public static void init(Context context) {
        prefs = context.getSharedPreferences(com.webgpt.app.App.PREFS_NAME, Context.MODE_PRIVATE);
        increase();
        Log.i(TAG, "Crash count = " + getCount());
    }

    /** Bump the crash counter by 1. Called from Application.onCreate(). */
    public static void increase() {
        if (prefs == null) return;
        prefs.edit().putInt(PREF_KEY, getCount() + 1).apply();
    }

    public static int getCount() {
        return prefs == null ? 0 : prefs.getInt(PREF_KEY, 0);
    }

    public static boolean hasCrashes() {
        return getCount() >= MAX_CRASHES;
    }

    /**
     * Reset the counter. Called after a successful page load and on a clean
     * activity destroy, so abandoned launches (no signal, swipe-away) no
     * longer count as crashes.
     */
    public static void reset() {
        if (prefs == null) return;
        prefs.edit().putInt(PREF_KEY, 0).apply();
    }
}
