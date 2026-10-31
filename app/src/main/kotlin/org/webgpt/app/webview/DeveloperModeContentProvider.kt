package org.webgpt.app.webview

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.util.Log

/**
 * Fake "developer mode" flags provider.
 *
 * Chromium WebView queries a content provider with authority
 * `<own-package>.DeveloperModeContentProvider` for developer-mode flag
 * overrides. [Hooker.hookInstallContentProviders] installs this class under the
 * chosen WebView package's name, so we get to fabricate the answers.
 *
 * Two flags are returned as enabled:
 *  - `ignore-gpu-blocklist` forces GPU acceleration even on blocklisted drivers.
 *    Useful on Android TV and low-end devices that would otherwise fall back to
 *    software rendering.
 *  - `WebViewSurfaceControl` composites through SurfaceControl, which is faster
 *    on devices that support it.
 *
 * Chromium only ever calls `query`, so the write methods are not supported.
 */
class DeveloperModeContentProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("flagName", "flagState"), FLAGS.size)
        FLAGS.forEach { (name, enabled) -> cursor.addRow(arrayOf<Any>(name, if (enabled) 1 else 0)) }
        Log.i(TAG, "Returning flags: $FLAGS")
        return cursor
    }

    override fun getType(uri: Uri): String = "content"

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("insert not supported")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("delete not supported")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("update not supported")

    private companion object {
        const val TAG = "DevModeContentProvider"
        val FLAGS = mapOf(
            "ignore-gpu-blocklist" to true,
            "WebViewSurfaceControl" to true
        )
    }
}
