package com.webgpt.app;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.provider.MediaStore;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Animation;
import android.view.animation.AnimationUtils;
import android.webkit.CookieManager;
import android.webkit.ConsoleMessage;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebStorage;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

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
import java.util.Map;

public class MainActivity extends Activity {

    private static final String TAG = "WebGPTApp";
    private static final String PREFS_NAME = "webgpt_prefs";
    private static final String KEY_DESKTOP_MODE = "desktop_mode";

    private static final String URL = "https://chatgpt.com/";

    private static final String UA_DESKTOP =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";

    private static final int REQUEST_FILE_CHOOSER = 54321;
    private static final int REQUEST_PERMS = 1001;
    private static final int REQUEST_STORAGE_PERM = 1003;

    WebView webview;
    ViewGroup rootLayout;
    View loadingOverlay;
    ImageView loadingLogo;
    boolean desktopMode;
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

    // Pending share-from-outside: file to inject into the WebGPT composer
    private Uri pendingShareFileUri;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

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
                CrashTracker.reset(true);
                Intent i = new Intent(this, SettingsActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
                finish();
                return;
            }
        }
        // ─── End WebView switcher pre-launch checks ────────────────────────

        setContentView(R.layout.activity_main);

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        desktopMode = prefs.getBoolean(KEY_DESKTOP_MODE, false);
        Log.i(TAG, "Launching with desktop_mode=" + desktopMode);

        // Request camera + microphone permissions silently at startup
        List<String> permsToRequest = new ArrayList<>();
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permsToRequest.add(android.Manifest.permission.CAMERA);
        }
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permsToRequest.add(android.Manifest.permission.RECORD_AUDIO);
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            permsToRequest.add(android.Manifest.permission.WRITE_EXTERNAL_STORAGE);
        }
        if (!permsToRequest.isEmpty()) {
            ActivityCompat.requestPermissions(this,
                    permsToRequest.toArray(new String[0]), REQUEST_PERMS);
        }

        extraHeaders = new HashMap<>();
        extraHeaders.put("X-Requested-With", "");

        webview = findViewById(R.id.activity_main_webview);
        rootLayout = (ViewGroup) webview.getParent();
        loadingOverlay = findViewById(R.id.loading_overlay);
        loadingLogo = findViewById(R.id.loading_logo);

        // Set correct logo color based on theme (black for light mode, white for dark mode)
        boolean isDark = isDarkMode();
        loadingLogo.setImageResource(isDark ? R.drawable.logo_white : R.drawable.logo_black);

        // Start spin + fade animation
        Animation spinFade = AnimationUtils.loadAnimation(this, R.anim.spin_fade);
        loadingLogo.startAnimation(spinFade);

        // Hardware acceleration
        webview.setLayerType(View.LAYER_TYPE_HARDWARE, null);

        configureWebView(webview.getSettings());
        setupClients(webview);
        setupDownloads(webview);
        setupImageContextMenu(webview);

        // First-launch welcome dialog — tells the user about the hidden
        // Settings menu (where the WebView switcher lives). Shows only once
        // per install; dismissed with "Understood".
        WelcomeDialog.showIfNeeded(this);

        // Process share-from-outside intent if any
        Intent launchIntent = getIntent();
        if (launchIntent != null) {
            handleShareIntent(launchIntent);
        }

        if (desktopMode) {
            CookieManager.getInstance().removeAllCookies(new ValueCallback<Boolean>() {
                @Override
                public void onReceiveValue(Boolean value) {
                    CookieManager.getInstance().flush();
                    webview.clearCache(true);
                    webview.clearHistory();
                    WebStorage.getInstance().deleteAllData();
                    loadUrlWithHeaders(webview, URL);
                }
            });
        } else {
            loadUrlWithHeaders(webview, URL);
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

    /**
     * Handle an incoming ACTION_SEND or ACTION_PROCESS_TEXT intent.
     * Saves the text/file for later injection after the WebView loads.
     */
    private void handleShareIntent(Intent intent) {
        if (intent == null || intent.getAction() == null) return;
        String action = intent.getAction();
        Log.i(TAG, "handleShareIntent: action=" + action + ", type=" + intent.getType());

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
     * Handle shared text — copy to clipboard and load the site.
     * User pastes manually into the prompt box.
     */
    private void handleSharedText(String text) {
        Log.i(TAG, "handleSharedText: " + (text.length() > 80 ? text.substring(0, 80) + "..." : text));
        copyToClipboard(text);
        loadUrlWithHeaders(webview, URL);
    }

    private void copyToClipboard(String text) {
        try {
            android.content.ClipboardManager clipboard = (android.content.ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                android.content.ClipData clip = android.content.ClipData.newPlainText("WebGPT", text);
                clipboard.setPrimaryClip(clip);
                Log.i(TAG, "Text copied to clipboard");
                Toast.makeText(this, "Text copied — paste it into WebGPT", Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "copyToClipboard failed", e);
        }
    }

    /**
     * Handle shared file — copy the file to the app's cache dir, then show a toast
     * telling the user to tap + and select the file. The file picker will open at
     * the cache directory.
     *
     * We can't auto-attach files to the composer because:
     * 1. The + button opens a menu, not directly the file picker
     * 2. Even if we trigger onShowFileChooser, the site expects the URI to be
     *    from a content picker, not a pre-set URI
     *
     * So we just show a toast and let the user attach manually.
     */
    private void handleSharedFile(Uri fileUri, String mime) {
        Log.i(TAG, "handleSharedFile: " + fileUri + " (" + mime + ")");

        new Thread(() -> {
            try {
                String fileName = "shared_file_" + System.currentTimeMillis();
                String originalName = getFileNameFromUri(fileUri);
                if (originalName != null && !originalName.isEmpty()) {
                    fileName = originalName;
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

                // Make the file available to the + button via FileProvider.
                // We do NOT auto-click the + button — the user attaches manually.
                final Uri sharedUri = androidx.core.content.FileProvider.getUriForFile(this,
                        getPackageName() + ".fileprovider", outFile);
                pendingShareFileUri = sharedUri;

                final String finalFileName = fileName;
                runOnUiThread(() -> Toast.makeText(this,
                        "Tap + in WebGPT to attach: " + finalFileName,
                        Toast.LENGTH_LONG).show());

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

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView(WebSettings settings) {
        // JS + storage
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setBlockNetworkImage(false);

        // PERFORMANCE OPTIMIZATIONS — removed deprecated calls that hurt performance
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setBlockNetworkLoads(false);
        settings.setMediaPlaybackRequiresUserGesture(false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }

        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        settings.setUserAgentString(desktopMode ? UA_DESKTOP : UA_MOBILE);

        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setBuiltInZoomControls(true);
        settings.setSupportZoom(true);
        settings.setDisplayZoomControls(false);

        webview.requestFocusFromTouch();
        CookieManager.getInstance().setAcceptThirdPartyCookies(webview, true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            WebView.setWebContentsDebuggingEnabled(true);
        }

        if (desktopMode) {
            webview.setInitialScale(33);
        } else {
            webview.setInitialScale(0);
        }

        // Add JS interface for AndroidBridge (clipboard override)
        webview.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");
    }

    /**
     * JS interface exposed to the wrapped site's JavaScript.
     */
    public static class WebAppInterface {
        private final MainActivity activity;

        WebAppInterface(MainActivity activity) {
            this.activity = activity;
        }

        /**
         * Called by the navigator.share({ text }) JS override.
         * Copies to the system clipboard SILENTLY (no toast) —
         * The site shows its own "Copied!" feedback in the UI.
         */
        @JavascriptInterface
        public void copyToClipboard(final String text) {
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

                // Determine mime + extension
                String mime = conn.getContentType();
                if (mime != null && mime.contains(";")) {
                    mime = mime.split(";")[0].trim();
                }
                if (mime == null || !mime.startsWith("image/")) {
                    mime = "image/png";  // default
                }
                String ext = ".png";
                if (mime.contains("jpeg") || mime.contains("jpg")) ext = ".jpg";
                else if (mime.contains("webp")) ext = ".webp";
                else if (mime.contains("gif")) ext = ".gif";

                File outFile = new File(getCacheDir(),
                        "shared_image_" + System.currentTimeMillis() + ext);
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

    private void setupClients(final WebView webView) {
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, cm.message() + " -- line " + cm.lineNumber() + " of " + cm.sourceId());
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    request.grant(request.getResources());
                    List<String> needed = new ArrayList<>();
                    for (String r : request.getResources()) {
                        if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(r)
                                && ContextCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.CAMERA)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            needed.add(android.Manifest.permission.CAMERA);
                        }
                        if (PermissionRequest.RESOURCE_AUDIO_CAPTURE.equals(r)
                                && ContextCompat.checkSelfPermission(MainActivity.this, android.Manifest.permission.RECORD_AUDIO)
                                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            needed.add(android.Manifest.permission.RECORD_AUDIO);
                        }
                    }
                    if (!needed.isEmpty()) {
                        ActivityCompat.requestPermissions(MainActivity.this,
                                needed.toArray(new String[0]), REQUEST_PERMS);
                    }
                });
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;

                // If we have a pending shared file, return it immediately
                if (pendingShareFileUri != null) {
                    Log.i(TAG, "onShowFileChooser: returning pending shared file " + pendingShareFileUri);
                    filePathCallback.onReceiveValue(new Uri[]{pendingShareFileUri});
                    filePathCallback = null;
                    pendingShareFileUri = null;
                    return true;
                }

                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentIntent.setType("*/*");
                contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);

                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                try {
                    File cameraFile = new File(getCacheDir(),
                            "camera_capture_" + System.currentTimeMillis() + ".jpg");
                    Uri cameraUri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".fileprovider", cameraFile);
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    pendingCameraUri = cameraUri;
                    pendingCameraFile = cameraFile;
                } catch (Exception e) {
                    Log.e(TAG, "Camera setup failed", e);
                    pendingCameraUri = null;
                    pendingCameraFile = null;
                }

                Intent chooser = Intent.createChooser(contentIntent, "Select file");
                if (pendingCameraUri != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                }
                try {
                    startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
                } catch (Exception e) {
                    Log.e(TAG, "File chooser failed", e);
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // Use the original createPopup approach (v4.0/v4.1).
                // The window.open JS override handles external links (X, Reddit, LinkedIn)
                // BEFORE they reach onCreateWindow. Internal popups (share menu, OAuth)
                // go through createPopup which creates a proper popup WebView.
                return createPopup(resultMsg);
            }
        });

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                // External links (X, Reddit, LinkedIn, etc.) → open in browser
                Uri uri;
                try { uri = Uri.parse(url); } catch (Exception e) { return false; }
                String host = uri.getHost();
                if (host != null && !host.endsWith("chatgpt.com")
                        && !host.endsWith("openai.com")
                        && !host.endsWith("openai.com")
                        && !host.equals("accounts.google.com")
                        && !host.endsWith(".accounts.google.com")) {
                    openUrlInBrowser(url);
                    return true;
                }
                return false;
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
                    loadingOverlay.setVisibility(View.VISIBLE);
                }
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                // Page loaded successfully → mark this launch as non-crashing.
                CrashTracker.reset(true);
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                // If the initial load is already complete (SPA navigation),
                // do nothing — no overlay to hide.
                if (initialLoadComplete) return;
                webview.postDelayed(() -> {
                    if (loadingOverlay == null || initialLoadComplete) return;
                    // Set the flag BEFORE starting the fade-out animation.
                    // Any onPageStarted that fires during the 400ms fade
                    // window will see initialLoadComplete=true and skip
                    // re-showing the overlay. This fixes the race condition
                    // where SPA navigation during the fade-out window
                    // caused the loading screen to flash.
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
                }, 1500);
            }

@Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });
    }

    /**
     * Inject JS override for navigator.share (text→clipboard only).
     *
     * The Copy button calls navigator.share({ text: "..." }). We override
     * it to delegate to AndroidBridge.copyToClipboard(). We do NOT handle the
     * {url} case — sharing via navigator.share never worked reliably and has
     * been removed entirely.
     *
     * We do NOT touch navigator.clipboard at all — any manipulation of it
     * (direct assignment, defineProperty, prototype override) breaks
     * navigator.share lookups in some WebViews.
     *
     * The document.execCommand('copy') fallback is kept for legacy code paths.
     */
    private void injectAllOverrides(WebView v) {
        String js = "(function(){" +
                "  // === navigator.share override (text→clipboard only) ===" +
                "  try {" +
                "    if (!window._shareOverridden) {" +
                "      window._shareOverridden = true;" +
                "      navigator.share = function(data) {" +
                "        try {" +
                "          var text = (data && data.text) ? data.text : '';" +
                "          if (text && window.AndroidBridge) {" +
                "            window.AndroidBridge.copyToClipboard(String(text));" +
                "            return Promise.resolve();" +
                "          }" +
                "          return Promise.reject(new Error('Nothing to copy'));" +
                "        } catch(e) { return Promise.reject(e); }" +
                "      };" +
                "      navigator.canShare = function() { return true; };" +
                "    }" +
                "  } catch(e) {}" +
                "  // === document.execCommand('copy') fallback ===" +
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
                "})();";
        v.evaluateJavascript(js, null);
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

    /**
     * Download image to Download/ folder (not share).
     */
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
                int code = conn.getResponseCode();
                if (code < 200 || code >= 400) {
                    runOnUiThread(() -> Toast.makeText(this, "Failed: HTTP " + code, Toast.LENGTH_LONG).show());
                    return;
                }
                String mime = conn.getContentType();
                if (mime != null && mime.contains(";")) mime = mime.split(";")[0].trim();
                if (mime == null || !mime.startsWith("image/")) mime = "image/png";
                String ext = ".png";
                if (mime.contains("jpeg") || mime.contains("jpg")) ext = ".jpg";
                else if (mime.contains("webp")) ext = ".webp";
                else if (mime.contains("gif")) ext = ".gif";
                String fileName = "chatgpt_image_" + System.currentTimeMillis() + ext;

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
            if (url != null && url.startsWith("blob:")) {
                downloadBlobUrl(webView, url, contentDisposition, mimetype);
                return;
            }
            downloadWithCookies(url, userAgent, contentDisposition, mimetype);
        });
    }

    /**
     * Download a blob: URL by fetching it via in-page JavaScript and passing
     * the base64-encoded bytes back to native code via the evaluateJavascript
     * callback. No persistent JS override is required — the snippet is
     * self-contained.
     */
    private void downloadBlobUrl(WebView webView, String blobUrl, String contentDisposition, String mimetype) {
        Toast.makeText(this, "Downloading...", Toast.LENGTH_SHORT).show();
        final String filename = guessFilenameFromDownload(blobUrl, contentDisposition, mimetype);
        final String finalMime = mimetype != null ? mimetype : "application/octet-stream";
        // Fetch the blob via fetch(), read as data URL, return base64 payload.
        // Single self-contained IIFE — no window.* globals left behind.
        String js = "(function(){" +
                "  try {" +
                "    fetch('" + blobUrl.replace("'", "\\'") + "')" +
                "      .then(function(r){return r.blob();})" +
                "      .then(function(b){" +
                "        var fr = new FileReader();" +
                "        fr.onloadend = function(){" +
                "          try { window.__blobResult = fr.result; } catch(e) { window.__blobResult = ''; }" +
                "        };" +
                "        fr.readAsDataURL(b);" +
                "      })" +
                "      .catch(function(){ window.__blobResult = ''; });" +
                "  } catch(e) { window.__blobResult = ''; }" +
                "})();";
        // Poll window.__blobResult a few times — FileReader is async.
        final String[] result = {null};
        android.os.Handler h = new android.os.Handler();
        final int[] tries = {0};
        Runnable poll = new Runnable() {
            @Override public void run() {
                webView.evaluateJavascript(
                        "(function(){var v=window.__blobResult||null; if(v!==null){window.__blobResult=null;} return v;})();",
                        value -> {
                            tries[0]++;
                            if (value != null && !value.equals("null") && !value.isEmpty()) {
                                // value is a JS string literal: "data:...;base64,XXXX"
                                // Strip the outer quotes that evaluateJavascript adds.
                                String s = value;
                                if (s.startsWith("\"") && s.endsWith("\"")) {
                                    s = s.substring(1, s.length() - 1);
                                    // Unescape \" → "
                                    s = s.replace("\\\"", "\"").replace("\\\\", "\\");
                                }
                                if (s.startsWith("data:")) {
                                    int comma = s.indexOf(',');
                                    if (comma > 0) {
                                        String b64 = s.substring(comma + 1);
                                        saveBlobBase64(b64, filename, finalMime);
                                        return;
                                    }
                                }
                                // Empty data URL → blob was empty or fetch failed.
                                // Tell the user to use the long-press menu instead.
                                Toast.makeText(MainActivity.this,
                                        "Can't download directly. Hold down on the image to open the share & download menu.",
                                        Toast.LENGTH_LONG).show();
                            } else if (tries[0] < 10) {
                                h.postDelayed(this, 200);
                            } else {
                                // Polling timed out — blob fetch took too long.
                                // Tell the user to use the long-press menu instead.
                                Toast.makeText(MainActivity.this,
                                        "Can't download directly. Hold down on the image to open the share & download menu.",
                                        Toast.LENGTH_LONG).show();
                            }
                        });
            }
        };
        webView.evaluateJavascript(js, null);
        h.postDelayed(poll, 200);
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
                conn.setRequestProperty("User-Agent", userAgent != null ? userAgent :
                        (desktopMode ? UA_DESKTOP : UA_MOBILE));
                if (cookies != null && !cookies.isEmpty()) {
                    conn.setRequestProperty("Cookie", cookies);
                }
                conn.setRequestProperty("Accept", "*/*");
                conn.setRequestProperty("Referer", "https://chatgpt.com/");

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

                input = conn.getInputStream();
                final String finalRealMime = realMime;
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    String mimeExtension = android.webkit.MimeTypeMap.getSingleton()
                            .getExtensionFromMimeType(realMime);
                    String displayName = fileName;
                    String effectiveMime = realMime;
                    if (mimeExtension != null && !mimeExtension.isEmpty()) {
                        int lastDot = displayName.lastIndexOf('.');
                        if (lastDot > 0) {
                            displayName = displayName.substring(0, lastDot);
                        }
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
                    File outFile = new File(downloadsDir, fileName);
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
                Log.i(TAG, "Downloaded " + total + " bytes (" + fileName + ")");

                final String finalFileName = fileName;
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

    private String guessFilenameFromDownload(String url, String contentDisposition, String mimetype) {
        if (contentDisposition != null && !contentDisposition.isEmpty()) {
            // Robust RFC-6266 filename extraction. Previous versions used a
            // hand-rolled substring parser that left a trailing `"` in the
            // result when the disposition had trailing parameters
            // (e.g. `filename="x.pdf"; size=123`). A regex that captures
            // everything between the (optional) opening and closing quotes
            // is bulletproof.
            java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                    "filename\\*?=(?:UTF-8'')?\"?([^\";]+)\"?",
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

    private String sanitizeFilename(String name) {
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

    private boolean createPopup(Message resultMsg) {
        final WebView popup = new WebView(this);
        popup.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        WebSettings ps = popup.getSettings();
        ps.setJavaScriptEnabled(true);
        ps.setDomStorageEnabled(true);
        ps.setDatabaseEnabled(true);
        ps.setSupportMultipleWindows(true);
        ps.setJavaScriptCanOpenWindowsAutomatically(true);
        ps.setUserAgentString(webview.getSettings().getUserAgentString());
        ps.setCacheMode(WebSettings.LOAD_DEFAULT);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            ps.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(popup, true);
        popup.addJavascriptInterface(new WebAppInterface(this), "AndroidBridge");

        popup.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url == null) return false;
                // External links → open in browser
                Uri uri;
                try { uri = Uri.parse(url); } catch (Exception e) { return false; }
                String host = uri.getHost();
                if (host != null && !host.endsWith("chatgpt.com")
                        && !host.endsWith("openai.com")
                        && !host.endsWith("openai.com")
                        && !host.equals("accounts.google.com")
                        && !host.endsWith(".accounts.google.com")
                        && !host.endsWith("auth0.com")) {
                    openUrlInBrowser(url);
                    return true;
                }
                return false;
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                super.onPageFinished(v, url);
                CookieManager.getInstance().flush();
                injectAllOverrides(v);
                Uri uri = Uri.parse(url);
                String host = uri.getHost();
                if (host != null && host.endsWith("chatgpt.com") && !url.contains("/auth/")) {
                    loadUrlWithHeaders(webview, url);
                    removePopup(popup);
                }
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.proceed();
            }
        });

        popup.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onCloseWindow(WebView window) {
                removePopup(popup);
            }

            @Override
            public boolean onConsoleMessage(ConsoleMessage cm) {
                Log.d(TAG, "[popup] " + cm.message());
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> request.grant(request.getResources()));
            }

            @Override
            public boolean onShowFileChooser(WebView w,
                                             ValueCallback<Uri[]> callback,
                                             FileChooserParams fileChooserParams) {
                if (filePathCallback != null) {
                    filePathCallback.onReceiveValue(null);
                }
                filePathCallback = callback;
                if (pendingShareFileUri != null) {
                    filePathCallback.onReceiveValue(new Uri[]{pendingShareFileUri});
                    filePathCallback = null;
                    pendingShareFileUri = null;
                    return true;
                }
                Intent contentIntent = new Intent(Intent.ACTION_GET_CONTENT);
                contentIntent.addCategory(Intent.CATEGORY_OPENABLE);
                contentIntent.setType("*/*");
                contentIntent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                Intent cameraIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                try {
                    File cameraFile = new File(getCacheDir(),
                            "camera_capture_" + System.currentTimeMillis() + ".jpg");
                    Uri cameraUri = androidx.core.content.FileProvider.getUriForFile(
                            MainActivity.this, getPackageName() + ".fileprovider", cameraFile);
                    cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraUri);
                    cameraIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                    pendingCameraUri = cameraUri;
                    pendingCameraFile = cameraFile;
                } catch (Exception e) {
                    pendingCameraUri = null;
                    pendingCameraFile = null;
                }
                Intent chooser = Intent.createChooser(contentIntent, "Select file");
                if (pendingCameraUri != null) {
                    chooser.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Intent[]{cameraIntent});
                }
                try {
                    startActivityForResult(chooser, REQUEST_FILE_CHOOSER);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
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
        try {
            rootLayout.removeView(popup);
            popupViews.remove(popup);
            popup.destroy();
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
        for (WebView popup : new ArrayList<>(popupViews)) {
            removePopup(popup);
        }
        if (loadingLogo != null) {
            loadingLogo.clearAnimation();
        }
        if (webview != null) {
            webview.destroy();
        }
        super.onDestroy();
    }
}
