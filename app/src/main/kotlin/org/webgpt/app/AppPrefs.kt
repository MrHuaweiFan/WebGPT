package org.webgpt.app

import android.content.Context
import android.content.SharedPreferences

/** The app's single SharedPreferences file. */
internal val Context.appPrefs: SharedPreferences
    get() = getSharedPreferences(App.PREFS_NAME, Context.MODE_PRIVATE)
