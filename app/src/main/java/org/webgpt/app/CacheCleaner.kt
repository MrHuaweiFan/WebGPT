package org.webgpt.app

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/**
 * Deletes cache files the app created for camera captures and shared content
 * once they are older than [MAX_AGE_MS]. Runs on a worker thread.
 */
internal object CacheCleaner {

    private const val MAX_AGE_MS = 48L * 60 * 60 * 1000

    private val PREFIXES = listOf("camera_capture_", "shared_image_", "shared_file_")

    fun sweep(context: Context) {
        val cacheDir = context.cacheDir
        thread(name = "cache-sweep") {
            val files = runCatching { cacheDir.listFiles() }.getOrNull() ?: return@thread
            val cutoff = System.currentTimeMillis() - MAX_AGE_MS
            for (file in files) {
                if (PREFIXES.any(file.name::startsWith) && file.lastModified() < cutoff) {
                    file.delete()
                }
            }
        }
    }
}
