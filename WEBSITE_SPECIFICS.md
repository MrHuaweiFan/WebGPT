# WEBSITE_SPECIFICS — the ChatGPT integration layer of WebGPT

> **This file exists in every app of the WebGPT family.**
>
> - In **WebGPT** (the base app, this repo): it documents **everything wired to
>   `chatgpt.com`** — every constant, selector, injected script, timing and
>   workaround that only exists because of how the ChatGPT website behaves.
> - In **derivative apps** (WebGLM, etc. — forks of WebGPT wrapping other
>   websites): the same file documents **everything that app changed relative
>   to WebGPT**, using the template in section 6.
>
> **Why it exists:** WebGPT is the base for several similar apps for other
> websites. When new features are built in WebGPT, they get ported to the
> derivative apps in a separate session, usually by an LLM that receives two
> source trees (e.g. "WebGPT v2" and "WebGLM v1, based on WebGPT v1") and is
> asked to port the changes. This file is what lets that LLM tell apart the
> *portable* machinery from the *website-specific* layer, instead of guessing.
>
> **Rule of thumb:** a change in the base app is safe to copy verbatim into a
> derivative only if it touches the machinery listed in section 3. Anything
> listed in section 2 must be adapted through the derivative's own
> WEBSITE_SPECIFICS.md (or skipped, if the target site lacks the underlying
> behavior).

## 0. How to use this file when porting (for LLMs and humans)

1. Read **this file** from the newer WebGPT source (sections 2 + 3 classify
   the whole codebase).
2. Read the **derivative app's WEBSITE_SPECIFICS.md** (its differences from
   the WebGPT version it was forked from).
3. Diff the two source trees to see what actually changed in the base since
   the fork.
4. For every change, classify it: **generic** (section 3 → copy as-is) or
   **website-specific** (section 2 → adapt using the derivative's selectors /
   hosts / timings, or skip).
5. **Never overwrite the derivative's selectors, hosts, URL constants or
   auth handling with the base app's values** — they are intentionally
   different there.
6. Follow the procedure in section 5, then update the derivative's lineage
   table and porting log (section 6).

## 1. App family conventions

- Every app in the family keeps a file named `WEBSITE_SPECIFICS.md` in the
  repository root, next to `README.md`.
- The derivative's file **must always record which WebGPT version it is
  synced to** (lineage table, section 6). When you port a batch of changes,
  update that table — it is the only reliable way to know what has already
  been ported.
- The fastlane changelogs (`fastlane/metadata/android/en-US/changelogs/`)
  double as a per-version change history of the base app — read them top-down
  when you need the version-by-version delta instead of a full diff.
- Files that describe machinery rather than the website (`BUILD.md`,
  `WEBVIEW_SWITCHER.md`) are base-app docs and port as-is (only names/URLs
  change).

## 2. Everything wired to chatgpt.com in this codebase

Everything in this section is **ChatGPT-specific**. Each item says where it
lives (all in `MainActivity.java` unless noted) and what to do when porting.

### 2.1 Constants: target URL, User-Agent, extra headers

| Item | Value | Notes for porting |
|---|---|---|
| `URL` | `https://chatgpt.com/` | Loaded on cold start, offline retry, and when no state to restore. Replace with the target site. |
| `UA_MOBILE` | `Mozilla/5.0 (Linux; Android 14; Pixel 8) … Chrome/131.0.0.0 Mobile Safari/537.36` | Spoofed modern-phone Chrome UA. chatgpt.com serves its full mobile web app to it. Try it first on the new site; some sites need a desktop UA or their own mobile UA. |
| `extraHeaders` | `X-Requested-With: ""` (empty) | Sent with **every** main-frame load via `loadUrlWithHeaders()`. Suppresses the default WebView `X-Requested-With: <package>` header, which chatgpt.com uses to detect WebViews and nag with the "install the app" banner. Keep it unless the target site behaves differently. |

**Trap:** `WebView.restoreState()` reloads the current page **without** these
headers. That is why `onCreate()` re-navigates explicitly through
`loadUrlWithHeaders()` after restoring state — if you remove that, the banner
comes back after process death. Keep this pattern when porting.

### 2.2 Host allowlist — `isAllowedHost()`

Allowed hosts (subdomains included): `chatgpt.com`, `openai.com`,
`accounts.google.com`, `google.com`, `googleusercontent.com`,
`gstatic.com`.

- `auth.openai.com` (OpenAI's login) is covered by the `openai.com` entry.
- Bare `auth0.com` is **deliberately NOT allowed**: it hosts arbitrary
  third-party tenants. Only ever allowlist the site's *own* auth subdomain.
- The Google entries exist for Google sign-in. `accounts.google.com` alone
  is **NOT enough**: after it, the account picker / consent screens hop
  through `myaccount.google.com`, `www.google.com` and
  `oauthaccount.googleusercontent.com` before returning to
  `auth.openai.com`. A popup OAuth chain must be allowlisted **end to end**
  or every unlisted hop gets routed to the system browser mid-login
  (v6.25 F-Droid review: app crash on login, Android 15 — see pitfall
  P14/P15 and 2.6). `gstatic.com` serves the login pages' static assets.

The allowlist is consumed in four places:

1. **Main-frame navigation** — `shouldOverrideNavigation()`: http(s) to an
   allowed host loads in-app; http(s) anywhere else goes to the system
   browser; `mailto:`/`tel:`/`sms:`/`geo:`/`intent:`/`market:` go to the
   browser; `file:`/`content:` are blocked; `blob:`/`data:`/`about:`/
   `javascript:` load in place.
2. **Iframe navigation** — `shouldOverrideNavigationFrame()`: URLs with a
   **null host** (blob:, data:) always load in place; http(s) to a
   non-allowed host goes to the browser. **Do not "harden" this** — see
   pitfall 4.6-P6.
3. **JS bridge origin gate** — `WebAppInterface.hostAllowed()` gates every
   bridge method on the WebView's real URL (popups inherit trust from the
   main WebView, because ChatGPT's share popup itself has a `blob:`/`about:`
   URL).
