package org.webgpt.app

import android.util.Log
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import java.net.URLDecoder
import java.util.Locale

/** File-name helpers shared by downloads and shared files. */
object FileNames {

    private const val TAG = "WebGPTApp"
    /** Generic type used when nothing better is known. */
    const val DEFAULT_MIME = "application/octet-stream"

    private const val MAX_NAME_LENGTH = 200

    private val PATH_SEPARATORS = Regex("[/\\\\]")

    /** RFC 5987 form: filename*=UTF-8''informe%202026.pdf */
    private val DISPOSITION_EXTENDED = Regex(
        "filename\\*=\\s*(?:utf-8|iso-8859-1)?''([^;]+)",
        RegexOption.IGNORE_CASE,
    )

    /** Plain form: filename="x.pdf" (quotes optional, trailing parameters allowed). */
    private val DISPOSITION_PLAIN = Regex(
        "filename=\\s*[\"']?([^\"';]+)[\"']?",
        RegexOption.IGNORE_CASE,
    )

    /** MimeTypeMap misses some of these (docx, md) on a number of devices. */
    private val MIME_EXTENSIONS = mapOf(
        "application/pdf" to "pdf",
        "application/msword" to "doc",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" to "docx",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" to "xlsx",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation" to "pptx",
        "text/markdown" to "md",
        "text/x-markdown" to "md",
        "text/plain" to "txt",
        "text/html" to "html",
        "text/csv" to "csv",
        "application/json" to "json",
        "image/png" to "png",
        "image/jpeg" to "jpg",
        "image/webp" to "webp",
        "image/gif" to "gif",
        "image/svg+xml" to "svg",
    )

    /** Replaces path separators and caps the length at 200 characters, keeping the extension. */
    fun sanitize(name: String): String {
        var n = name.replace(PATH_SEPARATORS, "_").trim()
        if (n.length > MAX_NAME_LENGTH) {
            val dot = n.lastIndexOf('.')
            n = if (dot > 0) {
                val ext = n.substring(dot)
                n.substring(0, MAX_NAME_LENGTH - ext.length) + ext
            } else {
                n.substring(0, MAX_NAME_LENGTH)
            }
        }
        return n.ifEmpty { "download" }
    }

    /** Sanitizes a provider-supplied display name for use inside the cache directory. */
    fun sanitizeShared(name: String): String =
        sanitize(name).trimStart('.').ifEmpty { "shared_file" }

    /**
     * The file name from a Content-Disposition header, or null if it has none.
     * RFC 5987 filename* wins over the plain token. Both are percent-decoded,
     * and '+' is kept literal (URLDecoder would turn it into a space).
     */
    fun fromDisposition(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrEmpty()) return null
        try {
            DISPOSITION_EXTENDED.find(contentDisposition)?.let { match ->
                return URLDecoder.decode(match.groupValues[1].trim().replace("+", "%2B"), "UTF-8")
            }
            DISPOSITION_PLAIN.find(contentDisposition)?.let { match ->
                val name = match.groupValues[1].trim()
                if (!name.contains('%')) return name
                return runCatching { URLDecoder.decode(name.replace("+", "%2B"), "UTF-8") }
                    .getOrDefault(name)
            }
        } catch (e: Exception) {
            Log.w(TAG, "fromDisposition failed", e)
        }
        return null
    }

    /** Best file name for a download: Content-Disposition first, then the URL and MIME type. */
    fun fromDownload(url: String?, contentDisposition: String?, mimeType: String?): String {
        val fromHeader = fromDisposition(contentDisposition)?.let(::sanitize)
        if (!fromHeader.isNullOrEmpty()) return fromHeader
        return sanitize(URLUtil.guessFileName(url, contentDisposition, mimeType))
    }

    /** The MIME type in the header of a data URL ("data:image/png;base64,..."), or [DEFAULT_MIME]. */
    fun mimeOfDataUrl(dataUrl: String): String {
        if (!dataUrl.startsWith("data:")) return DEFAULT_MIME
        val comma = dataUrl.indexOf(',').takeIf { it >= 0 } ?: dataUrl.length
        return dataUrl.substring("data:".length, comma).substringBefore(';').ifEmpty { DEFAULT_MIME }
    }

    /** File extension (without the dot) for [mime], or null if unknown. */
    fun extensionForMime(mime: String?): String? {
        if (mime == null) return null
        val type = mime.substringBefore(';').trim().lowercase(Locale.ROOT)
        return MIME_EXTENSIONS[type] ?: MimeTypeMap.getSingleton().getExtensionFromMimeType(type)
    }
}
