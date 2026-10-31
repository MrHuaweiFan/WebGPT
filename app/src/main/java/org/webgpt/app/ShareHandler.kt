package org.webgpt.app

import android.content.Intent
import android.net.Uri
import android.os.Parcelable
import android.os.SystemClock
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.view.inputmethod.InputMethodManager
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import kotlin.concurrent.thread
import org.json.JSONObject

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

    /**
     * Forgets everything a share left pending: the manual-attach Uri, the queued
     * pipelines and the base64 payload (up to about 67 MB of text for a 50 MiB file).
     * Runs when the activity is destroyed and when the attach window is seen expired.
     * onStop clears only the Uri, so a quick switch away and back during a cold-start
     * share still attaches.
     */
    fun clearPending() {
        pendingShareFileUri = null
        pendingShareReceivedAt = 0L
        pendingAutoAttach = false
        pendingAutoFocusText = false
        pendingFileB64 = null
        pendingFileName = null
        pendingFileMime = null
    }

    /** Handles an incoming ACTION_SEND or ACTION_PROCESS_TEXT intent. */
    fun handleShareIntent(intent: Intent?) {
        val action = intent?.action ?: return
        // A launcher tap (ACTION_MAIN) is not a share. Arming the focus-guard bypass for it
        // would let the keyboard open by itself in an existing chat.
        if (action != Intent.ACTION_SEND && action != Intent.ACTION_PROCESS_TEXT) return
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

    /**
     * Tells the page's focus guard that a share is in progress, so it lets the
     * composer take focus for the next few seconds.
     */
    fun markShareActive() {
        val webView = activity.webViewOrNull ?: return
        if (activity.isFinishing) return
        webView.evaluateJavascript("try{window.__webgptShareActiveUntil=Date.now()+6000;}catch(e){}", null)
    }

    /**
     * Starts the share pipelines that were deferred because the page was still loading.
     * Called from both the DOM-ready signal and onPageFinished. Whichever comes first
     * wins: the flags are cleared synchronously on the UI thread before the async wait
     * begins, so a second call does nothing and a file cannot be attached twice.
     */
    fun kickPendingSharePipelines() {
        if (pendingAutoAttach) {
            pendingAutoAttach = false
            // Without a base64 payload (file too large) only the manual "+ → Files" path remains.
            if (pendingFileB64 != null) waitForComposerReady(25_000, ::runFileDropSequence)
        }
        if (pendingAutoFocusText) {
            pendingAutoFocusText = false
            waitForComposerReady(25_000, ::settleComposerAfterAutoAttach)
        }
    }

    /**
     * Polls the page until the SPA has really loaded, then runs [action].
     *
     * Ready means the composer exists and either the splash greeting or the prompt
     * chips are visible, or the page has had no DOM mutation for 2 s and no
     * fetch/XHR start for 1.5 s (trackers installed at document start in
     * `window.__webgptLoad`). [maxMs] is a safety net: when it passes, [action]
     * runs anyway.
     */
    fun waitForComposerReady(maxMs: Int, action: () -> Unit) {
        if (activity.webViewOrNull == null || activity.isFinishing) return
        val deadline = SystemClock.elapsedRealtime() + maxMs
        lateinit var tick: Runnable
        tick = Runnable {
            val webView = activity.webViewOrNull
            if (webView == null || activity.isFinishing) return@Runnable
            webView.evaluateJavascript(COMPOSER_READY_JS) { result ->
                // evaluateJavascript delivers strings JSON-quoted.
                val signal = result?.trim('"').orEmpty()
                if (signal.startsWith("ready") || SystemClock.elapsedRealtime() >= deadline) {
                    action()
                } else {
                    webView.postDelayed(tick, COMPOSER_POLL_MS)
                }
            }
        }
        tick.run()
    }

    /**
     * Feeds the pending shared file into the site's composer. The base64 payload is
     * pushed into the page in chunks, then a File is built in JavaScript and handed
     * to the composer's `<input type=file>`, which runs the site's own attach
     * handler. Synthetic drag events are used only when the page has no file input.
     */
    fun runFileDropSequence() {
        val webView = activity.webViewOrNull ?: return
        val b64 = pendingFileB64
        val name = pendingFileName
        if (activity.isFinishing || b64 == null || name == null) return
        pendingFileB64 = null // consumed

        webView.evaluateJavascript("window.__webgptFileB64='';", null)
        var start = 0
        while (start < b64.length) {
            val end = minOf(start + FILE_CHUNK_CHARS, b64.length)
            // The base64 alphabet is safe inside a JavaScript string literal.
            webView.evaluateJavascript(
                "(function(){window.__webgptFileB64=(window.__webgptFileB64||'')+'${b64.substring(start, end)}';})();",
                null,
            )
            start = end
        }

        // Mark the injection before dispatching: a file chooser that opens during or
        // right after it is the site re-triggering, not the user (see FileChooser).
        lastFileInjectionAt = SystemClock.elapsedRealtime()
        webView.evaluateJavascript(
            fileDropScript(name, pendingFileMime ?: FileNames.DEFAULT_MIME),
            null,
        )

        // The SPA can run a late hydration pass that steals focus, so re-assert
        // focus and the keyboard several times after the drop lands.
        for (delayMs in longArrayOf(500, 1500, 2500)) {
            webView.postDelayed({ settleComposerAfterAutoAttach() }, delayMs)
        }
        for (delayMs in longArrayOf(800, 2000)) {
            webView.postDelayed({ showKeyboardForComposer() }, delayMs)
        }
    }

    private fun fileDropScript(name: String, mime: String): String = """
        (function () {
          try {
            window.__webgptShareActiveUntil = Date.now() + 6000;
            var b64 = window.__webgptFileB64 || '';
            window.__webgptFileB64 = null;
            var AB = window.${WebAppBridge.NAME};
            if (!b64) { if (AB && AB.onFileDropResult) AB.onFileDropResult(false, 'no data'); return; }
            var bin = atob(b64);
            var n = bin.length;
            var bytes = new Uint8Array(n);
            for (var i = 0; i < n; i++) bytes[i] = bin.charCodeAt(i);
            var file = new File([bytes], ${JSONObject.quote(name)}, {type: ${JSONObject.quote(mime)}});
            var dt = new DataTransfer();
            dt.items.add(file);
            var el = $COMPOSER_LOOKUP_JS;
            var input = null;
            try {
              var inputs = document.querySelectorAll('input[type=file]');
              if (inputs.length) input = inputs[0];
            } catch (e) {}
            if (input) {
              try { input.files = dt.files; } catch (e) {
                try { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'files').set.call(input, dt.files); } catch (e2) {}
              }
              try { input.dispatchEvent(new Event('input', {bubbles: true})); } catch (e) {}
              try { input.dispatchEvent(new Event('change', {bubbles: true})); } catch (e) {}
              if (el) { try { el.focus({preventScroll: true}); } catch (e) {} }
              if (AB && AB.onFileDropResult) AB.onFileDropResult(true, 'input ' + n + ' bytes');
              return;
            }
            if (!el) { if (AB && AB.onFileDropResult) AB.onFileDropResult(false, 'no composer'); return; }
            try { el.dispatchEvent(new DragEvent('dragenter', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}
            try { el.dispatchEvent(new DragEvent('dragover', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}
            try { el.dispatchEvent(new DragEvent('drop', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}
            try { document.dispatchEvent(new DragEvent('drop', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}
            try { document.dispatchEvent(new DragEvent('dragend', {bubbles: true, cancelable: true})); } catch (e) {}
            try { document.dispatchEvent(new DragEvent('dragleave', {bubbles: true, cancelable: true})); } catch (e) {}
            try { el.focus({preventScroll: true}); } catch (e) {}
            setTimeout(function () {
              try { document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true})); } catch (e) {}
              try { document.dispatchEvent(new KeyboardEvent('keyup', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true})); } catch (e) {}
              try { el.focus({preventScroll: true}); } catch (e) {}
            }, 350);
            if (AB && AB.onFileDropResult) AB.onFileDropResult(true, 'dropped ' + n + ' bytes');
          } catch (e) {
            try { var AB2 = window.${WebAppBridge.NAME}; if (AB2 && AB2.onFileDropResult) AB2.onFileDropResult(false, String(e)); } catch (e2) {}
          }
        })();
    """.trimIndent()

    /** Bridge callback: the page accepted or rejected the injected file. */
    fun handleFileDropResult(ok: Boolean, detail: String?) {
        Log.i(TAG, "drop result: ${if (ok) "ok" else "failed"} $detail")
        if (ok) {
            // Attached. Clear the manual-fallback Uri so it cannot hijack a later chooser.
            pendingShareFileUri = null
        } else if (pendingShareFileUri != null) {
            activity.longToast("Couldn't attach automatically — tap + and choose Files")
        }
    }

    /**
     * Focuses the composer so a pending attachment commits and pasted text lands.
     * Focus only, no click: an earlier selector matched the "+" attach button.
     * [COMPOSER_LOOKUP_JS] matches editable elements only.
     */
    fun settleComposerAfterAutoAttach() {
        val webView = activity.webViewOrNull ?: return
        if (activity.isFinishing) return
        val script = """
            (function () {
              try {
                window.__webgptShareActiveUntil = Date.now() + 6000;
                var el = $COMPOSER_LOOKUP_JS;
                var AB = window.${WebAppBridge.NAME};
                if (!el) { try { if (AB && AB.debugLog) AB.debugLog('composer NOT FOUND'); } catch (e) {} return; }
                try { if (AB && AB.debugLog) AB.debugLog('composer focus: ' + (el.id || el.getAttribute('data-testid') || el.tagName)); } catch (e) {}
                try { el.focus({preventScroll: true}); } catch (e) { try { el.focus(); } catch (e2) {} }
                try { el.dispatchEvent(new Event('focus', {bubbles: true})); } catch (e) {}
                try { el.dispatchEvent(new Event('focusin', {bubbles: true})); } catch (e) {}
              } catch (e) {}
            })();
        """.trimIndent()
        webView.evaluateJavascript(script, null)
        // JavaScript focus alone does not reliably bring up the Android keyboard, and the
        // site only commits a pending attachment while the composer is focused.
        showKeyboardForComposer()
    }

    /** Shows the soft keyboard for the WebView's composer. */
    fun showKeyboardForComposer() {
        try {
            val webView = activity.webViewOrNull ?: return
            if (activity.isFinishing) return
            webView.requestFocus()
            activity.getSystemService(InputMethodManager::class.java)
                ?.showSoftInput(webView, InputMethodManager.SHOW_IMPLICIT)
        } catch (t: Throwable) {
            Log.e(TAG, "showKeyboardForComposer failed", t)
        }
    }

    companion object {
        private const val TAG = SiteConfig.LOG_TAG
        private const val LOG_PREVIEW_CHARS = 80
        private const val MAX_INJECT_BYTES = 50L * 1024 * 1024
        private const val FILE_CHUNK_CHARS = 524_288
        private const val COMPOSER_POLL_MS = 700L

        /** A JavaScript expression that evaluates to the composer element, or null. */
        private val COMPOSER_LOOKUP_JS = SiteConfig.composerLookupJs()

        /** Evaluates to 'ready|..' or 'wait|..' (or a boolean when the load trackers are missing). */
        private val COMPOSER_READY_JS = """
            (function () {
              var el = !!$COMPOSER_LOOKUP_JS;
              var hEl = document.querySelector('[data-splash-headline-option]');
              var cEl = document.querySelector('[data-testid="use-case-prompt-chips"] button');
              function vis(e) {
                try { var r = e && e.getClientRects(); return !!(r && r.length && r[0].height > 0 && r[0].width > 0); }
                catch (x) { return false; }
              }
              var mk = !!((hEl && vis(hEl)) || (cEl && vis(cEl)));
              var L = window.__webgptLoad;
              if (!L) return el;
              var now = Date.now();
              var domAge = now - L.lastMut, netAge = now - L.lastStart;
              return (el && (mk || (domAge > 2000 && netAge > 1500)))
                ? 'ready|' + domAge + '|' + netAge : 'wait|' + domAge + '|' + netAge;
            })();
        """.trimIndent()

        /** How long after a share the file chooser may hand over the pending file. */
        const val SHARE_ATTACH_WINDOW_MS = 60_000L
    }
}