4. **Popup cleanup** — `maybeRemovePopupForExternalLink()` closes the popup
   WebView when its main-frame navigation was routed to the browser (kills
   the leftover black screen after opening X/Reddit/LinkedIn links).

### 2.3 DOM selectors — the ChatGPT UI contract

These selectors are the app's contract with chatgpt.com's DOM. They appear in
several places; **all of them must be updated together** when porting.

**Composer (the prompt input box)** — fallback chain, first match wins:

```
#prompt-textarea
div[contenteditable="true"][role="textbox"]
div[contenteditable="true"]
textarea[placeholder]
```

Used in: `PAGE_READY_WATCHER_JS` (tick), `waitForComposerReady()` (JS),
`settleComposerAfterAutoAttach()` (JS), `runFileDropSequence()` (dropJs),
`FOCUS_GUARD_JS` (`isComposer()`).

**Page-ready markers** (the last elements to appear when the SPA finishes
loading — used by `PAGE_READY_WATCHER_JS` and `waitForComposerReady()`):

- `[data-splash-headline-option]` — the new-chat greeting wrapper. Must be
  tested for **visibility** (`getClientRects()` with non-zero size), because
  in the mobile DOM the wrapper exists but is CSS-hidden (`hidden sm:block`)
  and lays out as a zero-height box.
- `[data-testid="use-case-prompt-chips"] button` — mobile suggestion chips.
  Also visibility-tested.
- `[data-testid^="conversation-turn"]` — a restored conversation (presence
  only; chats never render the splash).

**Existing-chat URL paths** (used by `FOCUS_GUARD_JS.isExistingChat()`):
`/c/…` (conversations) and `/g/…` (gems). While on these paths the site
auto-focuses the composer, which is why the focus guard exists at all.

**Login-finished marker** (popup `onPageFinished`): host is `chatgpt.com` /
`*.chatgpt.com` **and** the URL does **not** contain `/auth/` → the OAuth
round-trip ended back on the site → load that URL into the main WebView and
close the popup.

**Attachment verification** (`verifyAttachmentVisible()`): nodes matching
`[data-testid*=attachment i]`, `[class*=attachment i]`, `[aria-label*=file i]`
whose text contains the file name.

**File input** (`runFileDropSequence()`): the **first** `input[type=file]` in
the document — this is the input behind ChatGPT's `+ → Files` flow, and it is
the primary injection target for shared files.

**Selector rules learned the hard way:**

- Match only `data-testid` / attributes / ids — **never visible text**.
  Greetings ("ON_YOUR_MIND", "SHOULD_WE_BEGIN", …) and chip labels rotate and
  are localized.
- Presence ≠ visibility: test `getClientRects()` for anything that might be
  CSS-hidden in one form factor.
- `[data-testid*=composer]` matches ChatGPT's **+ attach button**, not the
  text box. This exact confusion once made the settle code click `+` and open
  menus instead of focusing the composer (pitfall 4.6-P3).

### 2.4 The three injected scripts (document-start, every frame)

Registered in `installDocumentStartOverrides()` via
`WebViewCompat.addDocumentStartJavaScript()` in this order (order matters —
the ready watcher reads `window.__webgptLoad` installed by the overrides):
`PAGE_OVERRIDES_JS`, then `PAGE_READY_WATCHER_JS`, then `FOCUS_GUARD_JS`.
They are also re-injected at `onPageFinished` (`injectAllOverrides()`) as a
fallback for WebViews without `DOCUMENT_START_SCRIPT` support — the scripts
are idempotent (`window.__…` guards). Popups get **only**
`PAGE_OVERRIDES_JS` (document-start) — never the watcher or the focus guard.

#### PAGE_OVERRIDES_JS — Web-API polyfills shaped by ChatGPT's behavior

The mechanics are generic web plumbing, but every design decision came from
chatgpt.com behavior. **Port it as-is first, then verify** on the target site.

1. `navigator.share` / `navigator.canShare` override → bridges to
   `AndroidBridge.shareText` / `shareFile` / `copyToClipboard`. There is
   **no JS-side origin gate** on purpose: ChatGPT runs its share/export UI in
   `blob:` and cross-origin iframes, and a JS allowlist blocked exactly those
   calls (symptom: the share spinner stops and nothing happens). The Java
   side still gates every bridge method (see 2.2). `toString()` is spoofed to
   `function share() { [native code] }` so feature-detecting page code sees a
   native-looking function. `canShare` is honest: text/url/title or exactly
   one file.
2. `document.execCommand('copy')` fallback → bridges the current selection to
   the clipboard (fixes the site's Copy button on WebViews without the async
   Clipboard API).
3. **Blob lifetime keeper** — wraps `URL.createObjectURL`/`revokeObjectURL`,
   stores the Blob **object** keyed by URL in `window.__webgptBlobs`, defers
   real revocation by 600 s, capped at 16 entries / 128 MB (oldest evicted).
   Rationale: chatgpt.com revokes export blob URLs immediately after
   triggering the download — long before Android's `DownloadListener` can
   fetch them — so a naive WebView download gets a dead URL. Key insight:
   revoking a URL does **not** destroy the Blob object; reading the stored
   object via `FileReader` still works after the URL is dead.
