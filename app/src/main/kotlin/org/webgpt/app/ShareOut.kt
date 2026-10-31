package org.webgpt.app

import android.app.Activity
import android.content.Intent
import android.util.Base64
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

private const val TAG = SiteConfig.LOG_TAG

/** Opens the system share sheet with [text] and/or [url]. */
internal fun Activity.shareText(text: String?, url: String?) {
    val message = listOfNotNull(
        text?.takeIf { it.isNotEmpty() },
        url?.takeIf { it.isNotEmpty() },
    ).joinToString("\n")
    if (message.isEmpty()) return
    try {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, message)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(Intent.createChooser(send, "Share"))
    } catch (e: Exception) {
        Log.e(TAG, "shareText failed", e)
        Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show()
    }
}

/**
 * Decodes [dataUrl] into the cache directory and shares the file through
 * FileProvider. The decoding runs on a background thread.
 */
internal fun Activity.shareFile(title: String?, dataUrl: String?, fileName: String?, mime: String?) {
    Log.i(TAG, "shareFile reached: $fileName")
    if (dataUrl == null || !dataUrl.startsWith("data:")) return
    val comma = dataUrl.indexOf(',')
    if (comma < 0) return

    Thread {
        try {
            val realMime = if (mime != null && mime.contains('/')) mime else FileNames.mimeOfDataUrl(dataUrl)
            val extension = FileNames.extensionForMime(realMime)
            var name = FileNames.sanitizeShared(fileName?.takeIf { it.isNotEmpty() } ?: "shared_file")
            if (extension != null && !name.lowercase(Locale.ROOT).endsWith(".$extension")) {
                name += ".$extension"
            }
            val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
            val file = File(cacheDir, name).apply { writeBytes(bytes) }
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
            runOnUiThread {
                try {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = realMime
                        putExtra(Intent.EXTRA_STREAM, uri)
                        if (!title.isNullOrEmpty()) putExtra(Intent.EXTRA_TEXT, title)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(Intent.createChooser(send, "Share"))
                } catch (e: Exception) {
                    Log.e(TAG, "shareFile intent failed", e)
                    longToast("Share failed: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "shareFile failed", e)
            runOnUiThread { longToast("Share failed: ${e.message}") }
        }
    }.start()
}
