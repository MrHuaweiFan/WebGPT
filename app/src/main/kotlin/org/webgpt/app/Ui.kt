package org.webgpt.app

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast

/** True while the system is in night mode. */
internal val Context.isDarkMode: Boolean
    get() = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES

internal fun Context.longToast(message: CharSequence) {
    Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