4. **Blob download interception** — `HTMLAnchorElement.prototype.click` hook
   + document-level capture click listener for `a[download]` with `blob:`
   hrefs → `webgptFetchBlob()`: look the Blob up in the keeper (including
   child frames and `window.parent` — the export UI runs in an iframe), fall
   back to sync XHR, then async `fetch()`. Payload goes to Java in 256 KB
   base64 chunks (`onBlobChunk`) or one shot (`onBlobDownload`).
5. **Load-state tracker** `window.__webgptLoad` — wraps `fetch` and
   `XMLHttpRequest.open` (last request-start timestamp) plus a
   `MutationObserver` (last DOM-mutation timestamp). Powers the settle
   heuristics (2.4 / 2.5): "no DOM mutation for 2 s AND no request started
   for 1.5 s" = page quiet. This is an in-page networkidle heuristic,
   device-speed independent.
6. `dbg()` telemetry → `AndroidBridge.debugLog` (logcat, EXPERIMENTAL builds
   only) and a one-per-load beacon "overrides active (main frame)".

`window.open` is deliberately **not** hooked — an earlier debug hook broke
the user-gesture context, `onCreateWindow` never fired, and external links
stopped opening (pitfall 4.6-P4).

#### PAGE_READY_WATCHER_JS — "the SPA is REALLY rendered" signal

ChatGPT's document `load` event can fire long after React has hydrated and
the page is usable; `onPageFinished`-based overlay dismissal left the splash
on top of a usable page ("site loaded, app not responding"). The watcher
polls every 200 ms, **main frame only**, host-gated to `chatgpt.com` /
`openai.com`, and calls `AndroidBridge.pageReady()` when the composer exists
**and** one of: a visible page-ready marker (2.3), or the settle heuristic,
with an 8 s hard cap. Java side (`onDomReady()`) then fades the loading
overlay.

This is the **misleading-full-load detection system** — chatgpt.com's
WebView progress (and its `load` event) claim "fully loaded" while the SPA
is still hydrating, and this watcher is what distinguishes the claim from
the truth. EVERYTHING the user sees during load must consume this signal,
not raw WebView progress: the loading screen dismisses on it (§3 UI chrome),
and since v6.28 the optional loading progress bar is also gated by it —
`onProgressChanged` maps the raw percentage onto 0–90% and only
`hideLoadingOverlayNow()` (the single funnel for the real-ready signal:
watcher, 3.5 s fallback, all of it) completes the bar to 100%, so "bar
full" and "loading screen gone" are the same event.

**ChatGPT-specific parts:** the hostname gate and the marker selectors. The
settle heuristic and the cap are generic. (A target site whose progress is
honest can drop the 90% cap — record the decision in the derivative's
file.)

#### FOCUS_GUARD_JS — stop the keyboard from auto-opening in existing chats

chatgpt.com's SPA calls `composer.focus()` programmatically when you open an
existing chat (`/c/…`, `/g/…`), on page load, SPA remount and
visibility-resume — which pops the soft keyboard without any user tap. The
guard (host-gated to `chatgpt.com` / `chat.openai.com` / `openai.com`, main
frame only):

- monkey-patches `HTMLElement.prototype.focus` to no-op the composer when on
  an existing chat (v2 mechanism — a listener + deferred blur **failed**
  because React re-focuses in rAF/microtasks faster than the blur lands;
  pitfall 4.6-P7),
- capture-phase `focusin` listener that blurs **untrusted** focus events only
  (real taps are `isTrusted` and always allowed),
- `visibilitychange` listener that blurs the composer while hidden (the JS
  thread is paused while hidden, so the blur survives the resume),
- a 500 ms interval that strips `autofocus` attributes (SPA remounts re-add
  them),
- a 6-second bypass window `window.__webgptShareActiveUntil` so the share
  pipeline's own focus calls can land (set from `markShareActive()` and
  inside the settle/drop JS — see 2.5).

**If the target site never auto-focuses its composer, the whole guard is
unnecessary** — record that in the derivative's file instead of porting it
blindly.

### 2.5 Share-into-app: the file auto-attach pipeline

Receiving a shared file (Android `ACTION_SEND`) and attaching it to the
ChatGPT composer is the most site-coupled pipeline in the app. Stages:

1. Java copies the file to the app cache (sanitized name), reads it as base64
   if < 50 MB, stages it into the page in **512 KB chunks** via
   `window.__webgptFileB64`.
2. **Primary injection** (`runFileDropSequence()`): build a `File` +
   `DataTransfer` in JS, assign to the first `input[type=file]`'s `files`
   (with prototype-setter fallback), dispatch `input` + `change` — this runs
   ChatGPT's own attach handler, with **no drag events**, so the site's
   full-screen drop overlay is never shown.
3. **Fallback injection** (only if no file input exists): synthetic
   `DragEvent` dragenter/dragover/drop on the composer, then document-level
   drop/dragend/dragleave + Escape 350 ms later to tear the drop overlay
   down.
4. **Commit window:** ChatGPT only commits a pending attachment while the
   composer is focused within roughly 1 second of the file landing; unattended,
   it silently drops it. So Java re-focuses the composer in staggered passes
   (`settleComposerAfterAutoAttach()` at 200/500/900/1400 ms — and
   500/1500/2500 ms after a drop) and summons the keyboard
   (`showKeyboardForComposer()`).
