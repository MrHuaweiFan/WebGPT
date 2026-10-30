# WebView Switcher — Implementation Summary

This document describes what was added to WebGPT
to implement the in-app WebView selector/switcher reverse-engineered from
Better xCloud.

## What was added

### New Kotlin source files

**`org.webgpt.app` package:**
- `App.kt` — `Application` subclass. Installs the three runtime hooks in
  `onCreate()` BEFORE any Activity constructs a WebView. This is critical:
  Android's `WebViewFactory` loads the WebView implementation lazily on the
  first `new WebView(ctx)` call, and once loaded it can't be swapped without
  killing the process.
- `AppRestart.kt` — `Activity.restartApp()`. Relaunches the app in a fresh
  process so a newly selected WebView gets loaded.

**`org.webgpt.app.webview` package:**
- `WebViewUtil.kt` — provider registry. Captures the system default WebView
  via `WebView.getCurrentWebViewPackage()`, scans a hardcoded list of 13
  package names (`ALLOWED_PROVIDERS`) for installed alternatives, reads the
  user's choice from SharedPreferences under `webview_implementation`, and
  calls `Hooker.hookServiceManagerService()` if a custom provider is selected.
- `Hooker.kt` — three runtime hooks:
  - `hookServiceManagerService()` — THE SWITCH. Replaces
    `ServiceManager.sCache["webviewupdate"]` with a Proxy.
  - `hookPackageManager()` — lies about `hasSystemFeature("audio.low_latency")`.
  - `hookInstallContentProviders()` — installs a fake
    `DeveloperModeContentProvider` with the chosen WebView package's authority
    so Chromium enables `ignore-gpu-blocklist` + `WebViewSurfaceControl`.
- `IServiceManagerProxy.kt` — `InvocationHandler` for the proxied `IBinder`.
  Intercepts `queryLocalInterface("android.webkit.IWebViewUpdateService")` and
  returns our service proxy.
- `IWebViewUpdateServiceProxy.kt` — `InvocationHandler` for
  `IWebViewUpdateService`. Intercepts `waitForAndGetProvider()` and
  reflectively overwrites the returned object's `packageInfo` field with the
  user's chosen `PackageInfo`.
- `IPackageManagerProxy.kt` — `InvocationHandler` for `IPackageManager`.
- `DeveloperModeContentProvider.kt` — fake content provider that returns
  dev-mode flag overrides.
- `CrashTracker.kt` — SharedPreferences-backed crash counter. Bumps on every
  cold start. It resets after the first successful page load, when the
  activity is paused, and on a clean destroy. After 3 crashes, MainActivity
  shows a toast asking for another WebView and bounces the user to Settings,
  where the WebView Manager button is.
- `WebViewManagerDialog.kt` — Material AlertDialog hosting the picker UI.
  Two tabs: "Installed" (radio list of installed providers) and "Downloader"
  (Google Play / Thorium / Mulch download sources).
- `WebViewInstalledAdapter.kt` — RecyclerView adapter for the Installed tab.
- `WebViewDownloaderAdapter.kt` — RecyclerView adapter for the Downloader tab.
- `WebViewSource.kt` — plain class describing one download source (id, name,
  source, `onDownload` action, `supported` flag).
- `ProxyReflection.kt` — `Method.invokeOn()` helper used by the three proxy
  handlers. It rethrows the callee's own exception instead of an
  `UndeclaredThrowableException`.
- `WelcomeDialog.kt` — one-time dialog that tells the user about the hidden
  Settings menu. Shown after the pre-launch checks pass.
- `UpdateChecker.kt` — checks GitHub for a newer app version
  (`HttpURLConnection` + `org.json`).
- `WebViewImplementation.kt` — enum (GOOGLE / THORIUM / MULCH).

### New layout files (3)
- `res/layout/dialog_webview_manager.xml` — the dialog with tab toggle.
- `res/layout/item_webview_installed.xml` — row layout for an installed provider.
- `res/layout/item_webview_download.xml` — row layout for a download source.

### Modified files
- `AndroidManifest.xml` — added `<queries>` block (13 WebView package names),
  `android:name=".App"` on `<application>`, and registered
  `DeveloperModeContentProvider` as a fallback manifest entry.
- `app/build.gradle` — added `androidx.recyclerview:recyclerview:1.3.2`,
  `androidx.core:core-splashscreen:1.0.1` and `androidx.webkit:webkit:1.10.0`
  dependencies.
- `res/values/strings.xml` — added the strings for the picker UI, the welcome
  dialog, the image actions and the update check.
- `res/layout/activity_settings.xml` — added a new "WebView" section with a
  WebView Manager button + an Optimize WebView toggle.
- `SettingsActivity.kt` — wired up the WebView Manager button to open the
  picker dialog and force a restart when the selection changes. Wired up the
  Optimize WebView toggle to persist its state.
