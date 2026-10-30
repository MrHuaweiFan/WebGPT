package org.webgpt.app

import android.content.ContentValues
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.FileProvider
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Long-press menu for images in the WebView.
 *
 * Holding down on an image opens a dialog with two actions: share the image
 * through the system chooser, or save it into the public Download folder. Both
 * fetch the image natively with the WebView's cookies, because the URL usually
 * needs the logged-in session.
 *
 * Long-press and dialog callbacks run on the UI thread. Network and disk work
 * runs on a worker thread, and toasts and the chooser are posted back to the UI thread.
 */
internal class ImageActions(private val activity: MainActivity) {

    /** Where a saved image is written. [legacyFile] is set below Android 10 only. */
    private class Target(val stream: OutputStream, val legacyFile: File?)

    fun attachTo(webView: WebView) {
        webView.setOnLongClickListener {
            val result = webView.hitTestResult
            val imageUrl = result.extra
            if (result.type == WebView.HitTestResult.IMAGE_TYPE && !imageUrl.isNullOrEmpty()) {
                showDialog(imageUrl)
                true
            } else {
                false
            }
        }
    }

    private fun showDialog(imageUrl: String) {
        val view = activity.layoutInflater.inflate(R.layout.dialog_image_actions, null)
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.image_actions_title)
            .setView(view)
            .create()

        view.findViewById<View>(R.id.action_share)?.setOnClickListener {
            dialog.dismiss()
            toast("Loading image to share...")
            shareImage(imageUrl)
        }
        view.findViewById<View>(R.id.action_download)?.setOnClickListener {
            dialog.dismiss()
            toast("Downloading image...")
            downloadImage(imageUrl)
        }
        dialog.show()
    }

    // ---- share ----------------------------------------------------------------------------

    /** Fetches [url] into the cache directory, then opens the share chooser. */
    private fun shareImage(url: String) {
        fetchImage("image-share", url) { connection, mime ->
            val extension = FileNames.extensionForMime(mime) ?: DEFAULT_EXTENSION
            val file = File(activity.cacheDir, "shared_image_${System.currentTimeMillis()}.$extension")
            try {
                connection.inputStream.use { input ->
                    FileOutputStream(file).use { input.copyTo(it) }
                }
            } catch (e: Exception) {
                file.delete()
                throw e
            }
            activity.runOnUiThread { shareFile(file, mime) }
        }
    }

    private fun shareFile(file: File, mime: String) {
        try {
            val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
            val send = Intent(Intent.ACTION_SEND).apply {
                type = mime
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(Intent.createChooser(send, "Share"))
        } catch (e: Exception) {
            Log.e(TAG, "shareFile failed", e)
            toast("Share failed: ${e.message}", long = true)
        }
    }

    // ---- download -------------------------------------------------------------------------

    /** Fetches [url] and saves it into the public Download folder. */
    private fun downloadImage(url: String) {
        fetchImage("image-download", url) { connection, mime ->
            val extension = FileNames.extensionForMime(mime) ?: DEFAULT_EXTENSION
            val fileName = "chatgpt_image_${System.currentTimeMillis()}.$extension"
            var legacyFile: File? = null
            connection.inputStream.use { input ->
                val target = openTarget(fileName, mime)
                legacyFile = target.legacyFile
                target.stream.use { input.copyTo(it) }
            }
            scanLegacyFile(legacyFile, mime)
            toast("Saved to Download/$fileName", long = true)
        }
    }

    // ---- shared fetch ---------------------------------------------------------------------

    /**
     * Requests [url] on a worker thread with the WebView's cookies and calls
     * [onImage] with the open connection and the image MIME type. HTTP errors, web
     * pages in place of an image and exceptions are reported as toasts and never
     * reach [onImage].
     */
    private fun fetchImage(
        threadName: String,
        url: String,
        onImage: (connection: HttpURLConnection, mime: String) -> Unit,
    ) {
        // Both are read on the UI thread, where WebView and CookieManager are safe to use.
        val cookies = CookieManager.getInstance().getCookie(url)
        val userAgent = activity.webViewOrNull?.settings?.userAgentString
            ?.takeIf { it.isNotEmpty() }
            ?: SiteConfig.USER_AGENT

        thread(name = threadName) {
            var connection: HttpURLConnection? = null
            try {
                connection = openConnection(url, cookies, userAgent)
                val code = connection.responseCode
                if (code !in 200..399) {
                    toast("Failed: HTTP $code", long = true)
                    return@thread
                }
                val declared = connection.contentType
                    ?.substringBefore(';')?.trim()?.lowercase(Locale.ROOT)
                // A web page in place of an image is an expired link or a login redirect.
                if (declared != null && declared.startsWith("text/html")) {
                    Log.e(TAG, "Image request got an HTML interstitial: $url")
                    toast("Failed: the server returned a web page", long = true)
                    return@thread
                }
                val mime = declared?.takeIf { it.startsWith("image/") } ?: DEFAULT_MIME
                onImage(connection, mime)
            } catch (e: Exception) {
                Log.e(TAG, "image request failed", e)
                toast("Failed: ${e.message}", long = true)
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun openConnection(url: String, cookies: String?, userAgent: String): HttpURLConnection {
        val scheme = Uri.parse(url).scheme?.lowercase(Locale.ROOT)
        require(scheme == "http" || scheme == "https") { "unsupported image link" }
        return (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            instanceFollowRedirects = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            if (!cookies.isNullOrEmpty()) setRequestProperty("Cookie", cookies)
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("Referer", SiteConfig.HOME_URL)
            setRequestProperty("Accept", "image/*,*/*")
            // Byte-exact image data: no transparent gzip around the file.
            setRequestProperty("Accept-Encoding", "identity")
        }
    }

    // ---- writing into the Download folder -------------------------------------------------

    /**
     * Opens an output stream for a new file in the public Download folder: through
     * MediaStore on Android 10 and later, as a plain file below that.
     */
    private fun openTarget(displayName: String, mime: String): Target {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
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

    /** Shows a toast from any thread. */
    private fun toast(message: String, long: Boolean = false) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
        }
    }

    private companion object {
        const val TAG = "WebGPTApp"

        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 60_000

        /** Used when the server names no image type, as in the Java original. */
        const val DEFAULT_MIME = "image/png"
        const val DEFAULT_EXTENSION = "png"
    }
}