5. **Double-attach guard:** right after a programmatic attach, ChatGPT
   **re-opens its file input**. `openFileChooser()` therefore ignores
   file-chooser requests within 4 s of `lastFileInjectionAt` — otherwise the
   pending shared file is handed over a second time, which trips ChatGPT's
   attach limit and makes the attachment vanish.
6. Verification 3 s later that the attachment chip is still visible
   (`verifyAttachmentVisible()`), a "Please wait — the file will attach
   automatically" toast up front, and a manual fallback toast ("tap + and
   choose Files") after 25 s.

**Timings are ChatGPT empirics.** On a new site, re-test: does the composer
have an `input[type=file]`? does it commit unattended attachments? does it
re-open the file chooser after an attach? Adjust or delete stages
accordingly and record it in the derivative file.

Shared **text** is simpler and almost generic: copy to clipboard + wait for
the real composer (`waitForComposerReady()`, 20–25 s budget) + focus it and
open the keyboard (`settleComposerAfterAutoAttach()`). The composer-wait
part is site-specific only through the selectors and the settle heuristic.

### 2.6 Sign-in flow

- Google sign-in runs in a **popup WebView** (`onCreateWindow` →
  `createPopup()`). Third-party cookies are **enabled on popups only**
  (blocked on the main WebView): the `chatgpt.com → auth.openai.com →
  accounts.google.com` redirect chain needs them to complete. Popups share
  the main `CookieManager` jar.
- Popup `onPageFinished`: when the popup lands back on `chatgpt.com` /
  `*.chatgpt.com` outside `/auth/`, the login is done → load that URL into
  the main WebView and destroy the popup.
- Non-allowed hosts in a popup go to the system browser and the popup is
  removed (no black screen left on top of the chat).
- **Popup teardown is always DEFERRED** (`removePopup()` detaches the view
  immediately but posts `destroy()` to the next message-loop pass).
  `WebView.destroy()` called synchronously from inside a WebView callback
  (`shouldOverrideUrlLoading`, `onPageFinished`, `onRenderProcessGone`,
  `DownloadListener`) crashed the app on Android 15 the moment a login
  popup was torn down — the v6.25 F-Droid review's "app crashes when
  tapping any login button". Same deferral applies to the main WebView in
  `recreateMainWebView()`. Do not "simplify" this back to synchronous
  destroys (pitfall P14).
- Known limitation: Google blocks OAuth in Android WebViews ("This browser
  or app may not be secure") for some accounts — email sign-in works.
- The app also requests camera/microphone on demand for the site's voice
  mode and photo attach (`onPermissionRequest` → runtime permission → grant
  only what the user approved). This fixes the silent voice-input failure
  seen in v6.24-based apps, whose `onPermissionRequest` only granted
  resources when the Android permission was ALREADY granted — it never
  requested it, so the mic button silently did nothing.

When porting: identify the target site's login mechanism (popup OAuth vs
same-window redirect vs none), keep the popup third-party-cookie pattern if
it uses popup OAuth, and find the equivalent "landed back on the site"
marker for the popup-close logic.

### 2.7 Downloads & exports

ChatGPT's exports (PDF/Word/Markdown chat exports, generated files) are
`blob:` anchor downloads created in iframes — the reason the whole blob
machinery in 2.4 exists. Around it, the Java side is generic: MediaStore
saves on Android 10+, legacy storage permission below, `Content-Disposition`
filename parsing, filename sanitization, cookie+UA-aware `HttpURLConnection`
downloads for http(s), data-URL saves, an 8 s suppression of the dead-URL
`DownloadListener` fallback after a successful bridge save, and a 30 s
give-up toast. Verify on the new site which export mechanisms it actually
uses; if it serves plain https downloads, only the generic path matters.

### 2.8 Keyboard & SPA behavior

- `windowSoftInputMode=adjustResize` (manifest) — chatgpt.com's own keyboard
  handling expects the viewport to shrink when the IME opens. Some OEMs
  resolve `adjustUnspecified` differently in floating/freeform windows and
  leave the composer under the keyboard.
- `initialLoadComplete` flag: ChatGPT is an SPA — opening its settings tab
  fires `onPageStarted`/`onPageFinished` again; those must not re-show the
  loading overlay.
- WebView background `#0D0D0D` (dark) / `#FFFFFF` (light) matches ChatGPT's
  own surfaces so pre-paint flashes are seamless. Match the target site's
  colors when porting.

### 2.9 Hardcoded strings & branding (per-app, not per-website)

`app_name` / settings screen header pair ("Settings" + "WebGPT", hardcoded
in `activity_settings.xml`), share-target labels ("Ask WebGPT",
"Send to WebGPT" — manifest intent-filters), clipboard label "WebGPT",
offline dialog text ("Couldn't load chatgpt.com…"), welcome-dialog and
WebView-info texts (they mention ChatGPT features and Thorium),
`UpdateChecker.REPO_API` (`api.github.com/repos/MrHuaweiFan/WebGPT/releases`),
README (including the not-affiliated-with-OpenAI disclaimer), icons, package
name `com.webgpt.app`. All of these change per app — see 4.1.

### 2.10 ChatGPT behavior quirks reference

