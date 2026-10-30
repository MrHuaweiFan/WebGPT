package org.webgpt.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast

private const val TAG = "WebGPTApp"

/**
 * Puts [text] on the clipboard without any feedback of its own.
 *
 * @return true if the clipboard accepted it.
 */
internal fun Context.copyText(text: String): Boolean = try {
    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("WebGPT", text)) != null
} catch (e: Exception) {
    Log.e(TAG, "copyText failed", e)
    false
}

/**
 * Copies shared text so the user can paste it into the prompt box. Android 13+
 * shows its own "Copied" overlay, so the toast is only needed below that.
 */
internal fun Context.copyShareToClipboard(text: String) {
    if (!copyText(text)) return
    Log.i(TAG, "Text copied to clipboard")
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        Toast.makeText(this, "Text copied — paste it into WebGPT", Toast.LENGTH_LONG).show()
    }
}
