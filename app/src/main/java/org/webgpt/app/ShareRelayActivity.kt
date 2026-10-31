package org.webgpt.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * Transparent trampoline that moves a TEXT share out of the sender's task and
 * into WebGPT's own task.
 *
 * Why it exists: some OEM share paths launch MainActivity (singleTask) INSIDE
 * the sender's task, which gives a non-root task, the wrong recents identity and
 * back-navigation into the sender. Re-launching with NEW_TASK straight from
 * MainActivity.onCreate does not work either: that activity has no window yet
 * and is not resumed, and several OEM task managers then create the target task
 * but never bring it to the foreground (the text is copied and the "paste it"
 * toast appears, but WebGPT never opens).
 *
 * The fix is ordering. MainActivity starts this relay in the SAME task (plain
 * intent, no flags) and finishes itself. The relay waits until it is RESUMED,
 * meaning our process is the foreground app with a window, and only then
 * forwards the share to MainActivity with FLAG_ACTIVITY_NEW_TASK. A start from a
 * resumed activity brings the task to the front on every Android version and
 * OEM. An existing instance receives the share in onNewIntent; a cold start
 * boots normally in its own task.
 *
 * Fail-safe: if forwarding fails, the text is copied to the clipboard so the
 * share is never silently lost.
 *
 * Manifest: transparent theme (nothing flashes), noHistory, taskAffinity=""
 * (can never spawn a task of its own), exported=false. excludeFromRecents is
 * deliberately NOT set: the relay lives in the SENDER's task, and that flag
 * would hide the sender from Recents.
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
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .putExtra(EXTRA_RELAUNCHED, true)
                startActivity(forward)
            }
        } catch (t: Throwable) {
            Log.e(TAG, "share relay forward failed", t)
            copyToClipboard()
        }
        finish()
    }

    /** The original ACTION_SEND / ACTION_PROCESS_TEXT intent, forwarded as-is. */
    private val shareIntent: Intent?
        @Suppress("DEPRECATION")
        get() = intent.getParcelableExtra(EXTRA_SHARE_INTENT)

    /** Degraded mode when forwarding failed: copy the text and tell the user to paste it. */
    private fun copyToClipboard() {
        shareIntent?.let(::extractText)?.let { copyShareToClipboard(it) }
    }

    private fun extractText(intent: Intent): String? = when (intent.action) {
        Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
        Intent.ACTION_PROCESS_TEXT -> intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString()
        else -> null
    }

    companion object {
        private const val TAG = SiteConfig.LOG_TAG

        /** Extra carrying the original share intent. */
        const val EXTRA_SHARE_INTENT = "share_intent"

        /** Loop guard understood by MainActivity's redirect check. */
        const val EXTRA_RELAUNCHED = "_webgpt_relaunched"
    }
}