| Quirk | Consequence in code |
|---|---|
| Detects `X-Requested-With` header | header blanked on every main-frame load (2.1) |
| Revokes export blob URLs immediately | blob lifetime keeper + object-based capture (2.4) |
| Share/export UI runs in blob:/cross-origin iframes | document-start injection in every frame; no JS origin gate (2.4) |
| Investigation/deep-research panel is a null-host iframe app | iframe policy loads blob:/data: in place (2.2) |
| Auto-focuses composer on existing chats | FOCUS_GUARD_JS (2.4) |
| Re-adds `autofocus` on SPA remounts | autofocus strip interval (2.4) |
| Commits attachments only while composer focused ~1 s | staggered settle passes (2.5) |
| Re-opens file input after programmatic attach | 4 s double-attach guard (2.5) |
| Attach limit — double-attach makes the chip vanish | same guard + 3 s chip verification (2.5) |
| Greeting/chip text localized and rotating | only testids/attributes matched, never text (2.3) |
| Greeting wrapper CSS-hidden on mobile | visibility test via getClientRects (2.3) |
| `load` event fires after hydration | page-ready watcher + settle heuristic (2.4) |
| Google OAuth blocked in WebViews (some accounts) | documented limitation; email login works (2.6) |

## 3. Website-agnostic machinery (port as-is)

None of the following knows anything about chatgpt.com. When porting new
features from a newer WebGPT, changes confined to these areas can be copied
verbatim (modulo the derivative's own app-level customizations, which its
WEBSITE_SPECIFICS.md lists):

- **WebView switcher** — the whole `com.webgpt.app.webview` package:
  `App`, `Hooker`, `IServiceManagerProxy`, `IWebViewUpdateServiceProxy`,
  `IPackageManagerProxy`, `DeveloperModeContentProvider`, `CrashTracker`,
  `WebViewManagerDialog`, `WebViewInstalledAdapter`, `WebViewDownloaderAdapter`,
  `WebViewSource`, `WebViewImplementation`, `WebViewUtil` (see
  `WEBVIEW_SWITCHER.md`). Only `UpdateChecker`'s GitHub repo URL is per-app.
- **Renderer-crash recovery** — `onRenderProcessGone` → in-place
  `recreateMainWebView()` (main view and popups).
- **Resume-snapshot mask + theme backgrounds** — PixelCopy overlay in the
  decor view (`captureResumeSnapshot`, `scheduleSnapshotFadeOut`),
  `applyBackgroundColors()` — the black-flash-on-resume fix. Pure
  Android/WebView behavior.
- **State save/restore** — `onSaveInstanceState`/`restoreState` + explicit
  re-navigation, full `configChanges` list in the manifest, `singleTask`,
  floating-window/split-screen/rotation survival.
- **Download framework** — `setupDownloads`, `downloadWithCookies`,
  `saveFileWithCookies`, `filenameFromDisposition`, `sanitizeFilename`,
  `saveBlobBase64`, `extensionForMime`, MediaStore integration.
- **JS-bridge framework** — the `AndroidBridge` plumbing itself:
  chunked transfer (`onBlobChunk`, 256 KB), dedup window, origin-gate
  mechanics, `debugLog`/EXPERIMENTAL gating.
- **Share framework** — `ACTION_SEND`/`ACTION_PROCESS_TEXT` intent handling,
  clipboard, system share sheet (`shareTextNative`/`shareFileNative`),
  `ShareRelayActivity` (the OEM mis-launched-task trampoline), FileProvider
  sharing.
- **File chooser & camera capture** — `openFileChooser`,
  `launchFileChooserNow`, camera `FileProvider` capture, runtime camera
  permission flow, cache sweep (`sweepCacheDir`, 48 h).
- **Image context menu** — long-press HitTestResult → share/download dialog.
- **Permissions** — on-demand camera/mic for `onPermissionRequest`.
- **UI chrome** — loading overlay + logo animation (v6.28: property
  animators `startLoadingLogoAnimation`/`stopLoadingLogoAnimation` with a
  hardware layer — the old `spin_fade` VIEW animation stuttered during page
  load; port the animator version, not the deleted XML), offline dialog
  (only its message text mentions the site), Settings activity, welcome
  dialog (text is per-app), Material 3 theme, installed-WebView row
  rounded press feedback (`ripple_installed_row` + nulled radio
  background — pure Material plumbing), and `applySystemBarColors()`
  (v6.28): re-applies status/navigation bar colors + icon tint from the
  current configuration in `onConfigurationChanged` — required because
  `uiMode` sits in the manifest's `configChanges` (WebView survival), so
  the themed bar colors would otherwise stay in the old mode on a live
  dark/light switch while the site adapts by itself.
