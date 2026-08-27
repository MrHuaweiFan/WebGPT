package com.webgpt.app;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

/**
 * Momentary transparent trampoline that moves a TEXT share out of a
 * foreign (the sender's) task into WebGPT's own task.
 *
 * Background (rounds 18-19): some OEM share paths launch MainActivity
 * (singleTask) INSIDE the sender's task — non-root, wrong recents
 * identity, back-nav into the sender. Round 18's fix re-launched
 * ourselves with NEW_TASK straight from onCreate, but a cross-task
 * start that early comes from an activity with NO window that is not
 * resumed yet; several OEM task managers create the target task but
 * never bring it to the foreground (symptom: text is copied + "paste
 * it" toast appears, yet WebGPT never opens — the backgrounded
 * instance still ran handleSharedText).
 *
 * This relay fixes the ordering. MainActivity starts it in the SAME
 * task (plain intent, no flags — the system walks the sender's stack
 * onto it like any normal activity) and finishes itself. The relay
 * then waits until it is genuinely RESUMED — our process is the
 * foreground app with an attached window — and only THEN forwards the
 * share to MainActivity with FLAG_ACTIVITY_NEW_TASK. A start issued by
 * a resumed, window-owning activity is foreground-guaranteed on every
 * Android version and OEM (it is the same path every app-to-app launch
 * uses), so WebGPT's own task is always brought to the front: an
 * existing instance receives the share via onNewIntent, and a cold
 * start boots normally in the proper task.
 *
 * Fail-safe: if forwarding fails for any reason, the shared text is
 * still copied to the clipboard so the user keeps the manual-paste
 * behavior instead of a silent no-op.
 *
 * Manifest notes: transparent framework theme (nothing flashes),
 * noHistory (it self-destructs anyway, this is just belt-and-suspenders),
 * taskAffinity="" (no affinity of its own — it can never spawn a task),
 * exported=false (only MainActivity starts it, with an explicit intent).
 * excludeFromRecents is deliberately NOT set: the relay lives in the
 * SENDER's task, and that flag would hide the sender from Recents.
 */
public class ShareRelayActivity extends Activity {

    private static final String TAG = "WebGPTApp";

    /** Original ACTION_SEND / ACTION_PROCESS_TEXT intent, forwarded as-is. */
    static final String EXTRA_SHARE_INTENT = "share_intent";

    /** Loop guard extra understood by MainActivity's redirect check. */
    static final String EXTRA_RELAUNCHED = "_webgpt_relaunched";

    private boolean forwarded;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // On re-creation after a process death mid-hop we must not forward a
        // second time — just dissolve and let the user re-share.
        if (savedInstanceState != null) forwarded = true;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (forwarded) {
            finish();
            return;
        }
        forwarded = true;
        try {
            Intent orig = getIntent().getParcelableExtra(EXTRA_SHARE_INTENT);
            if (orig != null) {
                Intent fwd = (Intent) orig.clone();
                // Explicit component: the cloned share intent may be implicit,
                // and we must never re-resolve it through the chooser.
                fwd.setComponent(new ComponentName(this, MainActivity.class));
                fwd.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                fwd.putExtra(EXTRA_RELAUNCHED, true);
                startActivity(fwd);
            }
        } catch (Throwable t) {
            Log.e(TAG, "share relay forward failed", t);
            copyFallback();
        }
        finish();
    }

    /**
     * Degraded mode if forwarding failed: keep the round-17 behavior
     * (copy + toast) so the share is never silently lost.
     */
    private void copyFallback() {
        try {
            String text = extractText(getIntent()
                    .getParcelableExtra(EXTRA_SHARE_INTENT));
            if (text == null) return;
            ClipboardManager cb = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (cb != null) {
                cb.setPrimaryClip(ClipData.newPlainText("WebGPT", text));
                // Android 13+ shows its own "Copied" overlay already.
                if (android.os.Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(this, "Text copied — paste it into WebGPT",
                            Toast.LENGTH_LONG).show();
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static String extractText(Intent i) {
        if (i == null || i.getAction() == null) return null;
        String action = i.getAction();
        if (Intent.ACTION_SEND.equals(action)) {
            return i.getStringExtra(Intent.EXTRA_TEXT);
        }
        if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence t = i.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            return t != null ? t.toString() : null;
        }
        return null;
    }
}
