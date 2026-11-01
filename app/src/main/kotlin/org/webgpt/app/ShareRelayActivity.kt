package org.webgpt.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The share target of the app: a transparent trampoline that moves a share into
 * WebGPT's own task. The manifest declares ACTION_SEND (text and files) and
 * ACTION_PROCESS_TEXT here, not on MainActivity.
 *
 * Why it exists: some OEM share paths launch the target INSIDE the sender's task,
 * which gives a non-root task, the wrong recents identity and back-navigation into
 * the sender. Re-launching with NEW_TASK straight from MainActivity.onCreate does not
 * work either: that activity has no window yet and is not resumed, and several OEM
 * task managers then create the target task but never bring it to the foreground
 * (the text is copied and the "paste it" toast appears, but WebGPT never opens).
 * A MainActivity created in the sender's task also paints its own launch window
 * (black in dark mode) over the sender while the share hops on, so MainActivity must
 * not be the share target at all.
 *
 * The relay waits until it is RESUMED, meaning our process is the foreground app with
 * a window, and only then forwards the share to MainActivity with
 * FLAG_ACTIVITY_NEW_TASK. A start from a resumed activity brings the task to the front
 * on every Android version and OEM. An existing instance receives the share in
 * onNewIntent; a cold start boots normally in its own task.
 *
 * Files: a shared file's URI grant arrives with the intent that started this relay
 * (its ClipData and FLAG_GRANT_READ_URI_PERMISSION). The forward copies the ClipData
 * and keeps that flag, so MainActivity receives the grant itself. Every other launch
 * flag of the incoming intent is dropped (a sender may set FORWARD_RESULT,
 * CLEAR_TASK and the like, which must never reach MainActivity).
 *
 * Fail-safe: if forwarding fails, shared text is copied to the clipboard so the
 * share is never silently lost; a shared file gets a toast.
 *
 * Manifest: transparent theme (nothing flashes), noHistory, taskAffinity=""
 * (can never spawn a task of its own). excludeFromRecents is deliberately NOT set:
 * the relay lives in the SENDER's task, and that flag would hide the sender from
 * Recents.
 */
class ShareRelayActivity : Activity() {

    private var forwarded = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Re-created after a process death mid-hop: do not forward a second time.
        if (savedInstanceState != null) forwarded = true
    }

    override fun onResume() {
        super.onResume()
        if (forwarded) {
            finish()
            return
        }
        forwarded = true
        try {
            shareIntent?.let { original ->
                // Explicit component: the share intent may be implicit, and it must
                // never be re-resolved through the chooser.
                val forward = Intent(original)
                    .setComponent(ComponentName(this, MainActivity::class.java))
                    .setFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP or
                            (original.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    )
                    .putExtra(EXTRA_RELAUNCHED, true)
                startActivity(forward)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "share relay forward failed", t)
            reportForwardFailure()
        }
        finish()
    }

    /**
     * The share intent: the one the system started this relay with, or the original
     * intent that MainActivity wrapped into [EXTRA_SHARE_INTENT] when it relays a
     * share that reached it inside the sender's task.
     */
    private val shareIntent: Intent?
        @Suppress("DEPRECATION")
        get() = intent.getParcelableExtra<Intent>(EXTRA_SHARE_INTENT)
            ?: intent.takeIf { it.action == Intent.ACTION_SEND || it.action == Intent.ACTION_PROCESS_TEXT }

    /** Degraded mode when forwarding failed: copy the text, or tell the user the file did not arrive. */
    private fun reportForwardFailure() {
        val original = shareIntent ?: return
        val text = if (original.streamUri() == null) extractText(original) else null
        if (text != null) {
            copyShareToClipboard(text)
        } else {
            longToast("Couldn't open the share in ${getString(R.string.app_name)}")
        }
    }

    private fun extractText(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    companion object {
        private const val TAG = SiteConfig.LOG_TAG

        /** Extra carrying the original share intent, when MainActivity relays a misplaced share. */
        const val EXTRA_SHARE_INTENT = "share_intent"

        /** Loop guard understood by MainActivity's redirect check. */
        const val EXTRA_RELAUNCHED = "_webgpt_relaunched"
    }
}
