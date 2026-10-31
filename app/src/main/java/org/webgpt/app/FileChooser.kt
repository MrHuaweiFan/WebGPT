package org.webgpt.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.webkit.ValueCallback
import android.webkit.WebView
import androidx.core.app.ActivityCompat
import androidx.core.content.FileProvider
import java.io.File

/**
 * Answers the site's file-input requests (onShowFileChooser) for the main
 * WebView and for popups: a pending shared file, or the system picker with an
 * optional camera entry.
 */
internal class FileChooser(private val activity: MainActivity) {

    private var callback: ValueCallback<Array<Uri>>? = null
    private var pendingCameraUri: Uri? = null
    private var pendingCameraFile: File? = null

    /** The system picker is waiting for the camera permission prompt. */
    private var waitingForCameraPermission = false

    /** Returns true: the request is always taken, even if it ends up cancelled. */
    fun open(source: WebView, newCallback: ValueCallback<Array<Uri>>): Boolean {
        callback?.onReceiveValue(null)
        callback = newCallback

        if (source === activity.webViewOrNull && handOverSharedFile()) return true
        // A popup's chooser (an OAuth page, or a popup-embedded input) deliberately skips the
        // pending share: the file was shared to the main composer, and only the main WebView's
        // attach flow may consume it.

        // CAMERA is declared in the manifest, so Android requires the runtime permission to be
        // held before a capture intent starts, or the camera app fails silently. Ask first and
        // show the chooser from the permission result.
        if (!activity.hasPermission(Manifest.permission.CAMERA)) {
            waitingForCameraPermission = true
            ActivityCompat.requestPermissions(
                activity, arrayOf(Manifest.permission.CAMERA), RequestCodes.CAMERA_FOR_CHOOSER
            )
        } else {
            launch(includeCamera = true)
        }
        return true
    }

    /**
     * Gives the pending shared file to the page, but only to the main WebView's
     * attach flow and only inside the share transaction window.
     *
     * @return true if this call answered the chooser request.
     */
    private fun handOverSharedFile(): Boolean {
        val share = activity.share
        if (share.pendingShareFileUri != null &&
            SystemClock.elapsedRealtime() - share.pendingShareReceivedAt > ShareHandler.SHARE_ATTACH_WINDOW_MS
        ) {
            // The transaction is over. Drop the file so it can never hijack this or a later
            // picker, and fall through to the system chooser so the user can still pick manually.
            Log.i(TAG, "pending shared file expired (attach window closed)")
            share.clearPending()
        }
        val file = share.pendingShareFileUri ?: return false

        if (SystemClock.elapsedRealtime() - share.lastFileInjectionAt < RETRIGGER_IGNORE_MS) {
            Log.i(TAG, "ignoring site re-trigger after injection")
            finish(null)
            return true
        }

        Log.i(TAG, "onShowFileChooser: returning pending shared file $file")
        finish(arrayOf(file))
        share.pendingShareFileUri = null
        // The site only commits a pending attachment while the composer is focused shortly after
        // the file lands. Unattended, it drops the file about a second later. Focus the composer
        // several times inside that window.
        SETTLE_DELAYS_MS.forEach { delay ->
            activity.webView.postDelayed(share::settleComposerAfterAutoAttach, delay)
        }
        return true
    }

    private fun launch(includeCamera: Boolean) {
        val contentIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        val cameraIntent = if (includeCamera) createCameraIntent() else null

        val chooser = Intent.createChooser(contentIntent, "Select file")
        if (cameraIntent != null) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(cameraIntent))
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(chooser, RequestCodes.FILE_CHOOSER)
        } catch (e: Exception) {
            Log.e(TAG, "File chooser failed", e)
            finish(null)
        }
    }

    private fun createCameraIntent(): Intent? = try {
        val file = File(activity.cacheDir, "camera_capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", file)
        pendingCameraUri = uri
        pendingCameraFile = file
        Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            // Some camera apps re-read the capture for EXIF rotation or thumbnails and crash
            // on a missing read grant, so both are granted.
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    } catch (e: Exception) {
        Log.e(TAG, "Camera setup failed", e)
        forgetCameraFile(delete = false)
        null
    }

    /** @return true if the result belonged to the file chooser. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RequestCodes.FILE_CHOOSER) return false
        if (callback == null) return true

        if (resultCode != Activity.RESULT_OK) {
            forgetCameraFile(delete = true)
            finish(null)
            return true
        }

        val clip = data?.clipData
        val results: Array<Uri>? = when {
            clip != null -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }.toTypedArray()
            data?.data != null -> arrayOf(data.data!!)
            // No data means the camera app wrote into our output file.
            else -> pendingCameraUri?.takeIf { pendingCameraFile?.length().let { size -> size != null && size > 0 } }
                ?.also { Log.i(TAG, "Camera capture result: $it") }
                ?.let { arrayOf(it) }
        }
        forgetCameraFile(delete = false)
        finish(results)
        return true
    }

    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode != RequestCodes.CAMERA_FOR_CHOOSER || !waitingForCameraPermission) return
        waitingForCameraPermission = false
        // Show the chooser either way, with the camera entry only if the permission was granted.
        val granted = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (callback != null) launch(includeCamera = granted)
    }

    /** Drops the pending callback without answering it (activity teardown). */
    fun release() {
        callback = null
    }

    private fun finish(results: Array<Uri>?) {
        callback?.onReceiveValue(results)
        callback = null
    }

    private fun forgetCameraFile(delete: Boolean) {
        if (delete) pendingCameraFile?.takeIf { it.exists() }?.delete()
        pendingCameraUri = null
        pendingCameraFile = null
    }

    private companion object {
        const val TAG = SiteConfig.LOG_TAG

        /** The site re-opens its file input right after a programmatic attach; that must not attach twice. */
        const val RETRIGGER_IGNORE_MS = 4000L

        val SETTLE_DELAYS_MS = longArrayOf(200, 500, 900, 1400)
    }
}