- `MainActivity.kt` — added pre-launch checks in `onCreate()`:
  1. If the current WebView doesn't support `DOCUMENT_START_SCRIPT`, force-open
     the picker non-cancelable.
  2. If `CrashTracker` reports 3+ crashes, bounce to Settings.
  Also reset `CrashTracker` on `onPageFinished()`.

## How the user interacts with it

1. Open Settings → scroll to the new **WebView** section.
2. Tap **WebView Manager**.
3. The dialog shows two tabs:
   - **Installed** — every WebView package currently installed on the device.
     Each row shows app icon, name, package name, version, and a radio button.
     Tap a row to select it. The selection is saved immediately to
     SharedPreferences.
   - **Downloader** — three sources (Google Play Store, Thorium on GitHub,
     Mulch on GitLab). Tap Download to open the source's page in the browser.
     There is no in-app APK download or install: install the APK from the
     browser, then pick it in the Installed tab.
4. After dismissing the dialog with a different selection, the app shows a
   "Restart required" toast and force-restarts so the new WebView gets loaded.
5. Toggle **Optimize WebView performance** to also install the
   `DeveloperModeContentProvider` hook (forces `ignore-gpu-blocklist` and
   `WebViewSurfaceControl` on the chosen WebView).

## Key invariants

- **Boot order matters**: `WebViewUtil.init()` MUST run in `Application.onCreate()`
  before any `new WebView(ctx)`. Otherwise `WebViewFactory` will already have
  cached the real binder and the swap won't take effect.
- **Process restart required on selection change**: the WebView native lib
  can't be unloaded, so changing the selection requires killing the process.
  The dialog + `SettingsActivity` handle this automatically via
  `restartApp()` (`AppRestart.kt`), which calls `finishAffinity()` and
  `Process.killProcess(myPid())`.
- **API 26+ only**: `WebView.getCurrentWebViewPackage()` was added in API 26.
  On older devices the switcher is silently disabled (`WebViewUtil.init()` is
  a no-op).
- **Process-scoped**: the swap does NOT affect any other app on the device —
  it only changes what `WebViewFactory` loads in the calling process.

## Dependencies added

| Dependency | Version | Purpose |
|-----------|---------|---------|
| `androidx.recyclerview:recyclerview` | 1.3.2 | Lists in the picker dialog |
| `androidx.webkit:webkit` | 1.10.0 | `WebViewFeature.isFeatureSupported()` check |
| `androidx.core:core-splashscreen` | 1.0.1 | Android 12+ splash screen exit animation |

No HTTP/JSON libraries were added. The app's own update check
(`UpdateChecker.kt`) uses the built-in `HttpURLConnection` and
`org.json`, which keeps the dependency footprint minimal.

## File map

```
app/src/main/
├── AndroidManifest.xml                          ← modified (queries, App, provider)
├── java/org/webgpt/app/
│   ├── App.kt                               ← NEW (Application subclass)
│   ├── AppRestart.kt                        ← NEW (restartApp)
│   ├── MainActivity.kt                      ← modified (pre-launch checks)
│   ├── SettingsActivity.kt                  ← modified (WebView Manager button)
│   └── webview/
│       ├── WebViewUtil.kt                   ← NEW
│       ├── Hooker.kt                        ← NEW
│       ├── IServiceManagerProxy.kt          ← NEW
│       ├── IWebViewUpdateServiceProxy.kt    ← NEW (THE actual swap)
│       ├── IPackageManagerProxy.kt          ← NEW
│       ├── DeveloperModeContentProvider.kt  ← NEW
│       ├── CrashTracker.kt                  ← NEW
│       ├── WebViewManagerDialog.kt          ← NEW
│       ├── WebViewInstalledAdapter.kt       ← NEW
│       ├── WebViewDownloaderAdapter.kt      ← NEW
│       ├── WebViewSource.kt                 ← NEW
│       ├── WebViewImplementation.kt         ← NEW
│       ├── ProxyReflection.kt               ← NEW
│       ├── WelcomeDialog.kt                 ← NEW
│       └── UpdateChecker.kt                 ← NEW
├── res/
│   ├── layout/
│   │   ├── dialog_webview_manager.xml           ← NEW
│   │   ├── item_webview_installed.xml           ← NEW
│   │   ├── item_webview_download.xml            ← NEW
│   │   └── activity_settings.xml                ← modified (WebView section)
│   └── values/
│       └── strings.xml                          ← modified (picker, welcome, image, update strings)
└── ../../build.gradle                           ← modified (3 new deps, outside this tree)
```

## How to build

Same as before — no changes to the build system besides the three new
dependencies. With Android Studio: open the project, sync Gradle, press Run.
With the command line:

```bash
./gradlew assembleRelease
# or
./gradlew assembleDebug
```

The signed release APK will be at `app/build/outputs/apk/release/app-release.apk`.
