package org.webgpt.app

import android.content.Context
import java.io.File
import kotlin.concurrent.thread

/**
 * Deletes cache files the app created for camera captures and shared content
 * once they are older than [CacheFiles.MAX_AGE_MS]. Runs on a worker thread.
 */
internal object CacheCleaner {

    /** The cache folder for files shared into the app, created on demand. */
    fun sharedFilesDir(context: Context): File =
        File(context.cacheDir, CacheFiles.SHARED_DIR).also { it.mkdirs() }

    fun sweep(context: Context) {
        val cacheDir = context.cacheDir
        thread(name = "cache-sweep") {
            val now = System.currentTimeMillis()
            sweepFolder(cacheDir, inSharedDir = false, now = now)
            sweepFolder(File(cacheDir, CacheFiles.SHARED_DIR), inSharedDir = true, now = now)
        }
    }

    private fun sweepFolder(folder: File, inSharedDir: Boolean, now: Long) {
        val files = runCatching { folder.listFiles() }.getOrNull() ?: return
        for (file in files) {
            if (file.isFile && CacheFiles.shouldSweep(inSharedDir, file.name, now - file.lastModified())) {
                file.delete()
            }
        }
    }
}
