package org.webgpt.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.util.Log
import android.widget.Toast

/**
 * Copies shared text so the user can paste it into the prompt box. Android 13+
 * shows its own "Copied" overlay, so the toast is only needed below that.
 */
internal fun Context.copyShareToClipboard(text: String) {
    try {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText("WebGPT", text))
        Log.i("WebGPTApp", "Text copied to clipboard")
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "Text copied — paste it into WebGPT", Toast.LENGTH_LONG).show()
        }
    } catch (e: Exception) {
        Log.e("WebGPTApp", "copyShareToClipboard failed", e)
    }
}
