# Building the APK on GitHub Actions

This fork has been set up so the APK is built automatically on GitHub's servers — no Android Studio required.

## What the workflow does

`.github/workflows/build-apk.yml` runs on every push to `master` / `main`, every pull request, and on demand via the **Actions → Build APK → Run workflow** button.

It:
1. Spins up an `ubuntu-latest` runner.
2. Installs JDK 11 (Temurin) and Gradle 6.5 — matching the project's old toolchain.
3. Runs `./gradlew assembleDebug` and `./gradlew assembleRelease`.
4. Decodes the signing keystore from the `WEBGPT_KEYSTORE_BASE64` GitHub Secret (plus the corresponding password / alias secrets).
5. Uploads **two artifacts** you can download from the run page:
   - `app-debug-apk` — `app-debug.apk` (signed with the debug key, ready to install)
   - `app-release-apk` — `app-release.apk` (signed with the private release keystore)

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

The release APK is signed with a **private** release keystore that is **never** committed to the repository. The keystore and its credentials are stored in GitHub Secrets and decoded at build time by the GitHub Actions workflow:

- `WEBGPT_KEYSTORE_BASE64` — base64-encoded release keystore
- `WEBGPT_KEYSTORE_PASSWORD` — keystore password
- `WEBGPT_KEY_ALIAS` — key alias
- `WEBGPT_KEY_PASSWORD` — key password

For local builds, put the same values in `~/.gradle/gradle.properties` (in your home directory, never committed):

```properties
WEBGPT_KEYSTORE=/absolute/path/to/webgpt-release.keystore
WEBGPT_KEYSTORE_PASSWORD=<password>
WEBGPT_KEY_ALIAS=webgpt
WEBGPT_KEY_PASSWORD=<password>
```

## Local build (optional)

You don't need this — that's the whole point of the workflow — but if you want to build locally:

```bash
# Requires JDK 21 and Android SDK with platforms;android-34 and build-tools;34.0.0
./gradlew assembleDebug
./gradlew assembleRelease
```

The APKs land in `app/build/outputs/apk/{debug,release}/`.

## Customizing the app

- **Web URL**: edit `String url = "https://chatgpt.com/";` in
  `app/src/main/java/com/webgpt/app/MainActivity.java`.
- **Allowed hostname for in-app navigation**: edit `hostname = "chatgpt.com";`
  in `MyWebViewClient.java`.
- **App name**: edit `<string name="app_name">WebGPT</string>` in
  `app/src/main/res/values/strings.xml`.
- **App icon**: replace the `ic_launcher.png` files under
  `app/src/main/res/mipmap-*/` (and the vector drawable at
  `app/src/main/res/drawable/ic_launcher_foreground.xml`).
- **Version**: edit `versionCode` / `versionName` in `app/build.gradle`.