- **Loading progress bar (v6.28)** — optional Material 3
  `LinearProgressIndicator` at the top of the loading overlay (Settings →
  Interface, pref `loading_progress`, off by default). `onProgressChanged`
  lives in the MAIN WebView's **WebChromeClient** (NOT `WebViewClient` — a
  classic porting mistake). ⚠ Its progress is deliberately NOT the raw
  WebView percentage: chatgpt.com reports 100% while the SPA is still
  hydrating (§2.4, the misleading full load), so the raw value is mapped
  onto the bar's first 90% and only `hideLoadingOverlayNow()` — the same
  funnel that dismisses the loading screen on the real-ready signal —
  completes the bar to 100%. Port the cap together with the bar, or the
  bar will claim "fully loaded" over a still-loading page.
  `syncLoadingProgressBar()` re-reads the toggle every time the overlay
  (re)appears (cold start, offline retry, renderer crash) so no restart is
  needed. The bar sits INSIDE the overlay so it appears/fades exactly with
  the logo and never pulses on its own. Requires material 1.12.0+
  (rounded ends + gap + stop indicator are 1.12 style defaults; 1.11 drew
  square ends). ⚠ ONE per-app token: `loading_progress_indicator` color —
  in WebGPT a single literal `#2F628C` for BOTH modes (the light-mode
  "Apply & restart" blue; deliberately NOT `@color/md_primary`, which
  would re-resolve to the dark scheme's pastel at night); forks pick
  their own color and record the day/night decision here.
- **Launch splash (v6.27)** — `Theme.AppSplash` + `splash_icon_none` +
  the exit-fade listener in `MainActivity.onCreate`. On Android 12+ the
  splash is a plain frame of the loading-screen background color with a
  deliberately transparent icon (the system's fixed icon container renders
  real logos wrong on some OEMs); the listener crossfades it into the
  loading screen so no OEM zoom animation can play. Pre-12, the theme's
  pinned `windowBackground`/bar colors keep the launch window identical to
  the app theme. ⚠ ONE per-app token inside otherwise-generic machinery:
  `windowSplashScreenBackground` (and the pre-12 `android:windowBackground`)
  MUST point at the derivative's OWN loading-screen background color —
  every fork's palette differs (WebGPT: `md_background`, #FCFCFC/#000000).
  It must be day/night-qualified like the loading overlay's
  `?android:attr/colorBackground`, or dark mode flashes the light color at
  launch. Never hardcode a hex value in the splash theme.
- **Build config** — EXPERIMENTAL flag pattern (debug key +
  `debuggable=false` so ART AOT-compiles experimental builds; release has
  `EXPERIMENTAL=false`), `dependenciesInfo` disabled for F-Droid's scanner.

## 4. Porting to a new website

### 4.1 Rebranding checklist (mechanical)

1. `applicationId` / `namespace` in `app/build.gradle` and the Java package
   tree (`com.webgpt.app` → `com.<newapp>.app`).
2. `versionCode`/`versionName` reset to the derivative's own scheme — do
   **not** inherit the base's version numbers; keep your own monotonic
   sequence so updates install correctly.
3. `app_name`, `settings_title`, share labels ("Ask …" / "Send to …") in
   `strings.xml` + manifest intent-filter labels; clipboard label in
   `MainActivity`/`ShareRelayActivity` ("WebGPT" → your app); the settings
   screen's hardcoded header pair "Settings" / "WebGPT" in
   `activity_settings.xml` (header stays "Settings", subtitle = your app's
   short name) — and `Theme.AppSplash`'s splash colors → your own
   loading-screen background tokens (see §3 "Launch splash").
4. Launcher icons (all densities), `logo_black`/`logo_white` drawables.
5. README: name, description, **trademark disclaimer for the target site**
   (replace the OpenAI paragraph), inspiration credits.
6. `UpdateChecker.REPO_API` + releases-page URL → the new app's repo.
7. Welcome-dialog / webview-info / offline-dialog texts → the new site's
   name and its real known limitations.
8. Fastlane metadata (`short_description`, `full_description`, screenshots)
   → per-app, per-website.

### 4.2 Integration checklist (the website layer)

Work through section 2 top-down; the concrete to-do list:

1. `URL` constant → target site.
2. `isAllowedHost()` → target site + its auth hosts (+ `accounts.google.com`
   only if it offers Google sign-in; never allowlist multi-tenant domains
   like bare `auth0.com`).
3. `UA_MOBILE` → verify against the target site (try the existing one first).
4. `extraHeaders` (`X-Requested-With`) → keep unless the site misbehaves.
5. Composer selectors (all 5 locations listed in 2.3).
6. Page-ready markers in `PAGE_READY_WATCHER_JS` + `waitForComposerReady()`;
   update the hostname gate in the watcher; if no stable markers exist, rely
   on the settle heuristic (automatic) and shorten the marker checks.
7. `FOCUS_GUARD_JS`: update the hostname gate + `/c/`-`/g/` path patterns —
   or drop the guard entirely if the target site doesn't autofocus (record
   the decision).
8. Popup login: keep popup third-party cookies; find the "landed back on
   the site" marker (host + path) for popup-close.
9. File-attach pipeline: check for `input[type=file]`, the commit window,
   and the re-open behavior; re-tune the timings in 2.5 or simplify.
