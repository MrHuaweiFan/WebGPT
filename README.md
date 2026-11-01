<div align="center">

<img src="app/src/main/res/mipmap-xxxhdpi/ic_launcher.png" width="128" alt="WebGPT logo">

# WebGPT

**Privacy-focused ChatGPT WebView wrapper, built for GMS-free devices.**

[![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Material Design 3](https://img.shields.io/badge/Material%20Design-3-6750A4?style=flat&logo=materialdesign&logoColor=white)](https://m3.material.io/) 
[![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)](#requirements)
[![F-Droid release](https://img.shields.io/f-droid/v/org.webgpt.app?logo=f-droid&label=f-droid%20release&logoColor=white)](https://f-droid.org/packages/org.webgpt.app/)
[![Latest release](https://img.shields.io/github/v/release/MrHuaweiFan/WebGPT?logo=github&label=release)](https://github.com/MrHuaweiFan/WebGPT/releases/latest)
[![Last Commit](https://img.shields.io/github/last-commit/MrHuaweiFan/WebGPT?logo=github&label=last%20commit)](https://github.com/MrHuaweiFan/WebGPT)
[![GitHub downloads](https://img.shields.io/github/downloads/MrHuaweiFan/WebGPT/total?logo=github&label=downloads)](https://github.com/MrHuaweiFan/WebGPT/releases)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

<a href="https://f-droid.org/packages/org.webgpt.app/"><img src="https://f-droid.org/badge/get-it-on.png" height="80" alt="Get it on F-Droid"></a>
<a href="https://github.com/MrHuaweiFan/WebGPT/releases/latest"><img src="./fastlane/metadata/android/en-US/images/badge_github.png" height="80" alt="Get it on GitHub"></a>

</div>

> **WebGPT is an independent, unofficial client.** It is not affiliated with, endorsed by, or sponsored by OpenAI. The WebGPT name, icon and source code are this project's own and do not use the ChatGPT trademark or logo. WebGPT loads the public `chatgpt.com` website inside an Android WebView, the same way any general-purpose browser would.

## Screenshots

<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/1.png" width="30%" alt="WebGPT screenshot 1">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/2.png" width="30%" alt="WebGPT screenshot 2">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/3.png" width="30%" alt="WebGPT screenshot 3">
</p>
<p align="center">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/4.png" width="30%" alt="WebGPT screenshot 4">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/5.png" width="30%" alt="WebGPT screenshot 5">
</p>

## Why WebGPT?

WebGPT is not a simple WebView wrapper, it offers many extra enhancements for a better mobile experience, such as a flexible WebView switcher, seamless Android sharing integration, and improved handling of downloads and external links.

## Features

### Main Features
- **Smooth loading screen.** A spinning logo that fades out with no white flash. An optional page-progress bar can be turned on in Settings.
- **Privacy.** Third-party cookies are blocked on the main page. Sign-in popups keep them, so Google sign-in still works. Camera and microphone permissions are requested only when the site asks for them.
- **Links open where they should.** Links to other sites open in your default browser.
- **Downloads.** Generated files, PDFs, Word documents and Markdown exports to your `Download` folder.
- **Long-press an image** to share it or save it.
- **Settings menu.** There is a hidden settings menu under `Android Settings` → `Apps` → `WebGPT` and tap the gear icon. There you can pick a WebView, show the page-progress bar and check for updates.

### Sharing
- **Share to WebGPT** from any app. Shared text is copied to the clipboard and shared files are attached to the chat for you.
- **Ask WebGPT** appears in the text-selection menu and copies the selected text to the clipboard.

### WebView switcher
- Choose between 13 WebView providers: Android System WebView, Chrome (stable, beta, dev, canary), Thorium, Mulch, Huawei WebView and Amazon WebView.
- A built-in downloader tab links to Google Play, Thorium (GitHub) and Mulch (GitLab).
- If the app crashes 3 times in a row, it sends you to Settings so you can pick another WebView.
- An optional "Optimize WebView performance" switch can improve speed on some devices.

## Known limitations

- **Voice dictation** may refuse to start even with permissions granted. Switching to another WebView can fix it.
- **Google sign-in** is sometimes blocked by Google in Android WebViews ("browser or app may not be secure"). Email sign-in works, and trying another WebView can help.

## For developers

| Document | What it covers |
| --- | --- |
| [BUILD.md](BUILD.md) | How to build and sign the app |
| [WEBSITE_SPECIFICS.md](WEBSITE_SPECIFICS.md) | Everything specific to `chatgpt.com`: what went wrong with the site, how WebGPT works around it, and a porting guide |
| [WEBVIEW_SWITCHER.md](WEBVIEW_SWITCHER.md) | How the in-app WebView switcher works |

WebGPT is the base app of a family of WebView wrappers for other websites. Each app in the family has its own `WEBSITE_SPECIFICS.md`. In WebGPT it lists everything tied to `chatgpt.com`. In a derived app it lists what that app changed. If you fork WebGPT for another site, read that file first. It tells you what to change and what to leave alone.

The app is written in Kotlin with Material 3. Tech stack: AGP 8.2.2, Gradle 8.5, JDK 17+, compileSdk 34, minSdk 21, targetSdk 34, Material 1.12.0, androidx.recyclerview 1.3.2, androidx.webkit 1.10.0, androidx.core-splashscreen 1.0.1, R8 enabled.

## Credits

- Based on the [webapp](https://github.com/wskang12138/webapp) template by `wskang12138`.
- Inspired by [duckAssist](https://github.com/diekaiju/duckAssist). The idea for this app came from trying it.
- The WebView switcher is reverse-engineered from Better xCloud.
- Built with **GLM agent mode through Z.AI** and **Claude Code** (v6.29+).

## License

WebGPT is free software under the [MIT License](LICENSE).
