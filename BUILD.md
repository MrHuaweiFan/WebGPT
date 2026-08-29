# Building the APK on GitHub Actions

This fork has been set up so the APK is built automatically on GitHub's servers — no Android Studio required.

## What the workflow does

`.github/workflows/build-apk.yml` runs on every push to `master` / `main`, every pull request, and on demand via the **Actions → Build APK → Run workflow** button.

It:
1. Spins up an `ubuntu-latest` runner.
2. Installs JDK 17 (Temurin) and the Android SDK (platform 34 + build-tools 34.0.0); the build itself uses Gradle 8.5 via the wrapper (AGP 8.2.2).
3. Runs `./gradlew assembleDebug` and `./gradlew assembleRelease` (the release build is skipped when no signing keystore secret is configured, e.g. on forks).
4. Decodes the signing keystore from the `WEBGPT_KEYSTORE_BASE64` GitHub Secret (plus the corresponding password / alias secrets).
5. Uploads the artifacts you can download from the run page:
   - `app-debug-apk` — `app-debug.apk` (signed with the debug key, ready to install)
   - `app-release-apk` — `app-release.apk` (signed with the private release keystore, when configured)

## How to use it

1. Create a new (empty) repository on GitHub, e.g. `WebGPT`.
   - Do **not** initialize it with a README / .gitignore / license — keep it empty.
2. Push this folder to that repository:
   ```bash
   git init
   git add .
   git commit -m "Initial commit — WebGPT webview app with GitHub Actions build"
   git branch -M main
   git remote add origin https://github.com/<your-user>/WebGPT.git
   git push -u origin main
   ```
3. Open the repo on GitHub → **Actions** tab → **Build APK** workflow → watch the run.
4. When the run finishes, scroll down to **Artifacts** and download the APK you want.

## Signing notes

The release APK is signed with a **private** release keystore that is **never committed** to the repository. The keystore and its credentials are stored in GitHub Secrets and decoded at build time by the GitHub Actions workflow:

- `WEBGPT_KEYSTORE_BASE64` — base64-encoded release keystore
- `WEBGPT_KEYSTORE_PASSWORD` — keystore password
- `WEBGPT_KEY_ALIAS` — key alias
- `WEBGPT_KEY_PASSWORD` — key password

For local release builds, set the same values as environment variables or in `~/.gradle/gradle.properties` (never in the project's own `gradle.properties`).

## Building locally

Requirements: JDK 17+ and the Android SDK (platform 34, build-tools 34.0.0).

```bash
./gradlew assembleDebug
```

> **Building from a source zip?** GitHub source archives drop the executable
> bit from scripts — run `chmod +x gradlew` before `./gradlew`.

## Customizing

- **Wrapped site**: edit `URL` in `MainActivity.java`.
- **Allowed hostnames for in-app navigation**: edit `isAllowedHost()` in `MainActivity.java`.
- **App name**: edit `<string name="app_name">WebGPT</string>` in
  `app/src/main/res/values/strings.xml`.
- **App icon**: replace the `ic_launcher.png` files under
  `app/src/main/res/mipmap-*/` (and the vector drawable at
  `app/src/main/res/drawable/ic_launcher_foreground.xml`).
- **Version**: edit `versionCode` / `versionName` in `app/build.gradle`.
