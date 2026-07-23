# WebGPT

A feature-rich Android WebView wrapper that loads the ChatGPT website (`chatgpt.com`), designed for devices without Google Mobile Services (GMS). Built entirely with **GLM 5.2 agent mode through Z.AI** — this app was coded, compiled, and iteratively debugged by an AI agent, testing the limits of what's possible with LLM-driven development.

> **WebGPT is an independent, unofficial client.** It is not affiliated with, endorsed by, or sponsored by OpenAI. The WebGPT name, icon, and source code are this project's own — they do not use the ChatGPT trademark or logo. WebGPT simply loads the public `chatgpt.com` website inside an Android WebView, the same way any general-purpose browser would.

## Inspiration

- Based on the [webapp](https://github.com/wskang12138/webapp) template by `wskang12138`
- Inspired by [duckAssist](https://github.com/diekaiju/duckAssist) — The idea of making this app came to me while trying duckAssist

## Features

### Core
- **WebView** wrapping `chatgpt.com` with session cookie persistence
- **Clean User-Agent** — removes Android WebView detection markers (`X-Requested-With` header override)
- **Material 3 design** — blue theme palette (from Material Theme Builder), dark mode support, Material Components throughout
- **Pure black dark mode** — status bar and background are `#000000` in dark mode (not the default off-black), `#FCFCFC` in light mode
- **Vector launcher icon** — adaptive icon with the WebGPT mark as a vector drawable (no PNGs for the foreground), black logo on white `#FCFCFC` background

### WebView Switcher (the headline feature)
- **In-app WebView provider picker** — switch the WebView implementation at runtime without affecting any other app on the device. Reverse-engineered from Better xCloud.
- **How it works** — swaps the cached `IBinder` for `"webviewupdate"` in `ServiceManager.sCache` with a `java.lang.reflect.Proxy` that intercepts `IWebViewUpdateService.waitForAndGetProvider()` and overwrites the returned `packageInfo` field with the user's chosen `PackageInfo`
- **13 supported providers** — Android System WebView, Chrome (stable/beta/dev/canary), Thorium, Mulch, Huawei WebView, Amazon WebView
- **Built-in downloader tab** — links to Google Play, Thorium (GitHub), and Mulch (GitLab) repository pages
- **Crash recovery** — after 3 consecutive crashes, automatically redirects to the WebView picker so the user can select a different provider
- **Developer mode flags** — optional "Optimize WebView performance" toggle installs a synthetic `DeveloperModeContentProvider` that enables `ignore-gpu-blocklist` and `WebViewSurfaceControl` on the chosen WebView

### Loading Screen
- Spinning WebGPT logo with fade animation
- Theme-aware: black logo on white background (light mode), white logo on black background (dark mode)
- No white flash in dark mode (1500ms overlay delay before fade-out)
- **Smooth fade-out** — overlay fades from opaque to transparent over 400ms (accelerate interpolator) instead of abruptly disappearing

### Hidden Settings (Material 3 UI)
- Accessible via the floating button at the top-right of the main screen, or via the gear icon in Android's "App info" screen
- **First-launch welcome dialog** — tells new users about the hidden settings menu and known limitations. Shows once per install, dismissed with "Understood"
- **Desktop mode toggle** (Material 3 switch) — forces desktop layout of the wrapped site
- **Optimize WebView performance toggle** — enables GPU blocklist bypass + surface control
- **Check for updates button** — checks GitHub releases for newer versions. When an update is found, opens the release download page in the user's default browser. The browser handles the APK download and install — no in-app `PackageInstaller` (which crashed on some OEM ROMs)
- **Google sign-in troubleshooting tips**

### File Handling
- **Downloads** — saves to the public `Download/` folder with original filename (MediaStore API on Android 10+)
- **Blob URL downloads** — attempts to fetch blob: URLs via in-page JavaScript. If the fetch times out, shows a helpful toast telling the user to long-press the image instead
- **File upload** — multi-select file picker + camera capture (full resolution via FileProvider)
- **Image context menu** — long-press any image → Material 3 AlertDialog (matching the WebView Manager style) with "Share image" and "Download image" buttons

### Sharing
- **Share to WebGPT** — receive shared text/files from any app (`ACTION_SEND` intent-filter, labeled "Send to WebGPT")
- **Ask WebGPT** — appears in Android's text selection menu (`ACTION_PROCESS_TEXT`), copies text to clipboard for pasting
- **Copy button** — `navigator.share({ text })` override delegates to `AndroidBridge.copyToClipboard()` (fixes the site's Copy button on WebViews that don't support the async Clipboard API)
- **External links** — X, Reddit, LinkedIn etc. open in the system browser
- **Note: chat/image sharing is unreliable** — the in-chat Share button (for sharing chat links and images) does not work on all devices. Long-press an image and choose "Share image" as the reliable alternative

### Camera & Microphone
- Auto-grants WebView permissions (`onPermissionRequest`)
- OS-level permissions requested at app startup

### Popup WebView
- Proper popup support for OAuth flows (Google sign-in)
- Shared cookie jar via `CookieManager`
- `AndroidBridge` JS interface injected for native communication

## Known Limitations

- **Chat/image sharing** — the wrapped site's native Share button is unreliable and does not work on all devices. The welcome dialog informs new users about this. Long-press an image → "Share image" is the recommended workaround.
- **Microphone dictation** — voice input detects the WebView environment and refuses to start, despite permissions being granted. This issue is specific to certain WebViews; selecting an alternative WebView could resolve the problem.
- **Google sign-in** — Google blocks OAuth in Android WebViews ("browser or app may not be secure"). Workaround: retry — Google sometimes lets you through on the second attempt. Email sign-in works without issues.

## Tech Stack

| Component | Version |
|-----------|---------|
| Android Gradle Plugin | 8.2.2 |
| Gradle | 8.5 |
| JDK | 21 |
| compileSdk | 34 |
| minSdk | 21 |
| Material Components | 1.11.0 |
| androidx.recyclerview | 1.3.2 |
| androidx.webkit | 1.10.0 |
| Target SDK | 34 |
| R8 minification | Enabled (53% smaller APK) |

## Build

### Prerequisites
- JDK 21
- Android SDK (platform 34, build-tools 34.0.0)
- Gradle 8.5 (via wrapper)

### Local build
```bash
export JAVA_HOME=/path/to/jdk21
export ANDROID_HOME=/path/to/android-sdk
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

## Architecture

```
App (Application)
  ├─ WebViewUtil.init()        — captures default provider, scans installed providers
  ├─ Hooker.hookPackageManager() — lies about hasSystemFeature
  ├─ Hooker.hookServiceManagerService() — THE WebView switch (swaps ServiceManager.sCache)
  └─ Hooker.hookInstallContentProviders() — installs DeveloperModeContentProvider

MainActivity
  ├─ Pre-launch checks (WebView supported? Crash threshold reached?)
  ├─ WebView host, loading screen, file handling, share intents
  ├─ injectAllOverrides() — navigator.share + execCommand('copy') JS overrides
  └─ WebAppInterface — @JavascriptInterface bridge
       └─ copyToClipboard() — navigator.share({text}) → Android clipboard

SettingsActivity
  ├─ Desktop mode toggle (MaterialSwitch, persists immediately)
  ├─ Optimize WebView toggle (MaterialSwitch, persists immediately)
  ├─ WebView Manager button → WebViewManagerDialog
  ├─ Check for updates button → UpdateChecker
  └─ Apply & restart button

WebViewManagerDialog (Material AlertDialog)
  ├─ Installed tab — RecyclerView of installed WebView providers (radio list)
  └─ Downloader tab — links to Google Play / Thorium / Mulch repos

UpdateChecker
  └─ checkForUpdates() — GitHub releases API → opens browser when update found

WelcomeDialog — first-launch info dialog (shown once)
```

## Updates

The in-app **Check for updates** button queries the GitHub releases API for this repository:

```
https://api.github.com/repos/MrHuaweiFan/WebGPT/releases
```

When a newer release is found, the app opens the release's download page in the user's default browser. The browser then handles the APK download and the system package installer handles the install — no in-app `PackageInstaller` (which crashed on some OEM ROMs). If you fork this repo, update `REPO_API` and `USER_AGENT` in `app/src/main/java/com/webgpt/app/webview/UpdateChecker.java` to point at your own releases.

## License

WebGPT is free software released under the **MIT License** — see [LICENSE](LICENSE) for the full text. The WebGPT name and icon are this project's own and do not infringe any third-party trademark. "ChatGPT" is a trademark of OpenAI; this project is not affiliated with OpenAI and merely loads the public `chatgpt.com` website inside an Android WebView.

## F-Droid metadata

The repository ships F-Droid / Fastlane upstream metadata so the app's
listing (description, icon, screenshots, changelog) is under direct
control of the developer:

```
fastlane/metadata/android/en-US/
├── short_description.txt        (≤ 80 chars, no trailing dot)
├── full_description.txt
├── changelogs/
│   └── 79.txt                   (max 500 chars; 79 = versionCode)
└── images/
    ├── icon.png                 (512×512 PNG)
    ├── phoneScreenshots/        (1.png … 5.png)
    └── sevenInchScreenshots/    (1.png)
```

See [Submitting to F-Droid — Quick Start Guide](https://f-droid.org/docs/Submitting_to_F-Droid_Quick_Start_Guide/)
for the official spec.

---

*This entire app — every line of Java, every XML layout, every build config — was written, debugged, and iteratively improved by GLM 5.2 (Z.AI agent mode) across a single conversation. No human wrote any code. During development, the AI compiled test APKs internally for verification; all release APKs are compiled by GitHub Actions.*

ChatGPT WebApp for Android repo is now private to avoid any brand infringement. 
