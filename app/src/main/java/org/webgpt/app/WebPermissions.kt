package org.webgpt.app

import android.Manifest
import android.util.Log
import android.webkit.PermissionRequest
import androidx.core.app.ActivityCompat

/**
 * Handles the site's camera and microphone requests:
 *  - only for allowlisted origins,
 *  - the missing Android permission is requested at runtime,
 *  - only what the user actually approved is granted.
 */
internal class WebPermissions(private val activity: MainActivity) {

    private var pending: PermissionRequest? = null

    fun handle(request: PermissionRequest) {
        activity.runOnUiThread {
            if (activity.isFinishing || activity.isDestroyed) return@runOnUiThread
            try {
                if (!NavigationPolicy.isAllowedHost(request.origin?.host)) {
                    request.denyQuietly()
                    return@runOnUiThread
                }
                val missing = request.resources
                    .mapNotNull(::androidPermissionFor)
                    .filterNot(activity::hasPermission)
                    .distinct()
                if (missing.isEmpty()) {
                    grant(request)
                } else {
                    pending?.denyQuietly()
                    pending = request
                    ActivityCompat.requestPermissions(activity, missing.toTypedArray(), RequestCodes.MEDIA_FOR_WEB)
                }
            } catch (t: Throwable) {
                Log.e(TAG, "onPermissionRequest failed", t)
            }
        }
    }

    fun onRequestPermissionsResult(requestCode: Int) {
        if (requestCode != RequestCodes.MEDIA_FOR_WEB) return
        val request = pending ?: return
        pending = null
        grant(request)
    }

    private fun grant(request: PermissionRequest) {
        val granted = request.resources.filter { resource ->
            androidPermissionFor(resource)?.let(activity::hasPermission) == true
        }
        try {
            if (granted.isEmpty()) request.deny() else request.grant(granted.toTypedArray())
        } catch (t: Throwable) {
            Log.e(TAG, "grantWebPermissionRequest failed", t)
        }
    }

    private fun androidPermissionFor(resource: String): String? = when (resource) {
        PermissionRequest.RESOURCE_VIDEO_CAPTURE -> Manifest.permission.CAMERA
        PermissionRequest.RESOURCE_AUDIO_CAPTURE -> Manifest.permission.RECORD_AUDIO
        else -> null
    }

    private fun PermissionRequest.denyQuietly() {
        try {
            deny()
        } catch (ignored: Throwable) {
            // The request may already be answered or its WebView gone.
        }
    }

    private companion object {
        const val TAG = "WebGPTApp"
    }
}
