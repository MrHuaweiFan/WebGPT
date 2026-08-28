package com.webgpt.app;

import com.webgpt.app.BuildConfig;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.Display;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.InputMethodManager;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.splashscreen.SplashScreen;
import androidx.core.splashscreen.SplashScreenViewProvider;
import androidx.webkit.WebViewCompat;

import com.webgpt.app.webview.CrashTracker;
import com.webgpt.app.webview.WebViewManagerDialog;
import com.webgpt.app.webview.WebViewUtil;
import com.webgpt.app.webview.WelcomeDialog;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "WebGPTApp";
    private static final String PREFS_NAME = "webgpt_prefs";

    private static final String URL = "https://chatgpt.com/";

    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final int REQUEST_FILE_CHOOSER = 54321;
    private static final int REQUEST_STORAGE_PERM = 1003;
    private static final int REQUEST_MEDIA_PERM = 1004;
    private static final int REQUEST_CAMERA_PERM = 1005;

    WebView webview;
    ViewGroup rootLayout;
    View loadingOverlay;
    ImageView loadingLogo;
    /** Snapshot overlay shown on top of rootLayout during resume to mask
     *  the brief GPU-surface-recreation black flash. Captured when the
     *  activity loses window focus (just before onPause), faded out 300ms
     *  after the activity regains focus. Fixes the "brief black screen
     *  flash on resume from task manager" symptom (which is NOT a renderer
     *  death — onRenderProcessGone never fires, the WebView is alive). */
    ImageView resumeSnapshot;
    Bitmap resumeSnapshotBitmap;
    final Handler snapshotHandler = new Handler();
    /**
     * True once the initial page load has completed and the loading overlay
     * has started fading out. After this point, SPA navigations (which fire
     * onPageStarted/onPageFinished for in-page route changes like ChatGPT's
     * settings tabs) are ignored — they must NOT re-show the loading overlay.
     */
    boolean initialLoadComplete = false;
    private final List<WebView> popupViews = new ArrayList<>();

    private Map<String, String> extraHeaders;

    private ValueCallback<Uri[]> filePathCallback;
    private Uri pendingCameraUri;
    private File pendingCameraFile;

    // Pending share-from-outside: file to inject into the WebGPT composer.
    // Volatile: written on the background copy thread, read on the UI thread.
    // Cleared in onStop so a forgotten share can never hijack a later
    // file-picker invocation.
    private volatile Uri pendingShareFileUri;

    // Pending WebView permission request (camera/mic) while the OS dialog is up
    private PermissionRequest pendingWebPermissionRequest;

    // True while the OS camera-permission dialog is up on behalf of the file chooser
    private boolean cameraPermForChooser;

    // True when a shared file is waiting for the page to finish loading so the
    // auto-attach sequence can start
    private volatile boolean pendingAutoAttach;

    // True when shared TEXT is waiting for the page to finish loading so the
    // composer focus + keyboard sequence can run
    private volatile boolean pendingAutoFocusText;

    // Drop-injection pipeline: shared file held as base64 until it is fed to
    // the composer via HTML5 drop events (no + menu, no file chooser race).
    private volatile String pendingFileB64;
    private volatile String pendingFileName;
    private volatile String pendingFileMime;

    // Pending download awaiting the legacy storage permission (pre-Android 10)
    private String[] pendingDownload;

    // Pending blob download state (JS bridge hands the data URL back to us)
    private volatile boolean blobDownloadInFlight;
    private volatile String pendingBlobFilename;
    private volatile String pendingBlobMime;

    // Guard so the offline dialog is not shown twice for one failure
    private boolean offlineDialogShowing;

    // Blob-save dedup window (anchor hook + DownloadListener fallback)
    private final Object blobSaveLock = new Object();
    private volatile long lastBlobSaveAt;
    private volatile int lastBlobSaveLen;

    // Chunked blob transfer accumulation (JS sends big exports in 256KB pieces)
    final Object blobChunksLock = new Object();
    final java.util.HashMap<String, StringBuilder> blobChunks = new java.util.HashMap<>();

    // Set when the JS bridge delivers a blob save; suppresses the dead-URL
    // DownloadListener fallback (and its failure toasts) for a few seconds.
    private volatile long blobBridgeSuccessAt;

    // Time of the last file injection into the page; suppresses the silent
    // pendingShareFileUri handover for a few seconds afterwards (the site
    // re-opens its file input after a programmatic attach — handing the file
    // over AGAIN then double-attaches it and trips ChatGPT's attach limit).
    private volatile long lastFileInjectionAt;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ─── Android 12+ splash screen (v6.27) ──────────────────────────
        // MainActivity launches with Theme.AppSplash (see the manifest).
        // The call below swaps the real AppTheme back in before any view
        // work happens. On Android < 12 that is the ONLY effect: the launch
        // window's background/bars are pinned to the same tokens AppTheme
        // uses, so older devices go straight into the loading screen with
        // no flash. On Android 12+ the system additionally shows its splash
        // screen first — a plain frame of the loading-screen background
        // color (day/night aware; the splash icon is a transparent drawable,
        // i.e. deliberately nothing) — and the exit listener below takes
        // over the hand-off: WITHOUT a listener some OEMs play their default
        // exit animation, which on several test devices read as "the icon
        // expands until it fills the screen, then the app fades in". With
        // the listener the splash view crossfades over 300 ms into the
        // app's loading screen, which is painted the exact same background
        // color — so the only visible change is the loading logo (and later
        // the spinner) fading in. No icon zoom, no color flashing, same on
        // every device and OEM.
        SplashScreen splashScreen = SplashScreen.installSplashScreen(this);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            splashScreen.setOnExitAnimationListener(
                    new SplashScreen.OnExitAnimationListener() {
                @Override
                public void onSplashScreenExit(SplashScreenViewProvider provider) {
                    ObjectAnimator fade = ObjectAnimator.ofFloat(
                            provider.getView(), View.ALPHA, 1f, 0f);
                    fade.setDuration(300L);
                    fade.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            // The system expects the app to remove the view
                            // itself once its custom exit animation ends.
                            provider.remove();
                        }
                    });
                    fade.start();
                }
            });
        }

        // ─── Foreign-task relay (text-share task bug, rounds 18-19) ────────
        // On some devices/OEMs a TEXT share launches the target INSIDE the
        // sender's task (so back returns to the sender, recents keeps the
        // SENDER's identity, and the real WebGPT task is ignored — a fresh
        // instance every time). Detection: we are not the root of our task.
        //
        // Round 18 tried to fix this by re-launching ourselves with NEW_TASK
        // directly from onCreate — but at that point this instance has NO
        // window and is not resumed, so OEM task managers created the WebGPT
        // task WITHOUT ever bringing it to the front (round 19 symptom: text
        // copied + "paste it" toast, but WebGPT never opened).
        //
        // Corrected approach: hand the share to a momentary transparent
        // trampoline (ShareRelayActivity) started in THIS task — a plain
        // same-task start the system always honors — and finish. Once the
        // trampoline is genuinely resumed (our process foreground, window
        // attached), IT forwards the share to MainActivity with NEW_TASK —
        // a foreground start, which is guaranteed to bring WebGPT's own task
        // to the front on every Android version and OEM.
        //
        // Files are deliberately excluded: their share intents can carry URI
        // grants that a re-launch would drop.
        if (isFinishing()) return;
        Intent bootIntent = getIntent();
        if (bootIntent != null
                && !bootIntent.getBooleanExtra(
                        ShareRelayActivity.EXTRA_RELAUNCHED, false)
                && !isTaskRoot()
                && bootIntent.getParcelableExtra(Intent.EXTRA_STREAM) == null) {
            try {
                Intent relay = new Intent(this, ShareRelayActivity.class);
                relay.putExtra(ShareRelayActivity.EXTRA_SHARE_INTENT, bootIntent);
                startActivity(relay);  // same task: no flags, no cross-task start
                finish();
                return;
            } catch (Throwable t) {
                // Never expected. Fall through and boot normally in this
                // task (round-17 behavior: visible, functional, wrong task)
                // rather than leaving the user with nothing at all.
                Log.e(TAG, "share relay start failed; booting in place", t);
            }
        }

        // ─── WebView switcher pre-launch checks ────────────────────────────
        // (1) If the current WebView is too old to support DOCUMENT_START_SCRIPT,
        //     force-open the WebView Manager picker non-cancelable. The user
        //     must pick or install a newer one before they can use the app.
        // (2) If the previous N launches all crashed, assume the chosen WebView
        //     is broken on this device — bounce the user to Settings to pick
        //     a different one.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            if (!WebViewUtil.isSupported()) {
                final WebViewManagerDialog[] dlg = new WebViewManagerDialog[1];
                dlg[0] = new WebViewManagerDialog(this,
                        new DialogInterface.OnDismissListener() {
                            @Override
                            public void onDismiss(DialogInterface d) {
                                if (dlg[0] != null && dlg[0].changedWebView()) {
                                    // Force restart to load the new WebView
                                    Intent i = getPackageManager()
                                            .getLaunchIntentForPackage(getPackageName());
                                    if (i != null) {
                                        i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP
                                                | Intent.FLAG_ACTIVITY_NEW_TASK
                                                | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                        startActivity(i);
                                    }
                                    finishAffinity();
                                    android.os.Process.killProcess(android.os.Process.myPid());
                                } else {
                                    finish();
                                }
                            }
                        });
                dlg[0].setCancelable(false);
                dlg[0].show();
                return;
            }

            if (CrashTracker.hasCrashes()) {
                Log.w(TAG, "Crash threshold reached; bouncing to WebView Manager");
                Toast.makeText(this, R.string.webview_pick_another, Toast.LENGTH_LONG).show();
                CrashTracker.reset();
                Intent i = new Intent(this, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                return;
            }
        }
        // ─── End WebView switcher pre-launch checks ────────────────────────

        setContentView(R.layout.activity_main);

        extraHeaders = new HashMap<>();
        extraHeaders.put("X-Requested-With", "");

        webview = findViewById(R.id.activity_main_webview);
        rootLayout = (ViewGroup) webview.getParent();
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingLogo = findViewById(R.id.loading_logo);

        // PRIMARY FIX (Bug 2): pin the window + rootLayout background to the
        // same dark-grey / white the WebView itself uses. Without this, the
        // ~1-frame GPU-surface-teardown gap on resume from task manager shows
        // the activity theme's pure-black colorBackground bleeding through a
        // transparent rootLayout — exactly the "brief black flash" symptom.
        // The resumeSnapshot PixelCopy overlay is a stronger mask when it
        // succeeds, but it's a race (vis=8 / GONE if PixelCopy's async
        // callback hasn't fired by onWindowFocusChanged(true)); this
        // background-color fallback is the reliable primary defense.
        applyBackgroundColors();

        // v6.24.31 diagnostic: log the panel's refresh-rate situation so we
        // can tell whether this OEM throttles third-party apps to 60Hz
        // while Chrome runs at 90/120Hz (a common Samsung/Xiaomi behavior,
        // and a classic cause of "the site feels smoother in the browser").
        // Logcat-only — zero runtime cost.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                Display disp = getWindowManager().getDefaultDisplay();
                Display.Mode cur = disp.getMode();
                StringBuilder modes = new StringBuilder();
                for (Display.Mode m : disp.getSupportedModes()) {
                    modes.append(String.format(Locale.US, "%dx%d@%.0f ",
                            m.getPhysicalWidth(), m.getPhysicalHeight(),
                            m.getRefreshRate()));
                }
                Log.d(TAG, String.format(Locale.US,
                        "display: current mode %dx%d@%.0fHz, supported: %s",
                        cur.getPhysicalWidth(), cur.getPhysicalHeight(),
                        cur.getRefreshRate(), modes.toString().trim()));
            } catch (Throwable t) {
                Log.e(TAG, "display mode query failed", t);
            }
        }

        // Resume-snapshot overlay: an ImageView placed at the topmost
        // position of the DECOR VIEW — i.e. covering the FULL WINDOW,
        // including the status-bar strip. This MUST match the geometry of
        // what PixelCopy.request(getWindow(), ...) captures (the whole
        // window). The v6.24.28/29 version added this ImageView to
        // rootLayout instead, which is only the content area BELOW the
        // status bar — so the full-window bitmap was scaled down ~4% to fit,
        // drawing a second black status-bar strip under the real one for the
        // 300ms the overlay was visible (the "app height is bugged for a
        // fraction of a second on resume from task manager" report).
        // Normally GONE; briefly VISIBLE during activity-resume to mask the
        // GPU-surface-recreation flash with a pixel-perfect copy of the
        // previous frame.
        resumeSnapshot = new ImageView(this);
        resumeSnapshot.setLayoutParams(new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        resumeSnapshot.setVisibility(View.GONE);
        try {
            ((ViewGroup) getWindow().getDecorView()).addView(resumeSnapshot);
        } catch (Throwable t) {
            // Should never happen (DecorView is a FrameLayout), but never
            // let an overlay problem take the app down — disable the mask.
            Log.e(TAG, "snapshot overlay attach failed", t);
            resumeSnapshot = null;
        }

        // Set correct logo color based on theme (black for light mode, white for dark mode)
        boolean isDark = isDarkMode();
        loadingLogo.setImageResource(isDark ? R.drawable.logo_white : R.drawable.logo_black);

        // Start spin + fade animation
        Animation spinFade = AnimationUtils.loadAnimation(this, R.anim.spin_fade);
        loadingLogo.startAnimation(spinFade);

        // Wire the WebView up (initial setup; recreated in place if the
        // renderer ever dies — see onRenderProcessGone).
        setupMainWebView();

        // Best-effort sweep of stale one-shot files (camera captures, shared
        // copies) so the cache directory cannot grow without bound.
        sweepCacheDir();

        // First-launch welcome dialog — tells the user about the hidden
        // Settings menu (where the WebView switcher lives). Shows only once
        // per install; dismissed with "Understood".
        WelcomeDialog.showIfNeeded(this);

        // ─── State restoration (activity destroyed / process restarted) ───
        // DuckAssist 0.4.2 pattern: when the system hands back a saved
        // state (activity recreated after a config change we could not
        // intercept, or process killed in the background), restore the
        // WebView's back/forward list and resume the EXACT page — the open
        // conversation included — instead of cold-booting the homepage
        // (the "app relaunches and loses my chat" symptom).
        boolean restoredFromState = false;
        if (savedInstanceState != null) {
            try {
                WebBackForwardList nav = webview.restoreState(savedInstanceState);
                if (nav != null && nav.getSize() > 0
                        && nav.getCurrentItem() != null
                        && nav.getCurrentItem().getUrl() != null) {
                    String cur = nav.getCurrentItem().getUrl();
                    if (cur.startsWith("http://") || cur.startsWith("https://")) {
                        // Re-navigate explicitly so our X-Requested-With
                        // header policy applies to the restored load too
                        // (restoreState alone loads without extra headers,
                        // which trips ChatGPT's "install the app" banner).
                        loadUrlWithHeaders(webview, cur);
                    }
                    restoredFromState = true;
                }
            } catch (Throwable t) {
                Log.e(TAG, "WebView state restore failed", t);
            }
        }
        if (!restoredFromState) {
            loadUrlWithHeaders(webview, URL);
        }

        // Process share-from-outside intent AFTER the initial load has been
        // triggered, so shared text no longer causes a second duplicate
        // navigation (and no longer resets an open conversation when the
        // activity is already running via onNewIntent).
        // SKIPPED when restored from state: the launch intent is stale (it
        // is the intent that originally created this task — re-processing
        // it after a rotation/process restart would re-copy an old share
        // to the clipboard every time).
        if (!restoredFromState) {
            Intent launchIntent = getIntent();
            if (launchIntent != null) {
                handleShareIntent(launchIntent);
            }
        }
    }

    private boolean isDarkMode() {
        return (getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleShareIntent(intent);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Leaving the app invalidates a pending shared file so it can never
        // silently hijack a later file-picker invocation.
        pendingShareFileUri = null;
    }

    /**
     * Handle an incoming ACTION_SEND or ACTION_PROCESS_TEXT intent.
     * Saves the text/file for later injection after the WebView loads.
     */
    private void handleShareIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "handleShareIntent: action=" + action + ", type=" + intent.getType());
        markShareActive();

        String sharedText = null;
        Uri sharedFileUri = null;
        String sharedFileMime = null;

        if (Intent.ACTION_SEND.equals(action)) {
            String type = intent.getType();
            if (type != null && type.startsWith("text/") && intent.getStringExtra(Intent.EXTRA_TEXT) != null) {
                sharedText = intent.getStringExtra(Intent.EXTRA_TEXT);
            } else {
                Uri fileUri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
                if (fileUri != null) {
                    sharedFileUri = fileUri;
                    sharedFileMime = type != null ? type : "*/*";
                }
            }
        } else if (Intent.ACTION_PROCESS_TEXT.equals(action)) {
            CharSequence text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT);
            if (text != null) {
                sharedText = text.toString();
            }
        }

        if (sharedText != null) {
            handleSharedText(sharedText);
        } else if (sharedFileUri != null) {
            handleSharedFile(sharedFileUri, sharedFileMime);
        }
    }

    /**
     * Handle shared text — copy to clipboard; the user pastes it into the
     * prompt box. The page is either already loading (cold share-in) or
     * already open (onNewIntent), so no reload is needed.
     */
    private void handleSharedText(String text) {
        // Do not log shared content in release builds (privacy).
        if (BuildConfig.EXPERIMENTAL) {
            Log.i(TAG, "handleSharedText: "
                    + (text.length() > 80 ? text.substring(0, 80) + "..." : text));
        }
        copyToClipboard(text);
        // Focus the composer AND open the soft keyboard so the user can paste
        // immediately — but only once the SPA has REALLY rendered its composer
        // (onPageFinished fires too early: the page steals focus back while
        // finishing its render, closing the keyboard).
        if (webview != null && !isFinishing()) {
            if (initialLoadComplete) {
                waitForComposerReady(20000, this::settleComposerAfterAutoAttach);
            } else {
                pendingAutoFocusText = true;
            }
        }
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("WebGPT", text);
                clipboard.setPrimaryClip(clip);
                Log.i(TAG, "Text copied to clipboard");
                // Android 13+ already shows its own "Copied" overlay;
                // avoid doubling it up.
                if (Build.VERSION.SDK_INT < 33) {
                    Toast.makeText(this, "Text copied — paste it into WebGPT", Toast.LENGTH_LONG).show();
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "copyToClipboard failed", e);
        }
    }

    /**
     * Handle shared file — copy the file to the app's cache dir, then try to
     * attach it AUTOMATICALLY: the injected sequence clicks the site's own
     * "+" button and its "Files" menu item, which makes the site call the
     * file chooser — and onShowFileChooser hands over this file with zero
     * user interaction. (Android forbids pushing a file into the page any
     * other way; the page must ask first.)
     */
    private void handleSharedFile(Uri fileUri, String mime) {
        Log.i(TAG, "handleSharedFile: " + fileUri + " (" + mime + ")");

        new Thread(() -> {
            try {
                String fileName = "shared_file_" + System.currentTimeMillis();
                String originalName = getFileNameFromUri(fileUri);
                if (originalName != null && !originalName.isEmpty()) {
                    // The display name comes from the sharing app's provider
                    // and must never be trusted as a path: sanitize it.
                    fileName = sanitizeSharedFileName(originalName);
                } else {
                    String ext = android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(mime);
                    if (ext != null && !ext.isEmpty()) {
                        fileName += "." + ext;
                    }
                }

                File outFile = new File(getCacheDir(), fileName);
                InputStream in = getContentResolver().openInputStream(fileUri);
                if (in == null) {
                    runOnUiThread(() -> Toast.makeText(this,
                            "Cannot read the shared file", Toast.LENGTH_LONG).show());
                    return;
                }
                java.io.OutputStream out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
                out.close();
                in.close();

                final Uri sharedUri = androidx.core.content.FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", outFile);

                // Read the file back as base64 for the drop-injection path
                // (skipped for huge files — those fall back to manual attach).
                String b64 = null;
                if (outFile.length() < 50L * 1024 * 1024) {
                    byte[] data = java.nio.file.Files.readAllBytes(outFile.toPath());
                    b64 = android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP);
                }

                final String fileB64 = b64;
                final String fileFinalName = fileName;
                final String fileMime = (mime != null && mime.contains("/")) ? mime
                        : android.webkit.MimeTypeMap.getSingleton()
                                .getMimeTypeFromExtension(android.webkit.MimeTypeMap
                                        .getFileExtensionFromUrl("file://x/" + fileName));

                runOnUiThread(() -> {
                    pendingShareFileUri = sharedUri;  // manual +->Files fallback
                    if (fileB64 != null) {
                        pendingFileB64 = fileB64;
                        pendingFileName = fileFinalName;
                        pendingFileMime = fileMime != null ? fileMime : "application/octet-stream";
                        // Auto-attach WILL run (now or once the page loads) —
                        // tell the user to wait instead of tapping + manually,
                        // which would cancel the automatic injection. Text
                        // shares have their own toast in copyToClipboard.
                        Toast.makeText(MainActivity.this,
                                "Please wait — the file will attach automatically",
                                Toast.LENGTH_LONG).show();
                    }
                    if (initialLoadComplete && webview != null) {
                        if (fileB64 != null) {
                            // Page already loaded — wait for it to go quiet.
                            waitForComposerReady(25000, this::runFileDropSequence);
                        } else {
                            // File too large to inject — manual path only.
                        }
                    } else {
                        pendingAutoAttach = true;
                    }
                    final WebView wv = webview;
                    if (wv != null) {
                        wv.postDelayed(() -> {
                            if (pendingShareFileUri != null && !isFinishing()) {
                                Toast.makeText(MainActivity.this,
                                        "Couldn't attach automatically — tap + and choose Files",
                                        Toast.LENGTH_LONG).show();
                            }
                        }, 25000);
                    }
                });

            } catch (Exception e) {
                Log.e(TAG, "handleSharedFile failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Failed to process file: " + e.getMessage(),
                        Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if ("content".equals(uri.getScheme())) {
            try (android.database.Cursor cursor = getContentResolver().query(
                    uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (idx >= 0) {
                        result = cursor.getString(idx);
                    }
                }
            }
        }
        if (result == null) {
            result = uri.getLastPathSegment();
        }
        return result;
    }

    private void loadUrlWithHeaders(WebView view, String url) {
        view.loadUrl(url, extraHeaders);
    }

    /** Wire up the main WebView (the initial instance from the layout, or a
     *  fresh one after a renderer crash). */
    private void setupMainWebView() {
        configureWebView(webview.getSettings());
        setupClients(webview);
        setupDownloads(webview);
        setupImageContextMenu(webview);
        installDocumentStartOverrides(webview);
    }

    /**
     * Inject the page overrides at DOCUMENT START in EVERY frame (main frame
     * AND iframes). This is the critical piece for the site's export/share
     * features: those run inside iframes, and evaluateJavascript() at
     * onPageFinished only ever touches the main frame — which is why blob
     * downloads and the share button kept failing while the page itself
     * worked. addDocumentStartJavaScript runs before any site script, in
     * every frame, on every navigation.
     */
    private void installDocumentStartOverrides(WebView webView) {
        try {
            if (WebViewUtil.isSupported()) {
                WebViewCompat.addDocumentStartJavaScript(
                        webView, PAGE_OVERRIDES_JS, java.util.Collections.singleton("*"));
                // Registered AFTER PAGE_OVERRIDES_JS on purpose: the ready
                // watcher's settle fallback reads window.__webgptLoad, which
                // the overrides script installs — document-start scripts run
                // in registration order.
                WebViewCompat.addDocumentStartJavaScript(
                        webView, PAGE_READY_WATCHER_JS, java.util.Collections.singleton("*"));
                // Focus guard: see FOCUS_GUARD_JS above. Registered last so
                // any page-script .focus() attempts can be intercepted from
                // the very first script execution.
                WebViewCompat.addDocumentStartJavaScript(
                        webView, FOCUS_GUARD_JS, java.util.Collections.singleton("*"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "addDocumentStartJavaScript failed", t);
        }
    }

    /** Replace a dead main WebView with a fresh one (renderer crash path). */
    private void recreateMainWebView() {
        try {
            rootLayout.removeView(webview);
            final WebView dead = webview;
            // Defer destroy(): this runs INSIDE onRenderProcessGone of this
            // very WebView — destroying it synchronously from within that
            // callback crashes on some Chromium builds (Android 15).
            // Detach now, destroy after the callback stack has unwound.
            rootLayout.post(() -> {
                try {
                    dead.destroy();
                } catch (Throwable t) {
                    Log.e(TAG, "deferred main WebView destroy failed", t);
                }
            });
        } catch (Throwable t) {
            Log.e(TAG, "Error destroying dead WebView", t);
        }
        webview = new WebView(this);
        webview.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        rootLayout.addView(webview, 0);
        setupMainWebView();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebSettings settings) {
        // JS + storage
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBlockNetworkImage(false);

        // Explicit security posture — do not rely on platform defaults, which
        // shift with targetSdk bumps: no local file/content access from the
        // page, Safe Browsing on, cleartext off (also declared in manifest).
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        // Same posture for mixed content (pattern from the AI Studio
        // webclient): chatgpt.com and every auth host are HTTPS-only, so
        // blocking http:// subresources inside the HTTPS page costs nothing
        // and cannot regress a future targetSdk default.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.setSafeBrowsingEnabled(true);
        }

        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBlockNetworkLoads(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        settings.setUserAgentString(UA_MOBILE);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(true);
        settings.setDisplayZoomControls(false);

        webview.requestFocusFromTouch();
        // Theme-matched background: the WebView's default background is opaque
        // WHITE, which flashes on every reload / renderer rebuild / popup
        // navigation before the site's own CSS paints. In night mode paint
        // ChatGPT's dark surface instead so those transitions are seamless.
        applyWebViewBackground(webview);
        // Privacy: block third-party cookies on the main page (cross-site
        // trackers embedded in chatgpt.com). OAuth POPUPS keep them enabled —
        // the Google/Auth0 redirect chain needs them to complete sign-in.
        CookieManager.getInstance().setAcceptThirdPartyCookies(webview, false);

        // WebView debugging (chrome://inspect) only in experimental builds
        // — never in release. v6.24.31: keyed on EXPERIMENTAL because the
        // debug buildType now sets debuggable=false (ART speed), which
        // flips BuildConfig.DEBUG to false.
        if (BuildConfig.EXPERIMENTAL && Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        // Add JS interface for AndroidBridge (clipboard override, share, blob download)
        webview.addJavascriptInterface(new WebAppInterface(this, webview), "AndroidBridge");
    }

    /**
     * Solid WebView background matching the current theme (pattern from the
     * AI Studio webclient, which pins #121212 for the same reason). Only the
     * pre-paint flash depends on this — the page itself always covers it
     * once rendered, so pure cosmetic continuity during navigations.
     */
    private void applyWebViewBackground(WebView w) {
        w.setBackgroundColor(isDarkMode() ? 0xFF0D0D0D : 0xFFFFFFFF);
    }

    /**
     * Apply the same dark-grey / white background to the {@code Window} AND
     * to {@code rootLayout} so the brief GPU-surface-teardown flash on
     * resume from task manager shows the matching color instead of the
     * pure-black window background bleeding through a transparent
     * rootLayout. Without this, the activity theme's
     * {@code ?android:attr/colorBackground} (#000000 in dark mode) is what
     * the user sees during the ~1-frame gap between the window being
     * re-attached and the WebView repainting — which is exactly the
     * "brief black flash" Bug 2 report. The {@link #resumeSnapshot}
     * PixelCopy overlay is a stronger mask when it succeeds, but it is a
     * race (the snapshot is GONE if PixelCopy's async callback hasn't
     * fired by the time {@code onWindowFocusChanged(true)} runs — see
     * toast "snapshot: no overlay to fade (vis=8)"). This background-color
     * fallback is the <em>reliable</em> primary defense; the snapshot
     * overlay remains a nice-to-have on top.
     */
    private void applyBackgroundColors() {
        int bg = isDarkMode() ? 0xFF0D0D0D : 0xFFFFFFFF;
        try {
            getWindow().setBackgroundDrawable(new ColorDrawable(bg));
        } catch (Throwable t) {
            Log.e(TAG, "window setBackgroundDrawable threw", t);
        }
        if (rootLayout != null) {
            rootLayout.setBackgroundColor(bg);
        }
    }

    /**
     * JS interface exposed to the wrapped site's JavaScript.
     */
    public static class WebAppInterface {
        private final MainActivity activity;
        private final WebView hostWebView;

        WebAppInterface(MainActivity activity, WebView hostWebView) {
            this.activity = activity;
            this.hostWebView = hostWebView;
        }

        /**
         * Origin gate: the bridge only serves pages hosted on our allowlisted
         * domains. POPUP WebViews (share menus, blob:/about:blank windows)
         * inherit trust from the main WebView — in this app popups are only
         * ever spawned by an allowlisted page (onCreateWindow), so a popup
         * with a blank/blob URL is still "ours". Without this, the site's
         * share menu (opened in a popup) was silently rejected right after
         * the "share called" toast.
         */
        private boolean hostAllowed() {
            try {
                if (urlAllowed(hostWebView.getUrl())) return true;
                WebView main = activity.webview;
                if (main != null && main != hostWebView && urlAllowed(main.getUrl())) {
                    return true;  // popup spawned by an allowlisted page
                }
                debugLog("bridge blocked: " + hostWebView.getUrl());
                return false;
            } catch (Throwable t) {
                return false;
            }
        }

        private static boolean urlAllowed(String url) {
            try {
                if (url == null) return false;
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
                    return false;
                }
                String host = uri.getHost();
                return host != null && isAllowedHost(host);
            } catch (Throwable t) {
                return false;
            }
        }

        /**
         * Called by the PAGE_READY_WATCHER_JS poller when the DOM signals
         * that the SPA is REALLY rendered (composer + late-appearing splash
         * markers, a restored conversation, or the settle heuristic).
         * Drives the loading overlay off the screen the instant the page is
         * usable instead of waiting for onPageFinished + a blind delay.
         */
        @JavascriptInterface
        public void pageReady() {
            if (!hostAllowed()) return;  // origin gate
            activity.runOnUiThread(() -> activity.onDomReady());
        }

        /**
         * Called by the navigator.share({ text }) JS override.
         * Copies to the system clipboard SILENTLY (no toast) —
         * The site shows its own "Copied!" feedback in the UI.
         */
        @JavascriptInterface
        public void copyToClipboard(final String text) {
            if (!hostAllowed()) return;  // origin gate
            activity.runOnUiThread(() -> {
                try {
                    android.content.ClipboardManager clipboard =
                            (android.content.ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if (clipboard != null) {
                        android.content.ClipData clip =
                                android.content.ClipData.newPlainText("WebGPT", text);
                        clipboard.setPrimaryClip(clip);
                        Log.i("WebGPTApp", "Text copied to clipboard via JS override");
                    }
                } catch (Exception e) {
                    Log.e("WebGPTApp", "copyToClipboard (JS) failed", e);
                }
            });
        }

        /**
         * navigator.share({ text, url }) — open the real system share sheet.
         * UNGATED by design: the system share sheet itself is the consent UI
         * (the user reviews the content before anything leaves the device),
         * so an origin gate adds no security here — and it broke sharing
         * from the site's share popup, whose own URL is blob:/about:blank.
         */
        @JavascriptInterface
        public void shareText(final String text, final String url) {
            debugLog("shareText invoked");
            activity.runOnUiThread(() -> activity.shareTextNative(text, url));
        }

        /**
         * navigator.share({ files: [File] }) — decode the data URL and hand
         * the file to the system share sheet via FileProvider.
         */
        /** navigator.share({ files }) — UNGATED (system sheet = consent UI). */
        @JavascriptInterface
        public void shareFile(final String title, final String dataUrl,
                              final String fileName, final String mime) {
            debugLog("shareFile invoked: " + fileName);
            activity.runOnUiThread(() -> activity.shareFileNative(title, dataUrl, fileName, mime));
        }

        /**
         * Blob-download callback: the injected fetch/FileReader snippet hands
         * the payload back as a data-URL string. Replaces the old
         * window.__blobResult polling, which capped downloads at 2 seconds.
         */
        @JavascriptInterface
        public void onBlobResult(final String dataUrl) {
            if (!hostAllowed()) return;
            activity.runOnUiThread(() -> activity.handleBlobResult(dataUrl));
        }

        /** Blob-download callback: the in-page fetch or read failed. */
        @JavascriptInterface
        public void onBlobFailed() {
            activity.runOnUiThread(() -> activity.handleBlobFailed());
        }

        /**
         * Direct blob-download path: fired by the anchor-click hook in
         * injectAllOverrides() while the blob URL is still alive. Carries the
         * suggested filename plus the full data-URL payload.
         */
        @JavascriptInterface
        public void onBlobDownload(final String name, final String dataUrl) {
            if (!hostAllowed()) return;
            activity.runOnUiThread(() -> activity.handleBlobDownload(name, dataUrl));
        }

        /**
         * Chunked blob transfer: large exports (PDF etc.) are delivered in
         * 256KB base64 pieces to stay clear of any JS-to-Java bridge string
         * limits. @JavascriptInterface calls are synchronous from JS, so the
         * chunks arrive in order; the final chunk (index == total-1)
         * assembles and saves the file.
         */
        @JavascriptInterface
        public void onBlobChunk(final String name, final String mime, final int index,
                                final int total, final String data) {
            String b64 = null;
            synchronized (activity.blobChunksLock) {
                StringBuilder sb = activity.blobChunks.get(name);
                if (sb == null) {
                    sb = new StringBuilder();
                    activity.blobChunks.put(name, sb);
                }
                sb.append(data);
                if (index == total - 1) {
                    b64 = sb.toString();
                    activity.blobChunks.remove(name);
                }
            }
            if (b64 != null) {
                final String payload = b64;
                activity.runOnUiThread(() ->
                        activity.handleBlobDownload(name, "data:" + mime + ";base64," + payload));
            }
        }

        /**
         * Debug telemetry (debug builds only): a visible toast reporting
         * which page APIs the site actually exercises (share, window.open,
         * blob downloads). Invaluable when diagnosing site features that
         * misbehave inside a WebView — no adb needed.
         */
        @JavascriptInterface
        public void debugLog(final String msg) {
            // Site-side diagnostic channel — logcat only (experimental
            // builds). Never toasts on screen: every Toast.show() is a
            // synchronous binder round-trip on the UI thread (the
            // v6.24.31 navigation-hot-path regression).
            if (BuildConfig.EXPERIMENTAL) {
                Log.d(TAG, "bridge: " + msg);
            }
        }

        /** Drop-injection result (functional, not debug): clears the manual
         *  fallback on success, prompts the user on failure. */
        @JavascriptInterface
        public void onFileDropResult(final boolean ok, final String detail) {
            activity.runOnUiThread(() -> activity.handleFileDropResult(ok, detail));
        }
    }

    /** Share text (and/or a URL) through the system share sheet. */
    private void shareTextNative(String text, String url) {
        Log.i(TAG, "shareText bridge reached");
        try {
            StringBuilder sb = new StringBuilder();
            if (text != null && !text.isEmpty()) sb.append(text);
            if (url != null && !url.isEmpty()) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(url);
            }
            if (sb.length() == 0) return;
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType("text/plain");
            share.putExtra(Intent.EXTRA_TEXT, sb.toString());
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(share, "Share"));
        } catch (Exception e) {
            Log.e(TAG, "shareTextNative failed", e);
            Toast.makeText(this, "Share failed", Toast.LENGTH_SHORT).show();
        }
    }

    /** Share a single file (decoded from a data URL) through the system sheet. */
    private void shareFileNative(String title, String dataUrl, String fileName, String mime) {
        Log.i(TAG, "shareFile bridge reached: " + fileName);
        new Thread(() -> {
            try {
                if (dataUrl == null || !dataUrl.startsWith("data:")) return;
                int comma = dataUrl.indexOf(',');
                if (comma < 0) return;
                String realMime = mime != null && mime.contains("/") ? mime : guessDataUrlMime(dataUrl);
                String ext = extensionForMime(realMime);
                String name = sanitizeSharedFileName(
                        fileName != null && !fileName.isEmpty() ? fileName : "shared_file");
                if (ext != null && !name.toLowerCase(Locale.ROOT).endsWith("." + ext)) {
                    name += "." + ext;
                }
                byte[] bytes = android.util.Base64.decode(dataUrl.substring(comma + 1),
                        android.util.Base64.DEFAULT);
                File outFile = new File(getCacheDir(), name);
                java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile);
                fos.write(bytes);
                fos.flush();
                fos.close();
                Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", outFile);
                runOnUiThread(() -> {
                    try {
                        Intent share = new Intent(Intent.ACTION_SEND);
                        share.setType(realMime != null ? realMime : "application/octet-stream");
                        share.putExtra(Intent.EXTRA_STREAM, uri);
                        if (title != null && !title.isEmpty()) {
                            share.putExtra(Intent.EXTRA_TEXT, title);
                        }
                        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(Intent.createChooser(share, "Share"));
                    } catch (Exception e) {
                        Log.e(TAG, "shareFileNative intent failed", e);
                        Toast.makeText(MainActivity.this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                });
            } catch (Exception e) {
                Log.e(TAG, "shareFileNative failed", e);
                runOnUiThread(() -> Toast.makeText(MainActivity.this,
                        "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private static String guessDataUrlMime(String dataUrl) {
        try {
            int comma = dataUrl.indexOf(',');
            String meta = dataUrl.substring(5, Math.max(comma, dataUrl.length()));
            int semi = meta.indexOf(';');
            return semi > 0 ? meta.substring(0, semi) : "application/octet-stream";
        } catch (Throwable t) {
            return "application/octet-stream";
        }
    }

    private void openUrlInBrowser(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Log.e(TAG, "openUrlInBrowser failed", e);
            Toast.makeText(this, "Cannot open URL", Toast.LENGTH_SHORT).show();
        }
    }

    private void setupClients(final WebView webView) {
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                // Do not mirror the site's console (it can contain chat
                // fragments) into logcat on release builds.
                if (BuildConfig.EXPERIMENTAL) {
                    Log.d(TAG, cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                handleWebPermissionRequest(request);
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                // Shared implementation (was previously duplicated in the
                // popup client, and the two copies had drifted apart).
                return openFileChooser(callback);
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // The window.open JS override handles external links (X, Reddit, LinkedIn)
                // BEFORE they reach onCreateWindow. Internal popups (share menu, OAuth)
                // go through createPopup which creates a proper popup WebView.
                return createPopup(resultMsg);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                // Legacy callback (API < 24, fires for all frames): original
                // permissive behavior — null-host URLs must load, or blob:/
                // data: iframe apps (investigation panel) break.
                return shouldOverrideNavigationFrame(url);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v,
                                                    android.webkit.WebResourceRequest request) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N
                        && !request.isForMainFrame()) {
                    return shouldOverrideNavigationFrame(request.getUrl().toString());
                }
                return shouldOverrideNavigation(request.getUrl().toString());
            }

            @SuppressWarnings("deprecation")
            @Override
            public void onReceivedError(WebView view, int errorCode,
                                        String description, String failingUrl) {
                // Legacy callback (API < 23) fires for the main frame only.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                    showOfflineDialog();
                }
            }

            @Override
            public void onReceivedError(WebView view,
                                        android.webkit.WebResourceRequest request,
                                        android.webkit.WebResourceError error) {
                // API 23+: react to main-frame failures only, so a failed
                // subresource on the SPA does not trigger the dialog.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                        && request.isForMainFrame()) {
                    showOfflineDialog();
                }
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                // Renderer death (OOM / driver crash): rebuild the WebView in
                // place instead of letting the whole process die.
                boolean wasMain = (view == webview);
                // didCrash()==true → renderer crashed; false → OS killed it
                // for memory pressure (the more common case on resume from
                // task manager).
                boolean didCrash = (detail != null) && detail.didCrash();
                String reasonStr = didCrash ? "CRASH" : "OOM_KILL";
                Log.e(TAG, "WebView renderer gone; recreating WebView (main=" + wasMain + ", reason=" + reasonStr + ")");
                if (wasMain) {
                    initialLoadComplete = false;
                    // Don't immediately show the loading overlay — let
                    // onPageStarted's 200ms-delayed show handle it. If the
                    // page reloads from cache within that window (typical on
                    // resume after Android killed the renderer in the
                    // background), the overlay never shows at all and the
                    // user just sees a brief black WebView instead of the
                    // full loading-screen flash. Fixes the "brief black
                    // screen on resume from task manager" report (present
                    // since the official v6.24 release).
                    recreateMainWebView();
                    loadUrlWithHeaders(webview, URL);
                } else {
                    removePopup(view);
                }
                return true;
            }

            @Override
            public void onPageStarted(WebView v, String url, Bitmap favicon) {
                super.onPageStarted(v, url, favicon);
                // Only show the loading overlay during the INITIAL page load.
                // Once initialLoadComplete is true, SPA navigations (ChatGPT's
                // settings tabs, share popups, etc.) fire onPageStarted too —
                // but we ignore them so the overlay doesn't flash.
                if (!initialLoadComplete && loadingOverlay != null
                        && loadingOverlay.getVisibility() != View.VISIBLE) {
                    // Delay showing the overlay by 200ms. If the page loads
                    // from cache within that window (common on resume after
                    // renderer-gone), the overlay never appears and the user
                    // is spared the brief loading-screen flash. If the page
                    // takes longer, the overlay shows as usual.
                    webview.postDelayed(() -> {
                        if (!initialLoadComplete && loadingOverlay != null
                                && loadingOverlay.getVisibility() != View.VISIBLE) {
                            loadingOverlay.setVisibility(View.VISIBLE);
                            if (loadingLogo != null) {
                                boolean dark = isDarkMode();
                                loadingLogo.setImageResource(dark ? R.drawable.logo_white : R.drawable.logo_black);
                                loadingLogo.startAnimation(
                                        AnimationUtils.loadAnimation(MainActivity.this, R.anim.spin_fade));
                            }
                        }
                    }, 200);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                // Page loaded successfully → mark this launch as non-crashing.
                CrashTracker.reset();
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                kickPendingSharePipelines();
                // If the initial load is already complete (SPA navigation or
                // the DOM-ready signal already fired), do nothing — no
                // overlay to hide.
                if (initialLoadComplete) return;
                // FALLBACK ONLY. The primary overlay-dismissal signal is the
                // DOM-ready watcher (PAGE_READY_WATCHER_JS → onDomReady),
                // which fires the moment the composer + late splash markers
                // are really rendered — usually well before or after this
                // point, never tied to the load event. This timer only
                // guarantees the overlay cannot get stuck if the watcher
                // never ran at all (ancient WebView, bridge failure).
                webview.postDelayed(() -> {
                    if (loadingOverlay == null || initialLoadComplete) return;
                    hideLoadingOverlayNow();
                }, 3500);
            }

        });
    }

    /**
     * DOM-ready signal from the page (AndroidBridge.pageReady, driven by
     * PAGE_READY_WATCHER_JS): the composer plus the last-appearing splash
     * elements are REALLY in the rendered DOM. Dismiss the loading overlay
     * right now — no blind delay, no waiting for the load event.
     * Idempotent: after the first call (or the fallback path) the
     * initialLoadComplete flag makes every later signal a no-op, so SPA
     * re-navigation, OAuth round trips and renderer-crash reloads are all
     * safe. (Renderer crash resets the flag and shows the overlay again —
     * the fresh document re-runs the watcher and re-fires this.)
     */
    void onDomReady() {
        if (isFinishing() || initialLoadComplete) return;
        hideLoadingOverlayNow();
        // A share arrived while the page was still booting: the DOM signal
        // means the composer is interactive NOW — start the pipeline
        // immediately instead of waiting for onPageFinished.
        kickPendingSharePipelines();
    }

    /**
     * Set initialLoadComplete and fade the loading overlay out.
     * The flag is set BEFORE starting the animation: any onPageStarted that
     * fires during the fade window sees initialLoadComplete=true and skips
     * re-showing the overlay (SPA navigation during fade-out used to make
     * the loading screen flash).
     */
    private void hideLoadingOverlayNow() {
        if (loadingOverlay == null || initialLoadComplete) return;
        initialLoadComplete = true;
        Animation fadeOut = AnimationUtils.loadAnimation(MainActivity.this, R.anim.fade_out);
        fadeOut.setAnimationListener(new Animation.AnimationListener() {
            @Override
            public void onAnimationStart(Animation animation) {}
            @Override
            public void onAnimationEnd(Animation animation) {
                loadingOverlay.setVisibility(View.GONE);
                if (loadingLogo != null) loadingLogo.clearAnimation();
            }
            @Override
            public void onAnimationRepeat(Animation animation) {}
        });
        loadingOverlay.startAnimation(fadeOut);
    }

    /**
     * Launch the deferred share pipelines (a file or text that arrived while
     * the page was still loading). Called from BOTH onPageFinished and
     * onDomReady — whoever comes first wins; the flags are cleared
     * synchronously on the UI thread before the async wait begins, so a
     * second invocation is always a no-op (no double attach).
     */
    private void kickPendingSharePipelines() {
        // A shared file arrived while the page was still loading — wait
        // for the REAL composer, then inject via drop events.
        if (pendingAutoAttach && pendingFileB64 != null) {
            pendingAutoAttach = false;
            waitForComposerReady(25000, MainActivity.this::runFileDropSequence);
        } else if (pendingAutoAttach) {
            pendingAutoAttach = false;  // no injectable payload — manual path
        }
        // Shared TEXT arrived while the page was still loading — wait
        // for the REAL composer, then focus it + open the keyboard.
        if (pendingAutoFocusText) {
            pendingAutoFocusText = false;
            waitForComposerReady(25000, MainActivity.this::settleComposerAfterAutoAttach);
        }
    }

    /**
     * Page overrides, installed at DOCUMENT START in EVERY frame (see
     * installDocumentStartOverrides). Because document-start scripts run
     * before any site code, the site can never capture a pre-override
     * reference. The same string doubles as the onPageFinished fallback for
     * WebViews that lack DOCUMENT_START_SCRIPT support — the guards inside
     * (_shareOverridden etc.) keep it idempotent.
     */
    /**
     * Page overrides, installed at DOCUMENT START in EVERY frame (see
     * installDocumentStartOverrides) via WebViewCompat.addDocumentStartJavaScript.
     *
     * Round-5 notes:
     *  - NO JavaScript-side origin gate: ChatGPT runs its share/export UI in
     *    blob: and cross-origin iframes, and a JS-side allowlist blocked
     *    exactly those calls (same "spinner stops, nothing happens" symptom
     *    as having no override at all). The Java side (WebAppInterface)
     *    still gates every bridge method on the WebView's real URL.
     *  - navigator.canShare is now HONEST (we support text/url/title or a
     *    single file), instead of blindly returning true.
     *  - The blob keeper is CAPPED (16 entries / 128MB, oldest evicted) so
     *    blob-heavy pages cannot be memory-bombed by our lifetime extension.
     *  - dbg() toasts (debug builds only) report when the page exercises
     *    share / window.open / blob-download — the telemetry that tells us
     *    which mechanism a misbehaving feature actually uses.
     */
    /**
     * Page overrides, installed at DOCUMENT START in EVERY frame (see
     * installDocumentStartOverrides) via WebViewCompat.addDocumentStartJavaScript.
     *
     * CRITICAL: Java string concatenation produces ONE LINE with no newlines,
     * so a single "//" comment anywhere in this script would comment out the
     * entire remainder of the script (the bug that made rounds 3-6 change
     * nothing). ALL comments here MUST be /* *\/ style.
     *
     * Round-7 notes:
     *  - No JS-side origin gate (ChatGPT share/export UI runs in blob: and
     *    cross-origin iframes); the Java side gates every bridge method.
     *  - Beacon: toasts "overrides active (main frame)" once per page load,
     *    so it is instantly visible whether this script is running at all.
     */
    private static final String PAGE_OVERRIDES_JS = "(function(){" +
            "  function dbg(m){ try { var b = window.AndroidBridge; if (b && b.debugLog) b.debugLog(String(m)); } catch(e) {} }" +
            "  /* 1. navigator.share -> system share sheet / clipboard */" +
            "  try {" +
            "    if (!window._shareOverridden) {" +
            "      window._shareOverridden = true;" +
            "      navigator.share = function(data) {" +
            "        try {" +
            "          var b = window.AndroidBridge;" +
            "          var d = data || {};" +
            "          var files = (d.files && d.files.length) ? d.files : null;" +
            "          var bm = b ? ((b.shareText ? 'S' : '') + (b.shareFile ? 'F' : '') + (b.copyToClipboard ? 'C' : '') + (b.debugLog ? 'D' : '')) : 'none';" +
            "          dbg('share called: files=' + (files ? files.length : 0) + ' text=' + (d.text ? 'yes' : 'no') + ' url=' + (d.url ? 'yes' : 'no') + ' bridge=' + bm);" +
            "          if (!b) return Promise.reject(new Error('No share support'));" +
            "          if (files && files.length === 1 && b.shareFile) {" +
            "            dbg('dispatching shareFile');" +
            "            var f = files[0];" +
            "            var fr = new FileReader();" +
            "            fr.onloadend = function(){" +
            "              try { b.shareFile(String(d.title || d.text || f.name || ''), String(fr.result), String(f.name || 'file'), String(f.type || '')); } catch(e) {}" +
            "            };" +
            "            fr.onerror = function(){" +
            "              try { dbg('share: file read failed'); } catch(e) {}" +
            "              try { if (d.text) b.copyToClipboard(String(d.text)); } catch(e) {}" +
            "            };" +
            "            fr.readAsDataURL(f);" +
            "            return Promise.resolve();" +
            "          }" +
            "          if (files && files.length > 1) return Promise.reject(new Error('Multiple files not supported'));" +
            "          if ((d.text || d.url) && b.shareText) {" +
            "            dbg('dispatching shareText');" +
            "            b.shareText(String(d.text || ''), String(d.url || ''));" +
            "            return Promise.resolve();" +
            "          }" +
            "          if (d.text && b.copyToClipboard) {" +
            "            b.copyToClipboard(String(d.text));" +
            "            return Promise.resolve();" +
            "          }" +
            "          return Promise.reject(new Error('Nothing to share'));" +
            "        } catch(e) { return Promise.reject(e); }" +
            "      };" +
            "      navigator.canShare = function(data) {" +
            "        try {" +
            "          var d = data || {};" +
            "          if (d.files && d.files.length) return d.files.length === 1;" +
            "          return !!(d.text || d.url || d.title);" +
            "        } catch(e) { return false; }" +
            "      };" +
            "      try { navigator.share.toString = function(){ return 'function share() { [native code] }'; }; } catch(e) {}" +
            "      try { navigator.canShare.toString = function(){ return 'function canShare() { [native code] }'; }; } catch(e) {}" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 2. document.execCommand('copy') fallback */" +
            "  try {" +
            "    if (!window._execOverridden) {" +
            "      window._execOverridden = true;" +
            "      var origExec = document.execCommand.bind(document);" +
            "      document.execCommand = function(cmd, showUI, value) {" +
            "        if (cmd === 'copy') {" +
            "          var sel = window.getSelection();" +
            "          if (sel && sel.toString()) {" +
            "            try {" +
            "              if (window.AndroidBridge) window.AndroidBridge.copyToClipboard(sel.toString());" +
            "            } catch(e) {}" +
            "            return true;" +
            "          }" +
            "        }" +
            "        return origExec(cmd, showUI, value);" +
            "      };" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 3. blob lifetime keeper (capped: 16 blobs / 128MB) */" +
            "  try {" +
            "    if (!window._blobKeep) {" +
            "      window._blobKeep = true;" +
            "      var origCreate = URL.createObjectURL;" +
            "      var origRevoke = URL.revokeObjectURL;" +
            "      var store = {};" +
            "      var order = [];" +
            "      var total = 0;" +
            "      function evict(){" +
            "        try {" +
            "          while (order.length > 16 || total > 134217728) {" +
            "            var u = order.shift();" +
            "            if (u === undefined) break;" +
            "            var blob = store[u];" +
            "            if (blob && blob.size) total -= blob.size;" +
            "            delete store[u];" +
            "            try { origRevoke.call(URL, u); } catch(e) {}" +
            "          }" +
            "        } catch(e) {}" +
            "      }" +
            "      URL.createObjectURL = function(blob) {" +
            "        var u = origCreate.call(URL, blob);" +
            "        try {" +
            "          store[u] = blob;" +
            "          order.push(u);" +
            "          if (blob && blob.size) total += blob.size;" +
            "          evict();" +
            "        } catch(e) {}" +
            "        return u;" +
            "      };" +
            "      URL.revokeObjectURL = function(u) {" +
            "        setTimeout(function(){" +
            "          try {" +
            "            delete store[u];" +
            "            var i = order.indexOf(u);" +
            "            if (i >= 0) order.splice(i, 1);" +
            "            origRevoke.call(URL, u);" +
            "          } catch(e) {}" +
            "        }, 600000);" +
            "      };" +
            "      window.__webgptBlobs = store;" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 4. blob download interception: prototype click + capture listener */" +
            "  function webgptSendChunks(name, mime, b64){" +
            "    try {" +
            "      var b = window.AndroidBridge;" +
            "      if (!b) return false;" +
            "      if (!b.onBlobChunk) {" +
            "        if (b.onBlobDownload) b.onBlobDownload(String(name), 'data:' + mime + ';base64,' + b64);" +
            "        return true;" +
            "      }" +
            "      var CH = 262144;" +
            "      var total = Math.ceil(b64.length / CH);" +
            "      if (total < 1) total = 1;" +
            "      for (var i = 0; i < total; i++) {" +
            "        b.onBlobChunk(String(name), String(mime), i, total, b64.substring(i * CH, Math.min((i + 1) * CH, b64.length)));" +
            "      }" +
            "      return true;" +
            "    } catch (e) { return false; }" +
            "  }" +
            "  function webgptFindBlob(href){" +
            "    try {" +
            "      var st = window.__webgptBlobs;" +
            "      if (st && st[href]) return st[href];" +
            /* same-origin child frames may hold the blob (export UI runs in an iframe) */
            "      for (var i = 0; i < window.frames.length; i++) {" +
            "        try { var fs = window.frames[i].__webgptBlobs; if (fs && fs[href]) return fs[href]; } catch (e) {}" +
            "      }" +
            "      try { var ps = window.parent.__webgptBlobs; if (ps && ps[href]) return ps[href]; } catch (e) {}" +
            "      return null;" +
            "    } catch (e) { return null; }" +
            "  }" +
            "  function webgptSendDataUrl(name, dataUrl){" +
            "    try {" +
            "      var b = window.AndroidBridge;" +
            "      if (!b) return false;" +
            "      var comma = dataUrl.indexOf(',');" +
            "      if (comma < 0) return false;" +
            "      var meta = dataUrl.substring(5, comma);" +
            "      var semi = meta.indexOf(';');" +
            "      var mime = semi > 0 ? meta.substring(0, semi) : 'application/octet-stream';" +
            "      return webgptSendChunks(name, mime, dataUrl.substring(comma + 1));" +
            "    } catch (e) { return false; }" +
            "  }" +
            /* THE KEY INSIGHT: revoking a blob URL does NOT destroy the Blob
               object. Our keeper stored the object at createObjectURL time,
               so reading the OBJECT via FileReader works even after the URL
               is dead — no URL resolution needed at all. This is the path a
               real browser's download manager effectively takes. */
            "  function webgptFetchBlob(href, name, retry){" +
            "    try {" +
            "      dbg('blob download: ' + name);" +
            "      var blobObj = webgptFindBlob(href);" +
            "      if (blobObj) {" +
            "        dbg('blob from store: ' + (blobObj.size || '?') + ' bytes');" +
            "        var fr = new FileReader();" +
            "        fr.onloadend = function(){" +
            "          try { dbg('blob read ok'); webgptSendDataUrl(name || 'download', String(fr.result)); } catch(e) {}" +
            "        };" +
            "        fr.onerror = function(){ dbg('blob read failed'); if (retry) retry(); };" +
            "        fr.readAsDataURL(blobObj);" +
            "        return;" +
            "      }" +
            "      dbg('blob store miss');" +
            /* sync XHR fallback (worker-created URLs resolvable from this frame) */
            "      try {" +
            "        var xhr = new XMLHttpRequest();" +
            "        xhr.open('GET', href, false);" +
            "        xhr.overrideMimeType('text/plain; charset=x-user-defined');" +
            "        xhr.send();" +
            "        var s = xhr.responseText || '';" +
            "        if ((xhr.status === 200 || xhr.status === 0) && s.length > 0) {" +
            "          var ct = xhr.getResponseHeader('Content-Type') || '';" +
            "          if (ct.indexOf(';') > 0) ct = ct.split(';')[0];" +
            "          if (!ct) ct = 'application/octet-stream';" +
            "          var parts = [];" +
            "          for (var i = 0; i < s.length; i += 0x2000) {" +
            "            var end = Math.min(i + 0x2000, s.length);" +
            "            var buf = new Array(end - i);" +
            "            for (var j = i; j < end; j++) buf[j - i] = s.charCodeAt(j) & 0xFF;" +
            "            parts.push(String.fromCharCode.apply(null, buf));" +
            "          }" +
            "          var b64 = btoa(parts.join(''));" +
            "          dbg('blob sync ok: ' + s.length + ' bytes');" +
            "          webgptSendChunks(name || 'download', ct, b64);" +
            "          return;" +
            "        }" +
            "        dbg('blob sync status ' + xhr.status);" +
            "      } catch (e) { dbg('blob sync xhr failed'); }" +
            /* async fetch fallback (keeper-preserved URLs) */
            "      fetch(href)" +
            "        .then(function(r){ return r.blob(); })" +
            "        .then(function(b){" +
            "          dbg('blob fetch ok: ' + b.size + ' bytes');" +
            "          var fr = new FileReader();" +
            "          fr.onloadend = function(){" +
            "            try { dbg('blob read ok'); webgptSendDataUrl(name || 'download', String(fr.result)); } catch(e) {}" +
            "          };" +
            "          fr.onerror = function(){ dbg('blob read failed'); if (retry) retry(); };" +
            "          fr.readAsDataURL(b);" +
            "        })" +
            "        .catch(function(e){ dbg('blob fetch failed'); if (retry) retry(); });" +
            "    } catch (e) { dbg('blob hook error'); if (retry) retry(); }" +
            "  }" +
            "  try {" +
            "    if (!window._dlHook) {" +
            "      window._dlHook = true;" +
            "      var origClick = HTMLAnchorElement.prototype.click;" +
            "      HTMLAnchorElement.prototype.click = function() {" +
            "        try {" +
            "          var href = this.href || '';" +
            "          if (href.indexOf('blob:') === 0 && this.hasAttribute('download')) {" +
            "            var self = this, args = arguments;" +
            "            webgptFetchBlob(href, this.getAttribute('download') || 'download', function(){" +
            "              try { origClick.apply(self, args); } catch (e) {}" +
            "            });" +
            "            return;" +
            "          }" +
            "        } catch (e) {}" +
            "        return origClick.apply(this, arguments);" +
            "      };" +
            "      document.addEventListener('click', function(ev){" +
            "        try {" +
            "          var t = ev && ev.target && ev.target.closest ? ev.target.closest('a[download]') : null;" +
            "          if (t) {" +
            "            var href = t.href || '';" +
            "            if (href.indexOf('blob:') === 0) {" +
            "              var b3 = window.AndroidBridge;" +
            "              if (b3 && b3.onBlobChunk) {" +
            /* the sync-XHR path will deliver the file; suppress the default
               download so the (dead-URL) DownloadListener fallback and its
               failure toasts never fire */
            "                try { ev.preventDefault(); ev.stopPropagation(); } catch (e2) {}" +
            "              }" +
            "              webgptFetchBlob(href, t.getAttribute('download') || 'download', null);" +
            "            }" +
            "          }" +
            "        } catch (e) {}" +
            "      }, true);" +
            "    }" +
            "  } catch(e) {}" +
            "  /* 5. window.open — NO HOOK. An earlier debug-only hook here" +
            "     * (dbg('window.open: '+url) before origOpen.apply) broke the" +
            "     * user-gesture context that WebView needs for onCreateWindow" +
            "     * to fire. The toast appeared but the popup was never" +
            "     * created, so external links (X, Reddit, LinkedIn) never" +
            "     * reached the user's default browser. Routing now happens" +
            "     * entirely in the popup's shouldOverrideUrlLoading via" +
            "     * openUrlInBrowser — same as the official release. */" +
            "  /* 6. beacon: proves the script is running (main frame, once per load) */" +
            "  try {" +
            "    if (window.top === window && !window.__webgptBeacon) {" +
            "      window.__webgptBeacon = true;" +
            "      dbg('overrides active (main frame)');" +
            "    }" +
            "  } catch(e) {}" +
            /* 7. LOAD-STATE TRACKER — event-based fully-loaded detection.
             * Two independent settle signals, both device-speed independent:
             *   - NET: last time a fetch/XHR was STARTED (streaming-friendly;
             *     long-lived responses started long ago do not block)
             *   - DOM: last DOM mutation observed anywhere (hydration, SPA
             *     re-renders, token streaming — all mutate continuously)
             * A page is settled when the composer exists, nothing mutated for
             * 2s, and no request started for 1.5s. This is the in-page
             * equivalent of Puppeteer's networkidle heuristic. */
            "  try {" +
            "    if (!window.__webgptLoad) {" +
            "      var L = {lastMut: Date.now(), lastStart: Date.now(), n: 0};" +
            "      window.__webgptLoad = L;" +
            "      var origFetch = window.fetch;" +
            "      if (origFetch) {" +
            "        window.fetch = function(){" +
            "          L.lastStart = Date.now();" +
            "          return origFetch.apply(window, arguments);" +
            "        };" +
            "      }" +
            "      var origOpen = XMLHttpRequest.prototype.open;" +
            "      XMLHttpRequest.prototype.open = function(){" +
            "        L.lastStart = Date.now();" +
            "        return origOpen.apply(this, arguments);" +
            "      };" +
            "      var mo = new MutationObserver(function(muts){ L.n += muts.length; L.lastMut = Date.now(); });" +
            "      mo.observe(document, {childList: true, subtree: true, attributes: true, characterData: true});" +
            "    }" +
            "  } catch(e) {}" +
            "})();";

    /**
     * PAGE-READY WATCHER — DOM-driven "site REALLY loaded" signal.
     *
     * Problem being solved: onPageFinished fires when the document load
     * event runs, which on chatgpt.com can land long AFTER the React app
     * has hydrated and rendered (streamed HTML, late subresources) — so the
     * loading overlay used to sit on top of an already-usable page for
     * seconds ("site loaded, app not responding").
     *
     * What the site REALLY looks like when done (verified against full
     * DOM snapshots of the loaded page in both variants):
     *   - the composer (id=prompt-textarea / contenteditable textbox), and
     *   - one of the LAST elements to appear:
     *       * the splash greeting — wrapper div carries the stable
     *         attribute data-splash-headline-option (value varies by time
     *         and locale: ON_YOUR_MIND, SHOULD_WE_BEGIN, ...). In the
     *         mobile DOM this wrapper EXISTS but is CSS-hidden
     *         ("hidden sm:block") — so we test VISIBILITY
     *         (getClientRects), not presence.
     *       * the mobile suggestion chips — buttons inside
     *         [data-testid=use-case-prompt-chips] ("Create an image...",
     *         "Write or edit", "Search the web" — text varies by locale).
     *       * a restored conversation — [data-testid^=conversation-turn].
     * Text is never matched — only stable testids/attributes, so it works
     * across languages and greeting rotations.
     *
     * Fallbacks so the overlay can never get stuck:
     *   - settle heuristic (same as waitForComposerReady): composer exists
     *     AND no DOM mutation for 2s AND no fetch/XHR start for 1.5s
     *     (reads window.__webgptLoad, installed by PAGE_OVERRIDES_JS);
     *   - hard cap: fire 8s after injection no matter what;
     *   - Java-side onPageFinished fallback timer.
     *
     * Runs in the MAIN frame only (window.top check + hostname gate), and
     * only on the main WebView — popups never register it. Pure polling at
     * 200ms (no MutationObserver): each tick is a couple of querySelectors
     * plus a layout read, ~5x/sec, cheaper than observer-driven layout
     * thrash during hydration.
     */
    private static final String PAGE_READY_WATCHER_JS = "(function(){" +
            "  try {" +
            "    if (window.top !== window) return;" +
            "    var hst = location.hostname || '';" +
            "    if (hst.indexOf('chatgpt.com') < 0 && hst.indexOf('openai.com') < 0) return;" +
            "    if (window._webgptReadyWatch) return;" +
            "    window._webgptReadyWatch = true;" +
            "    var sent = false, started = Date.now();" +
            "    function fire(){" +
            "      if (sent) return;" +
            "      sent = true;" +
            "      try {" +
            "        if (window.AndroidBridge && window.AndroidBridge.pageReady)" +
            "          window.AndroidBridge.pageReady();" +
            "      } catch(e) {}" +
            "    }" +
            "    function vis(el){" +
            "      try {" +
            "        var r = el && el.getClientRects();" +
            "        /* rendered AND non-zero size: on mobile the greeting wrapper" +
            "           itself is NOT display:none (only its inner child carries" +
            "           hidden sm:block), so it still lays out as an EMPTY flex" +
            "           box — a zero-height client rect. length>0 alone would" +
            "           mistake that for a visible greeting. */" +
            "        return !!(r && r.length && r[0].height > 0 && r[0].width > 0);" +
            "      }" +
            "      catch(e) { return false; }" +
            "    }" +
            "    function tick(){" +
            "      if (sent) return true;" +
            "      var now = Date.now();" +
            "      if (now - started > 8000) { fire(); return true; }" +
            "      try {" +
            "        var box = document.getElementById('prompt-textarea')" +
            "               || document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]');" +
            "        if (box) {" +
            "          if (vis(document.querySelector('[data-splash-headline-option]'))) { fire(); return true; }" +
            "          if (vis(document.querySelector('[data-testid=\"use-case-prompt-chips\"] button'))) { fire(); return true; }" +
            "          if (document.querySelector('[data-testid^=\"conversation-turn\"]')) { fire(); return true; }" +
            "          var L = window.__webgptLoad;" +
            "          if (L && (now - L.lastMut > 2000) && (now - L.lastStart > 1500)) { fire(); return true; }" +
            "        }" +
            "      } catch(e) {}" +
            "      return false;" +
            "    }" +
            "    var iv = setInterval(function(){ if (tick()) clearInterval(iv); }, 200);" +
            "  } catch(e) {}" +
            "})();";

    /**
     * Focus guard — suppresses chatgpt.com's programmatic .focus() calls on
     * the composer when the user is browsing an EXISTING chat (URL path
     * starts with /c/ or /g/), so the soft keyboard does NOT auto-open on
     * page load / SPA remount / visibility-resume. The user's TAP on the
     * composer still focuses it (that path goes through C++
     * Element::focus, not the JS prototype) so input still works.
     *
     * Bypassed for ~6 seconds after a share intent lands (markShareActive
     * sets window.__webgptShareActiveUntil), so the share pipeline's own
     * focus() calls still work to commit the attachment.
     *
     * Backup mechanisms on top of the prototype patch:
     *  - document 'focusin' listener (capture phase): blurs the composer
     *    if the focus event is programmatic (not isTrusted) and no share
     *    is active.
     *  - 'visibilitychange' listener: blurs the composer on hidden (the
     *    JS thread is paused while hidden, so the blur is durable across
     *    resume — the SPA's resume-time .focus() can't race with it).
     *  - setInterval(stripAutofocus, 500): SPA remounts can re-add the
     *    autofocus attribute; strip it so even the C++ focus path
     *    doesn't auto-focus.
     *
     * All checks are host-gated to chatgpt.com / openai.com and
     * frame-gated to window.top === window so iframe shares / blob:
     * origins never see the guard.
     *
     * History — v1 (v6.24.21, Round 21) used a focusin listener with a
     * 50ms cooldown and the blur deferred via setTimeout(0). That failed
     * because chatgpt.com's React/SPA kept re-calling composer.focus()
     * in rAF/microtasks: by the time our deferred blur fired, the SPA
     * had already re-focused. The 50ms cooldown was supposed to break
     * the loop but it actually became the escape valve that let the
     * keyboard win after a few cycles — visible as a blinking caret
     * (the intermittent "|" the user reported) and the "opens → closes →
     * opens again" cycle. v2 (v6.24.22, Round 24) kills the loop at the
     * source by monkey-patching the prototype: programmatic .focus() on
     * the composer becomes a no-op, so no focus event ever fires, no
     * blur is needed, and there's no cooldown to leak through. The 3s
     * resume threshold from v1 was also dropped — the user explicitly
     * did not want the keyboard to reopen on return to an existing chat
     * at all, even after a long absence.
     */
    private static final String FOCUS_GUARD_JS = "(function(){  if (window.__webgptFocusGuard) return;  window.__webgptFocusGuard = true;  try { if (window.top !== window) return; } catch(e) { return; }  function hostOk(){ try { var h=(location.hostname||'').toLowerCase();    return h==='chatgpt.com'||h==='chat.openai.com'||h==='openai.com'      || h.endsWith('.chatgpt.com')||h.endsWith('.openai.com'); } catch(e){ return false; } }  if (!hostOk()) return;  function isExistingChat(){ try { var p=location.pathname||'';    return p.indexOf('/c/')===0 || (p.indexOf('/g/')===0 && p.length>3); } catch(e){ return false; } }  function isComposer(el){ if(!el||!el.matches) return false;    try { if(el.id==='prompt-textarea') return true;      if(el.closest && el.closest('#prompt-textarea')) return true;      if(el.matches('div[contenteditable=\"true\"][role=\"textbox\"]')) return true;      if(el.tagName==='TEXTAREA') return true; return false; } catch(e){ return false; } }  function shareActive(){ try { return Date.now() < (window.__webgptShareActiveUntil||0); } catch(e){ return false; } }  function shouldSuppress(el){ try { return isExistingChat() && !shareActive() && isComposer(el); } catch(e){ return false; } }  try {    var origFocus = HTMLElement.prototype.focus;    HTMLElement.prototype.focus = function(){      try { if (shouldSuppress(this)) return; } catch(e){}      return origFocus.apply(this, arguments);    };  } catch(e){}  document.addEventListener('focusin', function(e){    try {      if (shareActive()||!isExistingChat()) return;      var t=e.target; if(!isComposer(t)) return;      if (e.isTrusted) return;      try { t.blur(); } catch(_){}    } catch(_){}  }, true);  document.addEventListener('visibilitychange', function(){    try {      if (document.visibilityState==='hidden'){        if (shareActive()||!isExistingChat()) return;        var ae=document.activeElement;        if (ae && isComposer(ae)){ try{ ae.blur(); }catch(_){} }        return;      }      if (document.visibilityState!=='visible') return;      if (shareActive()||!isExistingChat()) return;      var ae2=document.activeElement;      if (ae2 && isComposer(ae2)){ try{ ae2.blur(); }catch(_){} }    } catch(_){}  });  try {    var stripAutofocus = function(){ try {      var sels=['#prompt-textarea[autofocus]',        'div[contenteditable=\"true\"][role=\"textbox\"][autofocus]',        'textarea[autofocus]'];      for (var i=0;i<sels.length;i++){        var els=document.querySelectorAll(sels[i]);        for (var j=0;j<els.length;j++){          try{ els[j].removeAttribute('autofocus'); }catch(_){}        }      }    } catch(e){} };    setInterval(stripAutofocus, 500);  } catch(e){}})();";

    /**
     * Wait until the SPA has REALLY loaded — using EVENTS, not timers:
     *   - the composer element exists, AND
     *   - the splash is fully rendered (visible greeting wrapper
     *     [data-splash-headline-option] or mobile suggestion chips — the
     *     LAST elements to appear, verified against full DOM snapshots;
     *     presence is not enough on mobile, where the desktop greeting
     *     wrapper sits CSS-hidden in the DOM — hence the visibility test),
     *     OR the settle heuristic:
     *   - no DOM mutation anywhere for 2s (hydration/SPA re-renders mutate
     *     continuously — the "greening text" phase), AND
     *   - no fetch/XHR STARTED for 1.5s (network settle; long-lived streams
     *     that began long ago do not block).
     * The splash shortcut only matches the EMPTY new-chat state (chips and
     * greeting never render inside an existing conversation), so shares
     * into an already-running chat keep the battle-tested quiet heuristic.
     * The tracker hooks (window.__webgptLoad) are installed at document
     * start by PAGE_OVERRIDES_JS, so the ages are real activity timestamps,
     * not elapsed-time guesses — fast devices settle fast, slow ones slow.
     * The deadline is only a safety net, never the primary mechanism.
     */
    private void waitForComposerReady(int maxMs, Runnable action) {
        final WebView wv = webview;
        if (wv == null || isFinishing()) return;
        final long deadline = SystemClock.elapsedRealtime() + maxMs;
        final Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (webview == null || isFinishing()) return;
            webview.evaluateJavascript(
                    "(function(){"
                            + "var el=!!(document.querySelector('#prompt-textarea')"
                            + "||document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')"
                            + "||document.querySelector('div[contenteditable=\"true\"]')"
                            + "||document.querySelector('textarea[placeholder]'));"
                            + "var hEl=document.querySelector('[data-splash-headline-option]');"
                            + "var cEl=document.querySelector('[data-testid=\"use-case-prompt-chips\"] button');"
                            + "function vis(e){try{var r=e&&e.getClientRects();return !!(r&&r.length&&r[0].height>0&&r[0].width>0)}catch(x){return false}}"
                            + "var mk=!!((hEl&&vis(hEl))||(cEl&&vis(cEl)));"
                            + "var L=window.__webgptLoad;"
                            + "if(!L) return el;"
                            + "var now=Date.now();"
                            + "var domAge=now-L.lastMut, netAge=now-L.lastStart;"
                            + "return (el && (mk || (domAge>2000 && netAge>1500)))"
                            + " ? 'ready|'+domAge+'|'+netAge : 'wait|'+domAge+'|'+netAge;"
                            + "})();",
                    res -> {
                        // evaluateJavascript delivers strings JSON-quoted:
                        // strip the surrounding quotes before parsing.
                        String sig = res == null ? "" : res;
                        if (sig.length() >= 2 && sig.startsWith("\"") && sig.endsWith("\"")) {
                            sig = sig.substring(1, sig.length() - 1);
                        }
                        if (sig.startsWith("ready")) {
                            action.run();
                        } else if (SystemClock.elapsedRealtime() < deadline) {
                            webview.postDelayed(tick[0], 700);
                        } else {
                            action.run();
                        }
                    });
        };
        tick[0].run();
    }

    /** onPageFinished fallback (and re-injection after SPA navigations). */
    private void injectAllOverrides(WebView v) {
        v.evaluateJavascript(PAGE_OVERRIDES_JS, null);
        // Ready watcher fallback for WebViews without DOCUMENT_START_SCRIPT
        // support: starts late (page already rendered) but then usually finds
        // the markers on its very first tick. MAIN WebView only — the watcher
        // must never run in OAuth/share popups.
        if (v == webview) {
            v.evaluateJavascript(PAGE_READY_WATCHER_JS, null);
        }
    }

    /**
     * Feed the pending shared file straight into the site's composer via
     * HTML5 drop events: the base64 payload is pushed into the page in
     * chunks, then a File is constructed in JS and dragenter/dragover/drop
     * are dispatched on the composer. No + menu, no file chooser, none of
     * the focus-race fragility — this is the same code path the site uses
     * for real drag-and-drop attachments.
     */
    private void runFileDropSequence() {
        final String b64 = pendingFileB64;
        final String name = pendingFileName;
        final String mime = pendingFileMime;
        if (webview == null || isFinishing() || b64 == null || name == null) return;
        pendingFileB64 = null;  // consumed

        webview.evaluateJavascript("window.__webgptFileB64='';", null);
        final int CH = 524288;  // 512KB base64 chunks
        for (int i = 0; i < b64.length(); i += CH) {
            final String chunk = b64.substring(i, Math.min(i + CH, b64.length()));
            // base64 alphabet is JS-string-safe — no escaping needed
            webview.evaluateJavascript(
                    "(function(){window.__webgptFileB64=(window.__webgptFileB64||'')+'" + chunk + "';})();",
                    null);
        }

        final String safeName = name.replace("\\", "_").replace("'", "\\'");
        final String safeMime = (mime != null ? mime : "application/octet-stream").replace("'", "");
        // Mark the injection moment BEFORE dispatching: any file-chooser
        // opening during/right after the injection is a site re-trigger,
        // not user intent (see the guard in openFileChooser).
        lastFileInjectionAt = SystemClock.elapsedRealtime();
        /* PRIMARY PATH — hidden file input, NO drag events: the composer has
         * an <input type=file> (the + -> Files flow uses it). Setting
         * input.files programmatically and firing input+change runs the
         * site's own attach handler with ZERO drag events — which means the
         * full-screen drop overlay (triggered by synthetic dragenter/dragover
         * in earlier builds, with no reliable teardown) is never shown. */
        String dropJs = "(function(){" +
                "  try {" +
                "    window.__webgptShareActiveUntil = Date.now() + 6000;" +
                "    var b64 = window.__webgptFileB64 || '';" +
                "    window.__webgptFileB64 = null;" +
                "    var AB = window.AndroidBridge;" +
                "    if (!b64) { if (AB && AB.onFileDropResult) AB.onFileDropResult(false, 'no data'); return; }" +
                "    var bin = atob(b64);" +
                "    var n = bin.length;" +
                "    var bytes = new Uint8Array(n);" +
                "    for (var i = 0; i < n; i++) bytes[i] = bin.charCodeAt(i);" +
                "    var file = new File([bytes], '" + safeName + "', {type: '" + safeMime + "'});" +
                "    var dt = new DataTransfer();" +
                "    dt.items.add(file);" +
                "    var el = document.querySelector('#prompt-textarea')" +
                "          || document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')" +
                "          || document.querySelector('div[contenteditable=\"true\"]')" +
                "          || document.querySelector('textarea[placeholder]');" +
                "    var input = null;" +
                "    try {" +
                "      var ins = document.querySelectorAll('input[type=file]');" +
                "      for (var i = 0; i < ins.length; i++) { input = ins[i]; break; }" +
                "    } catch (e) {}" +
                "    if (input) {" +
                "      try { input.files = dt.files; } catch (e) { try { Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'files').set.call(input, dt.files); } catch (e2) {} }" +
                "      try { input.dispatchEvent(new Event('input', {bubbles: true})); } catch (e) {}" +
                "      try { input.dispatchEvent(new Event('change', {bubbles: true})); } catch (e) {}" +
                "      if (el) { try { el.focus({preventScroll: true}); } catch (e) {} }" +
                "      if (AB && AB.onFileDropResult) AB.onFileDropResult(true, 'input ' + n + ' bytes');" +
                "      return;" +
                "    }" +
                "    if (!el) { if (AB && AB.onFileDropResult) AB.onFileDropResult(false, 'no composer'); return; }" +
                /* FALLBACK — drag events (only when no file input exists).
                 * Synthetic dragenter/dragover can leave the site's drop
                 * overlay stuck, so fire document drop/dragend/dragleave and
                 * a page-level Escape afterwards to tear it down. */
                "    try { el.dispatchEvent(new DragEvent('dragenter', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { el.dispatchEvent(new DragEvent('dragover', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { el.dispatchEvent(new DragEvent('drop', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { document.dispatchEvent(new DragEvent('drop', {bubbles: true, cancelable: true, dataTransfer: dt})); } catch (e) {}" +
                "    try { document.dispatchEvent(new DragEvent('dragend', {bubbles: true, cancelable: true})); } catch (e) {}" +
                "    try { document.dispatchEvent(new DragEvent('dragleave', {bubbles: true, cancelable: true})); } catch (e) {}" +
                "    try { el.focus({preventScroll: true}); } catch (e) {}" +
                "    setTimeout(function(){" +
                "      try { document.dispatchEvent(new KeyboardEvent('keydown', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true})); } catch (e) {}" +
                "      try { document.dispatchEvent(new KeyboardEvent('keyup', {key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true})); } catch (e) {}" +
                "      try { el.focus({preventScroll: true}); } catch (e) {}" +
                "    }, 350);" +
                "    if (AB && AB.onFileDropResult) AB.onFileDropResult(true, 'dropped ' + n + ' bytes');" +
                "  } catch (e) {" +
                "    try { var AB2 = window.AndroidBridge; if (AB2 && AB2.onFileDropResult) AB2.onFileDropResult(false, String(e)); } catch (e2) {}" +
                "  }" +
                "})();";
        webview.evaluateJavascript(dropJs, null);

        // Re-assert focus + keyboard AFTER the drop lands: the SPA can still
        // run a late hydration pass that steals focus; several staggered
        // re-assertions keep the composer focused (and the attachment
        // committed) through it.
        webview.postDelayed(this::settleComposerAfterAutoAttach, 500);
        webview.postDelayed(this::settleComposerAfterAutoAttach, 1500);
        webview.postDelayed(this::settleComposerAfterAutoAttach, 2500);
        webview.postDelayed(this::showKeyboardForComposer, 800);
        webview.postDelayed(this::showKeyboardForComposer, 2000);
    }

    /** Bridge callback: the page accepted (or rejected) the injected drop. */
    private void handleFileDropResult(boolean ok, String detail) {
        Log.i(TAG, "drop result: " + (ok ? "ok" : "failed") + " " + detail);
        if (ok) {
            // Attached — clear the manual-fallback URI so it can never hijack
            // a later file-chooser invocation.
            pendingShareFileUri = null;
            // Verify 3s later that the attachment is STILL visible in the
            // composer (catches the site discarding it — e.g. an attach-limit
            // rejection — right after accepting it).
            final String checkName = pendingFileName;
            webview.postDelayed(() -> verifyAttachmentVisible(checkName), 3000);
        } else if (pendingShareFileUri != null) {
            Toast.makeText(this,
                    "Couldn't attach automatically — tap + and choose Files",
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Diagnostic: does the composer still show an attachment chip? */
    private void verifyAttachmentVisible(String name) {
        WebView wv = webview;
        if (wv == null || isFinishing() || name == null) return;
        final String jsName = name.replace("\\", "_").replace("'", "\\'");
        String js = "(function(){"
                + "var name='" + jsName + "';"
                + "var nodes=document.querySelectorAll('[data-testid*=attachment i],[class*=attachment i],[aria-label*=file i],div,span,button');"
                + "for(var i=0;i<nodes.length;i++){"
                + "  var t=((nodes[i].textContent||'')+' '+(nodes[i].getAttribute('aria-label')||''));"
                + "  if(t.indexOf(name)>=0 && t.length<300) return 'visible';"
                + "}"
                + "return 'gone';"
                + "})();";
        wv.evaluateJavascript(js, res -> {
            String r = res == null ? "" : res;
            if (r.length() >= 2 && r.startsWith("\"") && r.endsWith("\"")) {
                r = r.substring(1, r.length() - 1);
            }
            Log.i(TAG, "attach check: " + r);
        });
    }

    /**
     * Long-press context menu for images.
     * Shows a Material AlertDialog (same style as the WebView Manager) with
     * "Share image" and "Download image" options.
     */
    private void setupImageContextMenu(WebView webView) {
        webView.setOnLongClickListener(v -> {
            WebView.HitTestResult result = webView.getHitTestResult();
            if (result != null && result.getType() == WebView.HitTestResult.IMAGE_TYPE
                    && result.getExtra() != null) {
                showImageDialog(result.getExtra());
                return true;
            }
            return false;
        });
    }

    /**
     * Show a Material AlertDialog (same style as the WebView Manager) with
     * image action options. Uses a custom layout with icon + title + subtitle
     * rows for a richer Material 3 appearance.
     */
    private void showImageDialog(final String imageUrl) {
        View view = getLayoutInflater().inflate(R.layout.dialog_image_actions, null);
        View shareBtn = view.findViewById(R.id.action_share);
        View downloadBtn = view.findViewById(R.id.action_download);

        final com.google.android.material.dialog.MaterialAlertDialogBuilder builder =
                new com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                        .setTitle(R.string.image_actions_title)
                        .setView(view);

        final androidx.appcompat.app.AlertDialog dialog = builder.create();

        if (shareBtn != null) {
            shareBtn.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Loading image to share...", Toast.LENGTH_SHORT).show();
                downloadAndShareImageFile(imageUrl);
            });
        }
        if (downloadBtn != null) {
            downloadBtn.setOnClickListener(v -> {
                dialog.dismiss();
                Toast.makeText(this, "Downloading image...", Toast.LENGTH_SHORT).show();
                downloadImageToDownloads(imageUrl);
            });
        }
        dialog.show();
    }

    private void downloadAndShareImageFile(String url) {
        final String cookies = CookieManager.getInstance().getCookie(url);
        final String userAgent = webview.getSettings().getUserAgentString();

        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            java.io.OutputStream out = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Referer", "https://chatgpt.com/");
                conn.setRequestProperty("Accept", "image/*,*/*");

                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    final int c = code;
                    runOnUiThread(() -> Toast.makeText(this,
                            "Failed: HTTP " + c, Toast.LENGTH_LONG).show());
                    return;
                }

                String mime = conn.getContentType();
                if (mime != null && mime.contains(";")) {
                    mime = mime.split(";")[0].trim();
                }
                if (mime == null || !mime.startsWith("image/")) {
                    mime = "image/png";  // default
                }
                String ext = extensionForMime(mime);
                if (ext == null) ext = "png";

                File outFile = new File(getCacheDir(),
                        "shared_image_" + System.currentTimeMillis() + "." + ext);
                in = conn.getInputStream();
                out = new java.io.FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) {
                    out.write(buf, 0, n);
                }
                out.flush();
                final File finalOutFile = outFile;
                final String finalMime = mime;
                runOnUiThread(() -> shareFile(finalOutFile, finalMime));
            } catch (Exception e) {
                Log.e(TAG, "downloadAndShareImageFile failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (out != null) try { out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void shareFile(File file, String mime) {
        try {
            Uri uri = androidx.core.content.FileProvider.getUriForFile(this,
                    getPackageName() + ".fileprovider", file);
            Intent share = new Intent(Intent.ACTION_SEND);
            share.setType(mime);
            share.putExtra(Intent.EXTRA_STREAM, uri);
            share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            share.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(Intent.createChooser(share, "Share"));
        } catch (Exception e) {
            Log.e(TAG, "shareFile failed", e);
            Toast.makeText(this, "Share failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void downloadImageToDownloads(String url) {
        final String cookies = CookieManager.getInstance().getCookie(url);
        final String userAgent = webview.getSettings().getUserAgentString();
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream in = null;
            java.io.OutputStream out = null;
            try {
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                if (cookies != null) conn.setRequestProperty("Cookie", cookies);
                conn.setRequestProperty("User-Agent", userAgent);
                conn.setRequestProperty("Referer", "https://chatgpt.com/");
                conn.setRequestProperty("Accept", "image/*,*/*");
                // Byte-exact image bytes — see saveFileWithCookies.
                conn.setRequestProperty("Accept-Encoding", "identity");
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed: HTTP " + code, Toast.LENGTH_LONG).show());
                    return;
                }
                String mime = conn.getContentType();
                if (mime != null && mime.contains(";")) mime = mime.split(";")[0].trim();
                // Interstitial guard: never save an HTML error/login page as
                // an image — fail loudly instead (link expired / auth bounce).
                if (mime != null && mime.toLowerCase().startsWith("text/html")) {
                    Log.e(TAG, "Image download got HTML interstitial: " + url);
                    runOnUiThread(() -> Toast.makeText(this,
                            "Failed: the server returned a web page",
                            Toast.LENGTH_LONG).show());
                    return;
                }
                if (mime == null || !mime.startsWith("image/")) mime = "image/png";
                String ext = extensionForMime(mime);
                if (ext == null) ext = "png";
                String fileName = "chatgpt_image_" + System.currentTimeMillis() + "." + ext;

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentResolver resolver = getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    Uri collection = android.provider.MediaStore.Downloads
                            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri != null) {
                        out = resolver.openOutputStream(itemUri);
                    }
                } else {
                    File dl = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!dl.exists()) dl.mkdirs();
                    out = new java.io.FileOutputStream(new File(dl, fileName));
                }
                if (out == null) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed to save", Toast.LENGTH_LONG).show());
                    return;
                }
                in = conn.getInputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
                out.flush();
                final String fn = fileName;
                runOnUiThread(() -> Toast.makeText(this, "Saved to Download/" + fn, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "downloadImageToDownloads failed", e);
                runOnUiThread(() -> Toast.makeText(this, "Failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (in != null) try { in.close(); } catch (Exception ignored) {}
                if (out != null) try { out.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    private void setupDownloads(WebView webView) {
        webView.setDownloadListener((url, userAgent, contentDisposition, mimetype, contentLength) -> {
            Log.i(TAG, "Download requested: " + url + " (mime: " + mimetype + ")");
            if (url == null) return;
            if (url.startsWith("data:")) {
                // Data URLs carry the whole payload inline — save directly,
                // no second fetch needed.
                String filename = guessFilenameFromDownload(url, contentDisposition, mimetype);
                handleBlobDownload(filename, url);
                return;
            }
            if (url.startsWith("blob:")) {
                // Fallback for downloads the JS bridge did NOT already
                // deliver (store miss + sync XHR miss + async miss). When the
                // bridge just saved successfully, skip this dead-URL fetch so
                // its failure toasts do not fire pointlessly after a good save.
                if (SystemClock.elapsedRealtime() - blobBridgeSuccessAt < 8000) {
                    Log.i(TAG, "blob fallback suppressed");
                    return;
                }
                downloadBlobUrl(webView, url, contentDisposition, mimetype);
                return;
            }
            downloadWithCookies(url, userAgent, contentDisposition, mimetype);
        });
    }

    /**
     * Download a blob: URL by fetching it via in-page JavaScript and passing
     * the base64-encoded bytes back to native code via the JS bridge. Works
     * even after the site called revokeObjectURL() because our injected hook
     * delays the actual revocation.
     */
    private void downloadBlobUrl(WebView webView, String blobUrl, String contentDisposition, String mimetype) {
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show();
        // blob: URLs carry no usable filename and contentDisposition is
        // usually null — use a timestamp name; the real extension is fixed
        // from the payload's MIME type in handleBlobDownload.
        final String filename;
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            filename = guessFilenameFromDownload(blobUrl, contentDisposition, mimetype);
        } else {
            filename = "download_" + System.currentTimeMillis();
        }
        final String finalMime = mimetype != null ? mimetype : "application/octet-stream";
        pendingBlobFilename = filename;
        pendingBlobMime = finalMime;
        blobDownloadInFlight = true;
        String js = "(function(){" +
                "  function fail(){ try { window.AndroidBridge && AndroidBridge.onBlobFailed && AndroidBridge.onBlobFailed(); } catch(e) {} }" +
                "  try {" +
                "    fetch('" + blobUrl.replace("'", "\\'") + "')" +
                "      .then(function(r){ return r.blob(); })" +
                "      .then(function(b){" +
                "        var fr = new FileReader();" +
                "        fr.onloadend = function(){" +
                "          try {" +
                "            if (window.AndroidBridge && AndroidBridge.onBlobResult) { AndroidBridge.onBlobResult(String(fr.result)); }" +
                "            else { fail(); }" +
                "          } catch(e) { fail(); }" +
                "        };" +
                "        fr.onerror = fail;" +
                "        fr.readAsDataURL(b);" +
                "      })" +
                "      .catch(fail);" +
                "  } catch(e) { fail(); }" +
                "})();";
        webView.evaluateJavascript(js, null);
        // Safety net: if the bridge never answers (page navigated away,
        // renderer killed, ...) give up quietly after 30 seconds.
        webView.postDelayed(() -> {
            if (blobDownloadInFlight) {
                blobDownloadInFlight = false;
                pendingBlobFilename = null;
                pendingBlobMime = null;
                Toast.makeText(MainActivity.this,
                        "Download did not complete. Hold down on the image to open the share & download menu.",
                        Toast.LENGTH_LONG).show();
            }
        }, 30000);
    }

    /** Called by WebAppInterface.onBlobResult with a data-URL payload. */
    private void handleBlobResult(String dataUrl) {
        if (!blobDownloadInFlight || dataUrl == null) return;
        blobDownloadInFlight = false;
        String filename = pendingBlobFilename;
        pendingBlobFilename = null;
        pendingBlobMime = null;
        handleBlobDownload(filename, dataUrl);
    }

    /** Called by WebAppInterface.onBlobFailed when the in-page fetch failed. */
    private void handleBlobFailed() {
        if (!blobDownloadInFlight) return;
        blobDownloadInFlight = false;
        pendingBlobFilename = null;
        pendingBlobMime = null;
        Toast.makeText(this,
                "Can't download directly. Hold down on the image to open the share & download menu.",
                Toast.LENGTH_LONG).show();
    }

    /**
     * Direct blob/data download: payload arrives as a data URL
     * ("data:mime;base64,...") captured while the blob was still alive,
     * together with a suggested filename. Fixes the file extension from the
     * payload's real MIME type (PDF/Word/Markdown exports etc.).
     */
    private void handleBlobDownload(String name, String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:")) return;
        // Dedup: the anchor-click hook and the DownloadListener fallback can
        // both deliver the same blob (belt and suspenders). Skip a save if an
        // identical payload arrived moments ago.
        long now = SystemClock.elapsedRealtime();
        synchronized (blobSaveLock) {
            if (now - lastBlobSaveAt < 4000 && dataUrl.length() == lastBlobSaveLen) {
                Log.i(TAG, "blob duplicate skipped: " + name);
                return;
            }
            lastBlobSaveAt = now;
            lastBlobSaveLen = dataUrl.length();
        }
        Log.i(TAG, "blob download reached: " + name);
        blobBridgeSuccessAt = SystemClock.elapsedRealtime();
        String filename = sanitizeFilename(name != null && !name.isEmpty()
                ? name : ("download_" + System.currentTimeMillis()));
        String mime = guessDataUrlMime(dataUrl);
        String ext = extensionForMime(mime);
        if (ext != null && !filename.toLowerCase(Locale.ROOT)
                .endsWith("." + ext.toLowerCase(Locale.ROOT))) {
            int dot = filename.lastIndexOf('.');
            // Only strip an existing (wrong) extension when it is very short
            // or absent — never mangle names like "report.v2".
            if (dot > 0 && filename.length() - dot <= 5) {
                filename = filename.substring(0, dot);
            }
            filename += "." + ext;
        }
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return;
        String b64 = dataUrl.substring(comma + 1);
        Toast.makeText(this, "Downloading " + filename + "...", Toast.LENGTH_SHORT).show();
        saveBlobBase64(b64, filename, mime);
    }

    /** Common MIME → extension map (MimeTypeMap misses docx/md sometimes). */
    private static String extensionForMime(String mime) {
        if (mime == null) return null;
        String m = mime.split(";")[0].trim().toLowerCase(Locale.ROOT);
        switch (m) {
            case "application/pdf": return "pdf";
            case "application/msword": return "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document": return "docx";
            case "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet": return "xlsx";
            case "application/vnd.openxmlformats-officedocument.presentationml.presentation": return "pptx";
            case "text/markdown": case "text/x-markdown": return "md";
            case "text/plain": return "txt";
            case "text/html": return "html";
            case "text/csv": return "csv";
            case "application/json": return "json";
            case "image/png": return "png";
            case "image/jpeg": return "jpg";
            case "image/webp": return "webp";
            case "image/gif": return "gif";
            case "image/svg+xml": return "svg";
            default: {
                String e = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
                return e != null ? e : null;
            }
        }
    }

    private void saveBlobBase64(String b64, String fileName, String mime) {
        new Thread(() -> {
            try {
                byte[] bytes = android.util.Base64.decode(b64, android.util.Base64.DEFAULT);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    android.content.ContentResolver resolver = getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, fileName);
                    values.put(android.provider.MediaStore.Downloads.MIME_TYPE, mime);
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    Uri collection = android.provider.MediaStore.Downloads
                            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri == null) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Failed to create download entry", Toast.LENGTH_LONG).show());
                        return;
                    }
                    java.io.OutputStream out = resolver.openOutputStream(itemUri);
                    if (out == null) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Failed to open output stream", Toast.LENGTH_LONG).show());
                        return;
                    }
                    out.write(bytes);
                    out.flush();
                    out.close();
                } else {
                    File dl = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!dl.exists()) dl.mkdirs();
                    new java.io.FileOutputStream(new File(dl, fileName)).write(bytes);
                }
                runOnUiThread(() -> Toast.makeText(this,
                        "Saved to Download/" + fileName, Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "saveBlobBase64 failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        }).start();
    }

    private void downloadWithCookies(String url, String userAgent, String contentDisposition,
                                     String mimetype) {
        final String fileName = guessFilenameFromDownload(url, contentDisposition, mimetype);
        final String cookies = CookieManager.getInstance().getCookie(url);
        final String finalMimetype = mimetype != null ? mimetype : "application/octet-stream";

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            // Remember the download and re-issue it once the permission is
            // granted — previously this request dead-ended and the download
            // was silently dropped.
            pendingDownload = new String[]{
                    url, userAgent != null ? userAgent : "", contentDisposition, mimetype};
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQUEST_STORAGE_PERM);
            return;
        }

        Toast.makeText(this, "Downloading: " + fileName, Toast.LENGTH_SHORT).show();
        saveFileWithCookies(url, userAgent, cookies, fileName, finalMimetype);
    }

    private void saveFileWithCookies(String url, String userAgent, String cookies,
                                     String fileName, String mimetype) {
        new Thread(() -> {
            HttpURLConnection conn = null;
            InputStream input = null;
            java.io.OutputStream output = null;
            try {
                URL urlObj = new URL(url);
                conn = (HttpURLConnection) urlObj.openConnection();
                conn.setRequestMethod("GET");
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(60000);
                conn.setRequestProperty("User-Agent", userAgent != null ? userAgent : UA_MOBILE);
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Referer", "https://chatgpt.com/");
                // Byte-exact downloads (pattern from the AI Studio webclient):
                // forbid transparent gzip so a binary can never be saved with
                // a compression wrapper wedged around it.
                conn.setRequestProperty("Accept-Encoding", "identity");

                int responseCode = conn.getResponseCode();
                if (responseCode < 200 || responseCode >= 400) {
                    final int code = responseCode;
                    runOnUiThread(() -> Toast.makeText(this,
                            "Download failed: HTTP " + code, Toast.LENGTH_LONG).show());
                    return;
                }

                String realMime = conn.getContentType();
                if (realMime != null && realMime.contains("/")) {
                    realMime = realMime.split(";")[0].trim();
                } else {
                    realMime = mimetype;
                }

                // Post-redirect filename (AI Studio pattern): the original
                // name was guessed from the pre-redirect URL/disposition the
                // DownloadListener saw. If the chain bounced through
                // redirects, the FINAL response's Content-Disposition is
                // authoritative — prefer it when present.
                String effectiveName = fileName;
                String headerName = filenameFromDisposition(
                        conn.getHeaderField("Content-Disposition"));
                if (headerName != null && !headerName.isEmpty()) {
                    String cleaned = sanitizeFilename(headerName);
                    if (!cleaned.isEmpty() && !"shared_file".equals(cleaned)) {
                        effectiveName = cleaned;
                    }
                }

                // Interstitial guard (AI Studio pattern): a text/html answer
                // for a non-HTML download means the link bounced to an
                // error/login page — saving it would write an HTML page
                // named like the file. Fail loudly instead.
                if (realMime != null && realMime.toLowerCase().startsWith("text/html")
                        && !effectiveName.toLowerCase().endsWith(".html")
                        && !effectiveName.toLowerCase().endsWith(".htm")) {
                    final String fn = effectiveName;
                    Log.e(TAG, "Download got HTML interstitial for " + fn);
                    runOnUiThread(() -> Toast.makeText(this,
                            "Download failed: the server returned a web page (link may have expired)",
                            Toast.LENGTH_LONG).show());
                    return;
                }

                input = conn.getInputStream();
                final String finalRealMime = realMime;
                // Legacy (<Q) target file — hoisted so the post-save
                // MediaScanner pass can see it; stays null on Q+.
                File outFile = null;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String mimeExtension = extensionForMime(realMime);
                    String displayName = effectiveName;
                    String effectiveMime = realMime;
                    if (mimeExtension != null && !mimeExtension.isEmpty()) {
                        int lastDot = displayName.lastIndexOf('.');
                        if (lastDot > 0) {
                            displayName = displayName.substring(0, lastDot);
                        }
                        displayName += "." + mimeExtension;
                    } else {
                        effectiveMime = null;
                    }

                    android.content.ContentResolver resolver = getContentResolver();
                    android.content.ContentValues values = new android.content.ContentValues();
                    values.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, displayName);
                    if (effectiveMime != null) {
                        values.put(android.provider.MediaStore.Downloads.MIME_TYPE, effectiveMime);
                    }
                    values.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                            android.os.Environment.DIRECTORY_DOWNLOADS);

                    Uri collection = android.provider.MediaStore.Downloads
                            .getContentUri(android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    Uri itemUri = resolver.insert(collection, values);
                    if (itemUri == null) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Failed to create download entry", Toast.LENGTH_LONG).show());
                        return;
                    }
                    output = resolver.openOutputStream(itemUri);
                    if (output == null) {
                        runOnUiThread(() -> Toast.makeText(this,
                                "Failed to open output stream", Toast.LENGTH_LONG).show());
                        return;
                    }
                } else {
                    File downloadsDir = android.os.Environment.getExternalStoragePublicDirectory(
                            android.os.Environment.DIRECTORY_DOWNLOADS);
                    if (!downloadsDir.exists()) downloadsDir.mkdirs();
                    outFile = new File(downloadsDir, effectiveName);
                    output = new java.io.FileOutputStream(outFile);
                }

                byte[] buffer = new byte[8192];
                int bytesRead;
                long total = 0;
                while ((bytesRead = input.read(buffer)) != -1) {
                    output.write(buffer, 0, bytesRead);
                    total += bytesRead;
                }
                output.flush();
                Log.i(TAG, "Downloaded " + total + " bytes (" + effectiveName + ")");

                // Legacy (<Q) writes land in the public Downloads dir outside
                // MediaStore — scan them so Files/Gallery apps see the file
                // immediately instead of after the next media sweep.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q && outFile != null) {
                    try {
                        android.media.MediaScannerConnection.scanFile(MainActivity.this,
                                new String[]{outFile.getAbsolutePath()},
                                new String[]{realMime}, null);
                    } catch (Throwable t) {
                        Log.w(TAG, "MediaScanner failed", t);
                    }
                }

                final String finalFileName = effectiveName;
                runOnUiThread(() -> Toast.makeText(this,
                        "Saved to Download/" + finalFileName,
                        Toast.LENGTH_LONG).show());
            } catch (Exception e) {
                Log.e(TAG, "Download failed", e);
                runOnUiThread(() -> Toast.makeText(this,
                        "Download failed: " + e.getMessage(), Toast.LENGTH_LONG).show());
            } finally {
                if (input != null) try { input.close(); } catch (Exception ignored) {}
                if (output != null) try { output.close(); } catch (Exception ignored) {}
                if (conn != null) conn.disconnect();
            }
        }).start();
    }

    /**
     * Extract just the filename from a Content-Disposition header, or null
     * when the header carries none. Lighter than guessFilenameFromDownload:
     * no URL/mime fallbacks — callers keep their existing name when null.
     * RFC 5987 filename* wins over the plain filename token; both forms are
     * percent-decoded and '+' is protected from URLDecoder.
     */
    private static String filenameFromDisposition(String contentDisposition) {
        if (contentDisposition == null || contentDisposition.isEmpty()) return null;
        try {
            java.util.regex.Matcher star = java.util.regex.Pattern.compile(
                    "filename\\*=\\s*(?:utf-8|iso-8859-1)?''([^;]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (star.find()) {
                String name = star.group(1).trim().replace("+", "%2B");
                return java.net.URLDecoder.decode(name, "UTF-8");
            }
            java.util.regex.Matcher plain = java.util.regex.Pattern.compile(
                    "filename=\\s*[\"']?([^\"';]+)[\"']?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (plain.find()) {
                String name = plain.group(1).trim();
                if (name.contains("%")) {
                    try {
                        name = java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
                    } catch (Exception ignored) {}
                }
                return name;
            }
        } catch (Exception e) {
            Log.w(TAG, "filenameFromDisposition failed", e);
        }
        return null;
    }

    private String guessFilenameFromDownload(String url, String contentDisposition, String mimetype) {
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            // Robust RFC-6266 filename extraction. Previous versions used a
            // hand-rolled substring parser that left a trailing `"` in the
            // result when the disposition had trailing parameters
            // (e.g. `filename="x.pdf"; size=123`). A regex that captures
            // everything between the (optional) opening and closing quotes
            // is bulletproof.
            //
            // RFC 5987 `filename*` takes precedence when present, and its
            // value is percent-encoded — decode it (previous versions saved
            // the raw percent signs, e.g. "informe%202026.pdf").
            java.util.regex.Matcher star = java.util.regex.Pattern.compile(
                    "filename\\*=\\s*(?:utf-8|iso-8859-1)?''([^;]+)",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (star.find()) {
                String name = star.group(1).trim();
                // '+' is literal in RFC 5987 — protect it from URLDecoder,
                // which would turn it into a space.
                try {
                    name = java.net.URLDecoder.decode(name.replace("+", "%2B"), "UTF-8");
                } catch (Exception ignored) {
                }
                name = sanitizeFilename(name);
                if (!name.isEmpty()) {
                    return name;
                }
            }
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "filename=(?:UTF-8'')?\"?([^\";]+)\"?",
                    java.util.regex.Pattern.CASE_INSENSITIVE
            ).matcher(contentDisposition);
            if (m.find()) {
                String name = m.group(1).trim();
                if (!name.isEmpty()) {
                    return sanitizeFilename(name);
                }
            }
        }
        return sanitizeFilename(android.webkit.URLUtil.guessFileName(url, contentDisposition, mimetype));
    }

    private static String sanitizeFilename(String name) {
        name = name.replaceAll("[/\\\\]", "_").trim();
        if (name.length() > 200) {
            String ext = "";
            int dot = name.lastIndexOf('.');
            if (dot > 0) {
                ext = name.substring(dot);
                name = name.substring(0, 200 - ext.length()) + ext;
            } else {
                name = name.substring(0, 200);
            }
        }
        return name.isEmpty() ? "download" : name;
    }

    /** Sanitize a provider-supplied display name for use inside getCacheDir(). */
    private static String sanitizeSharedFileName(String name) {
        String n = sanitizeFilename(name);
        while (n.startsWith(".")) {
            n = n.substring(1);
        }
        return n.isEmpty() ? "shared_file" : n;
    }

    private boolean createPopup(Message resultMsg) {
        final WebView popup = new WebView(this);
        popup.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));
        // Same theme-matched background as the main WebView — OAuth pages
        // repaint over it immediately; this only kills the white flash in
        // dark mode during the popup's first paint.
        applyWebViewBackground(popup);

        WebSettings ps = popup.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setDatabaseEnabled(true);
        ps.setSupportMultipleWindows(true);
        ps.setJavaScriptCanOpenWindowsAutomatically(true);
        ps.setUserAgentString(webview.getSettings().getUserAgentString());
        ps.setCacheMode(WebSettings.LOAD_DEFAULT);
        ps.setAllowFileAccess(false);
        ps.setAllowContentAccess(false);
        // OAuth popup: third-party cookies stay ENABLED here — the
        // accounts.google.com / auth.openai.com redirect chain needs them.
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
        popup.addJavascriptInterface(new WebAppInterface(this, popup), "AndroidBridge");
        // Same document-start overrides as the main WebView — popup frames
        // (OAuth, share menus) need the blob/share hooks too.
        try {
            if (WebViewUtil.isSupported()) {
                WebViewCompat.addDocumentStartJavaScript(
                        popup, PAGE_OVERRIDES_JS, java.util.Collections.singleton("*"));
            }
        } catch (Throwable t) {
            Log.e(TAG, "popup addDocumentStartJavaScript failed", t);
        }

        popup.setWebViewClient(new WebViewClient() {
            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                boolean result = shouldOverrideNavigationFrame(url);
                if (result) maybeRemovePopupForExternalLink(url);
                return result;
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v,
                                                    android.webkit.WebResourceRequest request) {
                String url = request.getUrl().toString();
                boolean result;
                boolean isMainFrame = (Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
                        || request.isForMainFrame();
                if (!isMainFrame) {
                    result = shouldOverrideNavigationFrame(url);
                } else {
                    result = shouldOverrideNavigation(url);
                }
                if (result && isMainFrame) maybeRemovePopupForExternalLink(url);
                return result;
            }

            /** If shouldOverrideUrlLoading routed a MAIN-FRAME url to the
             *  browser (because the host is not in the allowlist), the
             *  popup WebView has nothing useful to render — it would just
             *  sit there as a black screen on top of the chat until the
             *  user back-presses. Remove it now so the user returns from
             *  the browser straight back to their chat. */
            private void maybeRemovePopupForExternalLink(String url) {
                if (url == null) return;
                try {
                    Uri uri = Uri.parse(url);
                    String host = uri.getHost();
                    if (host != null && !isAllowedHost(host)) {
                        removePopup(popup);
                    }
                } catch (Exception e) { /* ignore parse failures */ }
            }

            @Override
            public boolean onRenderProcessGone(WebView view,
                                               android.webkit.RenderProcessGoneDetail detail) {
                removePopup(popup);
                return true;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                if (host != null && (host.equals("chatgpt.com") || host.endsWith(".chatgpt.com")) && !url.contains("/auth/")) {
                    loadUrlWithHeaders(webview, url);
                    removePopup(popup);
                }
            }

        });

        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                removePopup(popup);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                if (BuildConfig.EXPERIMENTAL) {
                    Log.d(TAG, "[popup] " + cm.message());
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                handleWebPermissionRequest(request);
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                return openFileChooser(callback);
            }
        });

        rootLayout.addView(popup);
        popupViews.add(popup);

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popup);
        resultMsg.sendToTarget();
        return true;
    }

    private void removePopup(WebView popup) {
        removePopup(popup, false);
    }

    /**
     * Detach a popup WebView and destroy it.
     *
     * immediate=false (the default) is used whenever we are INSIDE a
     * WebView callback: shouldOverrideUrlLoading (external-link routing),
     * onPageFinished (OAuth completion), onRenderProcessGone (renderer
     * death under memory pressure), and the DownloadListener. Calling
     * WebView.destroy() synchronously from inside one of those callbacks
     * crashes on newer Chromium builds — on Android 15 it killed the whole
     * app the moment a login popup was torn down (the F-Droid review's
     * "app crashes when tapping any login button"). Detach NOW (the view
     * disappears immediately and nothing else can touch it) but POST the
     * destroy() to the next message-loop pass, after the callback stack
     * has unwound.
     *
     * immediate=true is for onDestroy(), where the activity is going away
     * and there will be no further useful loop pass — destroy inline.
     */
    private void removePopup(WebView popup, boolean immediate) {
        try {
            rootLayout.removeView(popup);
            popupViews.remove(popup);
            if (immediate) {
                popup.destroy();
            } else {
                rootLayout.post(() -> {
                    try {
                        popup.destroy();
                    } catch (Throwable t) {
                        Log.e(TAG, "deferred popup destroy failed", t);
                    }
                });
            }
        } catch (Exception e) {
            Log.e(TAG, "Error removing popup", e);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_FILE_CHOOSER) {
            if (filePathCallback == null) {
                return;
            }
            if (resultCode != RESULT_OK) {
                if (pendingCameraFile != null && pendingCameraFile.exists()) {
                    pendingCameraFile.delete();
                }
                pendingCameraUri = null;
                pendingCameraFile = null;
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
                return;
            }

            Uri[] results = null;
            if (data == null || (data.getData() == null && data.getClipData() == null)) {
                if (pendingCameraFile != null && pendingCameraFile.exists() && pendingCameraFile.length() > 0) {
                    results = new Uri[]{pendingCameraUri};
                    Log.i(TAG, "Camera capture result: " + pendingCameraUri);
                }
            } else {
                android.content.ClipData clipData = data.getClipData();
                if (clipData != null) {
                    results = new Uri[clipData.getItemCount()];
                    for (int i = 0; i < clipData.getItemCount(); i++) {
                        results[i] = clipData.getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
            pendingCameraUri = null;
            pendingCameraFile = null;
        }
    }

    // ─── Permission / navigation / chooser helpers ─────────────────────

    private boolean hasPermission(String perm) {
        return ContextCompat.checkSelfPermission(this, perm)
                == android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Handles a WebView permission request (camera/mic):
     *  - only for allowlisted origins,
     *  - requests the missing OS permission at runtime,
     *  - grants only what the user actually approved.
     */
    private void handleWebPermissionRequest(final PermissionRequest request) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) return;
            try {
                Uri origin = request.getOrigin();
                if (origin == null || !isAllowedHost(origin.getHost())) {
                    try {
                        request.deny();
                    } catch (Throwable ignored) {
                    }
                    return;
                }
                List<String> toRequest = new ArrayList<>();
                for (String r : request.getResources()) {
                    if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                            && !hasPermission(android.Manifest.permission.CAMERA)) {
                        toRequest.add(android.Manifest.permission.CAMERA);
                    }
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                            && !hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                        toRequest.add(android.Manifest.permission.RECORD_AUDIO);
                    }
                }
                if (toRequest.isEmpty()) {
                    grantWebPermissionRequest(request);
                } else {
                    if (pendingWebPermissionRequest != null) {
                        try {
                            pendingWebPermissionRequest.deny();
                        } catch (Throwable ignored) {
                        }
                    }
                    pendingWebPermissionRequest = request;
                    ActivityCompat.requestPermissions(MainActivity.this,
                            toRequest.toArray(new String[0]), REQUEST_MEDIA_PERM);
                }
            } catch (Throwable t) {
                Log.e(TAG, "onPermissionRequest failed", t);
            }
        });
    }

    private void grantWebPermissionRequest(PermissionRequest request) {
        List<String> granted = new ArrayList<>();
        for (String r : request.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                    && hasPermission(android.Manifest.permission.CAMERA)) {
                granted.add(r);
            }
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                    && hasPermission(android.Manifest.permission.RECORD_AUDIO)) {
                granted.add(r);
            }
        }
        try {
            if (granted.isEmpty()) {
                request.deny();
            } else {
                request.grant(granted.toArray(new String[0]));
            }
        } catch (Throwable t) {
            Log.e(TAG, "grantWebPermissionRequest failed", t);
        }
    }

    /**
     * Navigation policy shared by the main WebView and popups:
     *  - only http/https may load inside the app (file:, data:, javascript:
     *    and friends never load locally),
     *  - allowlisted hosts load in the app,
     *  - everything else goes to the system browser.
     */
    /**
     * MAIN-FRAME navigation policy. http/https with allowlisted hosts load
     * in-app; everything else on the allowlist boundary goes to the browser.
     * blob:/data:/about:/javascript: load in-place — the official release
     * allowed null-host URLs, and blocking them here would break any site
     * feature that navigates the main frame to generated content. Only
     * file:/content: stay blocked (the actual local-file hardening win).
     */
    private boolean shouldOverrideNavigation(String url) {
        if (url == null) return false;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        String scheme = uri.getScheme();
        boolean isWeb = "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        if (isWeb) {
            String host = uri.getHost();
            if (host != null && !isAllowedHost(host)) {
                openUrlInBrowser(url);
                return true;
            }
            return false;
        }
        String s = scheme == null ? "" : scheme.toLowerCase(Locale.ROOT);
        if (s.equals("mailto") || s.equals("tel") || s.equals("sms")
                || s.equals("geo") || s.equals("intent") || s.equals("market")) {
            openUrlInBrowser(url);
            return true;
        }
        if (s.equals("file") || s.equals("content")) return true; // blocked
        return false; // blob:, data:, about:, javascript:, schemeless -> load
    }

    /**
     * IFRAME navigation policy — EXACTLY the original app's behavior. The
     * earlier scheme allowlist (returning true for every non-http URL)
     * blocked blob:/data: iframes, which is what broke ChatGPT's embedded
     * investigation panel with "Error loading application: Runtime error":
     * the panel is an iframe app whose URL has no host. Iframes now load
     * anything with a null host; http iframes to non-allowlisted hosts keep
     * going to the browser, as the official release did.
     */
    private boolean shouldOverrideNavigationFrame(String url) {
        if (url == null) return false;
        Uri uri;
        try {
            uri = Uri.parse(url);
        } catch (Exception e) {
            return false;
        }
        String host = uri.getHost();
        if (host != null && !isAllowedHost(host)) {
            openUrlInBrowser(url);
            return true;
        }
        return false;
    }

    /** Shared file-chooser implementation (previously duplicated in both clients). */
    private boolean openFileChooser(ValueCallback<Uri[]> callback) {
        if (filePathCallback != null) {
            filePathCallback.onReceiveValue(null);
        }
        filePathCallback = callback;

        // If we have a pending shared file, return it immediately — UNLESS
        // we injected a file moments ago: the site re-opens its file input
        // right after a programmatic attach, and handing the same file over
        // again double-attaches it (which trips ChatGPT's attach limit and
        // makes the attachment disappear).
        if (pendingShareFileUri != null
                && SystemClock.elapsedRealtime() - lastFileInjectionAt < 4000) {
            Log.i(TAG, "ignoring site re-trigger after injection");
            filePathCallback.onReceiveValue(null);
            filePathCallback = null;
            return true;
        }
        if (pendingShareFileUri != null) {
            Log.i(TAG, "onShowFileChooser: returning pending shared file " + pendingShareFileUri);
            filePathCallback.onReceiveValue(new Uri[]{pendingShareFileUri});
            filePathCallback = null;
            pendingShareFileUri = null;
            Log.i(TAG, "auto-attach: file handed to page");
            // The site only COMMITS a pending attachment while the composer
            // is focused shortly after the file lands — unattended, it
            // silently drops the file about a second later (users had to tap
            // the text box within that window). Focus the composer
            // programmatically, several times, inside that window.
            webview.postDelayed(this::settleComposerAfterAutoAttach, 200);
            webview.postDelayed(this::settleComposerAfterAutoAttach, 500);
            webview.postDelayed(this::settleComposerAfterAutoAttach, 900);
            webview.postDelayed(this::settleComposerAfterAutoAttach, 1400);
            return true;
        }

        // Because the app declares CAMERA in the manifest, Android requires
        // the runtime permission to be HELD before a capture intent is
        // launched — otherwise the camera app fails silently. Ask first,
        // then show the chooser from the permission result.
        if (!hasPermission(android.Manifest.permission.CAMERA)) {
            cameraPermForChooser = true;
            ActivityCompat.requestPermissions(this,
                    new String[]{android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERM);
            return true;
        }
        launchFileChooserNow(true);
        return true;
    }

    /** Launch the system file chooser; include the camera option when usable. */
    private void launchFileChooserNow(boolean includeCamera) {
        Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
        contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
        contentIntent.setType("*/*");
        contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

        Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        boolean cameraReady = false;
        if (includeCamera) {
            try {
                File cameraFile = new File(getCacheDir(),
                        "camera_capture_" + System.currentTimeMillis() + ".jpg");
                Uri cameraUri = androidx.core.content.FileProvider.getUriForFile(
                        this, getPackageName() + ".fileprovider", cameraFile);
                cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                // Some camera apps re-read the capture for EXIF rotation or
                // thumbnails and crash on a missing read grant — grant both.
                cameraIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                        | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                pendingCameraUri = cameraUri;
                pendingCameraFile = cameraFile;
                cameraReady = true;
            } catch (Exception e) {
                Log.e(TAG, "Camera setup failed", e);
                pendingCameraUri = null;
                pendingCameraFile = null;
            }
        }

        Intent chooser = Intent.createChooser(contentIntent, "Select file");
        if (cameraReady) {
            chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
        }
        try {
            startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
        } catch (Exception e) {
            Log.e(TAG, "File chooser failed", e);
            if (filePathCallback != null) {
                filePathCallback.onReceiveValue(null);
                filePathCallback = null;
            }
        }
    }

    /**
     * Nudge the composer after the auto-attached file is handed to the page:
     * send Escape (closes the leftover "+" menu) and focus the prompt box.
     * This reproduces the user tap that the site needs to commit the
     * attachment within its ~1s window.
     */
    /**
     * Nudge the composer after the auto-attached file is handed to the page:
     * simulate the user's tap on the prompt box (pointer/mouse sequence +
     * focus). NO Escape key — Escape dismisses the pending attachment, which
     * is exactly how round 5 broke file sharing. The tap is what the site
     * needs to commit the attachment within its ~1s window.
     */
    /**
     * Mark a 6-second window during which the focus guard is bypassed, so
     * the share pipeline's own .focus() calls can land and commit the
     * attachment. Without this, FOCUS_GUARD_JS would block the share's own
     * focus() and the attachment would never land.
     *
     * The timestamp is set in THREE places so all share entry points are
     * covered:
     *  - Here, Java-side in handleShareIntent — runs via evaluateJavascript
     *    on the JS thread, which lands before the page's resume-time
     *    visibilitychange handler can fire, so there's no flicker on
     *    share-resume (the share's own focus() wins the race).
     *  - Inside settleComposerAfterAutoAttach's JS string — covers cold
     *    start (no resume event fires on cold start) and any staggered
     *    re-focus attempts the SPA makes later.
     *  - Inside runFileDropSequence's dropJs string — same coverage for
     *    the file-drop pipeline.
     *
     * Auto-expires (6s), so it can't get stuck on. If a share ever
     * misfires the user just waits 6 seconds and the guard re-engages.
     */
    private void markShareActive() {
        WebView wv = webview;
        if (wv == null || isFinishing()) return;
        wv.evaluateJavascript("try{window.__webgptShareActiveUntil=Date.now()+6000;}catch(e){}", null);
    }

    /**
     * Focus the composer so a pending attachment commits / pasted text lands.
     * FOCUS ONLY — no pointer/click dispatch: the old selector
     * ([data-testid*=composer]) matched the + ATTACH BUTTON in the current
     * ChatGPT UI, so the settle code was clicking + instead of focusing the
     * text box (opening menus, never activating the composer). The selector
     * list below deliberately matches only editable elements.
     */
    private void settleComposerAfterAutoAttach() {
        WebView wv = webview;
        if (wv == null || isFinishing()) return;
        String js = "(function(){" +
                "  try {" +
                "    window.__webgptShareActiveUntil = Date.now() + 6000;" +
                "    var el = document.querySelector('#prompt-textarea')" +
                "          || document.querySelector('div[contenteditable=\"true\"][role=\"textbox\"]')" +
                "          || document.querySelector('div[contenteditable=\"true\"]')" +
                "          || document.querySelector('textarea[placeholder]');" +
                "    var AB = window.AndroidBridge;" +
                "    if (!el) { try { if (AB && AB.debugLog) AB.debugLog('composer NOT FOUND'); } catch (e) {} return; }" +
                "    try { if (AB && AB.debugLog) AB.debugLog('composer focus: ' + (el.id || el.getAttribute('data-testid') || el.tagName)); } catch (e) {}" +
                "    try { el.focus({preventScroll: true}); } catch (e) { try { el.focus(); } catch (e2) {} }" +
                "    try { el.dispatchEvent(new Event('focus', {bubbles: true})); } catch (e) {}" +
                "    try { el.dispatchEvent(new Event('focusin', {bubbles: true})); } catch (e) {}" +
                "  } catch (e) {}" +
                "})();";
        wv.evaluateJavascript(js, null);
        // Programmatic JS focus does not reliably summon the Android IME —
        // the site only commits a pending attachment while the composer is
        // FOCUSED, which on a touch device means the keyboard being up.
        showKeyboardForComposer();
    }

    /** Show the soft keyboard for the WebView's composer. */
    private void showKeyboardForComposer() {
        try {
            WebView wv = webview;
            if (wv == null || isFinishing()) return;
            wv.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(wv, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Throwable t) {
            Log.e(TAG, "showKeyboardForComposer failed", t);
        }
    }

    /** Offline dialog with retry (main-frame load failures). */
    private void showOfflineDialog() {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed() || offlineDialogShowing) return;
            offlineDialogShowing = true;
            if (loadingOverlay != null) loadingOverlay.setVisibility(View.GONE);
            new com.google.android.material.dialog.MaterialAlertDialogBuilder(MainActivity.this)
                    .setTitle("Connection problem")
                    .setMessage("Couldn't load chatgpt.com. Check your internet connection and try again.")
                    .setCancelable(false)
                    .setPositiveButton("Retry", (d, w) -> {
                        offlineDialogShowing = false;
                        initialLoadComplete = false;
                        if (loadingOverlay != null) loadingOverlay.setVisibility(View.VISIBLE);
                        loadUrlWithHeaders(webview, URL);
                    })
                    .setNegativeButton("Close app", (d, w) -> {
                        offlineDialogShowing = false;
                        // Actually close the app (whole task), not just the dialog.
                        finishAffinity();
                    })
                    .show();
        });
    }

    /** Delete one-shot cache files older than 48 hours. */
    private void sweepCacheDir() {
        new Thread(() -> {
            try {
                File[] files = getCacheDir().listFiles();
                if (files == null) return;
                long cutoff = System.currentTimeMillis() - 48L * 60 * 60 * 1000;
                for (File f : files) {
                    String n = f.getName();
                    if ((n.startsWith("camera_capture_") || n.startsWith("shared_image_")
                            || n.startsWith("shared_file_")) && f.lastModified() < cutoff) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (Throwable ignored) {
            }
        }).start();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_STORAGE_PERM) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
            String[] d = pendingDownload;
            pendingDownload = null;
            if (granted && d != null) {
                downloadWithCookies(d[0], d[1], d[2], d[3]);
            } else if (d != null) {
                Toast.makeText(this, "Download cancelled — storage permission was denied",
                        Toast.LENGTH_LONG).show();
            }
        } else if (requestCode == REQUEST_MEDIA_PERM) {
            PermissionRequest req = pendingWebPermissionRequest;
            pendingWebPermissionRequest = null;
            if (req != null) {
                grantWebPermissionRequest(req);
            }
        } else if (requestCode == REQUEST_CAMERA_PERM) {
            // File chooser deferred until the camera permission was resolved:
            // show it now — with the camera option only if permission was
            // granted (works with "Ask every time" too).
            if (cameraPermForChooser) {
                cameraPermForChooser = false;
                boolean granted = grantResults.length > 0
                        && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
                if (filePathCallback != null) {
                    launchFileChooserNow(granted);
                }
            }
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Reaching PAUSE proves this launch was real and interactive — any
        // process death while backgrounded afterwards is NORMAL lifecycle
        // (OEM task managers kill background processes aggressively,
        // floating/picture-in-picture windows especially), not a WebView
        // crash. Reset the counter HERE (in addition to onPageFinished and
        // onDestroy) so background kills stop accumulating toward the
        // WebView-picker bounce — the "app closes itself right after I
        // open it" symptom.
        // A genuine crash-loop (broken WebView provider) still trips the
        // bounce: those launches die while foregrounded, BEFORE the first
        // pause and before the first page load.
        CrashTracker.reset();
        // Pause JS timers/layout for all our WebViews while backgrounded —
        // previously a streaming chat kept running (and draining battery) in
        // the background.
        if (webview != null) webview.onPause();
        for (WebView p : new ArrayList<>(popupViews)) {
            try { p.onPause(); } catch (Throwable ignored) {}
        }
        CookieManager.getInstance().flush();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // Persist the WebView navigation state whenever the system asks us
        // to (configuration-driven recreation, memory-pressure activity
        // destroy, process death). onCreate's restore path uses it to
        // resume the open conversation instead of the homepage.
        if (webview != null) {
            try {
                webview.saveState(outState);
            } catch (Throwable t) {
                Log.e(TAG, "WebView saveState failed", t);
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (webview != null) webview.onResume();
        for (WebView p : new ArrayList<>(popupViews)) {
            try { p.onResume(); } catch (Throwable ignored) {}
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            // Activity is about to be backgrounded — capture a snapshot
            // NOW (before onPause, while the surface is still alive) so we
            // can mask the black flash on resume.
            captureResumeSnapshot();
        } else {
            // Activity just regained focus — schedule the snapshot to fade
            // out 300ms from now, giving the WebView time to repaint.
            scheduleSnapshotFadeOut(300);
        }
    }

    /**
     * Capture a bitmap of the FULL WINDOW (DecorView: WebView, any open
     * popups, and the status-bar strip) and display it on top via
     * {@link #resumeSnapshot}. Uses PixelCopy on API 26+ (reliable on
     * hardware-accelerated views) and falls back to a software canvas draw on
     * older API levels. The bitmap is sized to the DecorView — the SAME
     * geometry PixelCopy.request(getWindow(), ...) captures and the SAME
     * geometry the overlay occupies — so it is displayed 1:1 with no scaling.
     * (v6.24.28/29 sized the bitmap to rootLayout but copied the whole
     * window, then showed it inside a rootLayout-sized overlay: the bitmap
     * was squeezed down by the status-bar height, producing a shrunken app
     * with a second black status-bar strip for ~300ms on resume.)
     */
    private void captureResumeSnapshot() {
        if (resumeSnapshot == null) return;
        View decor = getWindow() != null ? (View) getWindow().getDecorView() : null;
        if (decor == null || decor.getWidth() <= 0 || decor.getHeight() <= 0) {
            return;
        }
        final int w = decor.getWidth();
        final int h = decor.getHeight();
        // Hide the snapshot ImageView itself while capturing so we don't
        // recursively capture our own overlay.
        final int prevVis = resumeSnapshot.getVisibility();
        resumeSnapshot.setVisibility(View.GONE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // PixelCopy (API 26+) is async and reliable on hardware-accelerated
            // views. Capture the whole window (PixelCopy.request only accepts
            // Surface / SurfaceView / Window — there is no View overload).
            try {
                final Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                PixelCopy.request(getWindow(), bmp, result -> {
                    if (result == PixelCopy.SUCCESS) {
                        runOnUiThread(() -> {
                            if (resumeSnapshotBitmap != null) {
                                resumeSnapshotBitmap.recycle();
                            }
                            resumeSnapshotBitmap = bmp;
                            resumeSnapshot.setImageBitmap(bmp);
                            resumeSnapshot.setVisibility(View.VISIBLE);
                            // Late-callback safety: if window focus has
                            // ALREADY been regained by the time this async
                            // copy completes, onWindowFocusChanged(true) has
                            // come and gone and nothing would schedule the
                            // fade-out — the overlay would be stuck on top
                            // forever. Schedule it here instead.
                            if (hasWindowFocus()) scheduleSnapshotFadeOut(300);
                        });
                    } else {
                        Log.w(TAG, "snapshot PixelCopy failed code=" + result);
                        // Restore previous visibility (probably GONE) on failure
                        runOnUiThread(() -> resumeSnapshot.setVisibility(prevVis));
                    }
                }, snapshotHandler);
            } catch (Throwable t) {
                Log.e(TAG, "PixelCopy threw", t);
                Log.w(TAG, "snapshot PixelCopy threw: " + t.getClass().getSimpleName());
                resumeSnapshot.setVisibility(prevVis);
            }
        } else {
            // Pre-API-26 fallback: software canvas draw. Less reliable for
            // GPU-rendered content but better than nothing.
            try {
                Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(bmp);
                decor.draw(canvas);
                if (resumeSnapshotBitmap != null) {
                    resumeSnapshotBitmap.recycle();
                }
                resumeSnapshotBitmap = bmp;
                resumeSnapshot.setImageBitmap(bmp);
                resumeSnapshot.setVisibility(View.VISIBLE);
            } catch (Throwable t) {
                Log.e(TAG, "snapshot draw threw", t);
                resumeSnapshot.setVisibility(prevVis);
            }
        }
    }

    /**
     * Schedule the resume snapshot overlay to fade out and hide after
     * {@code delayMs} milliseconds. Idempotent — multiple calls in flight
     * will just keep rescheduling.
     */
    private void scheduleSnapshotFadeOut(int delayMs) {
        if (resumeSnapshot == null || resumeSnapshot.getVisibility() != View.VISIBLE) {
            return;
        }
        snapshotHandler.removeCallbacksAndMessages(null);
        snapshotHandler.postDelayed(() -> {
            runOnUiThread(() -> {
                if (resumeSnapshot != null
                        && resumeSnapshot.getVisibility() == View.VISIBLE) {
                    resumeSnapshot.setVisibility(View.GONE);
                    resumeSnapshot.setImageBitmap(null);
                    if (resumeSnapshotBitmap != null) {
                        resumeSnapshotBitmap.recycle();
                        resumeSnapshotBitmap = null;
                    }
                }
            });
        }, delayMs);
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // uiMode is now in configChanges — re-tint the loading logo ourselves
        // instead of letting the activity (and WebView) be recreated.
        if (loadingLogo != null) {
            boolean dark = (newConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK)
                    == Configuration.UI_MODE_NIGHT_YES;
            loadingLogo.setImageResource(dark ? R.drawable.logo_white : R.drawable.logo_black);
        }
        // Re-apply the window + rootLayout background color so it tracks the
        // new theme — otherwise a dark→light switch would leave the window
        // pinned to dark grey (Bug-2 mask) while the page goes white.
        applyBackgroundColors();
    }

    @Override
    public void onBackPressed() {
        if (!popupViews.isEmpty()) {
            WebView top = popupViews.remove(popupViews.size() - 1);
            removePopup(top);
            return;
        }
        if (webview != null && webview.canGoBack()) {
            webview.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onDestroy() {
        // A clean destroy is not a crash — reset the counter so abandoned
        // launches (no signal, swipe-away) no longer count toward the
        // "pick another WebView" bounce.
        CrashTracker.reset();
        for (WebView popup : new ArrayList<>(popupViews)) {
            removePopup(popup, true);
        }
        if (loadingLogo != null) {
            loadingLogo.clearAnimation();
        }
        if (webview != null) {
            // Documented teardown order: detach, remove child views, destroy.
            try {
                rootLayout.removeView(webview);
                webview.removeAllViews();
                webview.destroy();
            } catch (Exception e) {
                Log.e(TAG, "Error destroying WebView", e);
            }
            webview = null;
        }
        filePathCallback = null;
        pendingShareFileUri = null;
        super.onDestroy();
    }

    /**
     * DNS-boundary check for allowed hosts. Accepts exactly the listed domain
     * or any subdomain of it (e.g. "chatgpt.com" or "auth.openai.com"), but
     * NOT unrelated domains like "evilchatgpt.com".
     */
    private static boolean isAllowedHost(String host) {
        if (host == null) return false;
        // NOTE: bare "auth0.com" was deliberately removed — OpenAI's login
        // runs on auth.openai.com (covered by the openai.com entry), while
        // *.auth0.com hosts arbitrary third-party tenants we must not trust.
        //
        // The google.com / googleusercontent.com / gstatic.com entries are
        // REQUIRED for the "Continue with Google" OAuth chain: after
        // accounts.google.com the account picker and consent screens hop
        // through myaccount.google.com, www.google.com and
        // oauthaccount.googleusercontent.com before landing back on
        // auth.openai.com. Without these entries every one of those hops
        // was routed to the external browser mid-login (the F-Droid review
        // symptom: "app crashes when clicking any login button" — the
        // popup teardown that followed that routing crashed the app on
        // Android 15). Same set the Gemini-based sibling app ships.
        String[] allowed = {
            "chatgpt.com",
            "openai.com",
            "accounts.google.com",
            "google.com",
            "googleusercontent.com",
            "gstatic.com"
        };
        for (String domain : allowed) {
            if (host.equals(domain) || host.endsWith("." + domain)) {
                return true;
            }
        }
        return false;
    }
}