10. Iframe policy: keep null-host iframes loading in place (unless the site
    has none — still keep it, it's harmless).
11. Blob/download: test the site's export paths; PAGE_OVERRIDES_JS normally
    ports unchanged.
12. WebView background color → the target site's dark/light surface color.
13. Fill in the derivative's WEBSITE_SPECIFICS.md (section 6) as you go —
    that IS the deliverable that makes the next port easy.

### 4.3 How to discover selectors for a new site

- Build an **experimental** APK (`assembleDebug` — `EXPERIMENTAL=true`
  enables `chrome://inspect`) and inspect the live page from desktop Chrome,
  or open the site in desktop Chrome with device emulation + the app's UA.
- Find the composer: try `document.querySelector('#prompt-textarea')`, then
  contenteditables, then textareas. Note which one the site actually uses
  and whether it changes between home/existing-chat states.
- Find late-appearing stable markers for "really loaded": snapshot the DOM
  right after load and again 3 s later; elements that appear late and carry
  `data-testid` or stable attributes are your markers. Check mobile vs
  desktop DOM (CSS-hidden elements — remember the visibility test).
- Verify existing-chat URL patterns by opening a conversation and reading
  `location.pathname`.
- Record every finding in the derivative's WEBSITE_SPECIFICS.md immediately,
  with the date — sites drift, and the next porter needs to know when the
  selectors were last verified.

### 4.4 Test matrix (run before every release)

- Cold start: overlay clears when the page is truly usable.
- Login: every method the site offers (popup OAuth, email, none).
- New chat + send; open existing chat (keyboard must NOT auto-open if the
  focus guard is active).
- Share text in from another app: clipboard + composer focused + keyboard up.
- Share file in: small image (auto-attach) and a large file > 50 MB
  (manual-fallback path). Attachment survives 3+ seconds.
- In-chat share button (`navigator.share`); long-press image → share/download.
- Every export type the site offers downloads with a correct filename.
- External links open in the browser; no black popup remains.
- Background kill → relaunch restores the open conversation.
- Floating window / split-screen / rotation: no self-relaunch.
- Airplane mode: offline dialog; retry works.
- Dark + light mode; no white/black flashes on navigate or resume.

### 4.5 Pitfalls — learned the hard way, do not repeat

Each of these cost a debugging round during WebGPT's v6.24.x experimental
series; most also have a comment at the relevant code site.

- **P1 — One-line JS strings.** Java string concatenation produces a single
  line: a `//` comment anywhere in an injected script comments out the
  entire remainder (three fix rounds "changed nothing" because of this). Use
  `/* … */` comments only inside injected JS.
- **P2 — Escape dismisses pending attachments.** Never send Escape as part
  of the attach-settle sequence on ChatGPT (it cancels the pending
  attachment). The current code sends Escape only in the drag-overlay
  teardown, after the drop.
- **P3 — Click vs focus.** Don't "tap" to settle the composer; dispatch
  focus only. Selector-based clicking can hit the + attach button instead
  of the text box.
- **P4 — Don't hook `window.open`.** Wrapping it breaks the user-gesture
  context WebView needs for `onCreateWindow` — popups and external links die
  silently.
- **P5 — No JS-side origin gate on share/blob overrides.** The share UI
  runs in blob:/cross-origin iframes; a JS allowlist blocks exactly the
  calls you are trying to fix. Gate in Java (`hostAllowed()`), never in the
  injected script.
- **P6 — Don't scheme-allowlist iframes.** Blocking blob:/data: iframes
  broke ChatGPT's investigation panel ("Error loading application: Runtime
  error"). Null-host iframes must load in place.
- **P7 — Prototype patch beats focus listeners.** A focusin listener +
  deferred blur loses against React, which re-focuses in rAF/microtasks;
  the deferred blur fires after the SPA already re-focused. Patch
  `HTMLElement.prototype.focus` and no focus event is ever generated.
- **P8 — No toasts on hot paths.** Every `Toast.show()` is a synchronous
  binder round-trip on the UI thread; diagnostic toasts on the navigation
  hot path caused visible jank. Debug telemetry goes to logcat via
  `AndroidBridge.debugLog`, EXPERIMENTAL builds only.
- **P9 — `evaluateJavascript` at `onPageFinished` touches only the main
  frame.** Iframe features need `addDocumentStartJavaScript` (every frame,
  every navigation, before any site script — so the site can't capture
  pre-override references either).
- **P10 — `restoreState()` reloads without extra headers.** Always
  re-navigate explicitly through `loadUrlWithHeaders()` after restoring.
- **P11 — Handing a pending shared file to every `onShowFileChooser` call
  double-attaches** when the site re-opens its input after a programmatic
  attach. Suppress with the 4 s window.
- **P12 — `debuggable=true` APKs are JIT-only** and feel slow (the whole
  "slower than Chrome" series). Keep debug keys but `debuggable false`, and
  key debug behaviors on `BuildConfig.EXPERIMENTAL`.
- **P13 — Don't trust sharing apps' display names** as filenames: sanitize
  (`sanitizeSharedFileName`) before using them in cache paths.
- **P14 — Never `WebView.destroy()` synchronously inside a WebView
  callback.** v6.25 crashed on login (F-Droid review, Android 15, 4 GB
  device): the OAuth popup hit a non-allowlisted Google host, was routed
  to the browser and destroyed mid-`shouldOverrideUrlLoading`; the same
  pattern in `onRenderProcessGone` (renderer OOM on low-RAM devices while
  the login popup loads) killed the app too. Detach the view immediately,
  but post `destroy()` to the next message-loop pass (`removePopup()` does
  this). Immediate destroy is only correct in `onDestroy()`.
- **P15 — Allowlist the OAuth chain end to end.** The login flow's redirect
  chain is part of the site contract: for ChatGPT+Google that is
  `chatgpt.com → auth.openai.com → accounts.google.com →
  myaccount.google.com / www.google.com / oauthaccount.googleusercontent.com
  → auth.openai.com → chatgpt.com`. Missing any hop routes real user
  traffic to the external browser mid-login and (combined with P14) crashes
  the app. When porting, walk the target site's login chain with a network
  inspector BEFORE freezing the allowlist.
- **P16 — Don't put a real logo in the Android 12+ splash icon slot, and
  don't hardcode the splash background.** The system renders
  `windowSplashScreenAnimatedIcon` inside its own fixed-size, OEM-masked
  icon container — a plain bitmap logo looked wrong on several devices
  during v6.27 device testing. The robust pattern: transparent icon drawable +
  `windowSplashScreenBackground` pointing at the app's own day/night
  loading-screen color + pinned pre-12 `windowBackground`, plus the exit
  crossfade listener. Also remember the core-splashscreen base themes are
  DeviceDefault.LIGHT with no night variant — without the explicit pins,
  pre-12 dark-mode launches flash white. Same trap for forks: a splash
  color copied from WebGPT flashes against Webmini's different palette.
- **P17 — `app:tint` is silently ignored in a non-AppCompat activity.**
  `SettingsActivity` extends the plain framework `Activity`, so
  `<ImageView>` inflates as the FRAMEWORK `ImageView` — it only knows
  `android:tint`, and `app:tint` (an `AppCompatImageView` attribute) is
  dropped without any warning. The v6.28 info icon shipped this way and
  rendered its literal black `fillColor` in both modes while the card's
  text (theme-attr `textColor`) adapted correctly. Robust pattern for any
  standalone vector in these screens: bake the color into the drawable
  (`android:fillColor="@color/…"` with a day/night-qualified color
  resource — VectorDrawable resolves it per configuration) instead of
  tinting at the usage site. Same class of trap: don't rely on any
  `app:`-namespace attribute on plain framework views in this app's
  non-AppCompat activities.

### 4.6 Experiments that failed on chatgpt.com (do not re-attempt blindly)

These shipped in experimental builds, failed, and are **absent from v6.25**.
Before trying any of them on a new site, check whether the target site's
behavior actually differs:

- **Composer "+" keyboard fix** (`__webgptPlusFix`) — tried to fix the
  keyboard by patching the + button; broke other things; removed.
- **IME sync / "smooth keyboard tracking"** — experimental
  `ime_sync_switch` machinery; removed.
- **Foreground keep-alive service** — Android 14 notification/battery hoops;
  abandoned.
- **Desktop mode toggle** — UA switching wiped the session cookie on every
  cold start (logged users out); feature deleted in v6.25.
- **Diagnostic on-screen toasts** (`debugToast`/`diagToast`) — see P8;
  replaced by logcat-only `debugLog`.
- **`last_url` reopen** — storing "last page" and reopening it; superseded
  by proper `saveState`/`restoreState`.
- **JS-side origin gates + scheme allowlists + `window.open` hooks** — P5,
  P6, P4.

## 5. Procedure: porting new WebGPT features into a derivative app

1. **Inputs:** newer WebGPT source (with this file) + derivative source
   (with its own WEBSITE_SPECIFICS.md).
2. Read both files first. Note the derivative's **lineage** (which WebGPT
   version it is synced to) and its **website layer** (selectors, hosts,
   paths, timings).
