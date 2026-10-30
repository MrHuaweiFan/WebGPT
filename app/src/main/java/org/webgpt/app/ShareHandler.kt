package org.webgpt.app

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread

/** The stream attached to a share intent, or null if it is missing or not a Uri. */
@Suppress("DEPRECATION")
internal fun Intent.streamUri(): Uri? = getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri

/**
 * Receives text and files shared into the app (ACTION_SEND, ACTION_PROCESS_TEXT)
 * and gets them into the site's composer.
 *
 *  - Text is copied to the clipboard, then the composer is focused and the
 *    keyboard opened so the user can paste.
 *  - A file is copied to the cache dir and then attached automatically by
 *    feeding it to the composer as an HTML5 drop (base64 "drop injection").
 *    The cached copy is also kept as [pendingShareFileUri], a fallback for the
 *    site's own "+ → Files" flow, which the file chooser answers without
 *    showing the system picker.
 *
 * The fallback is a transaction: it is only handed to the MAIN WebView's
 * chooser and only within [SHARE_ATTACH_WINDOW_MS] of [pendingShareReceivedAt].
 * A stale share can therefore never attach to a much later picker, and a popup
 * can never consume it.
 */
class ShareHandler(private val activity: MainActivity) {

    /** Cached copy of the shared file. Cleared in MainActivity.onStop. */
    @Volatile var pendingShareFileUri: Uri? = null

    /** elapsedRealtime when [pendingShareFileUri] was set. */
    @Volatile var pendingShareReceivedAt = 0L

    /** A shared file is waiting for the page to finish loading. */
    @Volatile var pendingAutoAttach = false

    /** Shared text is waiting for the page to finish loading. */
    @Volatile var pendingAutoFocusText = false

    /** The file held as base64 until it is dropped into the composer. */
    @Volatile var pendingFileB64: String? = null
    @Volatile var pendingFileName: String? = null
    @Volatile var pendingFileMime: String? = null

    /**
     * When a file was last injected. The site re-opens its file input after a
     * programmatic attach, and answering that with the pending file would
     * attach it twice and trip ChatGPT's attachment limit.
     */
    @Volatile var lastFileInjectionAt = 0L

    /** Handles an incoming ACTION_SEND or ACTION_PROCESS_TEXT intent. */
    fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        Log.i(TAG, "handleShareIntent: action=$action, type=${intent.type}")
        markShareActive()

        var text: String? = null
        var fileUri: Uri? = null
        var fileMime: String? = null
        when (action) {
            Intent.ACTION_SEND -> {
                val type = intent.type
                val extraText = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (type != null && type.startsWith("text/") && extraText != null) {
                    text = extraText
                } else {
                    fileUri = intent.streamUri()
                    fileMime = type ?: "*/*"
                }
            }
            Intent.ACTION_PROCESS_TEXT ->
                text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        }

        when {
            text != null -> handleSharedText(text)
            fileUri != null -> handleSharedFile(fileUri, fileMime)
        }
    }

    /**
     * Shared text is copied to the clipboard and the user pastes it. The page is
     * either still loading (cold share-in) or already open (onNewIntent), so no
     * reload is needed. The composer is focused only once the SPA has really
     * rendered it: onPageFinished fires too early, and the page steals focus
     * back while it finishes, which closes the keyboard.
     */
    private fun handleSharedText(text: String) {
        // Shared content is only logged in experimental builds (privacy).
        if (BuildConfig.EXPERIMENTAL) {
            Log.i(TAG, "handleSharedText: ${text.take(LOG_PREVIEW_CHARS)}${if (text.length > LOG_PREVIEW_CHARS) "..." else ""}")
        }
        activity.copyShareToClipboard(text)
        if (activity.webViewOrNull == null || activity.isFinishing) return
        if (activity.initialLoadComplete) {
            waitForComposerReady(20_000, ::settleComposerAfterAutoAttach)
        } else {
            pendingAutoFocusText = true
        }
    }

    /**
     * Copies the shared file into the cache dir on a worker thread, then queues
     * the automatic attach. Files over [MAX_INJECT_BYTES] are not injected; the
     * user attaches those through "+ → Files" and the chooser hands the file over.
     */
    private fun handleSharedFile(fileUri: Uri, mime: String?) {
        Log.i(TAG, "handleSharedFile: $fileUri ($mime)")
        thread(name = "share-file-copy") {
            try {
                val copy = copyToCache(fileUri, mime)
                if (copy == null) {
                    activity.runOnUiThread { activity.longToast("Cannot read the shared file") }
                    return@thread
                }
                val sharedUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", copy)
                val b64 = if (copy.length() < MAX_INJECT_BYTES) {
                    Base64.encodeToString(copy.readBytes(), Base64.NO_WRAP)
                } else {
                    null
                }
                val resolvedMime = if (mime != null && '/' in mime) {
                    mime
                } else {
                    val ext = MimeTypeMap.getFileExtensionFromUrl("file://x/${copy.name}")
                    MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
                }
                activity.runOnUiThread { onSharedFileReady(sharedUri, copy.name, resolvedMime, b64) }
            } catch (e: Exception) {
                Log.e(TAG, "handleSharedFile failed", e)
                activity.runOnUiThread { activity.longToast("Failed to process file: ${e.message}") }
            }
        }
    }

    /** Runs on the UI thread once the copy is ready. */
    private fun onSharedFileReady(sharedUri: Uri, name: String, mime: String?, b64: String?) {
        pendingShareFileUri = sharedUri // fallback for the manual "+ → Files" flow
        pendingShareReceivedAt = SystemClock.elapsedRealtime()
        if (b64 != null) {
            pendingFileB64 = b64
            pendingFileName = name
            pendingFileMime = mime ?: "application/octet-stream"
            // Tapping "+" now would cancel the automatic injection.
            activity.longToast("Please wait — the file will attach automatically")
        }
        if (activity.initialLoadComplete && activity.webViewOrNull != null) {
            if (b64 != null) waitForComposerReady(25_000, ::runFileDropSequence)
        } else {
            pendingAutoAttach = true
        }
        activity.webViewOrNull?.postDelayed({
            if (pendingShareFileUri != null && !activity.isFinishing) {
                activity.longToast("Couldn't attach automatically — tap + and choose Files")
            }
        }, 25_000)
    }

    /** Copies [uri] into the cache dir under a sanitized name. Returns null if it cannot be opened. */
    private fun copyToCache(uri: Uri, mime: String?): File? {
        var name = "shared_file_${System.currentTimeMillis()}"
        val originalName = displayNameOf(uri)
        if (!originalName.isNullOrEmpty()) {
            // The display name comes from the sharing app's provider and must never be trusted as a path.
            name = FileNames.sanitizeShared(originalName)
        } else {
            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
            if (!ext.isNullOrEmpty()) name += ".$ext"
        }
        val out = File(activity.cacheDir, name)
        val input = activity.contentResolver.openInputStream(uri) ?: return null
        input.use { source -> FileOutputStream(out).use { source.copyTo(it) } }
        return out
    }

    private fun displayNameOf(uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            activity.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) result = cursor.getString(idx)
                }
            }
        }
        return result ?: uri.lastPathSegment
    }

    // <<MORE-SHARE>>

    companion object {
        private const val TAG = "WebGPTApp"
        private const val LOG_PREVIEW_CHARS = 80
        private const val MAX_INJECT_BYTES = 50L * 1024 * 1024

        /** How long after a share the file chooser may hand over the pending file. */
        const val SHARE_ATTACH_WINDOW_MS = 60_000L
    }
}
