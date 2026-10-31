package org.webgpt.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Opens [url] in the user's default browser, or shows a toast if nothing can handle it. */
internal fun Context.openInBrowser(url: String) {
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        Toast.makeText(this, "No browser available", Toast.LENGTH_SHORT).show()
    }
}
