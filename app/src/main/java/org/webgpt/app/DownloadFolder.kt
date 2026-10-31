package org.webgpt.app

import android.content.ContentValues
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Writes new files into the public Download folder: through MediaStore on
 * Android 10 and later, as plain files below that. Every save in the app goes
 * through here (file downloads, blob exports, images).
 */
internal class DownloadFolder(private val activity: MainActivity) {

    /** Where a file is written. [legacyFile] is set below Android 10 only. */
    class Target(val stream: OutputStream, val legacyFile: File?)

    /** Opens an output stream for a new file. [mime] is left out of the entry when it is null. */
    fun open(displayName: String, mime: String?): Target {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                if (mime != null) put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }
            val resolver = activity.contentResolver
            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val item = resolver.insert(collection, values) ?: throw IOException("Failed to create download entry")
            val stream = resolver.openOutputStream(item) ?: throw IOException("Failed to open output stream")
            return Target(stream, null)
        }
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, displayName)
        return Target(FileOutputStream(file), file)
    }

    /** Below Android 10 the file bypasses MediaStore, so tell the media scanner about it. */
    fun scanLegacy(file: File?, mime: String) {
        if (file == null) return
        runCatching {
            MediaScannerConnection.scanFile(activity, arrayOf(file.absolutePath), arrayOf(mime), null)
        }.onFailure { Log.w(SiteConfig.LOG_TAG, "MediaScanner failed", it) }
    }
}
