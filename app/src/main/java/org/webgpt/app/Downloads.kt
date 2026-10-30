package org.webgpt.app

import android.Manifest
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Everything that saves a file into the device's Download folder.
 *
 * Three kinds of download reach the WebView's download listener:
 * - data: URLs carry the whole payload, so they are decoded and saved directly;
 * - blob: URLs only exist inside the page, so the page reads them and hands the
 *   bytes to [WebAppBridge], which calls back into [handleBlobResult] or
 *   [handleBlobDownload];
 * - http(s) URLs are fetched natively with the WebView's cookies.
 *
 * Callbacks from the bridge and from the WebView arrive on the UI thread. The
 * actual saving runs on a worker thread.
 */
internal class Downloads(private val activity: MainActivity) {

    /** Reassembles large exports that the page sends in pieces. */
    val blobTransfers = BlobTransfers()

    private class PendingDownload(
        val url: String,
        val userAgent: String?,
        val contentDisposition: String?,
        val mimeType: String?,
    )

    /** A blob: download waiting for the page to answer. */
    private class PendingBlob(val filename: String)

    /** Where a download is written. [legacyFile] is set below Android 10 only. */
    private class Target(val stream: OutputStream, val legacyFile: File?)

    /** Waiting for the storage permission (Android 9 and below). */
    private var pendingDownload: PendingDownload? = null
    private var pendingBlob: PendingBlob? = null

    private val saveLock = Any()
    private var lastSaveAt = -DEDUP_WINDOW_MS
    private var lastSaveLength = -1

    /** When the bridge last delivered a blob, to suppress the dead-URL fallback. */
    @Volatile
    private var lastBridgeSaveAt = -FALLBACK_SUPPRESS_MS

