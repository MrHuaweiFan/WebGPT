package org.webgpt.app

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/** True if the runtime permission is currently granted. */
internal fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

/** Request codes for activity results and runtime permission prompts. */
internal object RequestCodes {
    const val FILE_CHOOSER = 1001
    const val CAMERA_FOR_CHOOSER = 1002
    const val MEDIA_FOR_WEB = 1003
    const val STORAGE_FOR_DOWNLOAD = 1004
}
