# WebGPT

A feature-rich Android WebView wrapper that loads the ChatGPT website (`chatgpt.com`), designed for devices without Google Mobile Services (GMS). Built entirely with **GLM agent mode through Z.AI** — this app was coded, compiled, and iteratively debugged by an AI agent, testing the limits of what's possible with LLM-driven development.

> **WebGPT is an independent, unofficial client.** It is not affiliated with, endorsed by, or sponsored by OpenAI. The WebGPT name, icon, and source code are this project's own — they do not use the ChatGPT trademark or logo. WebGPT simply loads the public `chatgpt.com` website inside an Android WebView, the same way any general-purpose browser would.

## Inspiration

- Based on the [webapp](https://github.com/wskang12138/webapp) template by `wskang12138`
- Inspired by [duckAssist](https://github.com/diekaiju/duckAssist) — The idea of making this app came to me while trying duckAssist

## App family & porting

WebGPT is the **base app** of a family of WebView wrappers — the other apps in
the family wrap other websites and are branched from this codebase. Every app
in the family ships a **`WEBSITE_SPECIFICS.md`** in the repository root:

- In WebGPT it documents everything wired to `chatgpt.com` (selectors, hosts,
  injected scripts, timings, workarounds) plus a full porting guide.
- In each derivative app it documents everything that app changed relative to
  WebGPT, using the same structure.

That file is what makes it possible to port new features from a newer WebGPT
into a derivative app without breaking the
site-specific layer. If you fork this project for another website, read
`WEBSITE_SPECIFICS.md` first — it tells you exactly what to change and what
to leave alone.

## Features

### Core
- **WebView** wrapping `chatgpt.com` with session cookie persistence
- **Clean User-Agent** — removes Android WebView detection markers (`X-Requested-With` header override)
- **Material 3 design** — blue theme palette (from Material Theme Builder), dark mode support, Material Components throughout
- **Pure black dark mode** — status bar and background are `#000000` in dark mode (not the default off-black), `#FCFCFC` in light mode; the status and navigation bars follow live system dark/light switches without restarting the app
- **Privacy: third-party cookies blocked** on the main page (cross-site trackers inside chatgpt.com cannot follow you); OAuth popups keep them so Google sign-in still works

### WebView Switcher (the headline feature)
- **In-app WebView provider picker** — switch the WebView implementation at runtime without affecting any other app on the device. Reverse-engineered from Better xCloud.
- **How it works** — swaps the cached `IBinder` for `"webviewupdate"` in `ServiceManager.sCache` with a `java.lang.reflect.Proxy` that intercepts `IWebViewUpdateService.waitForAndGetProvider()` and overwrites the returned `packageInfo` field with the user's chosen `PackageInfo`
- **13 supported providers** — Android System WebView, Chrome (stable/beta/dev/canary), Thorium, Mulch, Huawei WebView, Amazon WebView
- **Built-in downloader tab** — links to Google Play, Thorium (GitHub), and Mulch (GitLab) repository pages
- **Crash recovery** — after 3 consecutive crashes, automatically redirects to the WebView picker so the user can select a different provider
- **Developer mode flags** — optional "Optimize WebView performance" toggle installs a synthetic `DeveloperModeContentProvider` that enables `ignore-gpu-blocklist` and `WebViewSurfaceControl` on the chosen WebView

### Loading Screen
- Spinning WebGPT logo with fade animation (property animators on a hardware layer — stays smooth even while the page is loading)
- Theme-aware: black logo on white background (light mode), white logo on black background (dark mode)
- No white flash in dark mode (short 500ms overlay settle before fade-out)
- **Smooth fade-out** — overlay fades from opaque to transparent over 400ms (accelerate interpolator) instead of abruptly disappearing
- **Uniform launch splash** — the Android 12+ system splash screen is a plain frame of the loading screen's background color (light and dark mode, no icon): it crossfades into the loading screen over 300ms, so every device gets the same flash-free launch instead of OEM launch animations. On Android < 12 the launch window is pinned to the same colors
- **Optional page-progress bar** — a Material 3 `LinearProgressIndicator` (4dp, rounded ends, gap + stop indicator per the M3 spec) at the top of the loading screen. Its progress is honest: chatgpt.com reports "fully loaded" long before the chat UI is actually ready, so the bar caps at 90% and only completes when the app's real page-ready detection (the same system that dismisses the loading screen) fires. Off by default; enable it in Settings → Interface → "Show page loading progress"

### Hidden Settings (Material 3 UI)
- Accessible via the gear icon in Android's "App info" screen
- **First-launch welcome dialog** — tells new users about the hidden settings menu and known limitations. Shows once per install, dismissed with "Understood"
- **Optimize WebView performance toggle** — enables GPU blocklist bypass + surface control
- **Show page loading progress toggle** — off by default; adds a Material 3 progress bar to the loading screen (takes effect on the next page load, no restart)
- **Check for updates button** — checks GitHub releases for newer versions. When an update is found, opens the release download page in the user's default browser. The browser handles the APK download and install — no in-app `PackageInstaller` (which crashed on some OEM ROMs)
- **Google sign-in troubleshooting tips**

### File Handling
- **Downloads** — saves to the public `Download/` folder with original filename (MediaStore API on Android 10+)
- **Blob URL downloads** — blob URLs are captured while still alive (the site revokes them immediately after triggering a download, which is why naive WebView downloads fail) and saved with the correct extension — works for generated files, PDF/Word/Markdown exports, etc.
- **File upload** — multi-select file picker + camera capture (full resolution via FileProvider); camera permission is requested on demand
- **Image context menu** — long-press any image → Material 3 AlertDialog (matching the WebView Manager style) with "Share image" and "Download image" buttons

### Sharing
- **Share to WebGPT** — receive shared text/files from any app (`ACTION_SEND` intent-filter, labeled "Send to WebGPT"). Text is copied to the clipboard; files are attached to the composer automatically
- **Ask WebGPT** — appears in Android's text selection menu (`ACTION_PROCESS_TEXT`), copies text to clipboard for pasting
- **In-app Share button** — `navigator.share()` is bridged to Android's real share sheet: text/URL shares open the system share dialog, image/file shares hand the file to any app (Telegram, Drive, ...)
- **Copy button** — `navigator.share({ text })` with text only delegates to the clipboard (fixes the site's Copy button on WebViews that don't support the async Clipboard API)
- **External links** — X, Reddit, LinkedIn etc. open in the system browser

### Camera & Microphone
- Camera/microphone permissions are requested **on demand** — when the wrapped site asks for them, the app requests the matching Android permission and grants only what the user approved (allowlisted origins only)

### Popup WebView
- Proper popup support for OAuth flows (Google sign-in)
- Shared cookie jar via `CookieManager`
- AndroidBridge JS interface injected for native communication

### Known limitations
- **Microphone dictation** — voice input may refuse to start despite permissions. Selecting the alternative WebView could resolve the problem
- **Google sign-in** — Google blocks OAuth in Android WebViews ("browser or app may not be secure"). Email sign-in works without issues

## Tech stack

AGP 8.2.2, Gradle 8.5, JDK 17+, compileSdk 34, minSdk 21, targetSdk 34, Material 1.12.0, androidx.recyclerview 1.3.2, androidx.webkit 1.10.0, androidx.core-splashscreen 1.0.1, R8 enabled.

WebGPT is free software under the MIT License.