    fun attachTo(webView: WebView) {
        webView.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            onDownloadRequested(webView, url, userAgent, contentDisposition, mimeType)
        }
    }

    private fun onDownloadRequested(
        webView: WebView,
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        if (url.isNullOrEmpty()) return
        // A data: URL is the whole payload, so only its start goes to the log.
        Log.i(TAG, "Download requested: ${url.take(LOG_URL_CHARS)} (mime: $mimeType)")
        when {
            url.startsWith("data:") ->
                handleBlobDownload(FileNames.fromDownload(url, contentDisposition, mimeType), url)

            url.startsWith("blob:") -> {
                // The bridge may have saved this blob already. Fetching the dead
                // URL again would only add failure toasts after a good save.
                if (SystemClock.elapsedRealtime() - lastBridgeSaveAt < FALLBACK_SUPPRESS_MS) {
                    Log.i(TAG, "blob fallback suppressed")
                } else {
                    downloadBlobUrl(webView, url, contentDisposition, mimeType)
                }
            }

            else -> startDownload(PendingDownload(url, userAgent, contentDisposition, mimeType))
        }
    }

    // ---- blob: and data: URLs ----------------------------------------------------------

    /**
     * Reads a blob: URL through in-page JavaScript and receives the bytes as a data
     * URL on the bridge. This still works after the site called revokeObjectURL(),
     * because the injected hook delays the real revocation.
     */
    internal fun downloadBlobUrl(
        webView: WebView,
        blobUrl: String,
        contentDisposition: String?,
        mimeType: String?,
    ) {
        toast("Downloading...")
        // A blob: URL carries no file name and the disposition is usually missing.
        // The extension is fixed later from the payload's MIME type.
        val filename = if (!contentDisposition.isNullOrEmpty()) {
            FileNames.fromDownload(blobUrl, contentDisposition, mimeType)
        } else {
            "download_${System.currentTimeMillis()}"
        }
        val pending = PendingBlob(filename)
        pendingBlob = pending
        webView.evaluateJavascript(blobFetchScript(blobUrl), null)
        // If the bridge never answers (page navigated away, renderer killed), give up.
        webView.postDelayed({
            if (pendingBlob === pending) {
                pendingBlob = null
                toast(
                    "Download did not complete. Hold down on the image to open the share & download menu.",
                    long = true,
                )
            }
        }, BLOB_TIMEOUT_MS)
    }

    private fun blobFetchScript(blobUrl: String): String {
        val bridge = WebAppBridge.NAME
        return """
            (function () {
              function fail() {
                try { window.$bridge && $bridge.onBlobFailed && $bridge.onBlobFailed(); } catch (e) {}
              }
              try {
                fetch(${JSONObject.quote(blobUrl)})
                  .then(function (r) { return r.blob(); })
                  .then(function (b) {
                    var fr = new FileReader();
                    fr.onloadend = function () {
                      try {
                        if (window.$bridge && $bridge.onBlobResult) { $bridge.onBlobResult(String(fr.result)); }
                        else { fail(); }
                      } catch (e) { fail(); }
                    };
                    fr.onerror = fail;
                    fr.readAsDataURL(b);
                  })
                  .catch(fail);
              } catch (e) { fail(); }
            })();
        """.trimIndent()
    }

    /** The page delivered the bytes of the blob requested in [downloadBlobUrl]. */
    fun handleBlobResult(dataUrl: String) {
        val pending = pendingBlob ?: return
        pendingBlob = null
        handleBlobDownload(pending.filename, dataUrl)
    }

    /** The in-page fetch of the blob failed. */
    fun handleBlobFailed() {
        if (pendingBlob == null) return
        pendingBlob = null
        toast("Can't download directly. Hold down on the image to open the share & download menu.", long = true)
    }

    /**
     * Saves a payload that arrived as a data URL ("data:mime;base64,...") with a
     * suggested [name]. The extension is corrected from the payload's real MIME
     * type, since exports such as PDF, Word and Markdown often come without one.
     */
    fun handleBlobDownload(name: String?, dataUrl: String?) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return
        val comma = dataUrl.indexOf(',')
        if (comma < 0) return

        // The anchor-click hook and the download listener can deliver the same blob.
        if (isRecentDuplicate(dataUrl.length)) {
            Log.i(TAG, "blob duplicate skipped: $name")
            return
        }
        Log.i(TAG, "blob download reached: $name")
        lastBridgeSaveAt = SystemClock.elapsedRealtime()

        val mime = FileNames.mimeOfDataUrl(dataUrl)
        val baseName = FileNames.sanitize(name?.takeIf { it.isNotEmpty() } ?: "download_${System.currentTimeMillis()}")
        val filename = withMimeExtension(baseName, mime)
        toast("Downloading $filename...")
        saveBase64(dataUrl.substring(comma + 1), filename, mime)
    }

    private fun isRecentDuplicate(length: Int): Boolean = synchronized(saveLock) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSaveAt < DEDUP_WINDOW_MS && length == lastSaveLength) return true
        lastSaveAt = now
        lastSaveLength = length
        false
    }

    private fun saveBase64(base64: String, fileName: String, mime: String) {
        thread(name = "blob-save") {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val target = openTarget(fileName, knownMime(mime))
                target.stream.use { it.write(bytes) }
                scanLegacyFile(target.legacyFile, mime)
                toast("Saved to Download/$fileName", long = true)
            } catch (e: Exception) {
                Log.e(TAG, "saving blob failed", e)
                toast("Download failed: ${e.message}", long = true)
            }
        }
    }

    // ---- http(s) downloads --------------------------------------------------------------

    private fun startDownload(download: PendingDownload) {
        val fileName = FileNames.fromDownload(download.url, download.contentDisposition, download.mimeType)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(activity, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            // Keep the download and re-issue it once the user answers the prompt.
            pendingDownload = download
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                RequestCodes.STORAGE_FOR_DOWNLOAD,
            )
            return
        }

        toast("Downloading: $fileName")
        val cookies = CookieManager.getInstance().getCookie(download.url)
        thread(name = "download") {
            downloadBlocking(download, cookies, fileName)
        }
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != RequestCodes.STORAGE_FOR_DOWNLOAD) return
        val download = pendingDownload ?: return
        pendingDownload = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startDownload(download)
        } else {
            toast("Download cancelled — storage permission was denied", long = true)
        }
    }

    /** Fetches [download] with the WebView's cookies and saves it. Runs on a worker thread. */
    private fun downloadBlocking(download: PendingDownload, cookies: String?, fileName: String) {
        var connection: HttpURLConnection? = null
        try {
            connection = openConnection(download, cookies)
            val code = connection.responseCode
            if (code !in 200..399) {
                toast("Download failed: HTTP $code", long = true)
                return
            }

            val fallbackMime = download.mimeType ?: FileNames.DEFAULT_MIME
            val mime = connection.contentType
                ?.takeIf { '/' in it }
                ?.substringBefore(';')?.trim()
                ?: fallbackMime

            // After redirects, the final response's Content-Disposition is authoritative.
            val headerName = FileNames.fromDisposition(connection.getHeaderField("Content-Disposition"))
                ?.takeIf { it.isNotBlank() }
                ?.let(FileNames::sanitize)
            val name = headerName ?: fileName

            // A web page in answer to a non-HTML download is an error or login page.
            // Saving it would write HTML into a file named like the download.
            if (mime.startsWith("text/html", ignoreCase = true) &&
                !name.endsWith(".html", ignoreCase = true) && !name.endsWith(".htm", ignoreCase = true)
            ) {
                Log.e(TAG, "Download got HTML interstitial for $name")
                toast("Download failed: the server returned a web page (link may have expired)", long = true)
                return
            }

            val displayName = withMimeExtension(name, mime)
            var legacyFile: File? = null
            val total = connection.inputStream.use { input ->
                val target = openTarget(displayName, knownMime(mime))
                legacyFile = target.legacyFile
                target.stream.use { input.copyTo(it) }
            }
            Log.i(TAG, "Downloaded $total bytes ($displayName)")
            scanLegacyFile(legacyFile, mime)
            toast("Saved to Download/$displayName", long = true)
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            toast("Download failed: ${e.message}", long = true)
        } finally {
            connection?.disconnect()
        }
    }

    private fun openConnection(download: PendingDownload, cookies: String?): HttpURLConnection {
        val scheme = Uri.parse(download.url).scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "unsupported link" }
        val agent = download.userAgent?.takeIf { it.isNotEmpty() } ?: SiteConfig.USER_AGENT
        return (URL(download.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("User-Agent", agent)
            if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
            setRequestProperty("Accept", "*/*")
            setRequestProperty("Referer", SiteConfig.HOME_URL)
            // Byte-exact downloads: no transparent gzip around a binary file.
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    // ---- writing into the Download folder -------------------------------------------------

    /**
     * Opens an output stream for a new file in the public Download folder: through
     * MediaStore on Android 10 and later, as a plain file below that.
     */
    private fun openTarget(displayName: String, mime: String?): Target {
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
    private fun scanLegacyFile(file: File?, mime: String) {
        if (file == null) return
        runCatching {
            MediaScannerConnection.scanFile(activity, arrayOf(file.absolutePath), arrayOf(mime), null)
        }.onFailure { Log.w(TAG, "MediaScanner failed", it) }
    }

    /** [mime] if it names a specific type, null for the generic or an unknown one. */
    private fun knownMime(mime: String): String? =
        mime.takeIf { it != FileNames.DEFAULT_MIME && FileNames.extensionForMime(it) != null }

    /**
     * Gives [name] the extension that belongs to [mime]. A short existing extension
     * (up to four characters) is treated as the wrong one and replaced, while a
     * name such as "report.v2" keeps its dot. A generic MIME type changes nothing.
     */
    private fun withMimeExtension(name: String, mime: String): String {
        val extension = knownMime(mime)?.let(FileNames::extensionForMime) ?: return name
        if (name.endsWith(".$extension", ignoreCase = true)) return name
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0 && name.length - dot <= MAX_EXTENSION_WITH_DOT) name.substring(0, dot) else name
        return "$base.$extension"
    }

    /** Shows a toast from any thread. */
    private fun toast(message: String, long: Boolean = false) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "WebGPTApp"

        const val BLOB_TIMEOUT_MS = 30_000L
        const val DEDUP_WINDOW_MS = 4_000L
        const val FALLBACK_SUPPRESS_MS = 8_000L
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000
        const val LOG_URL_CHARS = 100

        /** ".html" is 5 characters including the dot. */
        const val MAX_EXTENSION_WITH_DOT = 5
    }
}