3. Establish the delta: diff the newer WebGPT against the WebGPT version the
   derivative is synced to (the fastlane changelogs list version-by-version
   changes if a diff is impractical).
4. Classify each delta item: **generic** (touches section-3 machinery) or
   **website-specific** (touches section-2 items).
5. Apply generic changes verbatim — but re-check them against the
   derivative's own customization list (it may have modified that machinery
   for its site; its file says where).
6. For website-specific changes: map them onto the derivative's equivalents
   from its file (its selectors/hosts/paths/timings). If the target site
   lacks the underlying behavior, **skip** the change and log it in the
   porting log.
7. Never copy the base's `URL`, `isAllowedHost`, selectors, hostname gates,
   `/c/`-`/g/` paths, popup-login markers or attach timings over the
   derivative's values.
8. Bump the derivative's own `versionCode`/`versionName`, update its
   fastlane changelog, build, run the test matrix (4.4).
9. Update the derivative's lineage table and porting log (section 6). This
   step is what keeps the next port cheap.

## 6. Template for derivative apps' WEBSITE_SPECIFICS.md

Copy the block below to the top of the derivative's own
`WEBSITE_SPECIFICS.md`, keep section 5 (procedure) verbatim, and fill this
in. **Keep the structure** — a porting LLM relies on finding the same
headings in every app of the family.

```markdown
# WEBSITE_SPECIFICS — <Site> (app: <AppName>)

## Lineage
- Forked from: WebGPT v<major.minor> (versionCode <n>)
- Last synced with WebGPT: v<major.minor> (versionCode <n>) on <YYYY-MM-DD>
- This file's selectors last verified against the live site: <YYYY-MM-DD>

## Differences from WebGPT base
| Area | WebGPT (base) | This app | Why |
|---|---|---|---|
| Target URL | https://chatgpt.com/ | https://…/ | |
| Allowed hosts | chatgpt.com, openai.com, accounts.google.com | … | |
| User-Agent | Pixel 8 / Chrome 131 mobile | … | |
| X-Requested-With header | blanked | kept/blanked | |
| Composer selectors | #prompt-textarea → … | … | |
| Ready markers | splash/chips/turn testids | … / none — settle heuristic | |
| Existing-chat paths | /c/, /g/ | … / none | |
| Focus guard | kept | kept/adapted/dropped | <site autofocuses or not> |
| Popup login | chatgpt.com outside /auth/ | … / no popups | |
| File attach | input.files primary, drag fallback, timings … | … | |
| Attach timings | settle 200/500/900/1400 ms; suppress 4 s | … | |
| WebView background | #0D0D0D / #FFFFFF | … | |
| Update checker repo | MrHuaweiFan/WebGPT | … | |

## Site-specific features added on top of base
- <feature, where it lives, what it does>

## Base features dropped (not applicable to this site)
- <feature, reason>

## Porting log
| Date | From WebGPT | Changes ported | Skipped (why) | Adaptations | Released as |
|---|---|---|---|---|---|
| | | | | | |

## Site quirks this app works around
- <quirk → code consequence>
```

### Final reminders for whoever (or whatever) ports next

- The two source trees you are given are the ground truth; this file is the
  map. If they disagree, trust the code and fix this file.
- Update this file in the same commit as the code it describes.
- A selector change on the live site is a bug in every app of the family
  until this file (and the code) say otherwise.

