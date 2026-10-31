package org.webgpt.app

/**
 * The rules for which cache files the app deletes. Plain Kotlin, so the rule can be
 * checked without Android.
 *
 * Files shared into the app live in their own folder ([SHARED_DIR]) under the cache
 * directory, because they keep the sharing app's display name and so match no prefix.
 * Everything in that folder is swept once it is old enough. In the cache root only
 * the one-shot files the app names itself ([PREFIXES]) are swept.
 */
internal object CacheFiles {

    /** Cache subfolder for shared files. Must not start with one of the [PREFIXES]. */
    const val SHARED_DIR = "shared_files"

    const val MAX_AGE_MS = 48L * 60 * 60 * 1000

    val PREFIXES = listOf("camera_capture_", "shared_image_", "shared_file_")

    /**
     * @param inSharedDir true for a file inside [SHARED_DIR], false for one in the cache root
     * @param ageMs now minus the file's last-modified time
     */
    fun shouldSweep(inSharedDir: Boolean, name: String, ageMs: Long): Boolean =
        ageMs > MAX_AGE_MS && (inSharedDir || PREFIXES.any(name::startsWith))
}
