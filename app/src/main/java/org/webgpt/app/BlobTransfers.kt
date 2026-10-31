package org.webgpt.app

import android.os.SystemClock
import android.util.Log

/**
 * Reassembles large downloads that the page sends to the bridge in base64 pieces.
 *
 * The accumulator is bounded because the bridge is callable from web content:
 * without limits, a page could grow memory without end. The caps are the number
 * of concurrent transfers, the size of one chunk, the total size of a transfer,
 * and an idle timeout that drops abandoned transfers.
 *
 * Chunks must arrive in order, starting at 0. The real sender is a synchronous
 * loop in the injected JavaScript, so anything else is a replay or a splice and
 * ends the transfer.
 */
internal class BlobTransfers {

    private class Transfer(var lastAtMs: Long) {
        val buffer = StringBuilder()
        var received = 0
    }

    private val lock = Any()
    private val transfers = HashMap<String, Transfer>()

    /**
     * Adds one chunk.
     *
     * @return the complete base64 payload once the last chunk has arrived, otherwise null.
     *   Invalid or rejected chunks also return null and are dropped without throwing.
     */
    fun append(
        name: String?,
        index: Int,
        total: Int,
        data: String?,
        nowMs: Long = SystemClock.elapsedRealtime(),
    ): String? {
        if (name.isNullOrEmpty() || name.length > MAX_NAME_CHARS) return null
        if (total < 1 || index < 0 || index >= total) return null
        if (data == null || data.length > MAX_CHUNK_CHARS) return null

        synchronized(lock) {
            transfers.values.removeAll { nowMs - it.lastAtMs > TRANSFER_IDLE_MS }

            var transfer = transfers[name]
            if (transfer == null) {
                if (index != 0) return null
                if (transfers.size >= MAX_TRANSFERS) return reject("too many concurrent transfers")
                transfer = Transfer(nowMs)
                transfers[name] = transfer
            } else if (index != transfer.received) {
                transfers.remove(name)
                return reject("out-of-order chunk")
            }

            if (transfer.buffer.length + data.length > MAX_B64_CHARS) {
                transfers.remove(name)
                return reject("size cap reached")
            }

            transfer.buffer.append(data)
            transfer.received = index + 1
            transfer.lastAtMs = nowMs
            if (index != total - 1) return null

            transfers.remove(name)
            return transfer.buffer.toString()
        }
    }

    private fun reject(reason: String): String? {
        if (BuildConfig.EXPERIMENTAL) Log.d(TAG, "blob transfer dropped: $reason")
        return null
    }

    private companion object {
        const val TAG = SiteConfig.LOG_TAG
        const val MAX_NAME_CHARS = 256

        /** Real use is one or two exports at a time. */
        const val MAX_TRANSFERS = 4

        /** The injected JavaScript sends 256 KB pieces. */
        const val MAX_CHUNK_CHARS = 262_144

        /** About 120 MB once decoded. */
        const val MAX_B64_CHARS = 160 * 1000 * 1000
        const val TRANSFER_IDLE_MS = 60_000L
    }
}
